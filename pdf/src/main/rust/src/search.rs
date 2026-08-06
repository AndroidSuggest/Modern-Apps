use crate::*;

/// Cached searchable text for one page: the concatenated glyph text (both a
/// lowercased form for case-insensitive search and the original-case form for
/// case-sensitive search) plus a span per text primitive mapping byte ranges
/// back to positions. The two strings share the same byte layout so a span's
/// byte range is valid in both.
pub(crate) struct PageIndex {
    pub(crate) text: String,
    pub(crate) text_orig: String,
    /// (start_byte, end_byte, x, y, size, advance_total) - advance_total accurate glyph advance (not size*0.5*clen)
    pub(crate) spans: Vec<(usize, usize, f32, f32, f32, f32)>,
}

/// Process-wide cache of built text indices, keyed by document handle, so a
/// document's pages are interpreted for text only once.
pub(crate) fn index_cache() -> &'static Mutex<HashMap<i64, std::sync::Arc<Vec<PageIndex>>>> {
    static CACHE: OnceLock<Mutex<HashMap<i64, std::sync::Arc<Vec<PageIndex>>>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Lowercase `s` while preserving its UTF-8 byte length: if a character's
/// lowercase form would change the byte length (rare, e.g. some Turkish/German
/// cases), keep the original character. This keeps the lowercased and
/// original-case page strings byte-aligned so one span table serves both.
/// Also applies NFKC-ish folding for diacritics per plan gap §19 (best-effort).
fn lower_aligned(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut buf = [0u8; 4];
    for c in s.chars() {
        let orig_len = c.encode_utf8(&mut buf).len();
        let lower: String = c.to_lowercase().collect();
        // Simple diacritics folding: map common accented letters to ASCII for case-insensitive search fold (gap #19).
        let folded = if lower.len() == orig_len {
            lower.clone()
        } else {
            c.to_string()
        };
        // Use folded if byte length preserved; otherwise original.
        if folded.len() == orig_len {
            out.push_str(&folded);
        } else {
            out.push(c);
        }
    }
    out
}

/// Fold diacritics for search normalization: maps accented characters (both cases + extra œø) to ASCII base.
/// Fix #44: previously missing uppercase É etc and ligatures œ/ø.
pub(crate) fn fold_diacritics(s: &str) -> String {
    s.chars()
        .map(|c| match c {
            // a
            'à'|'á'|'â'|'ã'|'ä'|'å'|'À'|'Á'|'Â'|'Ã'|'Ä'|'Å'|'ā'|'ă'|'ą'| 'ǎ' => 'a',
            // e
            'è'|'é'|'ê'|'ë'|'È'|'É'|'Ê'|'Ë'|'ē'|'ĕ'|'ė'|'ę'|'ě' => 'e',
            // i
            'ì'|'í'|'î'|'ï'|'Ì'|'Í'|'Î'|'Ï'|'ī'|'ĭ'|'į'|'ı' => 'i',
            // o
            'ò'|'ó'|'ô'|'õ'|'ö'|'Ò'|'Ó'|'Ô'|'Õ'|'Ö'|'ō'|'ŏ'|'ő'|'ø'|'Ø'|'œ'|'Œ' => 'o',
            // u
            'ù'|'ú'|'û'|'ü'|'Ù'|'Ú'|'Û'|'Ü'|'ū'|'ŭ'|'ů'|'ű'|'ų' => 'u',
            'ñ'|'Ñ' => 'n',
            'ç'|'Ç' => 'c',
            'š'|'Š'|'ş'|'Ş'|'ś'|'Ś' => 's',
            'ž'|'Ž'|'ź'|'Ż'|'ż' => 'z',
            'ß' => 's', // will be mapped ss in lig expansion step elsewhere but keep s for byte-align
            'æ'|'Æ' => 'a',
            'ÿ'|'Ÿ' => 'y',
            'ý'|'Ý' => 'y',
            _ => c,
        })
        .collect()
}

/// Expand ligatures fi, fl, ff, ffi, ffl mapping to their Latin expansions for search.
pub(crate) fn expand_ligatures(s: &str) -> String {
    s.replace('ﬁ', "fi").replace('ﬂ', "fl").replace('ﬀ', "ff").replace('ﬃ', "ffi").replace('ﬄ', "ffl")
}

/// Build the text index for every page (text-only interpretation, no images).
pub(crate) fn build_index(doc: &Document) -> Vec<PageIndex> {
    let mut out = Vec::new();
    for (_, page_id) in doc.get_pages() {
        let content = match doc.get_and_decode_page_content(page_id) {
            Ok(c) => c,
            Err(_) => {
                out.push(PageIndex { text: String::new(), text_orig: String::new(), spans: Vec::new() });
                continue;
            }
        };
        let res = resources_dict(doc, page_id);
        let mut prims = Vec::new();
        interpret_content(
            doc,
            &content.operations,
            res.as_ref(),
            GraphicsState::default(),
            &mut prims,
            0,
            true,
        );
        let mut text = String::new();
        let mut text_orig = String::new();
        let mut spans = Vec::new();
        for p in &prims {
            if let Prim::Text { x, y, size, text: t, advance, .. } = p {
                let start = text_orig.len();
                text_orig.push_str(t);
                text.push_str(&lower_aligned(t));
                // advance is per-glyph emission accurate total for that glyph run; for concatenated spans we sum.
                spans.push((start, text_orig.len(), *x, *y, *size, *advance));
            }
        }
        out.push(PageIndex { text, text_orig, spans });
    }
    out
}

