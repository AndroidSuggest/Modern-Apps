/// Walk the outline linked-list/tree collecting `(level, pageIndex, title)`.
///
/// `visited` makes a circular `/Next` or `/First` terminate, and `out.len()` caps
/// the total. `level` additionally caps the RECURSION DEPTH: `visited` bounds the
/// node count but not the nesting, so a chain of thousands of single-child
/// entries would recurse once per entry and could exhaust a JNI thread's stack.
pub(crate) fn walk_outline(
    doc: &Document,
    start: Option<ObjectId>,
    level: u16,
    page_index: &HashMap<ObjectId, i32>,
    visited: &mut std::collections::HashSet<ObjectId>,
    out: &mut Vec<(u16, i32, String)>,
) {
    if level > 64 {
        return;
    }
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
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
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
