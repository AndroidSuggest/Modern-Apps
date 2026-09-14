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
    registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, doc);
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
    // The same untrusted, user-picked bytes that `open_document_pw` takes, so the same
    // door: raw `Document::load_mem` skips the §7.5.1 pre-scan that `load_document_lenient`
    // performs, and both hazards it exists for are unrecoverable once lopdf is entered —
    // unbounded recursion in the object parser is a guard-page fault, not an unwind, so
    // the JNI `catch_unwind` cannot see it, and a degenerate `/W [0 0 0]` cross-reference
    // stream is a multi-billion-iteration loop rather than an error. It also means a
    // damaged file the viewer can open can now be appended too.
    let src = match load_document_lenient(bytes) {
        Some(d) => d,
        None => return 0,
    };
    if src.trailer.get(b"Encrypt").is_ok() {
        return 0;
    }
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
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
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
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
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
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
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
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
    let mut reg = registry().lock().unwrap_or_else(|e| e.into_inner());
    let doc = match reg.get_mut(&handle) {
        Some(d) => d,
        None => return false,
    };
    let page_id = match nth_page_id(doc, index) {
        Some(p) => p,
        None => return false,
    };
    let cur = page_rotation(doc, page_id) as i32;
    // `delta` arrives raw from the JNI boundary, so `cur + delta` can overflow i32:
    // a debug panic WHILE HOLDING the registry mutex (poisoning it for every other
    // caller), and a silent wrap in release, where overflow checks are off. Reducing
    // both terms first keeps the sum below 720.
    let new = (cur.rem_euclid(360) + delta.rem_euclid(360)).rem_euclid(360);
    if let Ok(pd) = doc.get_dictionary_mut(page_id) {
        pd.set("Rotate", Object::Integer(new as i64));
        true
    } else {
        false
    }
}

/// Extract the page at `index` into a standalone one-page PDF, returned as bytes.
pub(crate) fn extract_page(handle: i64, index: i32) -> Option<Vec<u8>> {
    let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
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

/// Stack for the page-interpretation worker (see [`render_page`]).
///
/// Smaller than `registry::OPEN_STACK_BYTES` because the interpreter's recursion
/// is already bounded by a small constant (`MAX_GROUP_DEPTH`,
/// `MAX_PATTERN_RECURSION` and the form-XObject depth), unlike lopdf's object
/// parser which is bounded only by the pre-scan.
///
/// Shared by every entry point that enters the interpreter — `render_page` here,
/// `forms::document_text` and `search::ensure_index` — so the three cannot drift
/// apart and leave one path with less headroom than the others.
pub(crate) const RENDER_STACK_BYTES: usize = 8 * 1024 * 1024;

/// Serialize page `index` (0-based) of the document behind `handle` into the
/// wire buffer, or `None` on any error.
pub(crate) fn render_page(handle: i64, index: i32) -> Option<Vec<u8>> {
    // Poison-tolerant lock: if a prior page's interpretation panicked while this
    // lock was held, recover the guard instead of cascading a panic to every
    // subsequent page (the "crashes halfway" failure mode). Matches the recovery
    // policy documented in registry.rs.
    let reg = registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let doc = reg.get(&handle)?;
    let pages = doc.get_pages();
    // `index` arrives unvalidated from the JNI boundary as a `jint`, so it may be
    // negative. `(index as u32) + 1` panicked in debug for -1, and this panics WHILE
    // HOLDING the registry mutex, which poisons it — recoverable in production because
    // every lock here is poison-tolerant, but it takes out unrelated callers that are
    // not. Reject negatives up front, as `annotations::nth_page_id` does.
    let page_number = u32::try_from(index).ok()?.checked_add(1)?;
    let page_id = *pages.get(&page_number)?;
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
    // Interpretation recurses for form XObjects, tiling/shading patterns, soft-mask
    // groups and Type 3 glyphs. Each of those is depth-capped, but the frames are
    // large and the total headroom is otherwise a property of whichever thread
    // called in — on Android a JNI thread with a fraction of a desktop stack. Pin
    // it here for the same reason `registry::load_mem_on_big_stack` does at open:
    // a guard-page fault is not an unwind, so the JNI `catch_unwind` cannot turn it
    // into a failed render. A panic is re-raised with its payload so that boundary
    // still sees it, and a spawn failure falls back to the calling thread.
    let interpreted = std::thread::scope(|s| {
        match std::thread::Builder::new()
            .name("pdf-render".to_owned())
            .stack_size(RENDER_STACK_BYTES)
            .spawn_scoped(s, || interpret_page(doc, page_id))
        {
            Ok(h) => match h.join() {
                Ok(r) => r,
                Err(payload) => std::panic::resume_unwind(payload),
            },
            Err(_) => interpret_page(doc, page_id),
        }
    });
    let page = interpreted.ok()?;
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

include!("docedit_part1.rs");
include!("docedit_part2.rs");
include!("docedit_part3.rs");
include!("docedit_part4.rs");