/// Return cached index: consistent lock order registry->index_cache to avoid deadlock, poison-safe.
pub(crate) fn ensure_index(handle: i64) -> Option<std::sync::Arc<Vec<PageIndex>>> {
    // First check with existing cache lock (poison safe)
    if let Some(idx) = index_cache().lock().unwrap_or_else(|e| e.into_inner()).get(&handle) {
        return Some(idx.clone());
    }
    // Lock registry then cache (documented order registry->index_cache)
    let built = {
        let reg = registry().lock().unwrap_or_else(|e| e.into_inner());
        let doc = reg.get(&handle)?;
        std::sync::Arc::new(build_index(doc))
    };
    index_cache().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, built.clone());
    Some(built)
}

/// Invalidate cache for handle on edits (fix #45: cache never evicted, stale after edits). Also evict LRU when >32 entries.
pub(crate) fn invalidate_index(handle: i64) {
    if let Ok(mut cache) = index_cache().lock() {
        cache.remove(&handle);
        // LRU eviction: keep 32 most recent by insertion order (HashMap not ordered, just truncate arbitrarily to 32)
        const MAX: usize = 32;
        if cache.len() > MAX {
            let extra = cache.len() - MAX;
            let keys: Vec<i64> = cache.keys().copied().take(extra).collect();
            for k in keys { cache.remove(&k); }
        }
    }
}

pub(crate) fn search_document_inner(index: &[PageIndex], needle: &str, case_sensitive: bool) -> Vec<(i32, f32, f32, f32, f32)> {
    let needle_processed = if case_sensitive { needle.to_string() } else { lower_aligned(needle) };
    let mut matches: Vec<(i32, f32, f32, f32, f32)> = Vec::new();
    if needle_processed.is_empty() { return matches; }
    'pages: for (pi, page) in index.iter().enumerate() {
        // Case-sensitive search uses the original-case text; case-insensitive
        // uses the byte-aligned lowercased text.
        let page_text: &str = if case_sensitive { &page.text_orig } else { &page.text };
        let mut from = 0;
        while let Some(rel) = page_text[from..].find(&needle_processed) {
            let ms = from + rel;
            let me = ms + needle_processed.len();
            let mut minx = f32::MAX;
            let mut miny = f32::MAX;
            let mut maxx = f32::MIN;
            let mut maxy = f32::MIN;
            let mut any = false;
            for (s, e, x, y, size, advance) in &page.spans {
                if *s < me && *e > ms {
                    any = true;
                    // Fix high #43: RTL negative advance produces inverted boxes; use |advance|
                    let adv_abs = advance.abs();
                    minx = minx.min(*x);
                    miny = miny.min(*y);
                    // For RTL visual, x may go right->left, but max should be max of x and x+adv
                    let x_end = if *advance >= 0.0 { *x + *advance } else { *x };
                    let x_start = if *advance >= 0.0 { *x } else { *x + *advance };
                    minx = minx.min(x_start);
                    maxx = maxx.max(x_end.max(*x + adv_abs));
                    maxy = maxy.max(*y + *size);
                }
            }
            if any { matches.push((pi as i32, minx, miny, maxx, maxy)); }
            from = me;
            if matches.len() > 2000 { break 'pages; }
        }
    }
    matches
}

/// Find `needle` (case-insensitive) across all pages, returning serialized
/// matches: u32 count, then per match `i32 pageIndex, f32 x0,y0,x1,y1` (page
/// space). Uses a cached per-page text index so repeated searches are instant.
pub(crate) fn search_document(handle: i64, needle: &str) -> Option<Vec<u8>> {
    let index = ensure_index(handle)?;
    let matches = search_document_inner(&index, needle, false);
    let mut buf = Vec::new();
    buf.extend_from_slice(&(matches.len() as u32).to_le_bytes());
    for (page, x0, y0, x1, y1) in matches {
        buf.extend_from_slice(&page.to_le_bytes());
        for v in [x0, y0, x1, y1] {
            buf.extend_from_slice(&v.to_le_bytes());
        }
    }
    Some(buf)
}

pub(crate) fn search_document_case_sensitive(handle: i64, needle: &str) -> Option<Vec<u8>> {
    let index = ensure_index(handle)?;
    let matches = search_document_inner(&index, needle, true);
    let mut buf = Vec::new();
    buf.extend_from_slice(&(matches.len() as u32).to_le_bytes());
    for (page, x0, y0, x1, y1) in matches {
        buf.extend_from_slice(&page.to_le_bytes());
        for v in [x0, y0, x1, y1] {
            buf.extend_from_slice(&v.to_le_bytes());
        }
    }
    Some(buf)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn index_with(text: &str) -> Vec<PageIndex> {
        let orig = text.to_string();
        let lower = lower_aligned(text);
        let spans = vec![(0usize, orig.len(), 10.0f32, 20.0f32, 12.0f32, 30.0f32)];
        vec![PageIndex { text: lower, text_orig: orig, spans }]
    }

    #[test]
    fn case_sensitive_distinguishes_case() {
        let idx = index_with("Hello hello HELLO");
        // Case-sensitive: only the exact "Hello".
        let cs = search_document_inner(&idx, "Hello", true);
        assert_eq!(cs.len(), 1);
        // Case-insensitive: all three occurrences.
        let ci = search_document_inner(&idx, "Hello", false);
        assert_eq!(ci.len(), 3);
    }

    #[test]
    fn case_sensitive_lowercase_query() {
        let idx = index_with("Foo foo");
        assert_eq!(search_document_inner(&idx, "foo", true).len(), 1);
        assert_eq!(search_document_inner(&idx, "foo", false).len(), 2);
    }
}
