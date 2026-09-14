
/// Cap on the bytes kept for one inline image when the stream is truncated.
const MAX_INLINE_DATA: usize = 64 * 1024 * 1024;

enum InlineResult {
    Image(Stream),
    Abort,
}

/// Parse a PDF numeric token leniently, returning an integer when the token has no
/// fractional part. Handles `.5`, `4.`, `+3`, `--5` and repeated decimal points.
fn parse_number(tok: &[u8]) -> NumTok {
    let mut neg = false;
    let mut i = 0;
    // Consume any run of signs. `--5` occurs in real files; Acrobat reads it as -5,
    // i.e. repeated minus signs do NOT toggle, so a single flag is correct here.
    while i < tok.len() && (tok[i] == b'+' || tok[i] == b'-') {
        if tok[i] == b'-' {
            neg = true;
        }
        i += 1;
    }
    let mut int_part: i64 = 0;
    let mut frac: f64 = 0.0;
    let mut scale = 0.1f64;
    let mut seen_dot = false;
    let mut overflow = false;
    let mut digits = 0usize;
    for &b in &tok[i..] {
        if b == b'.' {
            // A second '.' is malformed; treat the following digits as continuing
            // the fraction rather than discarding the whole token.
            seen_dot = true;
            continue;
        }
        if !b.is_ascii_digit() {
            // Trailing junk (e.g. `12abc`): keep what parsed.
            break;
        }
        digits += 1;
        let d = (b - b'0') as i64;
        if seen_dot {
            frac += d as f64 * scale;
            scale *= 0.1;
        } else {
            match int_part.checked_mul(10).and_then(|v| v.checked_add(d)) {
                Some(v) => int_part = v,
                None => overflow = true,
            }
        }
    }
    if digits == 0 {
        // A lone sign or dot. Zero is the harmless reading.
        return NumTok::Int(0);
    }
    if seen_dot || overflow {
        let v = int_part as f64 + frac;
        NumTok::Real(if neg { -v } else { v })
    } else {
        NumTok::Int(if neg { -int_part } else { int_part })
    }
}

enum NumTok {
    Int(i64),
    Real(f64),
}

impl From<NumTok> for Object {
    fn from(n: NumTok) -> Object {
        match n {
            NumTok::Int(i) => Object::Integer(i),
            NumTok::Real(r) => Object::Real(r as f32),
        }
    }
}

/// Byte offset just past a valid `EI` at or after `from` (skipping white space).
/// `None` when `from` is not a credible end-of-image position.
fn ei_at(d: &[u8], from: usize) -> Option<usize> {
    let mut i = from;
    // A truncated stream that simply ends where the data ends is acceptable.
    if i >= d.len() {
        return Some(d.len());
    }
    while i < d.len() && is_ws(d[i]) {
        i += 1;
    }
    if d.get(i) == Some(&b'E') && d.get(i + 1) == Some(&b'I') {
        let after = i + 2;
        // `EI` must be a complete token, not the start of `EIx`.
        if after == d.len() || !is_regular(d[after]) {
            return Some(after);
        }
    }
    None
}

/// Search for the `EI` that terminates inline-image data starting at `from`.
///
/// Returns `(end of image data, offset just past EI)`. A candidate must be
/// whitespace-delimited on both sides. The first pass additionally requires the
/// bytes after it to look like resumed content-stream operators, which is what
/// stops a chance `EI` inside pixel data from being accepted; only if no candidate
/// passes that test does a second pass take the first merely well-delimited one.
fn scan_for_ei(d: &[u8], from: usize) -> Option<(usize, usize)> {
    for strict in [true, false] {
        let mut i = from;
        while i + 1 < d.len() {
            if d[i] == b'E' && d[i + 1] == b'I' {
                let preceded = i > from && is_ws(d[i - 1]);
                let after = i + 2;
                let followed = after == d.len() || !is_regular(d[after]);
                if preceded && followed && (!strict || plausible_resume(&d[after..])) {
                    return Some((i - 1, after));
                }
            }
            i += 1;
        }
    }
    None
}

/// Whether `d` (the bytes right after a candidate `EI`) looks like a content stream
/// resuming, rather than more binary pixel data.
///
/// Accepts when a known operator turns up within [`EI_LOOKAHEAD`] bytes, or the
/// stream simply ends. Rejects as soon as a byte appears that could not occur in
/// content-stream syntax at this point, since binary data is dense in such bytes.
fn plausible_resume(d: &[u8]) -> bool {
    if d.is_empty() {
        return true;
    }
    let limit = d.len().min(EI_LOOKAHEAD);
    let mut i = 0;
    while i < limit {
        let b = d[i];
        if is_ws(b) {
            i += 1;
            continue;
        }
        // Content-stream syntax at this point is printable ASCII. A control byte or
        // a byte with the high bit set is the signature of pixel data.
        if !(0x20..=0x7e).contains(&b) {
            return false;
        }
        if b.is_ascii_alphabetic() || b == b'\'' || b == b'"' {
            let s = i;
            while i < d.len() && is_regular(d[i]) {
                i += 1;
            }
            // A keyword here is either an operator (good) or noise (bad).
            return is_known_operator(&d[s..i]);
        }
        // An operand or a delimiter: legitimate, but not yet proof. Keep looking for
        // the operator that must follow.
        i += 1;
    }
    // Ran out of look-ahead having seen only plausible bytes. Everything scanned was
    // printable ASCII, which binary pixel data essentially never is for 64 bytes.
    true
}

