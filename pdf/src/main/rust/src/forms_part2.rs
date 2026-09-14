pub(crate) fn set_text_field(handle: i64, widget_id: i64, value: &str) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(widget_id);
    // /V is a field key, so it belongs on the terminal field; setting it on the
    // clicked widget leaves every other widget of the field stale.
    let root_id = terminal_field_id(doc, id);
    // §12.7.3.1 Table 220 marks /Ff and §12.7.4.3 Table 222 marks /Q and /MaxLen
    // "Optional; inheritable", and §12.7.2 Table 218 makes the AcroForm /Q the
    // document-wide default. Reading them off one dictionary missed both routes:
    // a field whose /Ff sits on a grouping ancestor lost its Multiline flag and
    // rendered a wrapped value as one clipped line, and a form aligned solely by
    // the AcroForm /Q rendered every field flush left.
    let flags = inherited_num(doc, id, b"Ff").unwrap_or(0.0) as u32;
    let multiline = flags & (1 << 12) != 0; // Ff bit 13
    let comb = flags & (1 << 24) != 0; // Ff bit 25
    let quadding = field_quadding(doc, id);
    let max_len = inherited_num(doc, id, b"MaxLen").unwrap_or(0.0) as usize;
    // §12.7.3.3: /DA carries the field's font, size and colour. Resolved once for
    // the field, since /DA is a field key shared by all its widgets.
    let da = field_da(doc, id);
    let (da_res, da_font) = da_font_resources(doc, da.font.as_deref());

    // A field may have several /Kids widgets, all displaying the same value
    // (§12.7.3.1), so regenerate an appearance for EACH from its own /Rect —
    // updating only the clicked widget leaves the field stale everywhere else it
    // appears. Rects are collected first so the mutable borrow for
    // make_appearance does not overlap the reads.
    let widget_ids: Vec<ObjectId> = doc
        .get_dictionary(root_id)
        .ok()
        .and_then(|d| d.get(b"Kids").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
        .map(|kids| kids.iter().filter_map(|k| k.as_reference().ok()).collect::<Vec<_>>())
        .filter(|v: &Vec<ObjectId>| !v.is_empty())
        .unwrap_or_else(|| vec![id]);
    let widget_rects: Vec<(ObjectId, [f64; 4])> = widget_ids
        .iter()
        .filter_map(|wid| {
            doc.get_dictionary(*wid)
                .ok()
                .and_then(|d| d.get(b"Rect").ok())
                .and_then(|o| read_rect(doc, o))
                .map(|r| (*wid, normalize_rect(r)))
        })
        .collect();
    let mut aps: Vec<(ObjectId, ObjectId)> = Vec::with_capacity(widget_rects.len());
    for (wid, r) in widget_rects {
        // Field text must read upright, so lay the appearance out in the display
        // orientation of the page the widget sits on (§12.5.2 /P). Widgets of one
        // field can be on pages with different /Rotate, hence the per-widget
        // lookup; absent /P we assume no rotation, matching previous behaviour.
        let rot = doc
            .get_dictionary(wid)
            .ok()
            .and_then(|d| d.get(b"P").ok())
            .and_then(|o| o.as_reference().ok())
            .map(|pid| page_rotation(doc, pid))
            .unwrap_or(0);
        let (w, h, apm) = display_orientation(rot, r[2] - r[0], r[3] - r[1]);
        let size = field_font_size(da.size, h);
        let content = build_text_appearance(value, w, h, size, &da_font, da.argb, quadding, multiline, comb, max_len);
        aps.push((wid, make_appearance_oriented(doc, w, h, content, da_res.clone(), apm)));
    }

    // /V is a field attribute, so it belongs on the root; the widgets carry only
    // the regenerated appearance.
    let set_root = if let Ok(dict) = doc.get_dictionary_mut(root_id) {
        dict.set("V", Object::string_literal(value));
        true
    } else { false };
    let mut set_widget = false;
    for (wid, ap_id) in aps {
        if let Ok(dict) = doc.get_dictionary_mut(wid) {
            let mut ap = Dictionary::new();
            ap.set("N", Object::Reference(ap_id));
            dict.set("AP", Object::Dictionary(ap));
            set_widget = true;
        }
    }
    if !set_root && !set_widget { return false; }
    set_need_appearances(doc);
    true
}

