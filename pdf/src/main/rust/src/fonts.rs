use crate::*;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct FontStyle {
    pub(crate) bold: bool,
    pub(crate) italic: bool,
}

impl Default for FontStyle {
    fn default() -> Self { Self { bold: false, italic: false } }
}

pub(crate) struct FontInfo {
    /// Type0 (Identity-H) fonts use 2-byte codes; simple fonts use 1 byte.
    pub(crate) two_byte: bool,
    pub(crate) wmode: u8,
    pub(crate) vertical_metrics: HashMap<u32, (f64, f64)>,
    pub(crate) default_vertical: (f64, f64),
    pub(crate) cid_to_gid: Option<HashMap<u32, u16>>,
    /// `code -> unicode string` from the font's `/ToUnicode` CMap, if any.
    pub(crate) to_unicode: Option<HashMap<u32, String>>,
    /// `code -> unicode char` from the simple-font encoding (base + Differences),
    /// used when `/ToUnicode` is absent or lacks the code.
    pub(crate) encoding: HashMap<u32, char>,
    /// `code -> unicode char` recovered from an embedded TrueType `cmap`, for
    /// re-encoded subset fonts without `/ToUnicode`. Preferred over `encoding`.
    pub(crate) cmap_uni: HashMap<u32, char>,
    /// `code (or CID) -> glyph width` in text-space units (glyph units / 1000).
    pub(crate) widths: HashMap<u32, f64>,
    /// Fallback width (glyph units / 1000) for codes absent from `widths`.
    pub(crate) default_width: f64,
    /// Type 3 font data (glyph CharProc content streams), if this is a Type 3 font.
    pub(crate) t3: Option<Type3Font>,
    /// Synthetic font style recovered from BaseFont name + FontDescriptor.
    pub(crate) style: FontStyle,
    /// Descriptive base font name for fallback shaping (optional).
    pub(crate) base_font: String,
}

/// Type 3 font: glyphs are content streams drawn in glyph space, mapped to text
/// space by `font_matrix`.
pub(crate) struct Type3Font {
    pub(crate) font_matrix: Mat,
    /// Character code -> CharProc stream object id (via `/Encoding` Differences).
    pub(crate) char_procs: HashMap<u32, ObjectId>,
    pub(crate) resources: Option<Dictionary>,
}

fn is_space_codepoint(cp: u32, decoded_text: Option<&str>) -> bool {
    // Core ASCII + NBSP + full-width ideographic space 0x3000 + other
    // commonly-checked unicode spaces relevant for Tw detection.
    matches!(
        cp,
        32 |       // ASCII space
        0x00A0 |   // NBSP
        0x2000..=0x200A | // En quad .. hair space
        0x2002 | 0x2003 | // En/em space
        0x2009 | 0x202F | // Thin/narrow NBSP
        0x205F |          // Medium mathematical space
        0x3000             // Ideographic space (CJK full-width)
    ) || decoded_text.map(|s| s.chars().any(|c| c.is_whitespace())).unwrap_or(false)
}

impl FontInfo {
    /// Invoke `f(code, is_single_byte_space)` for each character code in the
    /// string, honoring this font's code width (1 or 2 bytes).
    pub(crate) fn for_each_code(&self, bytes: &[u8], mut f: impl FnMut(u32, bool)) {
        if self.two_byte {
            let mut i = 0;
            while i + 1 < bytes.len() {
                let code = ((bytes[i] as u32) << 8) | bytes[i + 1] as u32;
                // Word spacing (Tw) should apply to all unicode spaces, not just 0x20.
                // Check full-width (0x3000), NBSP (0xA0), and ToUnicode == " " or
                // any whitespace-like mapping.
                let to_uni = self.to_unicode.as_ref().and_then(|m| m.get(&code));
                let is_space = code == 32
                    || code == 0x00A0
                    || code == 0x3000
                    || to_uni.map(|s| s == " " || s.chars().any(|c| c.is_whitespace())).unwrap_or(false);
                f(code, is_space);
                i += 2;
            }
        } else {
            for &b in bytes {
                let code = b as u32;
                let is_space = code == 32
                    || code == 0x00A0
                    || self.encoding.get(&code).map(|c| c.is_whitespace()).unwrap_or(false);
                f(code, is_space);
            }
        }
    }

    /// Width of `code` in text-space units (glyph units / 1000).
    pub(crate) fn width(&self, code: u32) -> f64 {
        self.widths.get(&code).copied().unwrap_or(self.default_width)
    }

    pub(crate) fn push_code(&self, code: u32, out: &mut String) {
        if let Some(map) = &self.to_unicode {
            if let Some(s) = map.get(&code) {
                out.push_str(s);
                return;
            }
        }
        // Prefer the declared encoding (WinAnsi / Differences) so standard
        // punctuation is correct; fall back to the embedded cmap for symbolic
        // re-encoded subset fonts whose encoding doesn't cover the code.
        if let Some(c) = self.encoding.get(&code) {
            out.push(*c);
            return;
        }
        if let Some(c) = self.cmap_uni.get(&code) {
            out.push(*c);
            return;
        }
        // Last resort: Latin-1 for single-byte codes; best-effort otherwise.
        if let Some(c) = char::from_u32(code) {
            out.push(c);
        }
    }
}

/// Build a `font resource name -> FontInfo` map from a resources dictionary.
pub(crate) fn fonts_from_resources(doc: &Document, res_dict: &lopdf::Dictionary) -> HashMap<Vec<u8>, FontInfo> {
    let mut fonts = HashMap::new();
    let font_dict = match res_dict.get(b"Font").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Dictionary(d)) => d,
        _ => return fonts,
    };
    for (name, font_ref) in font_dict.iter() {
        if let Some(Object::Dictionary(fd)) = deref(doc, font_ref) {
            fonts.insert(name.clone(), font_info(doc, fd));
        }
    }
    fonts
}

