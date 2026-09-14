/// Widths for a Type 3 font: `/Widths` values are in glyph space and are scaled
/// to text space by the FontMatrix x-scale (rather than the /1000 used for
/// simple fonts).
fn type3_widths(doc: &Document, font: &lopdf::Dictionary, fm_scale: f64) -> (HashMap<u32, f64>, f64) {
    let mut widths = HashMap::new();
    let first_char = font.get(b"FirstChar").ok().and_then(|o| deref(doc, o)).and_then(num).unwrap_or(0.0) as u32;
    if let Some(Object::Array(arr)) = font.get(b"Widths").ok().and_then(|o| deref(doc, o)) {
        for (i, w) in arr.iter().enumerate() {
            if let Some(w) = deref(doc, w).and_then(num) {
                widths.insert(first_char.saturating_add(i as u32), w * fm_scale);
            }
        }
    }
    (widths, 0.0)
}

/// Widths for a simple (1-byte) font from `/Widths` + `/FirstChar`, with the
/// `/FontDescriptor /MissingWidth` fallback. Values are glyph units / 1000.
pub(crate) fn simple_widths(doc: &Document, font: &lopdf::Dictionary) -> (HashMap<u32, f64>, f64) {
    let mut widths = HashMap::new();
    // `/FirstChar` may be an indirect reference; `num` does not dereference, so
    // without the `deref` the whole width table silently shifts by FirstChar.
    let first_char = font
        .get(b"FirstChar")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(num)
        .unwrap_or(0.0) as u32;
    if let Some(Object::Array(arr)) = font.get(b"Widths").ok().and_then(|o| deref(doc, o)) {
        for (i, w) in arr.iter().enumerate() {
            if let Some(w) = deref(doc, w).and_then(num) {
                // `/FirstChar` is file-controlled and `num`'s `as u32` saturates at
                // u32::MAX, so `first_char + i` panics in debug and WRAPS in release
                // — putting the whole /Widths table on codes 0.. and charging every
                // glyph the wrong advance. Same hazard the `/W` array form carries.
                widths.insert(first_char.saturating_add(i as u32), w / 1000.0);
            }
        }
    }
    let missing = font
        .get(b"FontDescriptor")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"MissingWidth").ok())
        .and_then(|o| deref(doc, o))
        .and_then(num)
        .map(|v| v / 1000.0);
    // 9.6.2.1 Table 111: /MissingWidth is the width for codes the /Widths array
    // does not cover, and that includes every code when /Widths is absent. It is
    // only when the descriptor supplies no /MissingWidth either that a default is
    // invented — the spec default of 0 would stack a whole string on one point,
    // and the standard-14 AFM table fills in real metrics for the fonts where
    // this case is legitimate, so 0.5 applies to nothing but genuinely unknown
    // glyphs.
    let default_width = match missing {
        Some(w) => w,
        None if widths.is_empty() => 0.5,
        None => 0.0,
    };
    (widths, default_width)
}

/// Widths for a Type0/CID font from the descendant font's `/W` array + `/DW`.
/// The map is keyed by CID (== 2-byte code for Identity-H). Units glyph/1000.
pub(crate) fn cid_widths(doc: &Document, font: &lopdf::Dictionary) -> (HashMap<u32, f64>, f64) {
    let mut widths = HashMap::new();
    let mut default_width = 1.0; // /DW default is 1000 glyph units.

    let descendant = font
        .get(b"DescendantFonts")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| match o {
            Object::Array(a) => a.first(),
            _ => None,
        })
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok());

    let df = match descendant {
        Some(d) => d,
        None => return (widths, default_width),
    };

    if let Some(dw) = df.get(b"DW").ok().and_then(|o| deref(doc, o)).and_then(num) {
        default_width = dw / 1000.0;
    }

    // /W: [ c [w1 w2 ...]  cFirst cLast w  ... ]
    if let Some(Object::Array(w)) = df.get(b"W").ok().and_then(|o| deref(doc, o)) {
        let mut i = 0;
        while i < w.len() {
            let c = match deref(doc, &w[i]).and_then(num) {
                Some(v) => v as u32,
                None => break,
            };
            match w.get(i + 1).and_then(|o| deref(doc, o)) {
                Some(Object::Array(list)) => {
                    for (j, item) in list.iter().enumerate() {
                        if let Some(v) = deref(doc, item).and_then(num) {
                            // `c` is file-controlled and `num` saturates at
                            // u32::MAX, so `c + j` wraps in release and panics in
                            // debug. The range form is already clamped to MAX_CID;
                            // this form was not.
                            widths.insert(c.saturating_add(j as u32).min(MAX_CID), v / 1000.0);
                        }
                    }
                    i += 2;
                }
                _ => {
                    let c_last = w.get(i + 1).and_then(|o| deref(doc, o)).and_then(num);
                    let width = w.get(i + 2).and_then(|o| deref(doc, o)).and_then(num);
                    if let (Some(c_last), Some(width)) = (c_last, width) {
                        // CIDs are bounded at 65535 (PDF 9.7.4.3), so a corrupt or
                        // hostile `cLast` cannot drive a multi-billion-iteration loop.
                        let c_last = (c_last.max(0.0) as u32).min(MAX_CID);
                        for cid in c..=c_last {
                            widths.insert(cid, width / 1000.0);
                        }
                    }
                    i += 3;
                }
            }
        }
    }
    (widths, default_width)
}

