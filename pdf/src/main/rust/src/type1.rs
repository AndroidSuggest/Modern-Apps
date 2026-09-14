//! Minimal Type 1 (`/FontFile`) font parser: eexec decryption, `/CharStrings`
//! and `/Subrs` extraction, and a Type 1 charstring interpreter that produces
//! flattened glyph outlines in 1000-unit em space.
//!
//! Scope: the operators real Type 1 fonts use for glyph outlines — hsbw/sbw,
//! move/line/curve, closepath, callsubr/return, div, seac (accent composition),
//! callothersubr/pop (flex + hint replacement), endchar. Hint operators
//! (hstem/vstem/…) are parsed and ignored. Malformed input yields `None` or a
//! partial outline rather than panicking.

use crate::outlines::ContourBuilder;
use std::collections::HashMap;

pub(crate) struct Type1Font {
    /// Glyph name -> flattened contours in 1000-unit em space.
    pub(crate) glyphs: HashMap<String, Vec<Vec<(f64, f64)>>>,
    /// Built-in `/Encoding`: char code -> glyph name.
    pub(crate) encoding: HashMap<u32, String>,
    /// `/FontMatrix` (defaults to 0.001 scale) mapping glyph space -> text space.
    pub(crate) font_matrix: [f64; 6],
}

/// Type 1 / eexec decryption (Adobe Type 1 Font Format §7). Decrypts `cipher`
/// with the running key seeded at `r`, then drops the first `skip` plaintext
/// bytes (4 for eexec, `lenIV` for charstrings).
fn decrypt(cipher: &[u8], r0: u16, skip: usize) -> Vec<u8> {
    let c1: u16 = 52845;
    let c2: u16 = 22719;
    let mut r = r0;
    let mut out = Vec::with_capacity(cipher.len());
    for &c in cipher {
        let p = c ^ (r >> 8) as u8;
        r = (c as u16).wrapping_add(r).wrapping_mul(c1).wrapping_add(c2);
        out.push(p);
    }
    if skip >= out.len() {
        Vec::new()
    } else {
        out.split_off(skip)
    }
}

/// Locate the `eexec` keyword and return the encrypted section as raw bytes,
/// decoding it from ASCII-hex when the font stores that section as hex.
fn extract_eexec(data: &[u8]) -> Option<Vec<u8>> {
    let pos = find(data, b"eexec")?;
    let mut i = pos + 5;
    // Skip whitespace after `eexec`.
    while i < data.len() && matches!(data[i], b' ' | b'\r' | b'\n' | b'\t') {
        i += 1;
    }
    let section = &data[i..];
    // Hex-encoded if the first bytes are all hex digits (and there's whitespace,
    // which binary sections almost never begin with 4 hex + space patterns).
    let is_hex = section.iter().take(4).all(|b| b.is_ascii_hexdigit());
    if is_hex {
        let mut bytes = Vec::new();
        let mut hi: Option<u8> = None;
        for &b in section {
            let v = match b {
                b'0'..=b'9' => b - b'0',
                b'a'..=b'f' => b - b'a' + 10,
                b'A'..=b'F' => b - b'A' + 10,
                b' ' | b'\r' | b'\n' | b'\t' => continue,
                _ => break,
            };
            match hi.take() {
                None => hi = Some(v),
                Some(h) => bytes.push((h << 4) | v),
            }
        }
        Some(decrypt(&bytes, 55665, 4))
    } else {
        Some(decrypt(section, 55665, 4))
    }
}

fn find(hay: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || hay.len() < needle.len() {
        return None;
    }
    hay.windows(needle.len()).position(|w| w == needle)
}

fn find_from(hay: &[u8], needle: &[u8], start: usize) -> Option<usize> {
    if start >= hay.len() {
        return None;
    }
    find(&hay[start..], needle).map(|p| p + start)
}

/// Find a whitespace-delimited PostScript token. Searching for a bare `end`
/// substring would match inside a glyph name such as `/endash`.
fn find_token(hay: &[u8], tok: &[u8], start: usize) -> Option<usize> {
    let mut at = start;
    while let Some(p) = find_from(hay, tok, at) {
        let before_ok = p == 0 || hay[p - 1].is_ascii_whitespace();
        let after = p + tok.len();
        let after_ok = after >= hay.len() || hay[after].is_ascii_whitespace();
        if before_ok && after_ok {
            return Some(p);
        }
        at = p + 1;
    }
    None
}

