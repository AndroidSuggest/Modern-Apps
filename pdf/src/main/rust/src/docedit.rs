use crate::*;

/// Create a new empty PDF document and return its handle.
pub(crate) fn create_empty_document() -> i64 {
    let mut doc = Document::with_version("1.7");
    let pages_id = doc.add_object(dictionary! {
        "Type" => "Pages",
        "Kids" => Object::Array(vec![]),
        "Count" => 0,
    });
    let catalog_id = doc.add_object(dictionary! {
        "Type" => "Catalog",
        "Pages" => pages_id,
    });
    doc.trailer.set("Root", catalog_id);
    let handle = next_handle();
    registry().lock().unwrap().insert(handle, doc);
    handle
}

/// The document's `/Pages` root object id.
pub(crate) fn pages_root(doc: &Document) -> Option<ObjectId> {
    let root = doc.trailer.get(b"Root").ok().and_then(|o| o.as_reference().ok())?;
    let cat = doc.get_dictionary(root).ok()?;
    cat.get(b"Pages").ok().and_then(|o| o.as_reference().ok())
}

/// Append a page reference to the `/Pages` tree and refresh `/Count`.
/// Fix: handle indirect Kids array (common — was assuming inline, losing pages #34 high)
pub(crate) fn append_kid(doc: &mut Document, pages_id: ObjectId, page_id: ObjectId) {
    // Indirect ref case: Kids is Reference(id) holding Array
    let kids_ref_opt = doc.get_dictionary(pages_id).ok().and_then(|d| d.get(b"Kids").ok()).and_then(|o| {
        if let Object::Reference(id) = o { Some(*id) } else { None }
    });
    if let Some(kids_id) = kids_ref_opt {
        if let Ok(Object::Array(a)) = doc.get_object_mut(kids_id) {
            a.push(Object::Reference(page_id));
        }
        // compute count from that indirect array
        let cnt = doc.get_object(kids_id).ok().and_then(|o| o.as_array().ok()).map(|arr| arr.len() as i64).unwrap_or(0);
        if let Ok(pages) = doc.get_dictionary_mut(pages_id) {
            pages.set("Count", cnt);
        }
        return;
    }
    if let Ok(pages) = doc.get_dictionary_mut(pages_id) {
        let has = matches!(pages.get(b"Kids"), Ok(Object::Array(_)));
        if !has {
            pages.set("Kids", Object::Array(vec![]));
        }
        if let Ok(Object::Array(a)) = pages.get_mut(b"Kids") {
            a.push(Object::Reference(page_id));
        }
        let count = if let Ok(Object::Array(a)) = pages.get(b"Kids") { a.len() as i64 } else { 0 };
        pages.set("Count", count);
    }
}

/// Deep-copy an object, remapping any object references through `map`.
pub(crate) fn remap_object(obj: &Object, map: &HashMap<ObjectId, ObjectId>) -> Object {
    match obj {
        Object::Reference(id) => Object::Reference(*map.get(id).unwrap_or(id)),
        Object::Array(a) => Object::Array(a.iter().map(|o| remap_object(o, map)).collect()),
        Object::Dictionary(d) => {
            let mut nd = Dictionary::new();
            for (k, v) in d.iter() {
                nd.set(k.clone(), remap_object(v, map));
            }
            Object::Dictionary(nd)
        }
        Object::Stream(s) => {
            let mut ns = s.clone();
            let mut nd = Dictionary::new();
            for (k, v) in s.dict.iter() {
                nd.set(k.clone(), remap_object(v, map));
            }
            ns.dict = nd;
            Object::Stream(ns)
        }
        other => other.clone(),
    }
}

