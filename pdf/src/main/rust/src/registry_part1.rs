/// Scan `bytes` for `N G obj` indirect-object headers, returning a map of
/// object id -> (generation, byte offset of the first digit of `N`). When an id
/// appears more than once (incremental updates) the highest offset wins, which
/// matches "latest definition" semantics.
///
/// Stream bodies are skipped: binary stream data can contain a byte sequence that
/// looks like `<ws><digits><ws><digits>obj`, and because the highest offset wins such a
/// false match would OVERRIDE the real object's offset, pointing the rebuilt xref into
/// the middle of a stream.
fn scan_indirect_objects(bytes: &[u8]) -> std::collections::BTreeMap<u32, (u16, usize)> {
    let mut map: std::collections::BTreeMap<u32, (u16, usize)> = std::collections::BTreeMap::new();
    let n = bytes.len();
    let mut i = 0usize;
    while i + 3 <= n {
        // Jump over a stream body so its bytes cannot be mistaken for object headers.
        //
        // 7.3.8 puts the `stream` keyword straight after the dictionary's `>>`, so
        // requiring that boundary (as [`nesting_exceeds`] already does) keeps a name
        // like `/Substream`, or the word inside a literal string, from being taken for
        // the start of a body — which would skip forward to the next `endstream` and
        // lose every object header in between from the rebuilt table.
        if bytes[i..].starts_with(b"stream")
            && i > 0
            && (is_pdf_ws(bytes[i - 1]) || bytes[i - 1] == b'>')
        {
            let after = i + 6;
            match find_subsequence(bytes, b"endstream", after) {
                Some(end) => {
                    i = end + 9;
                    continue;
                }
                // Unterminated stream: nothing further can be trusted.
                None => break,
            }
        }
        if &bytes[i..i + 3] == b"obj"
            && (i + 3 == n || is_pdf_ws(bytes[i + 3]) || bytes[i + 3] == b'<' || bytes[i + 3] == b'[')
        {
            // Backtrack: <ws> <gen digits> <ws> <num digits>, ending just before `obj`.
            let mut p = i;
            while p > 0 && is_pdf_ws(bytes[p - 1]) {
                p -= 1;
            }
            let gen_end = p;
            while p > 0 && bytes[p - 1].is_ascii_digit() {
                p -= 1;
            }
            let gen_start = p;
            if gen_start == gen_end {
                i += 1;
                continue; // no generation number -> not a header (e.g. `endobj`)
            }
            while p > 0 && is_pdf_ws(bytes[p - 1]) {
                p -= 1;
            }
            let num_end = p;
            while p > 0 && bytes[p - 1].is_ascii_digit() {
                p -= 1;
            }
            let num_start = p;
            if num_start == num_end {
                i += 1;
                continue; // no object number
            }
            // Require the header to sit at a token boundary (start of file, or
            // preceded by whitespace / delimiter) to avoid matching digits that
            // are part of some larger token inside binary data.
            let boundary = num_start == 0 || is_pdf_ws(bytes[num_start - 1]) || bytes[num_start - 1] == b'>';
            let num = std::str::from_utf8(&bytes[num_start..num_end]).ok().and_then(|s| s.parse::<u32>().ok());
            let gen = std::str::from_utf8(&bytes[gen_start..gen_end]).ok().and_then(|s| s.parse::<u16>().ok());
            if boundary {
                if let (Some(num), Some(gen)) = (num, gen) {
                    let entry = map.entry(num).or_insert((gen, num_start));
                    if num_start >= entry.1 {
                        *entry = (gen, num_start);
                    }
                }
            }
            i += 3;
        } else {
            i += 1;
        }
    }
    map
}

/// Extract an indirect reference (`N G R`) that follows `key` inside a raw
/// trailer-dictionary byte slice.
fn ref_after_key(dict: &[u8], key: &[u8]) -> Option<(u32, u16)> {
    let pos = dict.windows(key.len()).position(|w| w == key)?;
    let mut i = pos + key.len();
    let n = dict.len();
    let skip_ws = |i: &mut usize| while *i < n && is_pdf_ws(dict[*i]) { *i += 1; };
    let read_uint = |i: &mut usize| -> Option<u64> {
        let s = *i;
        while *i < n && dict[*i].is_ascii_digit() { *i += 1; }
        if *i == s { return None; }
        std::str::from_utf8(&dict[s..*i]).ok()?.parse().ok()
    };
    skip_ws(&mut i);
    let num = read_uint(&mut i)? as u32;
    skip_ws(&mut i);
    let gen = read_uint(&mut i)? as u16;
    skip_ws(&mut i);
    if i < n && dict[i] == b'R' {
        Some((num, gen))
    } else {
        None
    }
}