pub(crate) fn set_checkbox(handle: i64, widget_id: i64, on: bool) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(widget_id);
    // Determine the "on" state name from the widget's /AP /N sub-dictionary.
    let on_state = doc
        .get_dictionary(id)
        .ok()
        .and_then(|d| d.get(b"AP").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|ap| ap.get(b"N").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|states| {
            states
                .iter()
                .map(|(k, _)| k.clone())
                .find(|k| k.as_slice() != b"Off")
        })
        .unwrap_or_else(|| b"Yes".to_vec());

    // Radio buttons: the widget belongs to a terminal field with several kid
    // widgets that must be mutually exclusive. Setting one on clears the others
    // and records the chosen export value on the field's /V.
    let field_id = terminal_field_id(doc, id);
    let sibling_ids: Vec<ObjectId> = if field_id == id {
        Vec::new()
    } else {
        doc.get_dictionary(field_id)
            .ok()
            .and_then(|pd| pd.get(b"Kids").ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_array().ok())
            .map(|kids| kids.iter().filter_map(|k| k.as_reference().ok()).collect())
            .unwrap_or_default()
    };

    if sibling_ids.len() > 1 {
        // Radio group: set each kid's /AS, and the field's /V.
        for kid in &sibling_ids {
            let state = if *kid == id && on { on_state.clone() } else { b"Off".to_vec() };
            if let Ok(kd) = doc.get_dictionary_mut(*kid) {
                kd.set("AS", Object::Name(state));
            }
        }
        if let Ok(pd) = doc.get_dictionary_mut(field_id) {
            let v = if on { on_state } else { b"Off".to_vec() };
            pd.set("V", Object::Name(v));
        }
        return true;
    }

    // Single widget. /AS selects which of /AP /N's states paints (§12.5.5) and
    // lives on the widget; /V is the field's value. The two are the same
    // dictionary for a merged field+widget, and different when the widget is a
    // lone /Kids entry — where the old code wrote /V onto the widget, leaving the
    // field itself unset and the checkbox reading as unchecked on reload.
    let state = if on { on_state } else { b"Off".to_vec() };
    let mut updated = false;
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("AS", Object::Name(state.clone()));
        updated = true;
    }
    if let Ok(dict) = doc.get_dictionary_mut(field_id) {
        dict.set("V", Object::Name(state));
        updated = true;
    }
    updated
}

