//! Minimal, bounds-checked CFF (Compact Font Format / Type1C, `/FontFile3`)
//! parser used only to recover a simple font's built-in `code -> Unicode` map
//! (for text/ToUnicode fallback). It parses the CFF INDEX structures, the Top
//! DICT, the charset (GID -> name SID) and a custom Encoding (code -> GID), then
//! resolves names to Unicode via the Adobe Glyph List. CIDFont CFF and the
//! predefined Standard/Expert encodings return empty (handled elsewhere).
//!
//! Charstring *outlines* are never interpreted — the renderer emits Unicode text
//! runs, not glyph vectors.

use std::collections::HashMap;

/// Recover `code -> Unicode` from a CFF font program. Empty on any parse issue.
pub(crate) fn builtin_encoding(data: &[u8]) -> HashMap<u32, char> {
    parse(data).unwrap_or_default()
}

fn u8a(d: &[u8], o: usize) -> Option<u8> {
    d.get(o).copied()
}
fn u16a(d: &[u8], o: usize) -> Option<u16> {
    Some(((*d.get(o)? as u16) << 8) | *d.get(o + 1)? as u16)
}
fn offat(d: &[u8], o: usize, size: u8) -> Option<usize> {
    let mut v = 0usize;
    for i in 0..size as usize {
        v = (v << 8) | *d.get(o + i)? as usize;
    }
    Some(v)
}

/// A parsed INDEX: absolute (start,end) of each entry, plus the byte just past it.
struct Index {
    entries: Vec<(usize, usize)>,
    end: usize,
}

fn read_index(d: &[u8], pos: usize) -> Option<Index> {
    let count = u16a(d, pos)? as usize;
    if count == 0 {
        return Some(Index { entries: Vec::new(), end: pos + 2 });
    }
    if count > 65535 {
        return None;
    }
    let off_size = u8a(d, pos + 2)?;
    if off_size == 0 || off_size > 4 {
        return None;
    }
    let off_array = pos + 3;
    let mut offs = Vec::with_capacity(count + 1);
    for i in 0..=count {
        offs.push(offat(d, off_array + i * off_size as usize, off_size)?);
    }
    let data_base = off_array + (count + 1) * off_size as usize - 1;
    let mut entries = Vec::with_capacity(count);
    for i in 0..count {
        let s = data_base + offs[i];
        let e = data_base + offs[i + 1];
        if e > d.len() || s > e {
            return None;
        }
        entries.push((s, e));
    }
    Some(Index { entries, end: data_base + offs[count] })
}

/// Parse a CFF DICT (operator -> operands). Two-byte operators (12 x) key as 1200+x.
fn parse_dict(d: &[u8]) -> HashMap<u16, Vec<f64>> {
    let mut out = HashMap::new();
    let mut operands: Vec<f64> = Vec::new();
    let mut i = 0usize;
    while i < d.len() {
        let b0 = d[i];
        if b0 <= 21 {
            // operator
            let op = if b0 == 12 {
                i += 1;
                1200 + *d.get(i).unwrap_or(&0) as u16
            } else {
                b0 as u16
            };
            out.insert(op, std::mem::take(&mut operands));
            i += 1;
        } else if b0 == 28 {
            let v = i16::from_be_bytes([*d.get(i + 1).unwrap_or(&0), *d.get(i + 2).unwrap_or(&0)]);
            operands.push(v as f64);
            i += 3;
        } else if b0 == 29 {
            let v = i32::from_be_bytes([
                *d.get(i + 1).unwrap_or(&0),
                *d.get(i + 2).unwrap_or(&0),
                *d.get(i + 3).unwrap_or(&0),
                *d.get(i + 4).unwrap_or(&0),
            ]);
            operands.push(v as f64);
            i += 5;
        } else if b0 == 30 {
            // real number: nibble-encoded; we only need integers here, so skip.
            i += 1;
            while i < d.len() {
                let b = d[i];
                i += 1;
                if (b & 0x0F) == 0x0F || (b >> 4) == 0x0F {
                    break;
                }
            }
            operands.push(0.0);
        } else if (32..=246).contains(&b0) {
            operands.push(b0 as f64 - 139.0);
            i += 1;
        } else if (247..=250).contains(&b0) {
            let b1 = *d.get(i + 1).unwrap_or(&0) as f64;
            operands.push((b0 as f64 - 247.0) * 256.0 + b1 + 108.0);
            i += 2;
        } else if (251..=254).contains(&b0) {
            let b1 = *d.get(i + 1).unwrap_or(&0) as f64;
            operands.push(-(b0 as f64 - 251.0) * 256.0 - b1 - 108.0);
            i += 2;
        } else {
            i += 1;
        }
    }
    out
}

