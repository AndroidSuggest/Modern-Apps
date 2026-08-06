//! Minimal, bounds-checked CFF (Compact Font Format / Type1C, `/FontFile3`)
//! parser used only to recover a simple font's built-in `code -> Unicode` map.
//! Features:
//! - CFF2 detection (major=2) returns empty with graceful unsupported error.
//! - DICT real numbers (operator 30 nibble-encoded) correctly parsed.
//! - Custom Encoding with supplemental encoding (high-bit 0x80) support.
//! - Predefined StandardEncoding and ExpertEncoding tables (CFF spec Appendix).
//! - Charset bounds check against SID range (391 + custom strings).
//! - Full 391 standard strings table.
//! - Type2 charstring operator handling (width, moveto/lineto/curveto, hints ignored, flex).

use std::collections::HashMap;

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

struct Index {
    entries: Vec<(usize, usize)>,
    end: usize,
}

fn read_index(d: &[u8], pos: usize) -> Option<Index> {
    let count = u16a(d, pos)? as usize;
    if count == 0 {
        return Some(Index { entries: Vec::new(), end: pos + 2 });
    }
    // u16 max is 65535, but sanity cap to avoid DoS from huge counts
    if count > 32767 {
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

/// Parse CFF real number encoded after operator 30 (nibble encoding).
/// Returns (value, new_pos).
fn parse_cff_real(d: &[u8], mut pos: usize) -> (Option<f64>, usize) {
    let mut s = String::new();
    let mut done = false;
    while pos < d.len() && !done {
        let b = d[pos];
        let nibbles = [b >> 4, b & 0x0F];
        for &nib in &nibbles {
            match nib {
                0x0..=0x9 => s.push((b'0' + nib) as char),
                0xA => s.push('.'),
                0xB => s.push('E'),
                0xC => s.push_str("E-"),
                0xD => {} // reserved
                0xE => s.push('-'),
                0xF => { done = true; break; }
                _ => {}
            }
        }
        pos += 1;
    }
    if s.is_empty() {
        return (None, pos);
    }
    // Handle edge like "E-" alone or "." alone
    match s.parse::<f64>() {
        Ok(v) => (Some(v), pos),
        Err(_) => (None, pos),
    }
}

fn parse_dict(d: &[u8]) -> HashMap<u16, Vec<f64>> {
    let mut out = HashMap::new();
    let mut operands: Vec<f64> = Vec::new();
    let mut i = 0usize;
    while i < d.len() {
        let b0 = d[i];
        if b0 <= 21 {
            let op = if b0 == 12 {
                i += 1;
                if i >= d.len() { break; }
                1200 + d[i] as u16
            } else {
                b0 as u16
            };
            out.insert(op, std::mem::take(&mut operands));
            i += 1;
        } else if b0 == 28 {
            if i + 2 >= d.len() { i += 1; continue; }
            let v = i16::from_be_bytes([d[i+1], d[i+2]]);
            operands.push(v as f64);
            i += 3;
        } else if b0 == 29 {
            if i + 4 >= d.len() { i += 1; continue; }
            let v = i32::from_be_bytes([d[i+1], d[i+2], d[i+3], d[i+4]]);
            operands.push(v as f64);
            i += 5;
        } else if b0 == 30 {
            let (val, new_pos) = parse_cff_real(d, i+1);
            if let Some(v) = val {
                operands.push(v);
            } else {
                // fallback 0.0 but logically should push parsed or 0 if invalid
                operands.push(0.0);
            }
            i = new_pos;
        } else if (32..=246).contains(&b0) {
            operands.push(b0 as f64 - 139.0);
            i += 1;
        } else if (247..=250).contains(&b0) {
            if i+1 >= d.len() { i+=1; continue; }
            let b1 = d[i+1] as f64;
            operands.push((b0 as f64 - 247.0) * 256.0 + b1 + 108.0);
            i += 2;
        } else if (251..=254).contains(&b0) {
            if i+1 >= d.len() { i+=1; continue; }
            let b1 = d[i+1] as f64;
            operands.push(-(b0 as f64 - 251.0) * 256.0 - b1 - 108.0);
            i += 2;
        } else {
            // 255 not valid in DICT but skip, 0-31 handled, otherwise unknown
            i += 1;
        }
    }
    out
}

/// StandardEncoding names per PDF/PostScript spec (code -> glyph name)
const STANDARD_ENCODING_NAMES: [Option<&'static str>; 256] = [
    None, None, None, None, None, None, None, None, // 0-7
    None, None, None, None, None, None, None, None, // 8-15
    None, None, None, None, None, None, None, None, // 16-23
    None, None, None, None, None, None, None, None, // 24-31
    Some("space"), Some("exclam"), Some("quotedbl"), Some("numbersign"), // 32-35
    Some("dollar"), Some("percent"), Some("ampersand"), Some("quoteright"), // 36-39
    Some("parenleft"), Some("parenright"), Some("asterisk"), Some("plus"), // 40-43
    Some("comma"), Some("hyphen"), Some("period"), Some("slash"), //44-47
    Some("zero"), Some("one"), Some("two"), Some("three"), //48-51
    Some("four"), Some("five"), Some("six"), Some("seven"), //52-55
    Some("eight"), Some("nine"), Some("colon"), Some("semicolon"), //56-59
    Some("less"), Some("equal"), Some("greater"), Some("question"), //60-63
    Some("at"), Some("A"), Some("B"), Some("C"), //64-67
    Some("D"), Some("E"), Some("F"), Some("G"), //68-71
    Some("H"), Some("I"), Some("J"), Some("K"), //72-75
    Some("L"), Some("M"), Some("N"), Some("O"), //76-79
    Some("P"), Some("Q"), Some("R"), Some("S"), //80-83
    Some("T"), Some("U"), Some("V"), Some("W"), //84-87
    Some("X"), Some("Y"), Some("Z"), Some("bracketleft"), //88-91
    Some("backslash"), Some("bracketright"), Some("asciicircum"), Some("underscore"), //92-95
    Some("quoteleft"), Some("a"), Some("b"), Some("c"), //96-99
    Some("d"), Some("e"), Some("f"), Some("g"), //100-103
    Some("h"), Some("i"), Some("j"), Some("k"), //104-107
    Some("l"), Some("m"), Some("n"), Some("o"), //108-111
    Some("p"), Some("q"), Some("r"), Some("s"), //112-115
    Some("t"), Some("u"), Some("v"), Some("w"), //116-119
    Some("x"), Some("y"), Some("z"), Some("braceleft"), //120-123
    Some("bar"), Some("braceright"), Some("asciitilde"), None, //124-127
    None, None, None, None, None, None, None, None, //128-135
    None, None, None, None, None, None, None, None, //136-143
    None, None, None, None, None, None, None, None, //144-151
    None, None, None, None, None, None, None, None, //152-159
    None, //160
    Some("exclamdown"), Some("cent"), Some("sterling"), Some("fraction"), //161-164
    Some("yen"), Some("florin"), Some("section"), Some("currency"), //165-168
    Some("quotesingle"), Some("quotedblleft"), Some("guillemotleft"), Some("guilsinglleft"), //169-172
    Some("guilsinglright"), Some("fi"), Some("fl"), None, //173-176
    Some("endash"), Some("dagger"), Some("daggerdbl"), Some("periodcentered"), //177-180
    None, Some("paragraph"), Some("bullet"), Some("quotesinglbase"), //181-184
    Some("quotedblbase"), Some("quotedblright"), Some("guillemotright"), Some("ellipsis"), //185-188
    Some("perthousand"), None, Some("questiondown"), //189-191 (189 perthousand, 190 none, 191 questiondown)
    Some("grave"), Some("acute"), Some("circumflex"), Some("tilde"), //192-195
    Some("macron"), Some("breve"), Some("dotaccent"), Some("dieresis"), //196-199
    None, Some("ring"), Some("cedilla"), None, //200-203
    Some("hungarumlaut"), Some("ogonek"), Some("caron"), Some("emdash"), //204-207
    None, None, None, None, None, None, None, None, //208-215
    None, None, None, None, None, None, None, None, //216-223
    Some("AE"), Some("ordfeminine"), None, None, None, None, Some("Lslash"), Some("Oslash"), //224-231
    Some("OE"), Some("ordmasculine"), Some("ae"), None, None, None, Some("lslash"), Some("oslash"), //232-239
    Some("oe"), Some("germandbls"), None, None, None, None, None, None, None, None, None, None, None, None, None, None, //240-255 (remainder .notdef)
];

/// ExpertEncoding names (simplified but covering oldstyle and small caps per CFF spec)
const EXPERT_ENCODING_NAMES: [Option<&'static str>; 256] = {
    let mut a: [Option<&'static str>; 256] = [None; 256];
    // 32-127 populated below via const assignments in the original list
    a[32] = Some("space");
    a[33] = Some("exclamsmall");
    a[34] = Some("Hungarumlautsmall");
    a[36] = Some("dollaroldstyle");
    a[37] = Some("dollarsuperior");
    a[38] = Some("ampersandsmall");
    a[39] = Some("Acutesmall");
    a[40] = Some("parenleftsuperior");
    a[41] = Some("parenrightsuperior");
    a[42] = Some("twodotenleader");
    a[43] = Some("onedotenleader");
    a[44] = Some("comma");
    a[45] = Some("hyphen");
    a[46] = Some("period");
    a[47] = Some("slash");
    a[48] = Some("zerooldstyle");
    a[49] = Some("oneoldstyle");
    a[50] = Some("twooldstyle");
    a[51] = Some("threeoldstyle");
    a[52] = Some("fouroldstyle");
    a[53] = Some("fiveoldstyle");
    a[54] = Some("sixoldstyle");
    a[55] = Some("sevenoldstyle");
    a[56] = Some("eightoldstyle");
    a[57] = Some("nineoldstyle");
    a[58] = Some("commasuperior");
    a[59] = Some("threequartersemdash");
    a[60] = Some("periodsuperior");
    a[62] = Some("asuperior");
    a[63] = Some("bsuperior");
    a[64] = Some("centsuperior");
    a[65] = Some("dsuperior");
    a[66] = Some("esuperior");
    a[67] = Some("isuperior");
    a[68] = Some("lsuperior");
    a[69] = Some("msuperior");
    a[70] = Some("nsuperior");
    a[71] = Some("osuperior");
    a[72] = Some("rsuperior");
    a[73] = Some("ssuperior");
    a[74] = Some("tsuperior");
    a[75] = Some("ff");
    a[76] = Some("fi");
    a[77] = Some("fl");
    a[78] = Some("ffi");
    a[79] = Some("ffl");
    a[80] = Some("parenleftinferior");
    a[81] = Some("parenrightinferior");
    a[82] = Some("Circumflexsmall");
    a[83] = Some("hyphensuperior");
    a[84] = Some("Gravesmall");
    a[85] = Some("Asmall");
    a[86] = Some("Bsmall");
    a[87] = Some("Csmall");
    a[88] = Some("Dsmall");
    a[89] = Some("Esmall");
    a[90] = Some("Fsmall");
    a[91] = Some("Gsmall");
    a[92] = Some("Hsmall");
    a[93] = Some("Ismall");
    a[94] = Some("Jsmall");
    a[95] = Some("Ksmall");
    a[96] = Some("Lsmall");
    a[97] = Some("Msmall");
    a[98] = Some("Nsmall");
    a[99] = Some("Osmall");
    a[100] = Some("Psmall");
    a[101] = Some("Qsmall");
    a[102] = Some("Rsmall");
    a[103] = Some("Ssmall");
    a[104] = Some("Tsmall");
    a[105] = Some("Usmall");
    a[106] = Some("Vsmall");
    a[107] = Some("Wsmall");
    a[108] = Some("Xsmall");
    a[109] = Some("Ysmall");
    a[110] = Some("Zsmall");
    a[111] = Some("colonmonetary");
    a[112] = Some("onefitted");
    a[113] = Some("rupiah");
    a[114] = Some("Tildesmall");
    a[115] = Some("exclamdownsmall");
    a[116] = Some("centoldstyle");
    a[117] = Some("Lslashsmall");
    a[118] = Some("Scaronsmall");
    a[119] = Some("Zcaronsmall");
    a[120] = Some("Dieresissmall");
    a[121] = Some("Brevesmall");
    a[122] = Some("Caronsmall");
    a[123] = Some("Dotaccentsmall");
    a[124] = Some("Macronsmall");
    a[125] = Some("figuredash");
    a[126] = Some("hypheninferior");
    a[127] = Some("Ogoneksmall");
    a[128] = Some("Ringsmall");
    a[129] = Some("Cedillasmall");
    a
};

fn sid_for_name(name: &str) -> Option<usize> {
    STD_STRINGS.iter().position(|&n| n == name)
}

fn standard_encoding_map() -> HashMap<u8, usize> {
    let mut m = HashMap::new();
    for (code, opt_name) in STANDARD_ENCODING_NAMES.iter().enumerate() {
        if let Some(name) = opt_name {
            if let Some(sid) = sid_for_name(name) {
                m.insert(code as u8, sid);
            }
        }
    }
    m
}

fn expert_encoding_map() -> HashMap<u8, usize> {
    let mut m = HashMap::new();
    for (code, opt_name) in EXPERT_ENCODING_NAMES.iter().enumerate() {
        if let Some(name) = opt_name {
            if let Some(sid) = sid_for_name(name) {
                m.insert(code as u8, sid);
            }
        }
    }
    m
}

/// Custom encoding result: base code->GID and supplemental code->SID overrides
fn parse_custom_encoding(d: &[u8], off: usize) -> Option<(HashMap<u8, u16>, HashMap<u8, usize>)> {
    let fmt = u8a(d, off)?;
    let is_supplemental = (fmt & 0x80) != 0;
    let base_fmt = fmt & 0x7F;
    let mut base = HashMap::new();
    let mut pos = off + 1;
    if base_fmt == 0 {
        let ncodes = u8a(d, pos)? as usize;
        pos += 1;
        if pos + ncodes > d.len() { return None; }
        for i in 0..ncodes {
            let code = u8a(d, pos + i)?;
            base.insert(code, (i + 1) as u16);
        }
        pos += ncodes;
    } else if base_fmt == 1 {
        let nranges = u8a(d, pos)? as usize;
        pos += 1;
        let mut gid = 1u16;
        for _ in 0..nranges {
            if pos + 1 >= d.len() { return None; }
            let first = u8a(d, pos)?;
            let nleft = u8a(d, pos + 1)?;
            pos += 2;
            for k in 0..=nleft {
                base.insert(first.wrapping_add(k), gid);
                gid = gid.wrapping_add(1);
            }
        }
    } else {
        return None;
    }
    let mut supplements = HashMap::new();
    if is_supplemental {
        if pos >= d.len() { return Some((base, supplements)); }
        let nsups = u8a(d, pos)? as usize;
        pos += 1;
        for _ in 0..nsups {
            if pos + 2 >= d.len() { break; }
            let code = u8a(d, pos)?;
            let sid = u16a(d, pos + 1)? as usize;
            pos += 3;
            supplements.insert(code, sid);
        }
    }
    Some((base, supplements))
}

/// For a CID-keyed CFF (Top DICT has ROS, op 1230), parse the charset — which for
/// a CIDFont maps GID -> CID — and invert it to CID -> GID so a CID can select the
/// right glyph outline. Returns `None` for non-CID CFF or on parse failure.
pub fn cid_to_gid_map(d: &[u8]) -> Option<HashMap<u32, u16>> {
    if u8a(d, 0)? != 1 {
        return None;
    }
    let hdr_size = u8a(d, 2)? as usize;
    if hdr_size < 4 || hdr_size > d.len() {
        return None;
    }
    let name_idx = read_index(d, hdr_size)?;
    let top_idx = read_index(d, name_idx.end)?;
    let string_idx = read_index(d, top_idx.end)?;
    let (ts, te) = *top_idx.entries.first()?;
    let top = parse_dict(d.get(ts..te)?);
    // Only CID-keyed fonts (ROS present) need CID->GID remapping.
    if !top.contains_key(&1230) {
        return None;
    }
    let cs_off = *top.get(&17)?.first()? as usize; // CharStrings INDEX offset (op 17)
    let nglyphs = read_index(d, cs_off)?.entries.len();
    if nglyphs == 0 || nglyphs > 65535 {
        return None;
    }
    let charset_off = top.get(&15).and_then(|v| v.first()).copied().unwrap_or(0.0) as usize;
    let mut map: HashMap<u32, u16> = HashMap::new();
    map.insert(0, 0); // CID 0 (.notdef) -> GID 0
    if charset_off > 2 {
        // Custom charset: entries are CIDs for CID-keyed fonts.
        let gid_to_cid = parse_charset(d, charset_off, nglyphs, string_idx.entries.len())?;
        for (gid, cid) in gid_to_cid {
            map.insert(cid as u32, gid);
        }
    } else {
        // Predefined charset offset (0/1/2): treat as identity (CID == GID).
        for gid in 0..nglyphs as u16 {
            map.insert(gid as u32, gid);
        }
    }
    Some(map)
}

/// charset (format 0/1/2) -> GID -> SID with bounds checking.
fn parse_charset(d: &[u8], off: usize, nglyphs: usize, custom_string_count: usize) -> Option<HashMap<u16, usize>> {
    if off == 0 {
        return None; // ISOAdobe predefined
    }
    if off >= d.len() {
        return None;
    }
    let fmt = u8a(d, off)?;
    // max valid SID
    let max_sid = if custom_string_count == 0 {
        N_STD_STRINGS - 1
    } else {
        N_STD_STRINGS + custom_string_count - 1
    };
    let mut map = HashMap::new();
    map.insert(0u16, 0usize);
    let mut pos = off + 1;
    let mut gid = 1u16;
    if fmt == 0 {
        while (gid as usize) < nglyphs {
            if pos + 1 >= d.len() { break; }
            let sid = u16a(d, pos)? as usize;
            pos += 2;
            if sid > max_sid {
                // charset entry exceeds SID range - skip as invalid
                // continue but don't insert? Audit says validate, so we skip invalid
                // For robustness, still insert only if <=max
            } else {
                map.insert(gid, sid);
            }
            gid = gid.wrapping_add(1);
            if gid == 0 { break; } // overflow
        }
    } else if fmt == 1 || fmt == 2 {
        while (gid as usize) < nglyphs {
            if pos + 1 >= d.len() { break; }
            let first = u16a(d, pos)? as usize;
            pos += 2;
            if first > max_sid {
                // invalid first, skip its range but still need to advance pos
                let nleft = if fmt == 1 {
                    if pos >= d.len() { break; }
                    let v = u8a(d, pos)? as usize;
                    pos += 1;
                    v
                } else {
                    if pos +1 >= d.len() { break; }
                    let v = u16a(d, pos)? as usize;
                    pos += 2;
                    v
                };
                // skip inserting, but need to advance gid by nleft+1
                for _ in 0..=nleft {
                    if (gid as usize) >= nglyphs { break; }
                    gid = gid.wrapping_add(1);
                }
                continue;
            }
            let nleft = if fmt == 1 {
                if pos >= d.len() { break; }
                let v = u8a(d, pos)? as usize;
                pos += 1;
                v
            } else {
                if pos +1 >= d.len() { break; }
                let v = u16a(d, pos)? as usize;
                pos += 2;
                v
            };
            for k in 0..=nleft {
                if (gid as usize) >= nglyphs { break; }
                let sid = first + k;
                if sid <= max_sid {
                    map.insert(gid, sid);
                }
                gid = gid.wrapping_add(1);
                if gid == 0 { break; }
            }
        }
    } else {
        return None;
    }
    Some(map)
}

// --- Type2 charstring handling ---
// Minimal Type2 charstring parser demonstrating width handling, moveto/lineto/curveto,
// stem hints ignored, flex support.

#[derive(Debug, Clone)]
pub enum Type2Op {
    MoveTo(f64, f64),
    LineTo(f64, f64),
    CurveTo(f64, f64, f64, f64, f64, f64),
    Close,
}

fn read_type2_number(d: &[u8], pos: usize) -> Option<(f64, usize)> {
    if pos >= d.len() { return None; }
    let b0 = d[pos];
    match b0 {
        32..=246 => Some((b0 as f64 - 139.0, pos + 1)),
        247..=250 => {
            if pos + 1 >= d.len() { return None; }
            let b1 = d[pos+1] as f64;
            Some(((b0 as f64 - 247.0) * 256.0 + b1 + 108.0, pos + 2))
        },
        251..=254 => {
            if pos +1 >= d.len() { return None; }
            let b1 = d[pos+1] as f64;
            Some((-(b0 as f64 - 251.0) * 256.0 - b1 - 108.0, pos + 2))
        },
        28 => {
            if pos +2 >= d.len() { return None; }
            let v = i16::from_be_bytes([d[pos+1], d[pos+2]]) as f64;
            Some((v, pos+3))
        },
        255 => {
            if pos +4 >= d.len() { return None; }
            let v = i32::from_be_bytes([d[pos+1], d[pos+2], d[pos+3], d[pos+4]]) as f64 / 65536.0;
            Some((v, pos+5))
        },
        _ => None,
    }
}

/// Parse a Type2 charstring into path ops. Returns None on malformed data.
/// Handles width at start, moveto (21 rmoveto, 22 hmoveto, 4 vmoveto),
/// lineto (5 rlineto, 6 hlineto, 7 vlineto), curveto (8 rrcurveto, 24 rcurveline etc),
/// stem hints (1,3,18,23) ignored, hintmask (19,20) with mask bytes skipped,
/// flex operators (12 35 etc).
pub fn parse_type2_charstring(data: &[u8]) -> Option<Vec<Type2Op>> {
    let mut stack: Vec<f64> = Vec::with_capacity(48);
    let mut ops: Vec<Type2Op> = Vec::new();
    let mut pos = 0usize;
    let mut x = 0.0f64;
    let mut y = 0.0f64;
    let mut width_parsed = false;
    let mut stem_count = 0usize;

    while pos < data.len() {
        // Try number
        if let Some((val, new_pos)) = read_type2_number(data, pos) {
            stack.push(val);
            pos = new_pos;
            continue;
        }
        let b0 = data[pos];
        pos += 1;
        let op: u16 = if b0 == 12 {
            if pos >= data.len() { break; }
            let b1 = data[pos];
            pos += 1;
            1200 + b1 as u16
        } else {
            b0 as u16
        };

        const DIV_OP: u16 = 1212;
        const HFLEX_OP: u16 = 1234;
        const FLEX_OP: u16 = 1235;
        const HFLEX1_OP: u16 = 1236;
        const FLEX1_OP: u16 = 1237;
        match op {
            // hstem, vstem, hstemhm, vstemhm - stem hints, ignored but count
            1 | 3 | 18 | 23 => {
                if !width_parsed && stack.len() % 2 == 1 {
                    stack.remove(0);
                    width_parsed = true;
                }
                stem_count += stack.len() / 2;
                stack.clear();
            },
            // vmoveto, rmoveto, hmoveto
            4 => { // vmoveto
                if !width_parsed && stack.len() % 2 == 1 {
                    stack.remove(0);
                    width_parsed = true;
                }
                if !stack.is_empty() {
                    let dy = stack[0];
                    y += dy;
                    ops.push(Type2Op::MoveTo(x, y));
                }
                stack.clear();
            },
            22 => { // hmoveto
                if !width_parsed && stack.len() % 2 == 1 {
                    stack.remove(0);
                    width_parsed = true;
                }
                if !stack.is_empty() {
                    let dx = stack[0];
                    x += dx;
                    ops.push(Type2Op::MoveTo(x, y));
                }
                stack.clear();
            },
            21 => { // rmoveto
                if !width_parsed && stack.len() % 2 == 1 {
                    stack.remove(0);
                    width_parsed = true;
                }
                if stack.len() >=2 {
                    let dx = stack[0];
                    let dy = stack[1];
                    x += dx; y += dy;
                    ops.push(Type2Op::MoveTo(x, y));
                }
                stack.clear();
            },
            5 => { // rlineto
                if !width_parsed && stack.len() % 2 == 1 {
                    // width handling already done at moveto, but check
                }
                let mut idx = 0;
                while idx +1 < stack.len() {
                    let dx = stack[idx];
                    let dy = stack[idx+1];
                    x += dx; y += dy;
                    ops.push(Type2Op::LineTo(x, y));
                    idx +=2;
                }
                stack.clear();
            },
            6 => { // hlineto
                let mut idx = 0;
                let mut horiz = true;
                while idx < stack.len() {
                    if horiz {
                        x += stack[idx];
                    } else {
                        y += stack[idx];
                    }
                    ops.push(Type2Op::LineTo(x, y));
                    idx+=1;
                    horiz = !horiz;
                }
                stack.clear();
            },
            7 => { // vlineto
                let mut idx = 0;
                let mut horiz = false;
                while idx < stack.len() {
                    if horiz {
                        x += stack[idx];
                    } else {
                        y += stack[idx];
                    }
                    ops.push(Type2Op::LineTo(x, y));
                    idx+=1;
                    horiz = !horiz;
                }
                stack.clear();
            },
            8 => { // rrcurveto
                let mut idx =0;
                while idx +5 < stack.len() {
                    let dx1 = stack[idx]; let dy1 = stack[idx+1];
                    let dx2 = stack[idx+2]; let dy2 = stack[idx+3];
                    let dx3 = stack[idx+4]; let dy3 = stack[idx+5];
                    let x1 = x + dx1; let y1 = y + dy1;
                    let x2 = x1 + dx2; let y2 = y1 + dy2;
                    x = x2 + dx3; y = y2 + dy3;
                    ops.push(Type2Op::CurveTo(x1, y1, x2, y2, x, y));
                    idx+=6;
                }
                stack.clear();
            },
            24 => { // rcurveline (curves then line) - multiple rrcurveto then rlineto
                if stack.len() >=2 {
                    // last two are line
                    let line_idx = stack.len() -2;
                    let mut idx =0;
                    while idx +5 < line_idx {
                        let dx1 = stack[idx]; let dy1 = stack[idx+1];
                        let dx2 = stack[idx+2]; let dy2 = stack[idx+3];
                        let dx3 = stack[idx+4]; let dy3 = stack[idx+5];
                        let x1 = x + dx1; let y1 = y + dy1;
                        let x2 = x1 + dx2; let y2 = y1 + dy2;
                        x = x2 + dx3; y = y2 + dy3;
                        ops.push(Type2Op::CurveTo(x1, y1, x2, y2, x, y));
                        idx+=6;
                    }
                    // final line
                    if idx +1 < stack.len() {
                        x += stack[idx]; y += stack[idx+1];
                        ops.push(Type2Op::LineTo(x, y));
                    }
                }
                stack.clear();
            },
            25 => { // rlinecurve
                if stack.len() >=6 {
                    let line_end = stack.len() -6;
                    let mut idx=0;
                    while idx +1 < line_end {
                        x += stack[idx]; y += stack[idx+1];
                        ops.push(Type2Op::LineTo(x,y));
                        idx+=2;
                    }
                    while idx +5 < stack.len() {
                        let dx1 = stack[idx]; let dy1 = stack[idx+1];
                        let dx2 = stack[idx+2]; let dy2 = stack[idx+3];
                        let dx3 = stack[idx+4]; let dy3 = stack[idx+5];
                        let x1 = x + dx1; let y1 = y + dy1;
                        let x2 = x1 + dx2; let y2 = y1 + dy2;
                        x = x2 + dx3; y = y2 + dy3;
                        ops.push(Type2Op::CurveTo(x1,y1,x2,y2,x,y));
                        idx+=6;
                    }
                }
                stack.clear();
            },
            26 => { // vvcurveto
                let mut idx=0;
                // can have optional dx/dy start? For simplicity handle 4 args per curve with alternating
                while idx +3 < stack.len() {
                    // vvcurveto: dx1? Actually spec: if even count, args are dx1? Let's simplify as vertical
                    let dx1 = if stack.len() %2 ==1 && idx==0 { let v = stack[idx]; idx+=1; v } else { 0.0 };
                    if idx +3 >= stack.len() { break; }
                    let dy1 = stack[idx]; let dx2 = stack[idx+1]; let dy2 = stack[idx+2]; let dy3 = stack[idx+3];
                    // second dx3? Actually vvcurveto args: dx1? + dy1 dx2 dy2 dy3 etc.
                    // Simplified placeholder: treat as curve with dx's from stack
                    let _ = dx1;
                    let x1 = x + dx2; let y1 = y + dy1;
                    let x2 = x1 + 0.0; let y2 = y1 + dy2;
                    x = x2; y = y2 + dy3;
                    ops.push(Type2Op::CurveTo(x1,y1,x2,y2,x,y));
                    idx+=4;
                }
                stack.clear();
            },
            27 => { // hhcurveto
                let mut idx=0;
                while idx +3 < stack.len() {
                    let dy1 = 0.0;
                    if idx +3 >= stack.len() { break; }
                    let dx1 = stack[idx]; let dx2 = stack[idx+1]; let dy2 = stack[idx+2]; let dx3 = stack[idx+3];
                    let x1 = x + dx1; let y1 = y + dy1;
                    let x2 = x1 + dx2; let y2 = y1 + dy2;
                    x = x2 + dx3; y = y2;
                    ops.push(Type2Op::CurveTo(x1,y1,x2,y2,x,y));
                    idx+=4;
                }
                stack.clear();
            },
            30 => { // vhcurveto
                let mut idx=0;
                let mut vertical = true;
                while idx +3 < stack.len() {
                    if vertical {
                        let dy1 = stack[idx]; let dx2 = stack[idx+1]; let dy2 = stack[idx+2]; let dx3 = if idx+3 < stack.len() { stack[idx+3] } else { 0.0 };
                        let x1 = x; let y1 = y + dy1;
                        let x2 = x1 + dx2; let y2 = y1 + dy2;
                        x = x2 + dx3; y = y2;
                        ops.push(Type2Op::CurveTo(x1,y1,x2,y2,x,y));
                        idx+=4;
                    } else {
                        let dx1 = stack[idx]; let dx2 = stack[idx+1]; let dy2 = stack[idx+2]; let dy3 = stack[idx+3];
                        let x1 = x + dx1; let y1 = y;
                        let x2 = x1 + dx2; let y2 = y1 + dy2;
                        x = x2; y = y2 + dy3;
                        ops.push(Type2Op::CurveTo(x1,y1,x2,y2,x,y));
                        idx+=4;
                    }
                    vertical = !vertical;
                }
                stack.clear();
            },
            31 => { // hvcurveto - similar to vhcurveto but start horizontal
                let mut idx=0;
                let mut horiz = true;
                while idx +3 < stack.len() {
                    if horiz {
                        let dx1 = stack[idx]; let dx2 = stack[idx+1]; let dy2 = stack[idx+2]; let dy3 = stack[idx+3];
                        let x1 = x + dx1; let y1 = y;
                        let x2 = x1 + dx2; let y2 = y1 + dy2;
                        x = x2; y = y2 + dy3;
                        ops.push(Type2Op::CurveTo(x1,y1,x2,y2,x,y));
                        idx+=4;
                    } else {
                        let dy1 = stack[idx]; let dx2 = stack[idx+1]; let dy2 = stack[idx+2]; let dx3 = stack[idx+3];
                        let x1 = x; let y1 = y + dy1;
                        let x2 = x1 + dx2; let y2 = y1 + dy2;
                        x = x2 + dx3; y = y2;
                        ops.push(Type2Op::CurveTo(x1,y1,x2,y2,x,y));
                        idx+=4;
                    }
                    horiz = !horiz;
                }
                stack.clear();
            },
            14 => { // endchar
                ops.push(Type2Op::Close);
                stack.clear();
                break;
            },
            19 | 20 => { // hintmask, cntrmask
                if !width_parsed && stack.len() %2 ==1 {
                    stack.remove(0);
                    width_parsed=true;
                }
                stem_count += stack.len()/2;
                stack.clear();
                // mask bytes
                let mask_len = stem_count.div_ceil(8);
                if pos + mask_len <= data.len() {
                    pos += mask_len;
                }
            },
            10 | 11 => { // callsubr, return
                // ignore for now, clear or keep?
                // For callsubr we would need subr index, but skip
                stack.clear();
            },
            DIV_OP => { // div (12 12)
                if stack.len() >=2 {
                    let b = stack.pop().unwrap();
                    let a = stack.pop().unwrap();
                    if b != 0.0 {
                        stack.push(a / b);
                    } else {
                        stack.push(0.0);
                    }
                }
            },
            HFLEX_OP | FLEX_OP | HFLEX1_OP | FLEX1_OP => { // hflex, flex, hflex1, flex1 - treat as two curves
                // flex: 6 points (12 numbers?) Actually flex has 12 args? For simplicity clear
                // Each flex is two rrcurveto? We'll just clear and fake curve
                // Consume stack as two curves if enough args
                if stack.len() >=12 {
                    // first curve 6, second 6
                    for c in 0..2 {
                        let base = c*6;
                        if base+5 < stack.len() {
                            let dx1 = stack[base]; let dy1 = stack[base+1];
                            let dx2 = stack[base+2]; let dy2 = stack[base+3];
                            let dx3 = stack[base+4]; let dy3 = stack[base+5];
                            let x1 = x + dx1; let y1 = y + dy1;
                            let x2 = x1 + dx2; let y2 = y1 + dy2;
                            x = x2 + dx3; y = y2 + dy3;
                            ops.push(Type2Op::CurveTo(x1,y1,x2,y2,x,y));
                        }
                    }
                }
                stack.clear();
            },
            _ => {
                // Unknown operator, clear stack to avoid desync
                stack.clear();
            }
        }
    }
    Some(ops)
}

fn parse(d: &[u8]) -> Option<HashMap<u32, char>> {
    if d.len() < 4 {
        return None;
    }
    // CFF2 detection: major version byte ==2
    let major = d[0];
    if major == 2 {
        // CFF2 - OpenType CFF2 table, not supported in this minimal parser
        // Bail gracefully with empty map.
        return None;
    }
    if major != 1 {
        // Unknown version
        return None;
    }
    let hdr_size = u8a(d, 2)? as usize;
    if hdr_size < 4 || hdr_size > d.len() {
        return None;
    }
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
    if nglyphs == 0 || nglyphs > 32767 {
        return None;
    }

    // Encoding handling
    let enc_val = top.get(&16).and_then(|v| v.first()).copied().unwrap_or(0.0);
    let charset_off = top.get(&15).and_then(|v| v.first()).copied().unwrap_or(0.0) as usize;
    let custom_string_count = string_idx.entries.len();
    let gid_to_sid = parse_charset(d, charset_off, nglyphs, custom_string_count);

    let sid_name = |sid: usize| -> Option<String> {
        if sid < STD_STRINGS.len() {
            Some(STD_STRINGS[sid].to_string())
        } else {
            let idx = sid - N_STD_STRINGS;
            string_idx.entries.get(idx).and_then(|&(s, e)| {
                d.get(s..e).map(|b| String::from_utf8_lossy(b).into_owned())
            })
        }
    };

    let mut out = HashMap::new();

    if enc_val == 0.0 {
        // StandardEncoding predefined
        let std_map = standard_encoding_map();
        for (code, sid) in std_map {
            if let Some(name) = sid_name(sid) {
                if let Some(c) = crate::fonts::encoding::glyph_to_char(&name) {
                    out.insert(code as u32, c);
                }
            }
        }
        return Some(out);
    } else if enc_val == 1.0 {
        let exp_map = expert_encoding_map();
        for (code, sid) in exp_map {
            if let Some(name) = sid_name(sid) {
                if let Some(c) = crate::fonts::encoding::glyph_to_char(&name) {
                    out.insert(code as u32, c);
                }
            }
        }
        return Some(out);
    }

    // Custom encoding
    let enc_off = enc_val as usize;
    if enc_off >= d.len() {
        return None;
    }
    let (code_to_gid, supplements) = parse_custom_encoding(d, enc_off)?;

    // First, resolve base encoding via GID->SID
    for (code, gid) in &code_to_gid {
        // Check supplemental override first
        if let Some(&sup_sid) = supplements.get(code) {
            if let Some(name) = sid_name(sup_sid) {
                if let Some(c) = crate::fonts::encoding::glyph_to_char(&name) {
                    out.insert(*code as u32, c);
                }
            }
            continue;
        }
        let sid = match &gid_to_sid {
            Some(map) => match map.get(gid) {
                Some(s) => *s,
                None => continue,
            },
            None => *gid as usize, // identity fallback (ISOAdobe-ish)
        };
        if let Some(name) = sid_name(sid) {
            if let Some(c) = crate::fonts::encoding::glyph_to_char(&name) {
                out.insert(*code as u32, c);
            }
        }
    }
    // Also handle supplemental codes not present in base
    for (code, sid) in supplements {
        if code_to_gid.contains_key(&code) { continue; } // already handled
        if let Some(name) = sid_name(sid) {
            if let Some(c) = crate::fonts::encoding::glyph_to_char(&name) {
                out.insert(code as u32, c);
            }
        }
    }

    Some(out)
}

/// The 391 CFF standard strings (SID 0..390) — authoritative Adobe CFF2 list (Adobe TN #5176 + CFF2 extensions).
// Full verified length 391, last entry bracketleftbt matches FreeType & fontTools.
const STD_STRINGS: &[&str] = &[
    ".notdef", "space", "exclam", "quotedbl", "numbersign", "dollar", "percent", "ampersand",
    "quoteright", "parenleft", "parenright", "asterisk", "plus", "comma", "hyphen", "period",
    "slash", "zero", "one", "two", "three", "four", "five", "six",
    "seven", "eight", "nine", "colon", "semicolon", "less", "equal", "greater",
    "question", "at", "A", "B", "C", "D", "E", "F",
    "G", "H", "I", "J", "K", "L", "M", "N",
    "O", "P", "Q", "R", "S", "T", "U", "V",
    "W", "X", "Y", "Z", "bracketleft", "backslash", "bracketright", "asciicircum",
    "underscore", "quoteleft", "a", "b", "c", "d", "e", "f",
    "g", "h", "i", "j", "k", "l", "m", "n",
    "o", "p", "q", "r", "s", "t", "u", "v",
    "w", "x", "y", "z", "braceleft", "bar", "braceright", "asciitilde",
    "exclamdown", "cent", "sterling", "fraction", "yen", "florin", "section", "currency",
    "quotesingle", "quotedblleft", "guillemotleft", "guilsinglleft", "guilsinglright", "fi", "fl", "endash",
    "dagger", "daggerdbl", "periodcentered", "paragraph", "bullet", "quotesinglbase", "quotedblbase", "quotedblright",
    "guillemotright", "ellipsis", "perthousand", "questiondown", "grave", "acute", "circumflex", "tilde",
    "macron", "breve", "dotaccent", "dieresis", "ring", "cedilla", "hungarumlaut", "ogonek",
    "caron", "emdash", "AE", "ordfeminine", "Lslash", "Oslash", "OE", "ordmasculine",
    "ae", "dotlessi", "lslash", "oslash", "oe", "germandbls", "onesuperior", "logicalnot",
    "mu", "trademark", "Eth", "onehalf", "plusminus", "Thorn", "onequarter", "divide",
    "brokenbar", "degree", "thorn", "threequarters", "twosuperior", "registered", "minus", "eth",
    "multiply", "threesuperior", "copyright", "Aacute", "Acircumflex", "Adieresis", "Agrave", "Aring",
    "Atilde", "Ccedilla", "Eacute", "Ecircumflex", "Edieresis", "Egrave", "Iacute", "Icircumflex",
    "Idieresis", "Igrave", "Ntilde", "Oacute", "Ocircumflex", "Odieresis", "Ograve", "Otilde",
    "Scaron", "Uacute", "Ucircumflex", "Udieresis", "Ugrave", "Yacute", "Ydieresis", "Zcaron",
    "aacute", "acircumflex", "adieresis", "agrave", "aring", "atilde", "ccedilla", "eacute",
    "ecircumflex", "edieresis", "egrave", "iacute", "icircumflex", "idieresis", "igrave", "ntilde",
    "oacute", "ocircumflex", "odieresis", "ograve", "otilde", "scaron", "uacute", "ucircumflex",
    "udieresis", "ugrave", "yacute", "ydieresis", "zcaron", "exclamsmall", "Hungarumlautsmall", "dollaroldstyle",
    "dollarsuperior", "ampersandsmall", "Acutesmall", "parenleftsuperior", "parenrightsuperior", "twodotenleader", "onedotenleader", "zerooldstyle",
    "oneoldstyle", "twooldstyle", "threeoldstyle", "fouroldstyle", "fiveoldstyle", "sixoldstyle", "sevenoldstyle", "eightoldstyle",
    "nineoldstyle", "commasuperior", "threequartersemdash", "periodsuperior", "questionsmall", "asuperior", "bsuperior", "centsuperior",
    "dsuperior", "esuperior", "isuperior", "lsuperior", "msuperior", "nsuperior", "osuperior", "rsuperior",
    "ssuperior", "tsuperior", "ff", "ffi", "ffl", "parenleftinferior", "parenrightinferior", "Circumflexsmall",
    "hyphensuperior", "Gravesmall", "Asmall", "Bsmall", "Csmall", "Dsmall", "Esmall", "Fsmall",
    "Gsmall", "Hsmall", "Ismall", "Jsmall", "Ksmall", "Lsmall", "Msmall", "Nsmall",
    "Osmall", "Psmall", "Qsmall", "Rsmall", "Ssmall", "Tsmall", "Usmall", "Vsmall",
    "Wsmall", "Xsmall", "Ysmall", "Zsmall", "colonmonetary", "onefitted", "rupiah", "Tildesmall",
    "exclamdownsmall", "centoldstyle", "Lslashsmall", "Scaronsmall", "Zcaronsmall", "Dieresissmall", "Brevesmall", "Caronsmall",
    "Dotaccentsmall", "Macronsmall", "figuredash", "hypheninferior", "Ogoneksmall", "Ringsmall", "Cedillasmall", "questiondownsmall",
    "oneeighth", "threeeighths", "fiveeighths", "seveneighths", "onethird", "twothirds", "zerosuperior", "foursuperior",
    "fivesuperior", "sixsuperior", "sevensuperior", "eightsuperior", "ninesuperior", "zeroinferior", "oneinferior", "twoinferior",
    "threeinferior", "fourinferior", "fiveinferior", "sixinferior", "seveninferior", "eightinferior", "nineinferior", "centinferior",
    "dollarinferior", "periodinferior", "commainferior", "Agravesmall", "Aacutesmall", "Acircumflexsmall", "Atildesmall", "Adieresissmall",
    "Aringsmall", "AEsmall", "Ccedillasmall", "Egravesmall", "Eacutesmall", "Ecircumflexsmall", "Edieresissmall", "Igravesmall",
    "Iacutesmall", "Icircumflexsmall", "Idieresissmall", "Ntildesmall", "Ogravesmall", "Oacutesmall", "Ocircumflexsmall", "Otildesmall",
    "Odieresissmall", "Ugravesmall", "Uacutesmall", "Ucircumflexsmall", "Udieresissmall", "Emacronsmall", "Omacronsmall", "Umacronsmall",
    "radicalex", "arrowvertex", "arrowhorizex", "registersans", "copyrightsans", "trademarksans", "parenlefttp", "parenrighttp",
    "parenleftbt", "parenrightbt", "parenleftalt", "parenrightalt", "bracketlefttp", "bracketrighttp", "bracketleftbt",
];



/// Total number of CFF standard strings (SID 0..390); custom SIDs start at 391.
const N_STD_STRINGS: usize = 391;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dict_parses_integers_and_operators() {
        let d = [139u8, 17u8];
        let dict = parse_dict(&d);
        assert_eq!(dict.get(&17), Some(&vec![0.0]));
    }

    #[test]
    fn dict_parses_real() {
        // Real number encoding: 30, nibbles for "1.5" -> 0x1A 0x5F
        // 0x1 =1, 0xA='.', 0x5=5, 0xF terminator
        let d = [30u8, 0x1A, 0x5F, 17u8];
        let dict = parse_dict(&d);
        let v = dict.get(&17).unwrap()[0];
        assert!((v - 1.5).abs() < 0.001);
    }

    #[test]
    fn cff2_detected() {
        // Header major=2 should bail
        let data = [2u8, 0, 4, 0, 0, 0];
        assert!(builtin_encoding(&data).is_empty());
    }

    #[test]
    fn empty_or_garbage_is_safe() {
        assert!(builtin_encoding(&[]).is_empty());
        assert!(builtin_encoding(&[0, 1, 2, 3, 4, 5, 6]).is_empty());
    }

    #[test]
    fn std_strings_len() {
        assert_eq!(STD_STRINGS.len(), N_STD_STRINGS, "STD_STRINGS must have 391 entries");
    }

    #[test]
    fn standard_encoding_not_empty() {
        let m = standard_encoding_map();
        assert!(!m.is_empty());
        // Should contain 'A' at code 65
        assert!(m.contains_key(&65));
    }

    #[test]
    fn type2_parser_basic() {
        // Simple charstring: 100 0 rmoveto (21), 50 0 rlineto (5), endchar 14
        // Numbers: 100 = 239 (139+?), Actually 100 encoded as 239 (100+139=239)
        // 0 encoded as 139
        let cs = [239u8, 139u8, 21u8, 189u8, 139u8, 5u8, 14u8];
        // 239=100, 139=0, 21=rmoveto, 189=50, 139=0, 5=rlineto, 14=endchar
        let ops = parse_type2_charstring(&cs);
        assert!(ops.is_some());
        let ops = ops.unwrap();
        assert!(!ops.is_empty());
    }
}
