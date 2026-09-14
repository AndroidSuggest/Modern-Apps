/// Every object id referenced from the trailer or from any object in `doc`.
///
/// References are collected, not followed, so this cannot loop on a cyclic graph.
/// Used to decide whether an object that has just been detached is now unreachable.
fn referenced_object_ids(doc: &Document) -> std::collections::HashSet<ObjectId> {
    fn walk(obj: &Object, out: &mut std::collections::HashSet<ObjectId>) {
        match obj {
            Object::Reference(id) => {
                out.insert(*id);
            }
            Object::Array(a) => a.iter().for_each(|o| walk(o, out)),
            Object::Dictionary(d) => d.iter().for_each(|(_, v)| walk(v, out)),
            Object::Stream(s) => s.dict.iter().for_each(|(_, v)| walk(v, out)),
            _ => {}
        }
    }
    let mut out = std::collections::HashSet::new();
    for (_, v) in doc.trailer.iter() {
        walk(v, &mut out);
    }
    for obj in doc.objects.values() {
        walk(obj, &mut out);
    }
    out
}

/// Whether the document has any redaction annotations pending.
pub(crate) fn has_redactions(handle: i64) -> bool {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
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

/// Remove content under redaction annotations and cover the region with black,
/// then delete the annotations. Returns whether any redaction was applied.
///
/// SECURITY LIMITATION — this is NOT a true redaction. `redact_operations`
/// removes only text-showing operators whose ORIGIN falls inside a rect, using an
/// approximate advance, so it does not remove images, inline images, form
/// XObjects, shadings or vector artwork; nor text that starts outside the rect
/// and runs into it. For all of those the black rectangle only COVERS the
/// content, which remains extractable from the saved file. Anything relying on
/// this for confidentiality needs content-level removal per §12.5.6.24 first.
///
/// What IS guaranteed: the operators this does drop are gone from the file, not
/// merely hidden — the pre-redaction content stream is detached and, when nothing
/// else references it, deleted, so `save_document` cannot ship it.
///
/// Refuses the WHOLE operation, without touching the document, if any page's content
/// stream can only be recovered by the lenient tokenizer — see the comment at the
/// `page_operations` call below.
pub(crate) fn apply_redactions(handle: i64) -> bool {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let page_ids: Vec<ObjectId> = doc.get_pages().values().copied().collect();
    // Phase 1 collects the work for every page and may refuse outright; phase 2 is the
    // only part that mutates. Nothing is written until every page has been read, so a
    // refusal can never leave the document half-redacted.
    type PageWork = (ObjectId, Vec<[f64; 4]>, Vec<ObjectId>, Vec<lopdf::content::Operation>);
    let mut work: Vec<PageWork> = Vec::new();
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
        // §8.9.7: lopdf 0.36 parses inline images inside nom's `cut(...)`, so one inline
        // image fails the entire stream. Every other caller falls back to the lenient
        // tokenizer and draws whatever it recovered, but redaction must not: an operator
        // the recovery skipped is a text-show operator we never had the chance to drop,
        // and re-encoding the recovered list would also discard whatever it could not
        // tokenize. Either way we would paint the black box, delete the annotation and
        // hand back a file the user believes is redacted with the text still in it. That
        // is worse than not redacting, so fail loudly: the annotations stay, so
        // `has_redactions` stays true and the UI keeps offering the action, and no black
        // box appears to claim otherwise.
        let (ops, recovered) = crate::content::page_operations(doc, page_id);
        if recovered {
            if cfg!(debug_assertions) {
                eprintln!(
                    "[pdf_render/docedit] page {page_id:?}: strict content parse failed; \
                     refusing to redact a stream that could only be recovered leniently"
                );
            }
            return false;
        }
        work.push((page_id, rects, redact_ids, ops));
    }

    let mut applied = false;
    // The pre-redaction content streams, detached below. §12.5.6.24 wants the content
    // GONE, and `save_document` writes every object in the document — leaving them
    // behind ships the redacted text inside the file for any object dumper to read.
    let mut stale: Vec<ObjectId> = Vec::new();
    for (page_id, rects, redact_ids, ops) in work {
        let new_ops = redact_operations(ops, &rects);
        let encoded = encode_operations(new_ops).unwrap_or_default();
        // §7.8.2: the page content is one concatenated stream, and the redacted
        // operator list can end with an unbalanced `q ... cm` or an active clip.
        // Bracketing it in q/Q means the cover rectangles below are painted from
        // the default graphics state — otherwise a leftover CTM could translate
        // them off the region they must hide, or a leftover clip discard them
        // entirely. Same fix flatten_document applies for the same reason.
        let mut bytes = b"q\n".to_vec();
        bytes.extend_from_slice(&encoded);
        bytes.extend_from_slice(b"\nQ\n");
        let mut cover = String::new();
        for r in &rects {
            cover.push_str(&format!(
                " q 0 0 0 rg {:.2} {:.2} {:.2} {:.2} re f Q",
                r[0], r[1], r[2] - r[0], r[3] - r[1]
            ));
        }
        bytes.extend_from_slice(cover.as_bytes());
        stale.extend(doc.get_page_contents(page_id));
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
    if applied {
        // Only drop a detached stream nothing else still points at: a /Contents stream
        // may legitimately be shared between pages (page imports duplicate the object
        // graph, not the objects), and removing one of those would blank the other page.
        let live = referenced_object_ids(doc);
        for id in stale {
            if !live.contains(&id) {
                doc.objects.remove(&id);
            }
        }
    }
    applied
}

pub(crate) fn save_document(handle: i64) -> Option<Vec<u8>> {
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = reg.get_mut(&handle)?;
    let mut buf = Vec::new();
    doc.save_to(&mut buf).ok()?;
    Some(buf)
}