/// Capture the last `trailer << ... >>` dictionary bytes (balanced `<< >>`).
fn last_trailer_dict(bytes: &[u8]) -> Option<Vec<u8>> {
    let kw = b"trailer";
    // Find the last occurrence of the `trailer` keyword.
    let mut search_from = 0usize;
    let mut last = None;
    while let Some(rel) = bytes[search_from..].windows(kw.len()).position(|w| w == kw) {
        let abs = search_from + rel;
        last = Some(abs);
        search_from = abs + kw.len();
    }
    let start_kw = last?;
    let mut i = start_kw + kw.len();
    let n = bytes.len();
    while i < n && is_pdf_ws(bytes[i]) {
        i += 1;
    }
    if i + 1 >= n || bytes[i] != b'<' || bytes[i + 1] != b'<' {
        return None;
    }
    let dict_start = i;
    let mut depth = 0i32;
    while i + 1 < n {
        if bytes[i] == b'<' && bytes[i + 1] == b'<' {
            depth += 1;
            i += 2;
        } else if bytes[i] == b'>' && bytes[i + 1] == b'>' {
            depth -= 1;
            i += 2;
            if depth == 0 {
                return Some(bytes[dict_start..i].to_vec());
            }
        } else {
            i += 1;
        }
    }
    None
}

/// Locate the object id whose body declares `/Type /Catalog` (the document root),
/// used to synthesize a trailer when none is recoverable. Scans highest id first: an
/// incrementally-updated file keeps its superseded catalogs at lower ids. An object
/// whose opening window holds both `/Catalog` and `/Type` wins outright; one holding
/// only `/Catalog` (a name used somewhere other than as the type) is kept as a
/// last-resort fallback. This is the last of three ways to recover `/Root`, reached
/// only when neither a `trailer` dictionary nor a cross-reference stream yielded one.
fn find_catalog_id(objs: &std::collections::BTreeMap<u32, (u16, usize)>, bytes: &[u8]) -> Option<(u32, u16)> {
    let mut fallback = None;
    for (id, (gen, off)) in objs.iter().rev() {
        let end = (*off + 4096).min(bytes.len());
        let window = &bytes[*off..end];
        if !window.windows(8).any(|w| w == b"/Catalog") {
            continue;
        }
        if window.windows(5).any(|w| w == b"/Type") {
            return Some((*id, *gen));
        }
        fallback = fallback.or(Some((*id, *gen)));
    }
    fallback
}

/// Recover `/Root` from a cross-reference STREAM dictionary (§7.5.8).
///
/// Files that use xref streams have no `trailer` keyword at all, so
/// [`last_trailer_dict`] finds nothing, and their catalog normally lives inside an
/// object stream where [`find_catalog_id`] cannot see it either — between them that
/// made recovery fail outright for essentially every modern PDF. The xref stream is
/// itself an ordinary top-level indirect object whose DICTIONARY is plain text (only
/// the body is compressed) and carries `/Root`, so it can simply be read.
///
/// Highest object id first, so an incremental update's xref stream wins over the one
/// it superseded. This is enough on its own: the rebuilt classic table lists the
/// ObjStm container objects, and lopdf expands every ObjStm it loads (reader.rs:306),
/// so a catalog inside one becomes reachable without needing type-2 entries.
fn root_from_xref_stream(
    objs: &std::collections::BTreeMap<u32, (u16, usize)>,
    bytes: &[u8],
) -> Option<(u32, u16)> {
    for (_, off) in objs.values().rev() {
        let end = (*off + 8192).min(bytes.len());
        let mut window = &bytes[*off..end];
        // Stop at the stream body: /Root is in the dictionary, and the body is binary
        // and could contain a byte sequence that looks like a reference.
        if let Some(p) = find_subsequence(window, b"stream", 0) {
            window = &window[..p];
        }
        if !window.windows(5).any(|w| w == b"/XRef") {
            continue;
        }
        if let Some(r) = ref_after_key(window, b"/Root") {
            return Some(r);
        }
    }
    None
}