pub(crate) fn font_info(doc: &Document, font: &lopdf::Dictionary) -> FontInfo {
    let subtype = font.get(b"Subtype").ok().and_then(|o| o.as_name().ok());
    let two_byte = matches!(subtype, Some(b"Type0"));
    let is_type3 = subtype == Some(b"Type3");
    let to_unicode = font
        .get(b"ToUnicode")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| match o {
            Object::Stream(s) => Some(stream_data(s)),
            _ => None,
        })
        .map(|data| cmap::parse(&data));

    // WMode: 0 horizontal (default), 1 vertical. Detect from Type0 font dict and descendant.
    let wmode: u8 = font.get(b"WMode").ok().and_then(num).map(|v| if v >= 1.0 { 1 } else { 0 }).unwrap_or(0) as u8;
    let desc_wmode: u8 = font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o)).and_then(|o| match o { Object::Array(a) => a.first(), _ => None }).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()).and_then(|d| d.get(b"WMode").ok()).and_then(num).map(|v| if v >= 1.0 { 1 } else { 0 }).unwrap_or(wmode as u8) as u8;
    let effective_wmode = desc_wmode.max(wmode as u8);

    // CIDToGIDMap
    let cid_to_gid: Option<HashMap<u32, u16>> = {
        font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o)).and_then(|o| match o { Object::Array(a) => a.first(), _ => None }).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()).and_then(|df| {
            match df.get(b"CIDToGIDMap").ok().and_then(|o| deref(doc, o)).or_else(|| df.get(b"CIDToGIDMap").ok()) {
                Some(Object::Stream(s)) => {
                    let data = stream_data(s);
                    let mut map = HashMap::new();
                    for (i, chunk) in data.chunks(2).enumerate() {
                        if chunk.len() < 2 { break; }
                        let gid = ((chunk[0] as u16) << 8) | chunk[1] as u16;
                        if gid != 0 {
                            map.insert(i as u32, gid);
                        }
                    }
                    Some(map)
                }
                Some(Object::Name(n)) if n == b"Identity" => None, // identity = no remap
                _ => None,
            }
        })
    };

    // Type 3 glyph data (parsed before widths so the FontMatrix scale is known).
    let t3 = if is_type3 {
        type3::parse_type3_font(doc, font).map(|info| {
            let mut char_procs = HashMap::new();
            for (code, name) in info.encoding.iter() {
                if let Some(id) = info.char_procs.get(name) {
                    char_procs.insert(*code as u32, *id);
                }
            }
            Type3Font { font_matrix: info.font_matrix, char_procs, resources: info.resources }
        })
    } else {
        None
    };

    let (widths, default_width) = if two_byte {
        cid_widths(doc, font)
    } else if is_type3 {
        let fm_scale = t3.as_ref().map(|t| t.font_matrix[0]).unwrap_or(0.001);
        type3_widths(doc, font, fm_scale)
    } else {
        simple_widths(doc, font)
    };

    // Vertical widths /W2 /DW2 for WMode=1 (best-effort: keep horizontal fallback for now, but record metrics)
    let vert_desc = font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o)).and_then(|o| match o { Object::Array(a) => a.first(), _ => None }).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()).cloned();
    let (vert_map, default_vert): (HashMap<u32, (f64, f64)>, (f64, f64)) = {
        let mut vm = HashMap::new();
        let mut dw2 = (0.0, -1000.0); // default per spec approx
        if let Some(ref df) = vert_desc {
            if let Some(Object::Array(arr)) = df.get(b"DW2").ok().and_then(|o| deref(doc, o)) {
                let v: Vec<f64> = arr.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).collect();
                if v.len() >= 2 {
                    dw2 = (v[0] / 1000.0, v[1] / 1000.0);
                }
            }
            if let Some(Object::Array(w2)) = df.get(b"W2").ok().and_then(|o| deref(doc, o)) {
                let mut i = 0;
                while i + 2 < w2.len() {
                    let c0 = match deref(doc, &w2[i]).and_then(num) { Some(v) => v as u32, None => break };
                    let c1 = match deref(doc, &w2[i+1]).and_then(num) { Some(v) => v as u32, None => break };
                    // w2 entries: c0 c1 w1 v1 v2 ...? spec: c0 c1 w1 v_h v_v OR c0 [w1 v_h v_v ...]
                    // Simplified best-effort:
                    if let Some(Object::Array(list)) = w2.get(i+2).and_then(|o| deref(doc, o)) {
                        for (j, item) in list.iter().enumerate() {
                            // Expect sequence of w, vx, vy triplets? Could be [w vx vy w vx vy...]
                            // Best-effort placeholder
                            if let Some(_w) = deref(doc, item).and_then(num) { vm.insert(c0 + j as u32, (dw2.0, _w / 1000.0)); }
                        }
                        i += 3;
                    } else {
                        let _w = w2.get(i+2).and_then(|o| deref(doc, o)).and_then(num).unwrap_or(-1000.0) / 1000.0;
                        let vx = w2.get(i+3).and_then(|o| deref(doc, o)).and_then(num).unwrap_or(0.0) / 1000.0;
                        let vy = w2.get(i+4).and_then(|o| deref(doc, o)).and_then(num).unwrap_or(0.0) / 1000.0;
                        for cid in c0..=c1 { vm.insert(cid, (vx, vy)); }
                        i += 5;
                    }
                }
            }
        }
        (vm, dw2)
    };

    let encoding = if two_byte {
        HashMap::new()
    } else {
        encoding::build(doc, font)
    };
    let cmap_uni = if two_byte || is_type3 {
        HashMap::new()
    } else {
        let mut m = ttf_code_map(doc, font);
        // Fall back to the embedded font program's built-in encoding for
        // symbolic/subset fonts that lack /ToUnicode and a TrueType cmap.
        if m.is_empty() {
            let t1 = type1_builtin_encoding(doc, font);
            if !t1.is_empty() {
                m = t1;
            } else {
                let cff = cff_builtin_encoding(doc, font);
                if !cff.is_empty() {
                    m = cff;
                }
            }
        }
        m
    };

    // --- Font style detection for bold/italic synthesis ---
    let base_font_name = font.get(b"BaseFont").ok().and_then(|o| o.as_name().ok())
        .map(|n| String::from_utf8_lossy(n).to_string())
        .or_else(|| {
            // Try descendant for Type0
            font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o))
                .and_then(|o| match o { Object::Array(a) => a.first(), _ => None })
                .and_then(|o| deref(doc, o))
                .and_then(|o| o.as_dict().ok())
                .and_then(|d| d.get(b"BaseFont").ok())
                .and_then(|o| o.as_name().ok())
                .map(|n| String::from_utf8_lossy(n).to_string())
        })
        .unwrap_or_default();

    let fd = font.get(b"FontDescriptor").ok().and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok()).cloned()
        .or_else(|| {
            // For Type0, try descendant's FontDescriptor
            font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o))
                .and_then(|o| match o { Object::Array(a) => a.first(), _ => None })
                .and_then(|o| deref(doc, o))
                .and_then(|o| o.as_dict().ok())
                .and_then(|d| d.get(b"FontDescriptor").ok())
                .and_then(|o| deref(doc, o))
                .and_then(|o| o.as_dict().ok())
                .cloned()
        });

    let mut bold = false;
    let mut italic = false;
    let lower = base_font_name.to_lowercase();
    if lower.contains("bold") || lower.contains("black") || lower.contains("heavy") {
        bold = true;
    }
    if lower.contains("italic") || lower.contains("oblique") || lower.contains("slanted") {
        italic = true;
    }
    if let Some(ref desc) = fd {
        if let Some(flags) = desc.get(b"Flags").ok().and_then(num) {
            let f = flags as i64;
            // Bit 18 (1<<18 = 262144) = Italic per PDF spec 9.8.2
            if f & (1<<18) != 0 || f & 64 != 0 { italic = true; } // 64 is common non-spec but some generators
            // There is no bold flag but some files use bit 6? Actually force bold is 18? We'll rely on StemV/Weight
        }
        if let Some(angle) = desc.get(b"ItalicAngle").ok().and_then(num) {
            if angle.abs() > 0.5 { italic = true; }
        }
        if let Some(weight) = desc.get(b"FontWeight").ok().and_then(num) {
            if weight >= 600.0 { bold = true; }
        } else if let Some(name) = desc.get(b"FontWeight").ok().and_then(|o| o.as_name().ok()) {
            if String::from_utf8_lossy(name).to_lowercase().contains("bold") { bold = true; }
        }
        if let Some(stemv) = desc.get(b"StemV").ok().and_then(num) {
            if stemv.abs() > 140.0 { bold = true; }
        }
        if desc.get(b"FontName").ok().and_then(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).to_lowercase().contains("bold")).unwrap_or(false) { bold = true; }
    }

    FontInfo {
        two_byte,
        wmode: effective_wmode,
        vertical_metrics: vert_map,
        default_vertical: default_vert,
        cid_to_gid,
        to_unicode,
        encoding,
        cmap_uni,
        widths,
        default_width,
        t3,
        style: FontStyle { bold, italic },
        base_font: base_font_name,
    }
}