/// Append every page of the PDF in `bytes` to the document behind `handle`.
/// Returns the number of pages added (0 on failure/encrypted source).
pub(crate) fn append_pdf(handle: i64, bytes: &[u8]) -> i32 {
    let src = match Document::load_mem(bytes) {
        Ok(d) => d,
        Err(_) => return 0,
    };
    if src.trailer.get(b"Encrypt").is_ok() {
        return 0;
    }
    let mut reg = registry().lock().unwrap();
    let dest = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return 0,
    };
    let pages_id = match pages_root(dest) {
        Some(p) => p,
        None => return 0,
    };
    // Reserve fresh ids for every source object, then copy them in remapped.
    let mut map: HashMap<ObjectId, ObjectId> = HashMap::new();
    for old_id in src.objects.keys() {
        dest.max_id += 1;
        map.insert(*old_id, (dest.max_id, 0));
    }
    for (old_id, obj) in &src.objects {
        let new = remap_object(obj, &map);
        dest.objects.insert(map[old_id], new);
    }
    let mut added = 0;
    for (_num, src_page_id) in src.get_pages() {
        let new_page_id = match map.get(&src_page_id) {
            Some(id) => *id,
            None => continue,
        };
        // Resolve inherited MediaBox/Resources onto the imported page since its
        // parent is now our (attribute-less) Pages root.
        let mb = media_box(&src, src_page_id);
        let res = inherited(&src, src_page_id, b"Resources").map(|o| remap_object(o, &map));
        if let Ok(pd) = dest.get_dictionary_mut(new_page_id) {
            pd.set("Parent", Object::Reference(pages_id));
            if pd.get(b"MediaBox").is_err() {
                pd.set(
                    "MediaBox",
                    Object::Array(vec![mb[0].into(), mb[1].into(), mb[2].into(), mb[3].into()]),
                );
            }
            if pd.get(b"Resources").is_err() {
                if let Some(r) = res {
                    pd.set("Resources", r);
                }
            }
        }
        append_kid(dest, pages_id, new_page_id);
        added += 1;
    }
    added
}

/// Append a JPEG image as a new full-width page. Returns 1 on success.
pub(crate) fn append_image_page(handle: i64, jpeg: &[u8], img_w: u32, img_h: u32) -> i32 {
    if img_w == 0 || img_h == 0 {
        return 0;
    }
    let mut reg = registry().lock().unwrap();
    let dest = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return 0,
    };
    let pages_id = match pages_root(dest) {
        Some(p) => p,
        None => return 0,
    };
    let pw = 595.0_f64; // A4 width in points
    let ph = pw * img_h as f64 / img_w as f64;

    let mut img_dict = Dictionary::new();
    img_dict.set("Type", name_obj("XObject"));
    img_dict.set("Subtype", name_obj("Image"));
    img_dict.set("Width", Object::Integer(img_w as i64));
    img_dict.set("Height", Object::Integer(img_h as i64));
    img_dict.set("BitsPerComponent", Object::Integer(8));
    img_dict.set("ColorSpace", name_obj("DeviceRGB"));
    img_dict.set("Filter", name_obj("DCTDecode"));
    let img_id = dest.add_object(Stream::new(img_dict, jpeg.to_vec()));

    let content = format!("q {pw:.2} 0 0 {ph:.2} 0 0 cm /Im0 Do Q").into_bytes();
    let content_id = dest.add_object(Stream::new(dictionary! {}, content));

    let page = dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => Object::Array(vec![0.into(), 0.into(), pw.into(), ph.into()]),
        "Contents" => content_id,
        "Resources" => dictionary! {
            "XObject" => dictionary! { "Im0" => img_id },
        },
    };
    let page_id = dest.add_object(page);
    append_kid(dest, pages_id, page_id);
    1
}

/// Move the page at `from` to index `to` in the page order. Returns success.
pub(crate) fn move_page(handle: i64, from: usize, to: usize) -> bool {
    let mut reg = registry().lock().unwrap();
    let dest = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let pages_id = match pages_root(dest) {
        Some(p) => p,
        None => return false,
    };
    if let Ok(pages) = dest.get_dictionary_mut(pages_id) {
        if let Ok(Object::Array(a)) = pages.get_mut(b"Kids") {
            if from < a.len() && to < a.len() {
                let item = a.remove(from);
                a.insert(to, item);
                return true;
            }
        }
    }
    false
}