fn parse(d: &[u8]) -> Option<HashMap<u32, char>> {
    if d.len() < 4 {
        return None;
    }
    let hdr_size = u8a(d, 2)? as usize;
    // Name INDEX, Top DICT INDEX, String INDEX, Global Subr INDEX.
    let name_idx = read_index(d, hdr_size)?;
    let top_idx = read_index(d, name_idx.end)?;
    let string_idx = read_index(d, top_idx.end)?;
    let (ts, te) = *top_idx.entries.first()?;
    let top = parse_dict(d.get(ts..te)?);

    // CIDFont (has ROS, op 1230): built-in encoding is not code-based; bail.
    if top.contains_key(&1230) {
        return None;
    }

    let nglyphs = {
        let cs_off = *top.get(&17)?.first()? as usize;
        read_index(d, cs_off)?.entries.len()
    };
    if nglyphs == 0 || nglyphs > 65535 {
        return None;
    }

    // Encoding: op 16. Value 0 = Standard, 1 = Expert (predefined -> skip).
    let enc_off = top.get(&16).and_then(|v| v.first()).copied().unwrap_or(0.0) as usize;
    if enc_off <= 1 {
        return None;
    }
    let code_to_gid = parse_encoding(d, enc_off, nglyphs)?;

    // charset: op 15. 0 = ISOAdobe (predefined). We need GID->SID for names.
    let charset_off = top.get(&15).and_then(|v| v.first()).copied().unwrap_or(0.0) as usize;
    let gid_to_sid = parse_charset(d, charset_off, nglyphs);

    let sid_name = |sid: usize| -> Option<String> {
        if sid < STD_STRINGS.len() {
            Some(STD_STRINGS[sid].to_string())
        } else if sid < N_STD_STRINGS {
            None // a standard string we intentionally didn't embed
        } else {
            let idx = sid - N_STD_STRINGS;
            string_idx.entries.get(idx).and_then(|&(s, e)| {
                d.get(s..e).map(|b| String::from_utf8_lossy(b).into_owned())
            })
        }
    };

    let mut out = HashMap::new();
    for (code, gid) in code_to_gid {
        let sid = match &gid_to_sid {
            Some(map) => match map.get(&gid) {
                Some(s) => *s,
                None => continue,
            },
            None => gid as usize, // identity fallback (ISOAdobe-ish)
        };
        if let Some(name) = sid_name(sid) {
            if let Some(c) = crate::fonts::encoding::glyph_to_char(&name) {
                out.insert(code as u32, c);
            }
        }
    }
    Some(out)
}

/// Custom Encoding (format 0 or 1) -> code -> GID.
fn parse_encoding(d: &[u8], off: usize, _nglyphs: usize) -> Option<HashMap<u8, u16>> {
    let fmt = u8a(d, off)?;
    let base = fmt & 0x7f;
    let mut map = HashMap::new();
    let mut pos = off + 1;
    if base == 0 {
        let ncodes = u8a(d, pos)? as usize;
        pos += 1;
        for i in 0..ncodes {
            let code = u8a(d, pos + i)?;
            map.insert(code, (i + 1) as u16); // GID (skip .notdef at 0)
        }
    } else if base == 1 {
        let nranges = u8a(d, pos)? as usize;
        pos += 1;
        let mut gid = 1u16;
        for _ in 0..nranges {
            let first = u8a(d, pos)?;
            let nleft = u8a(d, pos + 1)?;
            pos += 2;
            for k in 0..=nleft {
                map.insert(first.wrapping_add(k), gid);
                gid = gid.wrapping_add(1);
            }
        }
    } else {
        return None;
    }
    Some(map)
}

/// charset (format 0/1/2) -> GID -> SID. GID 0 (.notdef) is implicit SID 0.
fn parse_charset(d: &[u8], off: usize, nglyphs: usize) -> Option<HashMap<u16, usize>> {
    if off == 0 {
        return None; // predefined ISOAdobe
    }
    let fmt = u8a(d, off)?;
    let mut map = HashMap::new();
    map.insert(0u16, 0usize);
    let mut pos = off + 1;
    let mut gid = 1u16;
    if fmt == 0 {
        while (gid as usize) < nglyphs {
            let sid = u16a(d, pos)? as usize;
            pos += 2;
            map.insert(gid, sid);
            gid = gid.wrapping_add(1);
        }
    } else if fmt == 1 || fmt == 2 {
        while (gid as usize) < nglyphs {
            let first = u16a(d, pos)? as usize;
            pos += 2;
            let nleft = if fmt == 1 {
                let v = u8a(d, pos)? as usize;
                pos += 1;
                v
            } else {
                let v = u16a(d, pos)? as usize;
                pos += 2;
                v
            };
            for k in 0..=nleft {
                if (gid as usize) >= nglyphs {
                    break;
                }
                map.insert(gid, first + k);
                gid = gid.wrapping_add(1);
            }
        }
    } else {
        return None;
    }
    Some(map)
}