/// Widths for a Type 3 font: `/Widths` values are in glyph space and are scaled
/// to text space by the FontMatrix x-scale (rather than the /1000 used for
/// simple fonts).
fn type3_widths(doc: &Document, font: &lopdf::Dictionary, fm_scale: f64) -> (HashMap<u32, f64>, f64) {
    let mut widths = HashMap::new();
    let first_char = font.get(b"FirstChar").ok().and_then(num).unwrap_or(0.0) as u32;
    if let Some(Object::Array(arr)) = font.get(b"Widths").ok().and_then(|o| deref(doc, o)) {
        for (i, w) in arr.iter().enumerate() {
            if let Some(w) = deref(doc, w).and_then(num) {
                widths.insert(first_char + i as u32, w * fm_scale);
            }
        }
    }
    (widths, 0.0)
}

/// Widths for a simple (1-byte) font from `/Widths` + `/FirstChar`, with the
/// `/FontDescriptor /MissingWidth` fallback. Values are glyph units / 1000.
pub(crate) fn simple_widths(doc: &Document, font: &lopdf::Dictionary) -> (HashMap<u32, f64>, f64) {
    let mut widths = HashMap::new();
    let first_char = font
        .get(b"FirstChar")
        .ok()
        .and_then(num)
        .unwrap_or(0.0) as u32;
    if let Some(Object::Array(arr)) = font.get(b"Widths").ok().and_then(|o| deref(doc, o)) {
        for (i, w) in arr.iter().enumerate() {
            if let Some(w) = deref(doc, w).and_then(num) {
                widths.insert(first_char + i as u32, w / 1000.0);
            }
        }
    }
    let missing = font
        .get(b"FontDescriptor")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .and_then(|d| d.get(b"MissingWidth").ok())
        .and_then(num)
        .unwrap_or(0.0)
        / 1000.0;
    // Simple fonts without a /Widths array (e.g. the standard 14) get a
    // reasonable default so advances are non-degenerate.
    let default_width = if widths.is_empty() { 0.5 } else { missing };
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

    if let Some(dw) = df.get(b"DW").ok().and_then(num) {
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
                            widths.insert(c + j as u32, v / 1000.0);
                        }
                    }
                    i += 2;
                }
                _ => {
                    let c_last = w.get(i + 1).and_then(|o| deref(doc, o)).and_then(num);
                    let width = w.get(i + 2).and_then(|o| deref(doc, o)).and_then(num);
                    if let (Some(c_last), Some(width)) = (c_last, width) {
                        for cid in c..=(c_last as u32) {
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
    for line in s.split(|c| c == '\n' || c == '\r') {
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

/// Minimal TrueType `cmap` parser: recovers a character-code → Unicode map by
/// composing a code→glyph subtable (Mac 1,0 or Symbol 3,0) with the reverse of
/// a Unicode subtable (3,1 / 0,3 / 3,10). All reads are bounds-checked so
/// malformed font data can never panic.
pub(crate) mod ttf {
    use std::collections::HashMap;
use std::io::Cursor;

    fn u16b(b: &[u8], o: usize) -> u16 {
        ((*b.get(o).unwrap_or(&0) as u16) << 8) | *b.get(o + 1).unwrap_or(&0) as u16
    }
    fn u32b(b: &[u8], o: usize) -> u32 {
        ((u16b(b, o) as u32) << 16) | u16b(b, o + 2) as u32
    }

    fn table_offset(b: &[u8], tag: &[u8; 4]) -> Option<usize> {
        let num = u16b(b, 4) as usize;
        for i in 0..num {
            let rec = 12 + i * 16;
            if b.get(rec..rec + 4)? == tag {
                return Some(u32b(b, rec + 8) as usize);
            }
        }
        None
    }

    /// Parse a subtable at `off` into (code, glyphId) pairs.
    fn parse_subtable(b: &[u8], off: usize) -> Vec<(u32, u16)> {
        let mut out = Vec::new();
        let fmt = u16b(b, off);
        match fmt {
            0 => {
                // Byte encoding: 256 single-byte glyph ids.
                for c in 0..256u32 {
                    let g = *b.get(off + 6 + c as usize).unwrap_or(&0) as u16;
                    if g != 0 {
                        out.push((c, g));
                    }
                }
            }
            2 => {
                // Format 2 (CJK high-byte): sparse subHeaders + maps.
                // Structure: [format,u16][length,u16][lang,u16][subHeaderKeys 256×u16][subHeaders][glyphIndexArray]
                // Each subHeaderKey is idx*8 of subHeader, or 0 if single-byte. SubHeader: firstCode,reserved,entryCount,delta (i16),rangeOffset.
                // Bounds-check heavily — exotic.
                if b.len() < off + 6 || off + 6 > b.len() {
                    return out;
                }
                let sub_keys_off = off + 6;
                if sub_keys_off + 512 > b.len() {
                    return out;
                }
                // Pre-calc max subHeader idx from keys
                let mut max_key = 0usize;
                for k in 0..256 {
                    let v = u16b(b, sub_keys_off + k * 2) as usize;
                    if v / 8 > max_key {
                        max_key = v / 8;
                    }
                }
                let sub_header_off = sub_keys_off + 512;
                // GlyphIndexArray follows subHeaders: need to estimate
                let ghi_off = sub_header_off + (max_key + 1) * 8;
                if ghi_off > b.len() {
                    return out;
                }
                for sbyte in 0u32..256 {
                    let key_raw = u16b(b, sub_keys_off + sbyte as usize * 2) as usize;
                    let sh_idx = key_raw / 8;
                    if sh_idx == 0 {
                        // Single-byte code maps via one entry
                        let sh_off = sub_header_off + sh_idx * 8;
                        if sh_off + 8 > b.len() {
                            continue;
                        }
                        let first = u16b(b, sh_off) as u32;
                        // Only attempt when high byte matches etc — best-effort
                        // For format2, single-byte glyphs: range 0x00..0xFF
                        if sbyte == first {
                            let range_off = u16b(b, sh_off + 6) as usize;
                            let glyph: u16 = if range_off == 0 {
                                let delta = u16b(b, sh_off + 4) as i16;
                                (sbyte as i16 + delta) as u16
                            } else {
                                let addr = ghi_off + range_off;
                                u16b(b, addr)
                            };
                            if glyph != 0 {
                                out.push((sbyte, glyph));
                            }
                        }
                    }
                }
                // Two-byte sequence handling simplified: high byte groups
                for hi in 0u32..256 {
                    let key_raw = u16b(b, sub_keys_off + hi as usize * 2) as usize;
                    let sh_idx = key_raw / 8;
                    if sh_idx == 0 {
                        continue;
                    }
                    let sh_off = sub_header_off + sh_idx * 8;
                    if sh_off + 8 > b.len() {
                        continue;
                    }
                    let first_code = u16b(b, sh_off) as u32;
                    let entry_count = u16b(b, sh_off + 2) as u32;
                    let delta = u16b(b, sh_off + 4) as i16;
                    let range_off = u16b(b, sh_off + 6) as usize;
                    for low in 0u32..entry_count.min(256) {
                        let code = (hi << 8) | (first_code + low);
                        let gid = if range_off == 0 {
                            ((first_code + low) as i16 + delta) as u16
                        } else {
                            let addr = sub_header_off + sh_idx * 8 + 6 + range_off + (low as usize * 2);
                            u16b(b, addr)
                        };
                        if gid != 0 {
                            out.push((code, gid));
                        }
                    }
                }
            }
            6 => {
                let first = u16b(b, off + 6) as u32;
                let count = u16b(b, off + 8) as usize;
                for i in 0..count {
                    let g = u16b(b, off + 10 + i * 2);
                    if g != 0 {
                        out.push((first + i as u32, g));
                    }
                }
            }
            4 => {
                let segx2 = u16b(b, off + 6) as usize;
                let seg = segx2 / 2;
                let end_o = off + 14;
                let start_o = end_o + segx2 + 2;
                let delta_o = start_o + segx2;
                let range_o = delta_o + segx2;
                for i in 0..seg {
                    let end = u16b(b, end_o + i * 2);
                    let start = u16b(b, start_o + i * 2);
                    let delta = u16b(b, delta_o + i * 2);
                    let range = u16b(b, range_o + i * 2);
                    if start > end {
                        continue;
                    }
                    for c in start..=end {
                        if c == 0xFFFF {
                            break;
                        }
                        let gid = if range == 0 {
                            (c.wrapping_add(delta)) & 0xFFFF
                        } else {
                            let addr = range_o + i * 2 + range as usize + 2 * (c - start) as usize;
                            let g = u16b(b, addr);
                            if g == 0 {
                                0
                            } else {
                                (g.wrapping_add(delta)) & 0xFFFF
                            }
                        };
                        if gid != 0 {
                            out.push((c as u32, gid));
                        }
                    }
                }
            }
            8 => {
                // Format 8: mixed 16/32 coverage. Guarded best-effort.
                // [format 8][reserved][length u32][lang u32][is32 array 8192 bytes][nGroups u32][groups...] groups are [start,end,gid]
                if b.len() < off + 12 {
                    return out;
                }
                let length = u32b(b, off + 2) as usize;
                if off + length > b.len() || length < 8200 {
                    return out;
                }
                // After is32 bitmap (8192 bytes) at off+12, nGroups at off+8204
                let ngroups_off = off + 12 + 8192;
                if ngroups_off + 4 > b.len() {
                    return out;
                }
                let ngroups = u32b(b, ngroups_off) as usize;
                let groups_off = ngroups_off + 4;
                for g in 0..ngroups.min(100_000) {
                    let go = groups_off + g * 12;
                    if go + 12 > b.len() {
                        break;
                    }
                    let sc = u32b(b, go);
                    let ec = u32b(b, go + 4);
                    let sg = u32b(b, go + 8) as u16;
                    if sc > ec || ec - sc > 65535 || sg == 0 {
                        continue;
                    }
                    for c in sc..=ec {
                        out.push((c, (sg as u32 + (c - sc)) as u16));
                    }
                }
            }
            10 => {
                // Trimmed array (like format 6 but 32-bit code space).
                let first = u32b(b, off + 12);
                let count = u32b(b, off + 16) as usize;
                for i in 0..count.min(0x20000) {
                    let g = u16b(b, off + 20 + i * 2);
                    if g != 0 {
                        out.push((first + i as u32, g));
                    }
                }
            }
            12 => {
                let ngroups = u32b(b, off + 12) as usize;
                for i in 0..ngroups {
                    let g = off + 16 + i * 12;
                    let sc = u32b(b, g);
                    let ec = u32b(b, g + 4);
                    let sg = u32b(b, g + 8);
                    if sc > ec || ec - sc > 65535 {
                        continue;
                    }
                    for c in sc..=ec {
                        out.push((c, (sg + (c - sc)) as u16));
                    }
                }
            }
            13 => {
                // Many-to-one range mappings: every code in a group maps to the
                // same glyph (used for e.g. "last resort" fonts).
                let ngroups = u32b(b, off + 12) as usize;
                for i in 0..ngroups {
                    let g = off + 16 + i * 12;
                    let sc = u32b(b, g);
                    let ec = u32b(b, g + 4);
                    let gid = u32b(b, g + 8) as u16;
                    if sc > ec || ec - sc > 65535 || gid == 0 {
                        continue;
                    }
                    for c in sc..=ec {
                        out.push((c, gid));
                    }
                }
            }
            14 => {
                // Format 14: variation selectors — produces no direct code->gid mapping
                // for basic text extraction; skip but parse best-effort: if present,
                // treat first 3 tables? For extraction we ignore selectors and only
                // map base unicode via defaultUVS -> uVS. The cmap recovery composes
                // code->glyph and gid->uni anyway; variation tables provide alt uni for
                // <base, selector>. We produce base uni mapping ignoring selector for now.
                // Parse top [format 2byte][length 4][numVarSelectorRecords 4]
                if b.len() < off + 10 {
                    return out;
                }
                let num_recs = u32b(b, off + 6) as usize;
                // Each record: varSelector 3 byte, defaultUVS off 4, nonDefault off 4.
                // If defaultUVS non-zero, it contains ranges mapping base unicode -> selector maps to default glyph.
                // This logic is complex, for robustness we only handle defaultUVS path to map base uni to default glyph
                for i in 0..num_recs.min(1000) {
                    let rec_off = off + 10 + i * 11;
                    if rec_off + 11 > b.len() {
                        break;
                    }
                    let default_off = u32b(b, rec_off + 3) as usize;
                    if default_off != 0 {
                        let base_rec = off + default_off;
                        if base_rec + 4 > b.len() {
                            continue;
                        }
                        let num_ranges = u32b(b, base_rec) as usize;
                        for r in 0..num_ranges.min(10_000) {
                            let ro = base_rec + 4 + r * 4;
                            if ro + 4 > b.len() {
                                break;
                            }
                            let start = (b[ro] as u32) << 16 | u16b(b, ro + 1) as u32;
                            let addl = b[ro + 3] as u32;
                            for u in start..=start + addl {
                                out.push((u, 0)); // marker, will be filtered via uni mapping fallback?
                            }
                        }
                    }
                }
                // No gid mapping for format 14; fallback to other subtable
            }
            _ => {}
        }
        out
    }

    pub fn code_to_unicode(b: &[u8]) -> HashMap<u32, char> {
        let mut result = HashMap::new();
        let cmap = match table_offset(b, b"cmap") {
            Some(o) => o,
            None => return result,
        };
        let n = u16b(b, cmap + 2) as usize;

        let mut uni_sub: Option<usize> = None;
        let mut mac_sub: Option<usize> = None;
        let mut sym_sub: Option<usize> = None;
        for i in 0..n {
            let r = cmap + 4 + i * 8;
            let pid = u16b(b, r);
            let eid = u16b(b, r + 2);
            let so = cmap + u32b(b, r + 4) as usize;
            match (pid, eid) {
                (3, 1) | (0, 3) | (3, 10) | (0, 4) => uni_sub = Some(so),
                (1, 0) => mac_sub = Some(so),
                (3, 0) => sym_sub = Some(so),
                _ => {}
            }
        }

        // glyph -> unicode (from the Unicode subtable).
        let gid_to_uni: HashMap<u16, u32> = match uni_sub {
            Some(o) => {
                let mut m = HashMap::new();
                for (uni, gid) in parse_subtable(b, o) {
                    m.entry(gid).or_insert(uni);
                }
                m
            }
            None => return result,
        };

        // code -> glyph (from Mac and/or Symbol subtables), then -> unicode.
        for sub in [mac_sub, sym_sub].into_iter().flatten() {
            for (code, gid) in parse_subtable(b, sub) {
                if let Some(&uni) = gid_to_uni.get(&gid) {
                    if let Some(c) = char::from_u32(uni) {
                        result.entry(code).or_insert(c);
                        // Symbol (3,0) codes are often mapped at 0xF000+code.
                        if code >= 0xF000 {
                            result.entry(code - 0xF000).or_insert(c);
                        }
                    }
                }
            }
        }
        result
    }

    #[cfg(test)]
    mod tests {
        use super::parse_subtable;

        fn be16(v: u16) -> [u8; 2] { v.to_be_bytes() }
        fn be32(v: u32) -> [u8; 4] { v.to_be_bytes() }

        #[test]
        fn format13_maps_range_to_single_glyph() {
            let mut b = Vec::new();
            b.extend_from_slice(&be16(13));      // format
            b.extend_from_slice(&be16(0));       // reserved
            b.extend_from_slice(&be32(0));       // length
            b.extend_from_slice(&be32(0));       // language
            b.extend_from_slice(&be32(1));       // nGroups
            b.extend_from_slice(&be32(0x41));    // startChar
            b.extend_from_slice(&be32(0x43));    // endChar
            b.extend_from_slice(&be32(5));       // glyphID
            let pairs = parse_subtable(&b, 0);
            assert!(pairs.contains(&(0x41, 5)));
            assert!(pairs.contains(&(0x42, 5)));
            assert!(pairs.contains(&(0x43, 5)));
        }

        #[test]
        fn format10_trimmed_array() {
            let mut b = Vec::new();
            b.extend_from_slice(&be16(10));      // format
            b.extend_from_slice(&be16(0));       // reserved
            b.extend_from_slice(&be32(0));       // length
            b.extend_from_slice(&be32(0));       // language
            b.extend_from_slice(&be32(0x41));    // startCharCode
            b.extend_from_slice(&be32(2));       // numChars
            b.extend_from_slice(&be16(7));       // glyph for 0x41
            b.extend_from_slice(&be16(8));       // glyph for 0x42
            let pairs = parse_subtable(&b, 0);
            assert!(pairs.contains(&(0x41, 7)));
            assert!(pairs.contains(&(0x42, 8)));
        }
    }
}

// ---------------------------------------------------------------------------
// Simple-font encodings (base encoding + /Differences)
// ---------------------------------------------------------------------------

pub(crate) mod encoding {
    use super::{deref, num, Object};
    use lopdf::Document;
    use std::collections::HashMap;
use std::io::Cursor;

    /// Build a `code -> unicode char` map for a simple font: start from the base
    /// encoding (WinAnsi / MacRoman / Standard, or Symbol / ZapfDingbats for
    /// those base fonts), then apply any `/Encoding /Differences`.
    pub fn build(doc: &Document, font: &lopdf::Dictionary) -> HashMap<u32, char> {
        let base_font = font
            .get(b"BaseFont")
            .ok()
            .and_then(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).into_owned())
            .unwrap_or_default();

        let enc_obj = font.get(b"Encoding").ok().and_then(|o| deref(doc, o));
        let base_name = match &enc_obj {
            Some(Object::Name(n)) => Some(String::from_utf8_lossy(n).into_owned()),
            Some(Object::Dictionary(d)) => d
                .get(b"BaseEncoding")
                .ok()
                .and_then(|o| o.as_name().ok())
                .map(|n| String::from_utf8_lossy(n).into_owned()),
            _ => None,
        };

        let mut map = if base_font.contains("Symbol") {
            symbol_table()
        } else if base_font.contains("ZapfDingbats") || base_font.contains("Dingbats") {
            zapf_table()
        } else {
            match base_name.as_deref() {
                Some("WinAnsiEncoding") => win_ansi(),
                Some("MacRomanEncoding") => crate::glyphlist::mac_roman(),
                Some("StandardEncoding") => standard(),
                Some("Symbol") => symbol_table(),
                Some("ZapfDingbats") => zapf_table(),
                // Default base encoding for most simple fonts is Standard, but
                // WinAnsi is the safest superset for modern PDFs.
                _ => win_ansi(),
            }
        };

        // Apply /Differences: [ code /name /name code /name ... ].
        if let Some(Object::Dictionary(d)) = &enc_obj {
            if let Some(Object::Array(diffs)) = d.get(b"Differences").ok().and_then(|o| deref(doc, o))
            {
                let mut code = 0u32;
                for item in diffs {
                    match item {
                        Object::Integer(_) | Object::Real(_) => {
                            code = num(item).unwrap_or(0.0) as u32;
                        }
                        Object::Name(name) => {
                            if let Some(c) = glyph_to_char(&String::from_utf8_lossy(name)) {
                                map.insert(code, c);
                            }
                            code += 1;
                        }
                        _ => {}
                    }
                }
            }
        }
        map
    }

    /// Resolve an Adobe glyph name to a Unicode scalar. Handles `uniXXXX`,
    /// `uXXXXXX`, the Adobe Glyph List (standard Latin/Greek/symbol names),
    /// single-character names, and named digits/letters.
    pub fn glyph_to_char(name: &str) -> Option<char> {
        // Strip a font-specific suffix like "name.sc" / "name.alt".
        let base = name.split('.').next().unwrap_or(name);
        if let Some(hex) = base.strip_prefix("uni") {
            if hex.len() >= 4 {
                if let Ok(cp) = u32::from_str_radix(&hex[..4], 16) {
                    return char::from_u32(cp);
                }
            }
        }
        if base.starts_with('u') && base.len() >= 5 && base.len() <= 7 {
            if let Ok(cp) = u32::from_str_radix(&base[1..], 16) {
                if let Some(c) = char::from_u32(cp) {
                    return Some(c);
                }
            }
        }
        // Adobe Glyph List (standard names).
        if let Some(c) = crate::glyphlist::agl(base) {
            return Some(c);
        }
        if let Some(c) = curated(base) {
            return Some(c);
        }
        // Single-character glyph name (e.g. "A", "a", "1").
        let mut chars = base.chars();
        if let (Some(c), None) = (chars.next(), chars.clone().next()) {
            return Some(c);
        }
        None
    }

    fn curated(name: &str) -> Option<char> {
        let c = match name {
            "space" | "nbspace" => ' ',
            "bullet" => '\u{2022}',
            "periodcentered" => '\u{00B7}',
            "endash" => '\u{2013}',
            "emdash" => '\u{2014}',
            "hyphen" | "sfthyphen" => '-',
            "quoteleft" => '\u{2018}',
            "quoteright" => '\u{2019}',
            "quotedblleft" => '\u{201C}',
            "quotedblright" => '\u{201D}',
            "quotesingle" => '\'',
            "quotedbl" => '"',
            "comma" => ',',
            "period" => '.',
            "colon" => ':',
            "semicolon" => ';',
            "slash" => '/',
            "backslash" => '\\',
            "asterisk" => '*',
            "ampersand" => '&',
            "at" => '@',
            "numbersign" => '#',
            "percent" => '%',
            "dollar" => '$',
            "cent" => '\u{00A2}',
            "sterling" => '\u{00A3}',
            "euro" => '\u{20AC}',
            "yen" => '\u{00A5}',
            "trademark" => '\u{2122}',
            "registered" => '\u{00AE}',
            "copyright" => '\u{00A9}',
            "degree" => '\u{00B0}',
            "plusminus" => '\u{00B1}',
            "multiply" => '\u{00D7}',
            "divide" => '\u{00F7}',
            "ellipsis" => '\u{2026}',
            "dagger" => '\u{2020}',
            "daggerdbl" => '\u{2021}',
            "paragraph" => '\u{00B6}',
            "section" => '\u{00A7}',
            "fi" => '\u{FB01}',
            "fl" => '\u{FB02}',
            "exclam" => '!',
            "question" => '?',
            "parenleft" => '(',
            "parenright" => ')',
            "bracketleft" => '[',
            "bracketright" => ']',
            "braceleft" => '{',
            "braceright" => '}',
            "less" => '<',
            "greater" => '>',
            "equal" => '=',
            "plus" => '+',
            "minus" => '\u{2212}',
            "underscore" => '_',
            "hyphenminus" => '-',
            "arrowright" => '\u{2192}',
            "arrowleft" => '\u{2190}',
            "arrowup" => '\u{2191}',
            "arrowdown" => '\u{2193}',
            "zero" => '0',
            "one" => '1',
            "two" => '2',
            "three" => '3',
            "four" => '4',
            "five" => '5',
            "six" => '6',
            "seven" => '7',
            "eight" => '8',
            "nine" => '9',
            _ => return None,
        };
        Some(c)
    }

    /// WinAnsiEncoding (CP1252): Latin-1 with the 0x80–0x9F range remapped.
    pub fn win_ansi() -> HashMap<u32, char> {
        let mut m = latin1();
        let overrides: [(u32, u32); 27] = [
            (0x80, 0x20AC),
            (0x82, 0x201A),
            (0x83, 0x0192),
            (0x84, 0x201E),
            (0x85, 0x2026),
            (0x86, 0x2020),
            (0x87, 0x2021),
            (0x88, 0x02C6),
            (0x89, 0x2030),
            (0x8A, 0x0160),
            (0x8B, 0x2039),
            (0x8C, 0x0152),
            (0x8E, 0x017D),
            (0x91, 0x2018),
            (0x92, 0x2019),
            (0x93, 0x201C),
            (0x94, 0x201D),
            (0x95, 0x2022),
            (0x96, 0x2013),
            (0x97, 0x2014),
            (0x98, 0x02DC),
            (0x99, 0x2122),
            (0x9A, 0x0161),
            (0x9B, 0x203A),
            (0x9C, 0x0153),
            (0x9E, 0x017E),
            (0x9F, 0x0178),
        ];
        for (code, cp) in overrides {
            if let Some(c) = char::from_u32(cp) {
                m.insert(code, c);
            }
        }
        m
    }

    /// StandardEncoding: for the ASCII range it matches Latin-1; good enough as
    /// a base to which /Differences are applied.
    fn standard() -> HashMap<u32, char> {
        latin1()
    }

    /// Codes 0x20–0xFF mapped as Latin-1 (identity to Unicode).
    fn latin1() -> HashMap<u32, char> {
        let mut m = HashMap::new();
        for code in 0x20u32..=0xFF {
            if let Some(c) = char::from_u32(code) {
                m.insert(code, c);
            }
        }
        m
    }

    /// The full Adobe Symbol-font encoding (Greek + math operators).
    fn symbol_table() -> HashMap<u32, char> {
        crate::glyphlist::symbol()
    }

    /// The ZapfDingbats encoding (dingbats/ornaments).
    fn zapf_table() -> HashMap<u32, char> {
        crate::glyphlist::zapf()
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn winansi_maps_bullet_and_dashes() {
            let m = win_ansi();
            assert_eq!(m.get(&0x95), Some(&'\u{2022}'));
            assert_eq!(m.get(&0x96), Some(&'\u{2013}'));
            assert_eq!(m.get(&0x97), Some(&'\u{2014}'));
            assert_eq!(m.get(&0x41), Some(&'A'));
        }

        #[test]
        fn glyph_names_resolve() {
            assert_eq!(glyph_to_char("bullet"), Some('\u{2022}'));
            assert_eq!(glyph_to_char("uni20AC"), Some('\u{20AC}'));
            assert_eq!(glyph_to_char("A"), Some('A'));
            assert_eq!(glyph_to_char("emdash"), Some('\u{2014}'));
            assert_eq!(glyph_to_char("Aacute"), Some('\u{00C1}'));
        }
    }
}

#[cfg(test)]
mod encrypt_tests {
    use super::*;

    fn build_doc_bytes(title: &[u8]) -> Vec<u8> {
        let mut doc = Document::with_version("1.7");
        let info = doc.add_object(dictionary! {
            "Title" => Object::String(title.to_vec(), lopdf::StringFormat::Literal),
        });
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
        });
        doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
            "Type" => "Pages",
            "Kids" => vec![page_id.into()],
            "Count" => 1,
        }));
        let catalog = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", catalog);
        doc.trailer.set("Info", info);
        let mut out = Vec::new();
        doc.save_to(&mut out).unwrap();
        out
    }

    fn roundtrip(algo: crate::EncryptAlgo) {
        let title = b"SecretTitle123";
        let plain = build_doc_bytes(title);
        let pw = b"hunter2";
        let enc = crate::encrypt_doc_bytes(&plain, pw, pw, algo).expect("encrypt");
        // Wrong/empty password should not authenticate.
        let mut doc0 = Document::load_mem(&enc).unwrap();
        assert!(doc0.trailer.get(b"Encrypt").is_ok(), "should be encrypted");
        assert_ne!(crate::decrypt_in_place(&mut doc0, b""), crate::DecryptStatus::Ok);
        // Correct password decrypts and recovers the /Title string.
        let mut doc = Document::load_mem(&enc).unwrap();
        assert_eq!(crate::decrypt_in_place(&mut doc, pw), crate::DecryptStatus::Ok);
        let info_ref = doc.trailer.get(b"Info").unwrap().as_reference().unwrap();
        let info = doc.get_dictionary(info_ref).unwrap();
        let got = info.get(b"Title").unwrap().as_str().unwrap();
        assert_eq!(got, &title[..], "title should round-trip through {:?}", algo as u8);
    }

    #[test]
    fn rc4_save_roundtrip() {
        roundtrip(crate::EncryptAlgo::Rc4_128);
    }

    #[test]
    fn aes128_save_roundtrip() {
        roundtrip(crate::EncryptAlgo::Aes128);
    }

    #[test]
    fn aes256_save_roundtrip() {
        roundtrip(crate::EncryptAlgo::Aes256);
    }
}