/// Rebuild a parseable PDF by scanning for indirect objects and appending a
/// fresh classic cross-reference table + trailer pointing at the scanned
/// offsets. Returns the new byte buffer, or `None` if no usable root is found.
fn rebuild_with_scanned_xref(bytes: &[u8]) -> Option<Vec<u8>> {
    let objs = scan_indirect_objects(bytes);
    if objs.is_empty() {
        return None;
    }
    let max_id = *objs.keys().max()?;
    // The xref table used to be emitted as one 0..=max_id run, so a single bogus
    // `999999999 0 obj` header produced ~20 GB of free entries and OOM-killed the app.
    // A plausible file cannot have an id far beyond the number of objects we actually
    // found; bail rather than emit a table dominated by free entries.
    if max_id as usize > objs.len().saturating_mul(8).saturating_add(4096) {
        return None;
    }

    // Recover /Root (and optional /Info) from the existing trailer if present,
    // else from the catalog object. A freshly synthesized trailer avoids reusing
    // /Prev or /XRefStm links back to the broken xref chain.
    let trailer_dict = last_trailer_dict(bytes);
    let root = trailer_dict
        .as_deref()
        .and_then(|d| ref_after_key(d, b"/Root"))
        // An xref-stream file has no `trailer` keyword, so its /Root lives in the
        // cross-reference stream's own dictionary (A19).
        .or_else(|| root_from_xref_stream(&objs, bytes))
        .or_else(|| find_catalog_id(&objs, bytes))?;
    let info = trailer_dict.as_deref().and_then(|d| ref_after_key(d, b"/Info"));

    let mut out = bytes.to_vec();
    if !out.ends_with(b"\n") {
        out.push(b'\n');
    }
    let xref_pos = out.len();
    out.extend_from_slice(b"xref\n");
    // Emit one subsection per contiguous run of ids actually found, plus the mandatory
    // free entry for object 0. This keeps the table proportional to the objects present.
    out.extend_from_slice(b"0 1\n");
    out.extend_from_slice(b"0000000000 65535 f \n");
    let ids: Vec<u32> = objs.keys().copied().filter(|&id| id != 0).collect();
    let mut i = 0usize;
    while i < ids.len() {
        let start = ids[i];
        let mut j = i;
        while j + 1 < ids.len() && ids[j + 1] == ids[j] + 1 {
            j += 1;
        }
        out.extend_from_slice(format!("{} {}\n", start, j - i + 1).as_bytes());
        for &id in &ids[i..=j] {
            if let Some((gen, off)) = objs.get(&id) {
                out.extend_from_slice(format!("{:010} {:05} n \n", off, gen).as_bytes());
            }
        }
        i = j + 1;
    }
    out.extend_from_slice(b"trailer\n<<");
    out.extend_from_slice(format!("/Size {}", max_id + 1).as_bytes());
    out.extend_from_slice(format!("/Root {} {} R", root.0, root.1).as_bytes());
    if let Some((inum, igen)) = info {
        out.extend_from_slice(format!("/Info {} {} R", inum, igen).as_bytes());
    }
    out.extend_from_slice(b">>\n");
    out.extend_from_slice(format!("startxref\n{}\n%%EOF\n", xref_pos).as_bytes());
    Some(out)
}

pub(crate) fn page_count(handle: i64) -> i32 {
    let mut reg = registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    // Bump accessed entry to MRU for true LRU semantics
    if let Some(idx) = reg.get_index_of(&handle) {
        let len = reg.len();
        if len > 0 && idx + 1 < len {
            reg.move_index(idx, len - 1);
        }
        reg.get(&handle).map(|d| d.get_pages().len() as i32).unwrap_or(0)
    } else {
        0
    }
}

pub(crate) fn close_document(handle: i64) {
    {
        let mut reg = registry()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        // shift_remove, not swap_remove: swap_remove moves the LAST (most recently used)
        // entry into the freed slot, which destroys the ordering that eviction relies on
        // (`shift_remove_index(0)` in open_document_pw) and could evict a document the
        // user is actively viewing.
        reg.shift_remove(&handle);
    }
    // Lock ordering: registry released before index_cache, or registry -> index_cache. Here we already released registry, safe.
    // For consistency also support registry->index_cache, but separate scopes avoid holding both.
    let mut ic = index_cache()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    ic.remove(&handle);
}

// ---------------------------------------------------------------------------
// Compose / merge ("cut and glue")
// ---------------------------------------------------------------------------