/// Delete the page at `index` from the page order (keeps orphan objects).
pub(crate) fn remove_page(handle: i64, index: usize) -> bool {
    let mut reg = registry().lock().unwrap();
    let dest = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let pages_id = match pages_root(dest) {
        Some(p) => p,
        None => return false,
    };
    if let Ok(pages) = dest.get_dictionary_mut(pages_id) {
        let removed = if let Ok(Object::Array(a)) = pages.get_mut(b"Kids") {
            if index < a.len() {
                a.remove(index);
                true
            } else {
                false
            }
        } else {
            false
        };
        if removed {
            let count = if let Ok(Object::Array(a)) = pages.get(b"Kids") { a.len() as i64 } else { 0 };
            pages.set("Count", count);
        }
        return removed;
    }
    false
}

/// Rotate the page at `index` by `delta` degrees (adjusts `/Rotate`).
pub(crate) fn rotate_page(handle: i64, index: i32, delta: i32) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let page_id = match nth_page_id(doc, index) {
        Some(p) => p,
        None => return false,
    };
    let cur = page_rotation(doc, page_id) as i32;
    let new = (((cur + delta) % 360) + 360) % 360;
    if let Ok(pd) = doc.get_dictionary_mut(page_id) {
        pd.set("Rotate", Object::Integer(new as i64));
        true
    } else {
        false
    }
}

/// Extract the page at `index` into a standalone one-page PDF, returned as bytes.
pub(crate) fn extract_page(handle: i64, index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let src = reg.get(&handle)?;
    let src_page_id = nth_page_id(src, index)?;

    let mut out = Document::with_version("1.7");
    let pages_id = out.add_object(dictionary! {
        "Type" => "Pages",
        "Kids" => Object::Array(vec![]),
        "Count" => 0,
    });
    let catalog_id = out.add_object(dictionary! {
        "Type" => "Catalog",
        "Pages" => pages_id,
    });
    out.trailer.set("Root", catalog_id);

    // Copy the whole source object graph, then attach just the chosen page.
    let mut map: HashMap<ObjectId, ObjectId> = HashMap::new();
    for old_id in src.objects.keys() {
        out.max_id += 1;
        map.insert(*old_id, (out.max_id, 0));
    }
    for (old_id, obj) in &src.objects {
        out.objects.insert(map[old_id], remap_object(obj, &map));
    }
    let new_page_id = *map.get(&src_page_id)?;
    let mb = media_box(src, src_page_id);
    let res = inherited(src, src_page_id, b"Resources").map(|o| remap_object(o, &map));
    let rot = page_rotation(src, src_page_id);
    drop(reg);

    if let Ok(pd) = out.get_dictionary_mut(new_page_id) {
        pd.set("Parent", Object::Reference(pages_id));
        if pd.get(b"MediaBox").is_err() {
            pd.set(
                "MediaBox",
                Object::Array(vec![mb[0].into(), mb[1].into(), mb[2].into(), mb[3].into()]),
            );
        }
        if pd.get(b"Resources").is_err() {
            if let Some(r) = res {
                pd.set("Resources", r);
            }
        }
        if rot != 0 {
            pd.set("Rotate", Object::Integer(rot));
        }
    }
    append_kid(&mut out, pages_id, new_page_id);

    let mut buf = Vec::new();
    out.save_to(&mut buf).ok()?;
    Some(buf)
}

/// Serialize page `index` (0-based) of the document behind `handle` into the
/// wire buffer, or `None` on any error.
pub(crate) fn render_page(handle: i64, index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap();
    let doc = reg.get(&handle)?;
    let pages = doc.get_pages();
    let page_id = *pages.get(&((index as u32) + 1))?;
    // Content stream size guard (DoS mitigation per plan §18): reject absurdly large page contents before full interpretation.
    if let Ok(dict) = doc.get_dictionary(page_id) {
        if let Ok(cont) = dict.get(b"Contents") {
            let estimate = match cont {
                Object::Reference(_) => 0usize, // indirect — hard to estimate cheaply, allow
                Object::Stream(s) => s.content.len(),
                Object::Array(a) => a.len() * 4096, // rough
                _ => 0,
            };
            if estimate > 25 * 1024 * 1024 { // 25MB single-page content cap
                return None;
            }
        }
    }
    let page = interpret_page(doc, page_id).ok()?;
    Some(wire::serialize(&page))
}


// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------