#[cfg(test)]
mod type1_tests {
    use super::*;

    #[test]
    fn type1_encoding_scan() {
        let text = b"/Encoding 256 array\n0 1 255 {1 index exch /.notdef put} for\ndup 65 /A put\ndup 97 /a put\ndup 233 /eacute put\nreadonly def";
        let m = parse_type1_encoding_text(text);
        assert_eq!(m.get(&65), Some(&'A'));
        assert_eq!(m.get(&97), Some(&'a'));
        assert_eq!(m.get(&233), Some(&'\u{00E9}'));
    }
}

// ---------------------------------------------------------------------------
// ToUnicode CMap parsing
// ---------------------------------------------------------------------------

pub(crate) mod cmap {
    use std::collections::HashMap;
use std::io::Cursor;

    enum Token {
        Hex(Vec<u8>),
        ArrayOpen,
        ArrayClose,
        Keyword(String),
    }

    /// Parse a `/ToUnicode` CMap stream into a `code -> string` map, handling
    /// `beginbfchar`/`endbfchar` and `beginbfrange`/`endbfrange`.
    pub fn parse(data: &[u8]) -> HashMap<u32, String> {
        let tokens = tokenize(data);
        let mut map = HashMap::new();
        let mut i = 0;
        while i < tokens.len() {
            match &tokens[i] {
                Token::Keyword(k) if k == "beginbfchar" => {
                    i += 1;
                    while i < tokens.len() {
                        if let Token::Keyword(e) = &tokens[i] {
                            if e == "endbfchar" {
                                break;
                            }
                        }
                        if let (Token::Hex(src), Some(Token::Hex(dst))) =
                            (&tokens[i], tokens.get(i + 1))
                        {
                            map.insert(code(src), utf16be(dst));
                            i += 2;
                        } else {
                            i += 1;
                        }
                    }
                    i += 1; // skip endbfchar
                }
                Token::Keyword(k) if k == "beginbfrange" => {
                    i += 1;
                    while i < tokens.len() {
                        if let Token::Keyword(e) = &tokens[i] {
                            if e == "endbfrange" {
                                break;
                            }
                        }
                        match (tokens.get(i), tokens.get(i + 1), tokens.get(i + 2)) {
                            (Some(Token::Hex(lo)), Some(Token::Hex(hi)), Some(Token::Hex(dst))) => {
                                let (lo, hi) = (code(lo), code(hi));
                                let base = utf16be_units(dst);
                                for (n, c) in (lo..=hi).enumerate() {
                                    map.insert(c, units_to_string_incremented(&base, n as u32));
                                }
                                i += 3;
                            }
                            (Some(Token::Hex(lo)), Some(Token::Hex(_hi)), Some(Token::ArrayOpen)) => {
                                let lo = code(lo);
                                i += 3; // skip lo, hi, '['
                                let mut n = 0u32;
                                while i < tokens.len() {
                                    match &tokens[i] {
                                        Token::ArrayClose => {
                                            i += 1;
                                            break;
                                        }
                                        Token::Hex(dst) => {
                                            map.insert(lo + n, utf16be(dst));
                                            n += 1;
                                            i += 1;
                                        }
                                        _ => i += 1,
                                    }
                                }
                            }
                            _ => i += 1,
                        }
                    }
                    i += 1; // skip endbfrange
                }
                _ => i += 1,
            }
        }
        map
    }

