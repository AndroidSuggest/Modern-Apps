use crate::*;

/// Field type + name inherited through the widget's `/Parent` chain.
pub(crate) fn field_attr<'a>(doc: &'a Document, mut id: ObjectId, key: &[u8]) -> Option<&'a Object> {
    for _ in 0..16 {
        let dict = doc.get_dictionary(id).ok()?;
        if let Ok(v) = dict.get(key) {
            return Some(v);
        }
        id = dict.get(b"Parent").ok().and_then(|o| o.as_reference().ok())?;
    }
    None
}

/// Resolve a GoTo destination to a 0-based page index, or -1.
pub(crate) fn resolve_dest_page(doc: &Document, d: &Object, page_of: &HashMap<ObjectId, i32>) -> i32 {
    let d = deref(doc, d).unwrap_or(d);
    if let Object::Array(a) = d {
        if let Some(first) = a.first() {
            if let Ok(id) = first.as_reference() {
                return *page_of.get(&id).unwrap_or(&-1);
            }
        }
    }
    -1
}

/// Serialize link annotations for a page: rect (displayed space), destination
/// page (-1 if none), and URI (empty if none).
pub(crate) fn list_links(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);
    let mut page_of: HashMap<ObjectId, i32> = HashMap::new();
    for (n, id) in doc.get_pages() {
        page_of.insert(id, (n as i32) - 1);
    }
    let mut records: Vec<([f64; 4], i32, String)> = Vec::new();
    if let Some(Object::Array(annots)) = doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        for a in annots {
            let dict = match a.as_reference().ok().and_then(|id| doc.get_dictionary(id).ok()) {
                Some(d) => d,
                None => continue,
            };
            if dict.get(b"Subtype").ok().and_then(|o| o.as_name().ok()) != Some(b"Link".as_ref()) {
                continue;
            }
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (x0, y0) = transform(&base, n[0], n[1]);
                    let (x1, y1) = transform(&base, n[2], n[3]);
                    normalize_rect([x0, y0, x1, y1])
                }
                None => continue,
            };
            let mut dest_page = -1i32;
            let mut uri = String::new();
            if let Some(action) = dict.get(b"A").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()) {
                let s = action.get(b"S").ok().and_then(|o| o.as_name().ok());
                if s == Some(b"URI".as_ref()) {
                    if let Ok(u) = action.get(b"URI").and_then(|o| o.as_str()) {
                        uri = String::from_utf8_lossy(u).into_owned();
                    }
                } else if s == Some(b"GoTo".as_ref()) {
                    if let Ok(d) = action.get(b"D") {
                        dest_page = resolve_dest_page(doc, d, &page_of);
                    }
                }
            } else if let Ok(d) = dict.get(b"Dest") {
                dest_page = resolve_dest_page(doc, d, &page_of);
            }
            if dest_page >= 0 || !uri.is_empty() {
                records.push((rect, dest_page, uri));
            }
        }
    }
    let mut buf = Vec::new();
    buf.extend_from_slice(&(records.len() as u32).to_le_bytes());
    for (rect, dest, uri) in records {
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        buf.extend_from_slice(&dest.to_le_bytes());
        let b = uri.as_bytes();
        let len = b.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(len as u16).to_le_bytes());
        buf.extend_from_slice(&b[..len]);
    }
    Some(buf)
}

pub(crate) fn list_form_fields(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);

    // (widgetId, typeCode, rect, name, value, checked)
    let mut fields: Vec<(i64, u8, [f64; 4], String, String, u8)> = Vec::new();
    if let Some(Object::Array(annots)) = doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        for a in annots {
            let id = match a.as_reference() {
                Ok(id) => id,
                Err(_) => continue,
            };
            let dict = match doc.get_dictionary(id) {
                Ok(d) => d,
                Err(_) => continue,
            };
            let is_widget = dict.get(b"Subtype").ok().and_then(|o| o.as_name().ok())
                == Some(b"Widget".as_ref());
            let ft = field_attr(doc, id, b"FT").and_then(|o| o.as_name().ok());
            if !is_widget || ft.is_none() {
                continue;
            }
            let ft = ft.unwrap();
            let type_code = match ft {
                b"Tx" => 0u8,
                b"Btn" => 1u8,
                b"Ch" => 2u8,
                _ => 3u8,
            };
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (dx0, dy0) = transform(&base, n[0], n[1]);
                    let (dx1, dy1) = transform(&base, n[2], n[3]);
                    normalize_rect([dx0, dy0, dx1, dy1])
                }
                None => continue,
            };
            let name = field_attr(doc, id, b"T")
                .and_then(|o| o.as_str().ok())
                .map(decode_pdf_text)
                .unwrap_or_default();
            let value = field_attr(doc, id, b"V")
                .map(|o| match o {
                    Object::String(s, _) => decode_pdf_text(s),
                    Object::Name(n) => String::from_utf8_lossy(n).into_owned(),
                    _ => String::new(),
                })
                .unwrap_or_default();
            let checked = if type_code == 1 {
                let as_state = dict.get(b"AS").ok().and_then(|o| o.as_name().ok());
                let on = as_state.map(|s| s != b"Off").unwrap_or(false)
                    || (!value.is_empty() && value != "Off");
                on as u8
            } else {
                0
            };
            fields.push((encode_id(id), type_code, rect, name, value, checked));
        }
    }

    let mut buf = Vec::new();
    buf.extend_from_slice(&(fields.len() as u32).to_le_bytes());
    for (id, tc, rect, name, value, checked) in fields {
        buf.extend_from_slice(&id.to_le_bytes());
        buf.push(tc);
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        for s in [&name, &value] {
            let b = s.as_bytes();
            let len = b.len().min(u16::MAX as usize);
            buf.extend_from_slice(&(len as u16).to_le_bytes());
            buf.extend_from_slice(&b[..len]);
        }
        buf.push(checked);
    }
    Some(buf)
}