// ---------------------------------------------------------------------------
// Inline-image data length (§8.9.7)
// ---------------------------------------------------------------------------

fn abbr<'a>(dict: &'a Dictionary, short: &[u8], long: &[u8]) -> Option<&'a Object> {
    dict.get(short).or_else(|_| dict.get(long)).ok()
}

fn int_of(obj: Option<&Object>) -> Option<i64> {
    match obj? {
        Object::Integer(i) => Some(*i),
        Object::Real(r) => Some(*r as i64),
        _ => None,
    }
}

fn is_true(dict: &Dictionary, short: &[u8], long: &[u8]) -> bool {
    matches!(abbr(dict, short, long), Some(Object::Boolean(true)))
}

/// Candidate byte lengths for the raw data of an inline image, best first.
///
/// An empty result means the length is genuinely not computable and the caller must
/// fall back to scanning. Several candidates are returned when the component count
/// is ambiguous (a `/CS` naming a colour-space resource we cannot resolve here); the
/// caller keeps the one whose `EI` verifies, which is far safer than scanning.
fn inline_len_candidates(dict: &Dictionary, remaining: usize) -> Vec<usize> {
    // `/L` (and its `/Length` synonym) is authoritative and, since PDF 2.0, exists
    // precisely so that a consumer need not scan for `EI`.
    if let Some(l) = int_of(abbr(dict, b"L", b"Length")) {
        if l >= 0 && (l as u64) <= remaining as u64 {
            return vec![l as usize];
        }
    }
    // A filter makes the encoded length unknowable from the geometry.
    if abbr(dict, b"F", b"Filter").is_some() {
        return Vec::new();
    }
    let (Some(w), Some(h)) = (
        int_of(abbr(dict, b"W", b"Width")),
        int_of(abbr(dict, b"H", b"Height")),
    ) else {
        return Vec::new();
    };
    if w <= 0 || h <= 0 {
        return Vec::new();
    }
    let (w, h) = (w as u64, h as u64);

    // §8.9.6.2: a stencil mask has one bit per sample and no colour space at all,
    // which is exactly the case lopdf rejects for its missing /BPC and /CS.
    let stencil = is_true(dict, b"IM", b"ImageMask");
    let bpc = if stencil {
        1
    } else {
        match int_of(abbr(dict, b"BPC", b"BitsPerComponent")).unwrap_or(8) {
            v @ (1 | 2 | 4 | 8 | 16) => v as u64,
            _ => return Vec::new(),
        }
    };

    let ncomps: Vec<u64> = if stencil {
        vec![1]
    } else {
        colorspace_components(abbr(dict, b"CS", b"ColorSpace"))
    };

    let mut out = Vec::new();
    for n in ncomps {
        // ceil(W * ncomp * bpc / 8) per row, rows are byte-aligned (§8.9.5.1).
        let Some(bits) = w.checked_mul(n).and_then(|v| v.checked_mul(bpc)) else {
            continue;
        };
        let row = bits.div_ceil(8);
        let Some(total) = row.checked_mul(h) else { continue };
        if total <= remaining as u64 && total <= MAX_INLINE_DATA as u64 {
            out.push(total as usize);
        }
    }
    out
}

/// Candidate component counts for an inline image's `/CS`, best first.
///
/// §8.9.7 Table 93 abbreviates the device spaces as `/G`, `/RGB`, `/CMYK` and `/I`.
/// `/G` in particular is valid but missing from lopdf's accepted list, which is one
/// of the three ways a perfectly good inline image blanks a page today.
fn colorspace_components(cs: Option<&Object>) -> Vec<u64> {
    // No colour space at all on a non-stencil image is malformed (§8.9.7 requires one
    // unless /ImageMask is true), which is also where lopdf gives up entirely. Offer
    // the possible counts and let the `EI` check decide rather than guessing once.
    let Some(cs) = cs else { return vec![1, 3, 4] };
    match cs {
        Object::Name(n) => match n.as_slice() {
            b"DeviceGray" | b"G" | b"CalGray" => vec![1],
            b"DeviceRGB" | b"RGB" | b"CalRGB" => vec![3],
            b"DeviceCMYK" | b"CMYK" => vec![4],
            // Indexed is always one component per sample, whatever its base space.
            b"Indexed" | b"I" => vec![1],
            b"Lab" => vec![3],
            // A name referring to the page's /ColorSpace resources, which are not
            // available here. Offer the possible component counts in order of
            // real-world frequency; the caller keeps whichever lands on a verified
            // `EI`, which is far safer than scanning binary data for one.
            _ => vec![1, 3, 4],
        },
        Object::Array(a) => {
            let family = match a.first() {
                Some(Object::Name(n)) => n.as_slice(),
                _ => return vec![1, 3, 4],
            };
            match family {
                b"Indexed" | b"I" => vec![1],
                b"CalGray" => vec![1],
                b"CalRGB" | b"Lab" => vec![3],
                // /N lives in the ICC profile's stream dictionary, which an inline
                // image cannot carry, so the count stays ambiguous.
                b"ICCBased" => vec![3, 1, 4],
                // Arity is the length of the names array in element 1.
                b"DeviceN" => match a.get(1) {
                    Some(Object::Array(names)) if !names.is_empty() => vec![names.len() as u64],
                    _ => vec![1, 3, 4],
                },
                _ => vec![1, 3, 4],
            }
        }
        _ => vec![1, 3, 4],
    }
}