/// Serialize `handle` with streams deflate-compressed and unused objects pruned.
pub(crate) fn save_compressed(handle: i64) -> Option<Vec<u8>> {
    let bytes = save_document(handle)?;
    let mut doc = Document::load_mem(&bytes).ok()?;
    doc.compress();
    doc.prune_objects();
    let mut out = Vec::new();
    doc.save_to(&mut out).ok()?;
    Some(out)
}

/// Ensure page `page_id` has an inline `/Resources /XObject` mapping `name` -> `xid`.
pub(crate) fn add_page_xobject(doc: &mut Document, page_id: ObjectId, name: &str, xid: ObjectId) {
    // Resolve to an inline Resources dict on the page (copying a referenced one).
    let res_inline = matches!(
        doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Resources").ok()),
        Some(Object::Dictionary(_))
    );
    if !res_inline {
        let copied = doc
            .get_dictionary(page_id)
            .ok()
            .and_then(|d| d.get(b"Resources").ok())
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .cloned()
            .unwrap_or_else(Dictionary::new);
        if let Ok(p) = doc.get_dictionary_mut(page_id) {
            p.set("Resources", Object::Dictionary(copied));
        }
    }
    if let Ok(p) = doc.get_dictionary_mut(page_id) {
        if let Ok(Object::Dictionary(res)) = p.get_mut(b"Resources") {
            let has_xo = matches!(res.get(b"XObject"), Ok(Object::Dictionary(_)));
            if !has_xo {
                res.set("XObject", Object::Dictionary(Dictionary::new()));
            }
            if let Ok(Object::Dictionary(xo)) = res.get_mut(b"XObject") {
                xo.set(name, Object::Reference(xid));
            }
        }
    }
}

/// Append `content_id` (a content stream) to page `page_id`'s `/Contents`.
pub(crate) fn append_content(doc: &mut Document, page_id: ObjectId, content_id: ObjectId) {
    let current = doc.get_dictionary(page_id).ok().and_then(|d| d.get(b"Contents").ok()).cloned();
    let new_contents = match current {
        Some(Object::Reference(r)) => Object::Array(vec![Object::Reference(r), Object::Reference(content_id)]),
        Some(Object::Array(mut a)) => {
            a.push(Object::Reference(content_id));
            Object::Array(a)
        }
        _ => Object::Array(vec![Object::Reference(content_id)]),
    };
    if let Ok(p) = doc.get_dictionary_mut(page_id) {
        p.set("Contents", new_contents);
    }
}

/// Flatten every annotation's appearance into its page content stream, then drop
/// the annotations. Makes overlays (incl. redaction boxes) permanent.
pub(crate) fn flatten_document(handle: i64) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let page_ids: Vec<ObjectId> = doc.get_pages().values().copied().collect();
    for page_id in page_ids {
        // Collect (xobject name, appearance id, placement matrix) for each annot.
        let annot_ids: Vec<ObjectId> = match doc
            .get_dictionary(page_id)
            .ok()
            .and_then(|d| d.get(b"Annots").ok())
            .and_then(|o| deref(doc, o))
        {
            Some(Object::Array(a)) => a.iter().filter_map(|o| o.as_reference().ok()).collect(),
            _ => continue,
        };
        if annot_ids.is_empty() {
            continue;
        }
        let mut placements: Vec<(String, ObjectId, Mat)> = Vec::new();
        for (i, aid) in annot_ids.iter().enumerate() {
            let dict = match doc.get_dictionary(*aid) {
                Ok(d) => d,
                Err(_) => continue,
            };
            let flags = dict.get(b"F").ok().and_then(num).unwrap_or(0.0) as i64;
            if flags & 0b10 != 0 {
                continue;
            }
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => r,
                None => continue,
            };
            let ap_id = match dict.get(b"AP").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok())
                .and_then(|ap| ap.get(b"N").ok())
                .and_then(|n| n.as_reference().ok())
            {
                Some(id) => id,
                None => continue,
            };
            let (bbox, matrix) = match doc.get_object(ap_id).ok().and_then(|o| o.as_stream().ok()) {
                Some(s) => {
                    let bbox = s.dict.get(b"BBox").ok().and_then(|o| read_rect(doc, o)).unwrap_or([0.0, 0.0, 1.0, 1.0]);
                    let matrix = s.dict.get(b"Matrix").ok().and_then(read_matrix_obj).unwrap_or(IDENTITY);
                    (bbox, matrix)
                }
                None => continue,
            };
            let m = appearance_matrix(rect, bbox, matrix);
            placements.push((format!("Fl{}_{}", page_id.0, i), ap_id, m));
        }
        if placements.is_empty() {
            continue;
        }
        let mut content = String::new();
        for (name, _, m) in &placements {
            content.push_str(&format!(
                "q {:.4} {:.4} {:.4} {:.4} {:.4} {:.4} cm /{} Do Q ",
                m[0], m[1], m[2], m[3], m[4], m[5], name
            ));
        }
        let cid = doc.add_object(Stream::new(dictionary! {}, content.into_bytes()));
        append_content(doc, page_id, cid);
        for (name, ap_id, _) in &placements {
            add_page_xobject(doc, page_id, name, *ap_id);
        }
        if let Ok(p) = doc.get_dictionary_mut(page_id) {
            p.remove(b"Annots");
        }
    }
    true
}

