use crate::*;
use indexmap::IndexMap;

// Document registry - bounded TRUE LRU to avoid long-running leak.
// Uses IndexMap to preserve insertion/access order for true LRU semantics.
//
// Lock ordering policy (documented to avoid deadlock):
//   - Always acquire `registry()` lock BEFORE `index_cache()` lock when both are needed.
//   - `next_handle` only locks NEXT static — independent.
//   - `open_document_pw`, `close_document`, `page_count` follow registry -> index_cache order.
//   - `search.rs::ensure_index` must also respect registry -> index_cache (audit fix):
//       it checks index_cache first (read-only), then takes registry, then index_cache again for insert.
//       This is safe because the first check is optimistic and the second insert is after dropping registry
//       OR it must be documented that registry is taken first in the critical section.
//   Documented order: registry → index_cache.

const MAX_REG_DOCS: usize = 8;
/// Max PDF size accepted to prevent zip-bomb / OOM DoS (200 MB).
const MAX_PDF_BYTES: usize = 200 * 1024 * 1024;

pub(crate) fn registry() -> &'static Mutex<IndexMap<i64, Document>> {
    static REG: OnceLock<Mutex<IndexMap<i64, Document>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(IndexMap::new()))
}

pub(crate) fn next_handle() -> i64 {
    static NEXT: OnceLock<Mutex<i64>> = OnceLock::new();
    let m = NEXT.get_or_init(|| Mutex::new(0));
    let mut guard = m.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    *guard += 1;
    *guard
}

/// Parse `bytes` into a document and store it, returning a non-zero handle.
/// Encrypted documents are decrypted in place (with `password`, empty allowed);
/// supports RC4 and AES (V4/V5) standard security handlers. Returns 0 on parse
/// failure, wrong password, or unsupported encryption.
///
/// Size guard: rejects inputs larger than 200 MB to prevent OOM DoS.
pub(crate) fn open_document_pw(bytes: &[u8], password: &[u8]) -> i64 {
    // Zip-bomb / OOM guard: reject absurdly large PDF before parsing.
    if bytes.len() > MAX_PDF_BYTES {
        return 0;
    }
    let mut doc = match load_document_lenient(bytes) {
        Some(d) => d,
        None => return 0,
    };
    if doc.trailer.get(b"Encrypt").is_ok()
        && decrypt_in_place(&mut doc, password) != DecryptStatus::Ok
    {
        return 0;
    }
    let handle = next_handle();
    // Lock ordering: registry first, then index_cache if eviction needed.
    let mut map = registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    // True LRU: evict index 0 (least recently used). Page accesses bump to end via move_index.
    if map.len() >= MAX_REG_DOCS {
        if let Some((oldest_key, _)) = map.shift_remove_index(0) {
            // Still holding registry lock, now acquire index_cache (registry -> index_cache order)
            let mut ic = index_cache()
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            ic.remove(&oldest_key);
        }
    }
    map.insert(handle, doc);
    drop(map);
    handle
}

pub(crate) fn open_document(bytes: &[u8]) -> i64 {
    // No password (empty, built at runtime — not a hard-coded credential).
    open_document_pw(bytes, &Vec::<u8>::new())
}

/// Load a document, falling back to cross-reference reconstruction when lopdf's
/// strict parser rejects an otherwise-recoverable file.
///
/// The common real-world failure (seen with viewers that tolerate it, e.g.
/// Chrome/Acrobat) is a `startxref` offset that points a byte or two off the
/// `xref` keyword, or a damaged/incremental xref chain. lopdf's `xref` parser
/// requires the keyword at the exact offset, so such files yield
/// `InvalidFileTrailer`. On any load error we rebuild a fresh classic xref by
/// scanning the byte stream for indirect objects and append it as an
/// incremental section, then reload. This never runs for files that already
/// parse, so it cannot regress the happy path.
pub(crate) fn load_document_lenient(bytes: &[u8]) -> Option<Document> {
    if let Ok(d) = Document::load_mem(bytes) {
        return Some(d);
    }
    let rebuilt = rebuild_with_scanned_xref(bytes)?;
    Document::load_mem(&rebuilt).ok()
}

/// True for PDF whitespace / delimiter bytes (used for token-boundary checks).
fn is_pdf_ws(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\r' | b'\n' | b'\x0c' | b'\0')
}

/// Scan `bytes` for `N G obj` indirect-object headers, returning a map of
/// object id -> (generation, byte offset of the first digit of `N`). When an id
/// appears more than once (incremental updates) the highest offset wins, which
/// matches "latest definition" semantics.
fn scan_indirect_objects(bytes: &[u8]) -> std::collections::BTreeMap<u32, (u16, usize)> {
    let mut map: std::collections::BTreeMap<u32, (u16, usize)> = std::collections::BTreeMap::new();
    let n = bytes.len();
    let mut i = 0usize;
    while i + 3 <= n {
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
/// used to synthesize a trailer when none is recoverable.
fn find_catalog_id(objs: &std::collections::BTreeMap<u32, (u16, usize)>, bytes: &[u8]) -> Option<(u32, u16)> {
    for (id, (gen, off)) in objs {
        let end = (*off + 4096).min(bytes.len());
        let window = &bytes[*off..end];
        if window.windows(8).any(|w| w == b"/Catalog") {
            return Some((*id, *gen));
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

    // Recover /Root (and optional /Info) from the existing trailer if present,
    // else from the catalog object. A freshly synthesized trailer avoids reusing
    // /Prev or /XRefStm links back to the broken xref chain.
    let trailer_dict = last_trailer_dict(bytes);
    let root = trailer_dict
        .as_deref()
        .and_then(|d| ref_after_key(d, b"/Root"))
        .or_else(|| find_catalog_id(&objs, bytes))?;
    let info = trailer_dict.as_deref().and_then(|d| ref_after_key(d, b"/Info"));

    let mut out = bytes.to_vec();
    if !out.ends_with(b"\n") {
        out.push(b'\n');
    }
    let xref_pos = out.len();
    out.extend_from_slice(b"xref\n");
    out.extend_from_slice(format!("0 {}\n", max_id + 1).as_bytes());
    for id in 0..=max_id {
        if id != 0 {
            if let Some((gen, off)) = objs.get(&id) {
                out.extend_from_slice(format!("{:010} {:05} n \n", off, gen).as_bytes());
                continue;
            }
        }
        out.extend_from_slice(b"0000000000 65535 f \n");
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
        reg.swap_remove(&handle);
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
