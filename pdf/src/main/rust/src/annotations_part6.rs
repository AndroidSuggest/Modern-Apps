pub(crate) fn list_annotations(handle: i64, page_index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get(&handle)?;
    let page_id = nth_page_id(doc, page_index)?;
    let base = page_base_matrix(doc, page_id);

    let mut records: Vec<(i64, u8, [f64; 4], u32, String)> = Vec::new();
    if let Some(Object::Array(annots)) = doc
        .get_dictionary(page_id)
        .ok()
        .and_then(|d| d.get(b"Annots").ok())
        .and_then(|o| deref(doc, o))
    {
        for a in annots {
            // Direct annotation dictionaries are skipped deliberately: the
            // `encode_id` in the record below is the handle the editor uses to
            // select, move and delete the annotation, and a direct dictionary
            // has no ObjectId to fill it with. Surfacing one under a sentinel id
            // would offer edit affordances that silently do nothing. §12.5.2
            // permits the direct form and `render_annotations` paints it, so
            // such an annotation is visible but not selectable — a known and
            // accepted divergence, not an oversight.
            let id = match a.as_reference() {
                Ok(id) => id,
                Err(_) => continue,
            };
            let dict = match doc.get_dictionary(id) {
                Ok(d) => d,
                Err(_) => continue,
            };
            let subtype = dict.get(b"Subtype").ok().and_then(|o| deref(doc, o).or(Some(o))).and_then(|o| o.as_name().ok());
            let code = subtype.map(subtype_code).unwrap_or(0);
            // Report rects in displayed space so the editor's hit-testing and
            // selection boxes line up with the (rotation-baked) render.
            let rect = match dict.get(b"Rect").ok().and_then(|o| read_rect(doc, o)) {
                Some(r) => {
                    let n = normalize_rect(r);
                    let (dx0, dy0) = transform(&base, n[0], n[1]);
                    let (dx1, dy1) = transform(&base, n[2], n[3]);
                    normalize_rect([dx0, dy0, dx1, dy1])
                }
                None => continue,
            };
            let color = annot_color(doc, dict);
            let contents = dict
                .get(b"Contents")
                .ok()
                .and_then(|o| o.as_str().ok())
                .map(decode_pdf_text)
                .unwrap_or_default();
            records.push((encode_id(id), code, rect, color, contents));
        }
    }

    let mut buf = Vec::new();
    buf.extend_from_slice(&(records.len() as u32).to_le_bytes());
    for (id, code, rect, color, contents) in records {
        buf.extend_from_slice(&id.to_le_bytes());
        buf.push(code);
        for v in rect {
            buf.extend_from_slice(&(v as f32).to_le_bytes());
        }
        buf.extend_from_slice(&color.to_le_bytes());
        let b = contents.as_bytes();
        let len = b.len().min(u16::MAX as usize);
        buf.extend_from_slice(&(len as u16).to_le_bytes());
        buf.extend_from_slice(&b[..len]);
    }
    Some(buf)
}
