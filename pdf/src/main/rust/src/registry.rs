use crate::*;

// Document registry - bounded LRU to avoid long-running leak (P3 fix)
// ---------------------------------------------------------------------------

const MAX_REG_DOCS: usize = 8;

/// Process-wide registry of parsed documents keyed by an opaque `i64` handle,
/// mirroring the weather crate's `cached_backend`. Keeping the parsed
/// `Document` alive lets Kotlin re-render / re-scroll pages without re-parsing
/// the file. Bounded to `MAX_REG_DOCS` LRU to avoid leak when many PDFs are opened.
pub(crate) fn registry() -> &'static Mutex<HashMap<i64, Document>> {
    static REG: OnceLock<Mutex<HashMap<i64, Document>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(crate) fn next_handle() -> i64 {
    static NEXT: OnceLock<Mutex<i64>> = OnceLock::new();
    let m = NEXT.get_or_init(|| Mutex::new(0));
    let mut guard = m.lock().unwrap();
    *guard += 1;
    *guard
}

/// Parse `bytes` into a document and store it, returning a non-zero handle.
/// Encrypted documents are decrypted in place (with `password`, empty allowed);
/// supports RC4 and AES (V4/V5) standard security handlers. Returns 0 on parse
/// failure, wrong password, or unsupported (public-key) encryption.
pub(crate) fn open_document_pw(bytes: &[u8], password: &[u8]) -> i64 {
    let mut doc = match Document::load_mem(bytes) {
        Ok(d) => d,
        Err(_) => return 0,
    };
    if doc.trailer.get(b"Encrypt").is_ok() {
        if decrypt_in_place(&mut doc, password) != DecryptStatus::Ok {
            return 0;
        }
    }
    let handle = next_handle();
    let mut map = registry().lock().unwrap();
    // Bounded eviction to avoid unbounded leak (P3 fix)
    if map.len() >= MAX_REG_DOCS {
        if let Some(oldest) = map.keys().next().copied() {
            map.remove(&oldest);
            index_cache().lock().unwrap().remove(&oldest);
        }
    }
    map.insert(handle, doc);
    drop(map);
    handle
}

pub(crate) fn open_document(bytes: &[u8]) -> i64 {
    open_document_pw(bytes, b"")
}

pub(crate) fn page_count(handle: i64) -> i32 {
    let reg = registry().lock().unwrap();
    match reg.get(&handle) {
        Some(doc) => doc.get_pages().len() as i32,
        None => 0,
    }
}

pub(crate) fn close_document(handle: i64) {
    registry().lock().unwrap().remove(&handle);
    index_cache().lock().unwrap().remove(&handle);
}

// ---------------------------------------------------------------------------
// Compose / merge ("cut and glue")
// ---------------------------------------------------------------------------