/// Approximate per-string text length for advance estimation (byte count).
pub(crate) fn approx_text_len(op: &lopdf::content::Operation) -> f64 {
    if op.operator == "TJ" {
        if let Some(Object::Array(a)) = op.operands.first() {
            return a
                .iter()
                .map(|o| if let Object::String(s, _) = o { s.len() as f64 } else { 0.0 })
                .sum();
        }
        return 0.0;
    }
    op.operands
        .iter()
        .rev()
        .find_map(|o| if let Object::String(s, _) = o { Some(s.len() as f64) } else { None })
        .unwrap_or(0.0)
}

/// Rewrite a page's operator list, dropping text-show operators whose origin
/// falls within any redaction `rects` (page space). Heuristic advance tracking.
pub(crate) fn redact_operations(
    ops: Vec<lopdf::content::Operation>,
    rects: &[[f64; 4]],
) -> Vec<lopdf::content::Operation> {
    let mut out: Vec<lopdf::content::Operation> = Vec::with_capacity(ops.len());
    let mut ctm_stack: Vec<Mat> = Vec::new();
    let mut ctm = IDENTITY;
    let mut tm = IDENTITY;
    let mut lm = IDENTITY;
    let mut font_size = 0.0f64;
    let mut leading = 0.0f64;
    let mut char_spacing = 0.0f64;
    let mut h_scale = 1.0f64;
    let n = |o: Option<&Object>| o.and_then(num).unwrap_or(0.0);
    for op in ops {
        let operands = &op.operands;
        match op.operator.as_str() {
            "q" => ctm_stack.push(ctm),
            "Q" => {
                if let Some(m) = ctm_stack.pop() {
                    ctm = m;
                }
            }
            "cm" if operands.len() >= 6 => {
                let m = [
                    n(operands.first()), n(operands.get(1)), n(operands.get(2)),
                    n(operands.get(3)), n(operands.get(4)), n(operands.get(5)),
                ];
                ctm = mat_mul(&m, &ctm);
            }
            "BT" => {
                tm = IDENTITY;
                lm = IDENTITY;
            }
            "Tf" if operands.len() >= 2 => font_size = n(operands.get(1)),
            "TL" => leading = n(operands.first()),
            "Tc" => char_spacing = n(operands.first()),
            "Tz" => h_scale = n(operands.first()) / 100.0,
            "Tm" if operands.len() >= 6 => {
                let m = [
                    n(operands.first()), n(operands.get(1)), n(operands.get(2)),
                    n(operands.get(3)), n(operands.get(4)), n(operands.get(5)),
                ];
                tm = m;
                lm = m;
            }
            "Td" if operands.len() >= 2 => {
                lm = mat_mul(&translate(n(operands.first()), n(operands.get(1))), &lm);
                tm = lm;
            }
            "TD" if operands.len() >= 2 => {
                leading = -n(operands.get(1));
                lm = mat_mul(&translate(n(operands.first()), n(operands.get(1))), &lm);
                tm = lm;
            }
            "T*" => {
                lm = mat_mul(&translate(0.0, -leading), &lm);
                tm = lm;
            }
            "Tj" | "'" | "\"" | "TJ" => {
                if op.operator == "'" || op.operator == "\"" {
                    lm = mat_mul(&translate(0.0, -leading), &lm);
                    tm = lm;
                }
                let trm = mat_mul(&tm, &ctm);
                let (x, y) = (trm[4], trm[5]);
                let hit = rects.iter().any(|r| {
                    x >= r[0] - 1.0 && x <= r[2] + 1.0 && y >= r[1] - 2.0 && y <= r[3] + font_size + 2.0
                });
                let len = approx_text_len(&op);
                let adv = len * font_size * 0.5 * h_scale + len * char_spacing;
                if !hit {
                    out.push(op);
                }
                tm = mat_mul(&translate(adv, 0.0), &tm);
                continue;
            }
            _ => {}
        }
        out.push(op);
    }
    out
}