    fn tokenize(data: &[u8]) -> Vec<Token> {
        let mut tokens = Vec::new();
        let mut i = 0;
        while i < data.len() {
            let b = data[i];
            match b {
                b'<' => {
                    let mut hex = String::new();
                    i += 1;
                    while i < data.len() && data[i] != b'>' {
                        if !data[i].is_ascii_whitespace() {
                            hex.push(data[i] as char);
                        }
                        i += 1;
                    }
                    i += 1; // consume '>'
                    tokens.push(Token::Hex(hex_to_bytes(&hex)));
                }
                b'[' => {
                    tokens.push(Token::ArrayOpen);
                    i += 1;
                }
                b']' => {
                    tokens.push(Token::ArrayClose);
                    i += 1;
                }
                _ if b.is_ascii_alphabetic() => {
                    let mut kw = String::new();
                    while i < data.len()
                        && (data[i].is_ascii_alphanumeric() || data[i] == b'*')
                    {
                        kw.push(data[i] as char);
                        i += 1;
                    }
                    tokens.push(Token::Keyword(kw));
                }
                _ => i += 1,
            }
        }
        tokens
    }

    fn hex_to_bytes(hex: &str) -> Vec<u8> {
        let mut h = hex.to_string();
        if h.len() % 2 == 1 {
            h.push('0');
        }
        (0..h.len())
            .step_by(2)
            .filter_map(|i| u8::from_str_radix(&h[i..i + 2], 16).ok())
            .collect()
    }