/// Build a `code -> unicode char` map from an embedded simple TrueType font's
/// `/FontFile2` cmap, used to recover text from re-encoded subset fonts that
/// lack a `/ToUnicode` map. Empty if unavailable.
pub(crate) fn ttf_code_map(doc: &Document, font: &lopdf::Dictionary) -> HashMap<u32, char> {
    let ff = font
        .get(b"FontDescriptor")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"FontFile2").ok())
        .and_then(|o| deref(doc, o));
    match ff {
        Some(Object::Stream(s)) => ttf::code_to_unicode(&stream_data(s)),
        _ => HashMap::new(),
    }
}

/// Recover a Type 1 (`/FontFile`) font's built-in `/Encoding` array by scanning
/// the clear-text (pre-`eexec`) portion for `dup <code> /<name> put` entries and
/// mapping glyph names to Unicode. Empty if the font has no `/FontFile`.
pub(crate) fn type1_builtin_encoding(doc: &Document, font: &lopdf::Dictionary) -> HashMap<u32, char> {
    let ff = font
        .get(b"FontDescriptor")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"FontFile").ok())
        .and_then(|o| deref(doc, o));
    let stream = match ff {
        Some(Object::Stream(s)) => s,
        _ => return HashMap::new(),
    };
    let data = stream_data(stream);
    // Only the clear-text segment (`/Length1` bytes, or up to `eexec`) holds the
    // /Encoding array in ASCII.
    let len1 = stream
        .dict
        .get(b"Length1")
        .ok()
        .and_then(num)
        .map(|v| v as usize)
        .unwrap_or(data.len());
    let end = len1.min(data.len());
    let text = &data[..end];
    parse_type1_encoding_text(text)
}

/// Scan `dup <code> /<name> put` records from a Type 1 clear-text segment.
fn parse_type1_encoding_text(bytes: &[u8]) -> HashMap<u32, char> {
    let mut map = HashMap::new();
    let s = String::from_utf8_lossy(bytes);
    for line in s.split(['\n', '\r']) {
        // Tokens: dup <code> /<name> put
        let mut it = line.split_whitespace();
        loop {
            match it.next() {
                Some("dup") => {}
                Some(_) => continue,
                None => break,
            }
            let code = match it.next().and_then(|t| t.parse::<u32>().ok()) {
                Some(c) => c,
                None => break,
            };
            let name_tok = match it.next() {
                Some(t) if t.starts_with('/') => &t[1..],
                _ => break,
            };
            if it.next() == Some("put") {
                if let Some(c) = encoding::glyph_to_char(name_tok) {
                    map.insert(code, c);
                }
            }
            break;
        }
    }
    map
}

/// Recover a CFF (`/FontFile3`) font's built-in encoding as `code -> Unicode`
/// via its Encoding + charset. Empty on parse failure or for CIDFont CFF (which
/// is code-mapped through a CMap, not the CFF encoding).
pub(crate) fn cff_builtin_encoding(doc: &Document, font: &lopdf::Dictionary) -> HashMap<u32, char> {
    let ff = font
        .get(b"FontDescriptor")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"FontFile3").ok())
        .and_then(|o| deref(doc, o));
    let stream = match ff {
        Some(Object::Stream(s)) => s,
        _ => return HashMap::new(),
    };
    cff::builtin_encoding(&stream_data(stream))
}