/// Set the AcroForm `/NeedAppearances` flag so conformant viewers regenerate
/// field appearances after a value change.
pub(crate) fn set_need_appearances(doc: &mut Document) {
    let acro_id = doc
        .catalog()
        .ok()
        .and_then(|c| c.get(b"AcroForm").ok())
        .and_then(|o| o.as_reference().ok());
    if let Some(id) = acro_id {
        if let Ok(af) = doc.get_dictionary_mut(id) {
            af.set("NeedAppearances", Object::Boolean(true));
        }
    }
}

/// Build the content stream for a text field's `/N` appearance, honoring
/// alignment (`/Q`: 0 left, 1 center, 2 right), multiline (line-wrapped) and
/// comb (one glyph per `/MaxLen` cell) fields. Widths are approximated with
/// Helvetica's ~0.5em average since exact metrics aren't needed for a legible
/// generated appearance.
fn build_text_appearance(
    value: &str,
    w: f64,
    h: f64,
    size: f64,
    quadding: i64,
    multiline: bool,
    comb: bool,
    max_len: usize,
) -> Vec<u8> {
    let char_w = size * 0.5;
    let mut body = String::new();
    body.push_str("q 0 0 0 rg BT /F1 ");
    body.push_str(&format!("{size} Tf "));

    if comb && max_len > 0 {
        // One glyph per cell, centered in each cell.
        let cell_w = w / max_len as f64;
        let base_y = (h - size) / 2.0;
        body.push_str(&format!("1 0 0 1 0 {base_y:.2} Tm "));
        for (i, ch) in value.chars().take(max_len).enumerate() {
            let cx = i as f64 * cell_w + (cell_w - char_w) / 2.0;
            body.push_str(&format!(
                "1 0 0 1 {cx:.2} 0 Tm ({}) Tj ",
                escape_pdf_literal(&ch.to_string())
            ));
        }
    } else if multiline {
        // Split on explicit newlines and greedily wrap to the box width.
        let leading = size * 1.15;
        let max_chars = ((w - 4.0) / char_w).floor().max(1.0) as usize;
        let mut lines: Vec<String> = Vec::new();
        for raw in value.split(['\n', '\r']) {
            if raw.is_empty() {
                lines.push(String::new());
                continue;
            }
            let mut cur = String::new();
            for word in raw.split(' ') {
                if cur.is_empty() {
                    cur = word.to_string();
                } else if cur.len() + 1 + word.len() <= max_chars {
                    cur.push(' ');
                    cur.push_str(word);
                } else {
                    lines.push(std::mem::take(&mut cur));
                    cur = word.to_string();
                }
            }
            lines.push(cur);
        }
        let mut y = h - size - 2.0;
        for line in lines {
            let x = aligned_x(&line, w, char_w, quadding);
            body.push_str(&format!(
                "1 0 0 1 {x:.2} {y:.2} Tm ({}) Tj ",
                escape_pdf_literal(&line)
            ));
            y -= leading;
            if y < -size {
                break;
            }
        }
    } else {
        let x = aligned_x(value, w, char_w, quadding);
        let y = (h - size) / 2.0;
        body.push_str(&format!(
            "1 0 0 1 {x:.2} {y:.2} Tm ({}) Tj ",
            escape_pdf_literal(value)
        ));
    }
    body.push_str("ET Q");
    body.into_bytes()
}

/// Horizontal text origin for a line given the box width, approximate glyph
/// width and `/Q` alignment.
fn aligned_x(line: &str, w: f64, char_w: f64, quadding: i64) -> f64 {
    let text_w = line.chars().count() as f64 * char_w;
    match quadding {
        1 => ((w - text_w) / 2.0).max(2.0),      // centered
        2 => (w - text_w - 2.0).max(2.0),        // right
        _ => 2.0,                                 // left
    }
}