    fn code(bytes: &[u8]) -> u32 {
        let mut c = 0u32;
        for &b in bytes {
            c = (c << 8) | b as u32;
        }
        c
    }

    fn utf16be_units(bytes: &[u8]) -> Vec<u16> {
        bytes
            .chunks(2)
            .map(|c| {
                let hi = c[0] as u16;
                let lo = *c.get(1).unwrap_or(&0) as u16;
                (hi << 8) | lo
            })
            .collect()
    }

    fn utf16be(bytes: &[u8]) -> String {
        String::from_utf16_lossy(&utf16be_units(bytes))
    }

    /// Increment the last UTF-16 code unit by `n` (per PDF bfrange semantics)
    /// and decode the result.
    fn units_to_string_incremented(units: &[u16], n: u32) -> String {
        let mut u = units.to_vec();
        if let Some(last) = u.last_mut() {
            *last = last.wrapping_add(n as u16);
        }
        String::from_utf16_lossy(&u)
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn parses_bfchar_single_byte() {
            let cmap = b"2 beginbfchar\n<41> <0041>\n<42> <0042>\nendbfchar";
            let map = parse(cmap);
            assert_eq!(map.get(&0x41).map(String::as_str), Some("A"));
            assert_eq!(map.get(&0x42).map(String::as_str), Some("B"));
        }

        #[test]
        fn parses_bfchar_two_byte() {
            let cmap = b"1 beginbfchar\n<0003> <0048>\nendbfchar";
            let map = parse(cmap);
            assert_eq!(map.get(&0x0003).map(String::as_str), Some("H"));
        }

        #[test]
        fn parses_bfrange_incrementing() {
            let cmap = b"1 beginbfrange\n<0041> <0043> <0061>\nendbfrange";
            let map = parse(cmap);
            assert_eq!(map.get(&0x41).map(String::as_str), Some("a"));
            assert_eq!(map.get(&0x42).map(String::as_str), Some("b"));
            assert_eq!(map.get(&0x43).map(String::as_str), Some("c"));
        }

        #[test]
        fn parses_bfrange_after_preamble() {
            // A realistic ToUnicode with a dict/codespace preamble before the
            // bfrange block (regression for a token double-increment bug).
            let cmap = b"/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n<< /Registry (TTX+0) /Ordering (T1) /Supplement 0 >> def\n1 begincodespacerange\n<0000><FFFF>\nendcodespacerange\n2 beginbfrange\n<0033><0033><0050>\n<0055><0055><0072>\nendbfrange\nendcmap";
            let map = parse(cmap);
            assert_eq!(map.get(&0x33).map(String::as_str), Some("P"));
            assert_eq!(map.get(&0x55).map(String::as_str), Some("r"));
        }
    }
}

// ---------------------------------------------------------------------------
// Wire serialization
// ---------------------------------------------------------------------------