/// Parse `n` decimal integer tokens starting at/after `pos`, returning them with
/// the index just past the last one consumed.
fn read_int(data: &[u8], mut i: usize) -> Option<(i64, usize)> {
    while i < data.len() && data[i].is_ascii_whitespace() {
        i += 1;
    }
    let start = i;
    if i < data.len() && (data[i] == b'-' || data[i] == b'+') {
        i += 1;
    }
    while i < data.len() && data[i].is_ascii_digit() {
        i += 1;
    }
    if i == start {
        return None;
    }
    std::str::from_utf8(&data[start..i]).ok()?.parse::<i64>().ok().map(|v| (v, i))
}

/// Parse the cleartext `/Encoding` array (`dup <code> /<name> put`) into
/// code -> name. Recognizes the `StandardEncoding` shorthand.
fn parse_encoding(cleartext: &[u8]) -> HashMap<u32, String> {
    let mut enc = HashMap::new();
    if let Some(p) = find(cleartext, b"/Encoding") {
        let region = &cleartext[p..(p + 200).min(cleartext.len())];
        if find(region, b"StandardEncoding").is_some() {
            for (code, name) in STANDARD_ENCODING {
                enc.insert(*code as u32, (*name).to_string());
            }
            return enc;
        }
    }
    // Scan `dup <code> /<name> put` entries.
    let mut search = 0usize;
    while let Some(dp) = find_from(cleartext, b"dup ", search) {
        search = dp + 4;
        let (code, mut j) = match read_int(cleartext, dp + 4) {
            Some(v) => v,
            None => continue,
        };
        while j < cleartext.len() && cleartext[j].is_ascii_whitespace() {
            j += 1;
        }
        if j >= cleartext.len() || cleartext[j] != b'/' {
            continue;
        }
        j += 1;
        let ns = j;
        while j < cleartext.len() && !cleartext[j].is_ascii_whitespace() {
            j += 1;
        }
        if let Ok(name) = std::str::from_utf8(&cleartext[ns..j]) {
            if (0..256).contains(&code) {
                enc.insert(code as u32, name.to_string());
            }
        }
    }
    enc
}

fn parse_font_matrix(cleartext: &[u8]) -> [f64; 6] {
    let default = [0.001, 0.0, 0.0, 0.001, 0.0, 0.0];
    let p = match find(cleartext, b"/FontMatrix") {
        Some(p) => p,
        None => return default,
    };
    let lb = match find_from(cleartext, b"[", p) {
        Some(p) => p,
        None => return default,
    };
    let rb = match find_from(cleartext, b"]", lb) {
        Some(p) => p,
        None => return default,
    };
    let vals: Vec<f64> = std::str::from_utf8(&cleartext[lb + 1..rb])
        .unwrap_or("")
        .split_whitespace()
        .filter_map(|s| s.parse::<f64>().ok())
        .collect();
    if vals.len() == 6 {
        [vals[0], vals[1], vals[2], vals[3], vals[4], vals[5]]
    } else {
        default
    }
}

/// Read the `RD`/`-|` binary-data operator: after an integer length and the RD
/// token comes exactly one space and then `len` raw bytes. Returns the bytes and
/// the index just past them.
fn read_rd_binary(data: &[u8], len_pos: usize) -> Option<(Vec<u8>, usize)> {
    let (len, mut i) = read_int(data, len_pos)?;
    if len < 0 {
        return None;
    }
    // Skip whitespace, then the RD or -| token, then exactly one space.
    while i < data.len() && data[i].is_ascii_whitespace() {
        i += 1;
    }
    // token is `RD` or `-|`
    if i + 2 > data.len() {
        return None;
    }
    let tok = &data[i..i + 2];
    if tok != b"RD" && tok != b"-|" {
        return None;
    }
    i += 2;
    // exactly one binary-preceding space
    if i >= data.len() {
        return None;
    }
    i += 1;
    let end = i + len as usize;
    if end > data.len() {
        return None;
    }
    Some((data[i..end].to_vec(), end))
}