/// Permanently remove content under redaction annotations, cover with black, and
/// delete the annotations. Returns whether any redaction was applied.
/// Whether the document has any redaction annotations pending.
pub(crate) fn has_redactions(handle: i64) -> bool {
    let reg = registry().lock().unwrap();
    let doc = match reg.get(&handle) {
        Some(d) => d,
        None => return false,
    };
    for page_id in doc.get_pages().values().copied() {
        if let Some(Object::Array(annots)) = doc
            .get_dictionary(page_id)
            .ok()
            .and_then(|d| d.get(b"Annots").ok())
            .and_then(|o| deref(doc, o))
        {
            for a in annots {
                if let Some(dict) = a.as_reference().ok().and_then(|id| doc.get_dictionary(id).ok()) {
                    if matches!(dict.get(b"PdfRedact"), Ok(Object::Boolean(true))) {
                        return true;
                    }
                }
            }
        }
    }
    false
}

pub(crate) fn apply_redactions(handle: i64) -> bool {
    let mut reg = registry().lock().unwrap();
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let page_ids: Vec<ObjectId> = doc.get_pages().values().copied().collect();
    let mut applied = false;
    for page_id in page_ids {
        let annot_ids: Vec<ObjectId> = match doc
            .get_dictionary(page_id)
            .ok()
            .and_then(|d| d.get(b"Annots").ok())
            .and_then(|o| deref(doc, o))
        {
            Some(Object::Array(a)) => a.iter().filter_map(|o| o.as_reference().ok()).collect(),
            _ => continue,
        };
        let mut rects: Vec<[f64; 4]> = Vec::new();
        let mut redact_ids: Vec<ObjectId> = Vec::new();
        for aid in &annot_ids {
            if let Ok(dict) = doc.get_dictionary(*aid) {
                if matches!(dict.get(b"PdfRedact"), Ok(Object::Boolean(true))) {
                    if let Some(r) = dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                        rects.push(normalize_rect(r));
                        redact_ids.push(*aid);
                    }
                }
            }
        }
        if rects.is_empty() {
            continue;
        }
        let content = match doc.get_and_decode_page_content(page_id) {
            Ok(c) => c,
            Err(_) => continue,
        };
        let new_ops = redact_operations(content.operations, &rects);
        let mut bytes = lopdf::content::Content { operations: new_ops }.encode().unwrap_or_default();
        let mut cover = String::new();
        for r in &rects {
            cover.push_str(&format!(
                " q 0 0 0 rg {:.2} {:.2} {:.2} {:.2} re f Q",
                r[0], r[1], r[2] - r[0], r[3] - r[1]
            ));
        }
        bytes.extend_from_slice(cover.as_bytes());
        let cid = doc.add_object(Stream::new(dictionary! {}, bytes));
        if let Ok(p) = doc.get_dictionary_mut(page_id) {
            p.set("Contents", Object::Reference(cid));
        }
        for rid in redact_ids {
            remove_annot_ref(doc, page_id, rid);
            doc.objects.remove(&rid);
        }
        applied = true;
    }
    applied
}

pub(crate) fn save_document(handle: i64) -> Option<Vec<u8>> {
    let mut reg = registry().lock().unwrap();
    let doc = reg.get_mut(&handle)?;
    let mut buf = Vec::new();
    doc.save_to(&mut buf).ok()?;
    Some(buf)
}