pub(crate) fn set_text_field(handle: i64, widget_id: i64, value: &str) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let id = decode_id(widget_id);
    let rect = doc
        .get_dictionary(id)
        .ok()
        .and_then(|d| d.get(b"Rect").ok())
        .and_then(|o| read_rect(doc, o))
        .map(normalize_rect);
    // Field flags / alignment / comb length (Q may be inherited from AcroForm).
    let dict_ro = doc.get_dictionary(id).ok();
    let flags = dict_ro
        .and_then(|d| d.get(b"Ff").ok())
        .and_then(num)
        .unwrap_or(0.0) as u32;
    let multiline = flags & (1 << 12) != 0; // Ff bit 13
    let comb = flags & (1 << 24) != 0; // Ff bit 25
    let quadding = dict_ro
        .and_then(|d| d.get(b"Q").ok())
        .and_then(num)
        .unwrap_or(0.0) as i64;
    let max_len = dict_ro
        .and_then(|d| d.get(b"MaxLen").ok())
        .and_then(num)
        .unwrap_or(0.0) as usize;

    let ap_id = rect.map(|r| {
        let (w, h) = (r[2] - r[0], r[3] - r[1]);
        let size = (h - 4.0).clamp(6.0, 14.0);
        let content = build_text_appearance(value, w, h, size, quadding, multiline, comb, max_len);
        make_appearance(doc, w, h, content, helvetica_resources())
    });

    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("V", Object::string_literal(value));
        if let Some(ap_id) = ap_id {
            let mut ap = Dictionary::new();
            ap.set("N", Object::Reference(ap_id));
            dict.set("AP", Object::Dictionary(ap));
        }
    } else {
        return false;
    }
    set_need_appearances(doc);
    true
}

pub(crate) fn set_checkbox(handle: i64, widget_id: i64, on: bool) -> bool {
    let mut reg = registry().lock().unwrap();
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

    let state = if on { on_state } else { b"Off".to_vec() };
    if let Ok(dict) = doc.get_dictionary_mut(id) {
        dict.set("AS", Object::Name(state.clone()));
        dict.set("V", Object::Name(state));
        true
    } else {
        false
    }
}

/// Extract the document's visible text (from rendered text primitives), one
/// blank line between pages.
pub(crate) fn document_text(handle: i64) -> Option<String> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
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
    Some(out)
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
                    if let Some(r) = search_name_tree(doc, child, name, visited) {
                        return Some(r);
                    }
                }
            }
        }
    }
    None
}

/// Walk the outline linked-list/tree collecting `(level, pageIndex, title)`.
pub(crate) fn walk_outline(
    doc: &Document,
    start: Option<ObjectId>,
    level: u16,
    page_index: &HashMap<ObjectId, i32>,
    visited: &mut std::collections::HashSet<ObjectId>,
    out: &mut Vec<(u16, i32, String)>,
) {
    let mut cur = start;
    while let Some(id) = cur {
        if !visited.insert(id) || out.len() > 5000 {
            break;
        }
        let dict = match doc.get_dictionary(id) {
            Ok(d) => d,
            Err(_) => break,
        };
        let title = dict
            .get(b"Title")
            .ok()
            .and_then(|o| o.as_str().ok())
            .map(decode_pdf_text)
            .unwrap_or_default();
        let page = dict
            .get(b"Dest")
            .ok()
            .and_then(|o| deref(doc, o))
            .map(|d| resolve_dest(doc, d, page_index))
            .or_else(|| {
                dict.get(b"A")
                    .ok()
                    .and_then(|o| deref(doc, o))
                    .and_then(|o| o.as_dict().ok())
                    .and_then(|a| a.get(b"D").ok())
                    .and_then(|o| deref(doc, o))
                    .map(|d| resolve_dest(doc, d, page_index))
            })
            .unwrap_or(-1);
        out.push((level, page, title));

        if let Some(first) = dict.get(b"First").ok().and_then(|o| o.as_reference().ok()) {
            walk_outline(doc, Some(first), level + 1, page_index, visited, out);
        }
        cur = dict.get(b"Next").ok().and_then(|o| o.as_reference().ok());
    }
}

/// Serialized document outline: u32 count, then per entry
/// `u16 level, i32 pageIndex, u16 titleLen, [utf8]`.
pub(crate) fn list_outline(handle: i64) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
    let outlines_id = doc
        .catalog()
        .ok()
        .and_then(|c| c.get(b"Outlines").ok())
        .and_then(|o| o.as_reference().ok())?;
    let pages = doc.get_pages();
    let mut page_index = HashMap::new();
    for (i, (_, id)) in pages.iter().enumerate() {
        page_index.insert(*id, i as i32);
    }
    let first = doc
        .get_dictionary(outlines_id)
        .ok()
        .and_then(|d| d.get(b"First").ok())
        .and_then(|o| o.as_reference().ok());
    let mut items = Vec::new();
    let mut visited = std::collections::HashSet::new();
    walk_outline(doc, first, 0, &page_index, &mut visited, &mut items);

    let mut buf = Vec::new();
    buf.extend_from_slice(&(items.len() as u32).to_le_bytes());
    for (level, page, title) in items {
        buf.extend_from_slice(&level.to_le_bytes());
        buf.extend_from_slice(&page.to_le_bytes());
        let b = title.as_bytes();
        let len = b.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(len as u16).to_le_bytes());
        buf.extend_from_slice(&b[..len]);
    }
    Some(buf)
}

// ---------------------------------------------------------------------------
// Full-text search
// ---------------------------------------------------------------------------
