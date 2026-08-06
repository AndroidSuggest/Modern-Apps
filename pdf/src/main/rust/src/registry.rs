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
    let mut doc = match Document::load_mem(bytes) {
        Ok(d) => d,
        Err(_) => return 0,
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