/// Set a Choice (`/Ch`) field's value: records `/V` and the matching `/Opt` index
/// in `/I` on the field, and builds a single-line appearance showing the
/// selection on the widget.
///
/// `value` is what the user saw, i.e. the DISPLAY string. §12.7.4.4 makes an
/// `/Opt` entry either a plain string (display == export) or a `[export display]`
/// pair, and `/V` must hold the EXPORT value; writing the display string there
/// submits the wrong data and stops matching `/Opt` on reload.
pub(crate) fn set_choice_field(handle: i64, widget_id: i64, value: &str) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(widget_id);
    let root_id = terminal_field_id(doc, id);
    let rect = doc
        .get_dictionary(id)
        .ok()
        .and_then(|d| d.get(b"Rect").ok())
        .and_then(|o| read_rect(doc, o))
        .map(normalize_rect);
    // Find the option matching `value` by display OR export string, and take its
    // export value for /V.
    let mut opt_index = None;
    let mut export = value.to_string();
    if let Some(opts) = field_attr(doc, id, b"Opt")
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
    {
        let text = |o: &Object| match deref(doc, o).unwrap_or(o) {
            Object::String(s, _) => decode_pdf_text(s),
            _ => String::new(),
        };
        for (i, o) in opts.iter().enumerate() {
            let (exp, disp) = match deref(doc, o).unwrap_or(o) {
                Object::Array(pair) if pair.len() >= 2 => (text(&pair[0]), text(&pair[1])),
                other => {
                    let s = text(other);
                    (s.clone(), s)
                }
            };
            if disp == value || exp == value {
                opt_index = Some(i);
                export = exp;
                break;
            }
        }
    }

    let da = field_da(doc, id);
    let (da_res, da_font) = da_font_resources(doc, da.font.as_deref());
    // §12.7.4.4 makes a choice field a variable-text field, so /Q applies to it
    // exactly as it does to a text field.
    let quadding = field_quadding(doc, id);
    let ap_id = rect.map(|r| {
        // Same display-orientation handling as set_text_field (§12.5.2 /P).
        let rot = doc
            .get_dictionary(id)
            .ok()
            .and_then(|d| d.get(b"P").ok())
            .and_then(|o| o.as_reference().ok())
            .map(|pid| page_rotation(doc, pid))
            .unwrap_or(0);
        let (w, h, apm) = display_orientation(rot, r[2] - r[0], r[3] - r[1]);
        let size = field_font_size(da.size, h);
        // The widget shows the display string, not the export value.
        let content = build_text_appearance(value, w, h, size, &da_font, da.argb, quadding, false, false, 0);
        make_appearance_oriented(doc, w, h, content, da_res, apm)
    });

    // /V and /I are field keys (§12.7.3.1) and belong on the root; only /AP is
    // per-widget.
    if let Ok(dict) = doc.get_dictionary_mut(root_id) {
        dict.set("V", Object::string_literal(export));
        match opt_index {
            Some(i) => { dict.set("I", Object::Array(vec![Object::Integer(i as i64)])); }
            None => { dict.remove(b"I"); }
        }
    } else {
        return false;
    }
    if let Some(ap_id) = ap_id {
        if let Ok(dict) = doc.get_dictionary_mut(id) {
            let mut ap = Dictionary::new();
            ap.set("N", Object::Reference(ap_id));
            dict.set("AP", Object::Dictionary(ap));
        }
    }
    set_need_appearances(doc);
    true
}

/// Concatenate the visible text of every page, one blank line between pages.
fn all_pages_text(doc: &Document) -> String {
    let mut out = String::new();
    for (_num, page_id) in doc.get_pages() {
        if let Ok(pd) = interpret_page(doc, page_id) {
            let mut last_y = f32::NAN;
            for p in &pd.prims {
                if let Prim::Text { text, y, .. } = p {
                    if !last_y.is_nan() && (last_y - *y).abs() > 2.0 {
                        out.push('\n');
                    }
                    out.push_str(text);
                    last_y = *y;
                }
            }
        }
        out.push_str("\n\n");
    }
    out
}

/// Extract the document's visible text (from rendered text primitives), one
/// blank line between pages.
pub(crate) fn document_text(handle: i64) -> Option<String> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get(&handle)?;
    // Same hazard as `docedit::render_page`: `interpret_page` recurses for form
    // XObjects, tiling patterns, soft-mask groups and Type 3 glyphs, and those caps
    // bound the DEPTH but not the frame SIZE, so the real headroom is whatever the
    // calling thread happens to have — a JNI thread on Android. A guard-page fault
    // is not an unwind, so the JNI `catch_unwind` cannot turn it into a failed
    // extraction; it kills the process. The stack has to be pinned here rather than
    // at the boundary because `JNIEnv` is not `Send`. A panic is re-raised with its
    // payload so that boundary still sees it, and a spawn failure falls back to the
    // calling thread.
    Some(std::thread::scope(|s| {
        match std::thread::Builder::new()
            .name("pdf-text".to_owned())
            .stack_size(crate::docedit::RENDER_STACK_BYTES)
            .spawn_scoped(s, || all_pages_text(doc))
        {
            Ok(h) => match h.join() {
                Ok(r) => r,
                Err(payload) => std::panic::resume_unwind(payload),
            },
            Err(_) => all_pages_text(doc),
        }
    }))
}