/// Extract `lenIV`, the `/Subrs` array, and the `/CharStrings` dictionary from
/// the decrypted eexec section, decrypting each charstring in place.
fn parse_private(dec: &[u8]) -> (Vec<Vec<u8>>, HashMap<String, Vec<u8>>) {
    let len_iv = find(dec, b"/lenIV")
        .and_then(|p| read_int(dec, p + 6))
        .map(|(v, _)| v.max(0) as usize)
        .unwrap_or(4);

    // --- Subrs: `dup <i> <len> RD <bytes> NP` ---
    // Both the declared count and each `dup` index come straight from the file and
    // size a heap allocation. A Type 1 font's Subrs array is at most a few
    // thousand entries; without a cap, `/Subrs 2000000000 array` (or one oversized
    // `dup` index) asks for tens of gigabytes before a single charstring is read.
    const MAX_SUBRS: usize = 65536;
    let charstrings_at = find(dec, b"/CharStrings");
    let mut subrs: Vec<Vec<u8>> = Vec::new();
    if let Some(sp) = find(dec, b"/Subrs") {
        if let Some((count, _)) = read_int(dec, sp + 6) {
            subrs = vec![Vec::new(); (count.max(0) as usize).min(MAX_SUBRS)];
        }
        let mut i = sp;
        let mut guard = 0;
        // The Subrs array ends where CharStrings begins. Tested BEFORE the entry
        // is stored, not after: a `dup <n> <len> RD` sequence occurring inside the
        // CharStrings dict (or in a glyph's binary) was otherwise loaded as subr
        // <n> — and could resize the subr table — before the loop noticed it had
        // walked past the end. Hoisted out of the loop as well: re-scanning the
        // whole decrypted font once per `dup` is quadratic, so a font with a few
        // thousand subrs spends longer hunting for this marker than it does
        // decrypting every charstring it has. Disabled entirely for the
        // pathological layout where /Subrs follows /CharStrings.
        let subrs_end = charstrings_at.filter(|&cs| cs > sp);
        while let Some(dp) = find_from(dec, b"dup ", i) {
            i = dp + 4;
            guard += 1;
            if guard > 100_000 {
                break;
            }
            if let Some(cs) = subrs_end {
                if dp > cs {
                    break;
                }
            }
            let (idx, j) = match read_int(dec, dp + 4) {
                Some(v) => v,
                None => continue,
            };
            let (bytes, next) = match read_rd_binary(dec, j) {
                Some(v) => v,
                None => continue,
            };
            i = next;
            if idx >= 0 && (idx as usize) < MAX_SUBRS {
                if (idx as usize) >= subrs.len() {
                    subrs.resize(idx as usize + 1, Vec::new());
                }
                subrs[idx as usize] = decrypt(&bytes, 4330, len_iv);
            }
        }
    }

    // --- CharStrings: `/<name> <len> RD <bytes> ND` ---
    let mut glyphs: HashMap<String, Vec<u8>> = HashMap::new();
    if let Some(cp) = charstrings_at {
        // Advance past the `begin` that opens the dict.
        let mut i = find_from(dec, b"begin", cp).map(|p| p + 5).unwrap_or(cp + 12);
        // `end` closes the dict. Hoisted: this is a fixed position, and searching
        // for it inside the loop makes a font whose CharStrings dict holds many
        // non-`RD` names quadratic in the size of the decrypted font.
        let dict_end = find_token(dec, b"end", cp);
        let mut guard = 0;
        while i < dec.len() {
            guard += 1;
            if guard > 500_000 {
                break;
            }
            // Find next `/name`.
            let slash = match find_from(dec, b"/", i) {
                Some(p) => p,
                None => break,
            };
            let ns = slash + 1;
            let mut je = ns;
            while je < dec.len() && !dec[je].is_ascii_whitespace() {
                je += 1;
            }
            let name = match std::str::from_utf8(&dec[ns..je]) {
                Ok(n) => n.to_string(),
                Err(_) => {
                    i = je;
                    continue;
                }
            };
            match read_rd_binary(dec, je) {
                Some((bytes, next)) => {
                    glyphs.insert(name, decrypt(&bytes, 4330, len_iv));
                    i = next;
                }
                None => {
                    i = je;
                    if let Some(ep) = dict_end {
                        if slash > ep {
                            break;
                        }
                    }
                }
            }
        }
    }

    (subrs, glyphs)
}

/// Interpret a decrypted Type 1 charstring into `out`, following `subrs` for
/// callsubr and `glyphs`/`encoding` for seac accent composition.
fn run_charstring(
    cs: &[u8],
    subrs: &[Vec<u8>],
    glyphs: &HashMap<String, Vec<u8>>,
    out: &mut ContourBuilder,
) {
    let mut st = Interp {
        stack: Vec::new(),
        ps_stack: Vec::new(),
        x: 0.0,
        y: 0.0,
        sbx: 0.0,
        flex_pts: Vec::new(),
        in_flex: false,
        subrs,
        glyphs,
        depth: 0,
    };
    st.exec(cs, out);
}

struct Interp<'a> {
    stack: Vec<f64>,
    ps_stack: Vec<f64>,
    x: f64,
    y: f64,
    sbx: f64,
    flex_pts: Vec<(f64, f64)>,
    in_flex: bool,
    subrs: &'a [Vec<u8>],
    glyphs: &'a HashMap<String, Vec<u8>>,
    depth: u32,
}

include!("type1_part1.rs");
include!("type1_part2.rs");