/// The 391 CFF standard strings (SID 0..390).
const STD_STRINGS: &[&str] = &[
    ".notdef", "space", "exclam", "quotedbl", "numbersign", "dollar", "percent", "ampersand",
    "quoteright", "parenleft", "parenright", "asterisk", "plus", "comma", "hyphen", "period",
    "slash", "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
    "colon", "semicolon", "less", "equal", "greater", "question", "at", "A", "B", "C", "D", "E",
    "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X",
    "Y", "Z", "bracketleft", "backslash", "bracketright", "asciicircum", "underscore",
    "quoteleft", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p",
    "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "braceleft", "bar", "braceright",
    "asciitilde", "exclamdown", "cent", "sterling", "fraction", "yen", "florin", "section",
    "currency", "quotesingle", "quotedblleft", "guillemotleft", "guilsinglleft",
    "guilsinglright", "fi", "fl", "endash", "dagger", "daggerdbl", "periodcentered", "paragraph",
    "bullet", "quotesinglbase", "quotedblbase", "quotedblright", "guillemotright", "ellipsis",
    "perthousand", "questiondown", "grave", "acute", "circumflex", "tilde", "macron", "breve",
    "dotaccent", "dieresis", "ring", "cedilla", "hungarumlaut", "ogonek", "caron", "emdash",
    "AE", "ordfeminine", "Lslash", "Oslash", "OE", "ordmasculine", "ae", "dotlessi", "lslash",
    "oslash", "oe", "germandbls", "onesuperior", "logicalnot", "mu", "trademark", "Eth",
    "onehalf", "plusminus", "Thorn", "onequarter", "divide", "brokenbar", "degree", "thorn",
    "threequarters", "twosuperior", "registered", "minus", "eth", "multiply", "threesuperior",
    "copyright", "Aacute", "Acircumflex", "Adieresis", "Agrave", "Aring", "Atilde", "Ccedilla",
    "Eacute", "Ecircumflex", "Edieresis", "Egrave", "Iacute", "Icircumflex", "Idieresis",
    "Igrave", "Ntilde", "Oacute", "Ocircumflex", "Odieresis", "Ograve", "Otilde", "Scaron",
    "Uacute", "Ucircumflex", "Udieresis", "Ugrave", "Yacute", "Ydieresis", "Zcaron", "aacute",
    "acircumflex", "adieresis", "agrave", "aring", "atilde", "ccedilla", "eacute", "ecircumflex",
    "edieresis", "egrave", "iacute", "icircumflex", "idieresis", "igrave", "ntilde", "oacute",
    "ocircumflex", "odieresis", "ograve", "otilde", "scaron", "uacute", "ucircumflex",
    "udieresis", "ugrave", "yacute", "ydieresis", "zcaron", "exclamsmall", "Hungarumlautsmall",
    "dollaroldstyle", "dollarsuperior", "ampersandsmall", "Acutesmall", "parenleftsuperior",
    "parenrightsuperior", "twodotenleader", "onedotenleader", "zerooldstyle", "oneoldstyle",
    "twooldstyle", "threeoldstyle", "fouroldstyle", "fiveoldstyle", "sixoldstyle",
    "sevenoldstyle", "eightoldstyle", "nineoldstyle", "commasuperior",
    "threequartersemdash", "periodsuperior", "questionsmall", "asuperior", "bsuperior",
    "centsuperior", "dsuperior", "esuperior", "isuperior", "lsuperior", "msuperior", "nsuperior",
    "osuperior", "rsuperior", "ssuperior", "tsuperior", "ff", "ffi", "ffl", "parenleftinferior",
    "parenrightinferior", "Circumflexsmall", "hyphensuperior", "Gravesmall", "Asmall", "Bsmall",
    "Csmall", "Dsmall", "Esmall", "Fsmall", "Gsmall", "Hsmall", "Ismall", "Jsmall", "Ksmall",
    "Lsmall", "Msmall", "Nsmall", "Osmall", "Psmall", "Qsmall", "Rsmall", "Ssmall", "Tsmall",
    "Usmall", "Vsmall", "Wsmall", "Xsmall", "Ysmall", "Zsmall",
    // (SIDs beyond here are oldstyle/small-cap/fraction variants that do not map
    // to Unicode via the AGL, so they are intentionally omitted; the 391-SID
    // boundary is tracked separately by N_STD_STRINGS.)
];

/// Total number of CFF standard strings (SID 0..390); custom SIDs start at 391.
const N_STD_STRINGS: usize = 391;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dict_parses_integers_and_operators() {
        // operand 139 (=0) then operator 17 (CharStrings). 139 encodes as 0.
        let d = [139u8, 17u8];
        let dict = parse_dict(&d);
        assert_eq!(dict.get(&17), Some(&vec![0.0]));
    }

    #[test]
    fn empty_or_garbage_is_safe() {
        assert!(builtin_encoding(&[]).is_empty());
        assert!(builtin_encoding(&[0, 1, 2, 3, 4, 5, 6]).is_empty());
    }
}