// ---------------------------------------------------------------------------
// Document outline (bookmarks)
// ---------------------------------------------------------------------------

/// Resolve a destination (array, or named) to a 0-based page index, or -1.
pub(crate) fn resolve_dest(doc: &Document, dest: &Object, page_index: &HashMap<ObjectId, i32>) -> i32 {
    let arr = match dest {
        Object::Array(a) => Some(a.clone()),
        Object::Name(n) => named_dest(doc, n),
        Object::String(s, _) => named_dest(doc, s),
        _ => None,
    };
    if let Some(a) = arr {
        if let Some(first) = a.first() {
            if let Ok(id) = first.as_reference() {
                return page_index.get(&id).copied().unwrap_or(-1);
            }
        }
    }
    -1
}

/// Look up a named destination's explicit dest array via `/Dests` and the
/// `/Names` name tree.
pub(crate) fn named_dest(doc: &Document, name: &[u8]) -> Option<Vec<Object>> {
    let catalog = doc.catalog().ok()?;
    // Old-style /Dests dictionary.
    if let Some(Object::Dictionary(dests)) = catalog.get(b"Dests").ok().and_then(|o| deref(doc, o)) {
        if let Ok(v) = dests.get(name) {
            return dest_array(doc, v);
        }
    }
    // /Names /Dests name tree.
    let root = catalog
        .get(b"Names")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"Dests").ok())
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())?;
    let mut visited = std::collections::HashSet::new();
    search_name_tree(doc, root, name, &mut visited)
}

pub(crate) fn dest_array(doc: &Document, obj: &Object) -> Option<Vec<Object>> {
    match deref(doc, obj)? {
        Object::Array(a) => Some(a.clone()),
        Object::Dictionary(d) => d.get(b"D").ok().and_then(|o| dest_array(doc, o)),
        _ => None,
    }
}

pub(crate) fn search_name_tree(
    doc: &Document,
    node: &lopdf::Dictionary,
    name: &[u8],
    visited: &mut std::collections::HashSet<ObjectId>,
) -> Option<Vec<Object>> {
    search_name_tree_at(doc, node, name, visited, 0)
}

/// `visited` alone bounds the number of nodes but not the DEPTH: a name tree
/// that is one long chain of single-kid nodes recurses once per node, so a
/// malformed file could exhaust the (small) JNI thread stack. §7.9.6 name trees
/// are balanced, so a real one is never deep.
fn search_name_tree_at(
    doc: &Document,
    node: &lopdf::Dictionary,
    name: &[u8],
    visited: &mut std::collections::HashSet<ObjectId>,
    depth: u32,
) -> Option<Vec<Object>> {
    if depth > 64 {
        return None;
    }
    if let Some(Object::Array(names)) = node.get(b"Names").ok().and_then(|o| deref(doc, o)) {
        let mut i = 0;
        while i + 1 < names.len() {
            if names[i].as_str().ok() == Some(name) {
                return dest_array(doc, &names[i + 1]);
            }
            i += 2;
        }
    }
    if let Some(Object::Array(kids)) = node.get(b"Kids").ok().and_then(|o| deref(doc, o)) {
        for kid in kids {
            if let Ok(id) = kid.as_reference() {
                if !visited.insert(id) {
                    continue;
                }
                if let Ok(child) = doc.get_dictionary(id) {
                    if let Some(r) = search_name_tree_at(doc, child, name, visited, depth + 1) {
                        return Some(r);
                    }
                }
            }
        }
    }
    None
}
