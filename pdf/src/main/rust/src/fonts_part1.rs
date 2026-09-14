pub(crate) fn font_info(doc: &Document, font: &lopdf::Dictionary) -> FontInfo {
    // Every one of these may be an indirect reference. An unresolved `/Subtype`
    // silently makes a Type0 font parse as a simple one — 2-byte codes read as
    // single bytes, so the whole string decodes to garbage.
    let subtype = font.get(b"Subtype").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_name().ok());
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

    // Type0 /Encoding CMap: Identity-H/V need no code->CID map (code == CID); a
    // named predefined CMap can't be embedded here (best-effort identity), but an
    // embedded CMap stream is parsed for real code->CID mapping and WMode.
    let mut encoding_cmap: Option<cmap::EncodingCMap> = None;
    let mut cmap_wmode: u8 = 0;
    if two_byte {
        match font.get(b"Encoding").ok().and_then(|o| deref(doc, o)) {
            Some(Object::Name(n)) => {
                let name = String::from_utf8_lossy(n);
                if name.ends_with("-V") { cmap_wmode = 1; }
                // A predefined CMap cannot be embedded, so its code->CID table is
                // unavailable and CID lookups stay identity. For the mixed-width
                // families we can still install the codespace ranges, which is what
                // determines how many bytes each code consumes -- without them a
                // 1-byte ASCII code inside a Shift-JIS/GBK/Big5/UHC string is read
                // as half of a 2-byte code and the whole rest of the string
                // desynchronizes. Identity-H/V and the Uni*-UCS2-* families are
                // pure 2-byte and need no CMap at all.
                if let Some(codespace) = cmap::predefined_codespace(&name) {
                    encoding_cmap = Some(cmap::EncodingCMap {
                        codespace,
                        wmode: cmap_wmode,
                        ..Default::default()
                    });
                }
            }
            Some(Object::Stream(s)) => {
                let cm = cmap::parse_encoding_cmap(&stream_data(s));
                cmap_wmode = cm.wmode;
                encoding_cmap = Some(cm);
            }
            _ => {}
        }
    }

    // WMode: 0 horizontal (default), 1 vertical. Detect from Type0 font dict and descendant.
    let wmode: u8 = font.get(b"WMode").ok().and_then(|o| deref(doc, o)).and_then(num).map(|v| if v >= 1.0 { 1 } else { 0 }).unwrap_or(0) as u8;
    let desc_wmode: u8 = font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o)).and_then(|o| match o { Object::Array(a) => a.first(), _ => None }).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()).and_then(|d| d.get(b"WMode").ok()).and_then(|o| deref(doc, o)).and_then(num).map(|v| if v >= 1.0 { 1 } else { 0 }).unwrap_or(wmode);
    let effective_wmode = desc_wmode.max(wmode).max(cmap_wmode);

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
                        // Zeros are deliberately NOT inserted. Do not "fix" this:
                        // outlines.rs treats an explicit map as authoritative and
                        // returns .notdef for a miss, so an absent entry and a
                        // present 0 already mean the same thing (PDF 9.7.4.2). If
                        // zeros were inserted here while that lookup fell back to
                        // identity, a CID mapped to .notdef would instead select the
                        // glyph at index == cid; if they were inserted and the
                        // lookup drew GID 0, every such glyph would render as a
                        // visible hollow .notdef box.
                        if gid != 0 {
                            map.insert(i as u32, gid);
                        }
                    }
                    // A zero-length or undecodable stream must not yield an empty
                    // map: `Some` means "an explicit mapping exists", and callers
                    // treat it as authoritative. Returning `Some(empty)` would send
                    // every CID of the font to .notdef.
                    if map.is_empty() { None } else { Some(map) }
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
            // §9.6.5 requires a Type 3 `/Encoding` to be a dictionary whose
            // `/Differences` gives the COMPLETE encoding, so a name `/Encoding` (or
            // a dictionary carrying only `/BaseEncoding`) is non-conformant. But the
            // failure was total and silent: no code mapped to any CharProc, so the
            // whole text block painted nothing while the pen still advanced — layout
            // looked right and only the ink was missing. Recover through the simple
            // font's own §9.6.6.2 order, base encoding then StandardEncoding, which
            // can only ever match a CharProc that is already named for a standard
            // glyph. Gated on an empty map so a real `/Differences` stays
            // authoritative and a partial one is not silently padded.
            if char_procs.is_empty() {
                let mut names = named_base_encoding_names(doc, font);
                if names.is_empty() {
                    for (code, name) in crate::type1::STANDARD_ENCODING {
                        names.insert(*code as u32, (*name).to_string());
                    }
                }
                for (code, name) in names {
                    if let Some(id) = info.char_procs.get(name.as_bytes()) {
                        char_procs.insert(code, *id);
                    }
                }
            }
            Type3Font { font_matrix: info.font_matrix, char_procs, resources: info.resources }
        })
    } else {
        None
    };

    let (mut widths, default_width) = if two_byte {
        cid_widths(doc, font)
    } else if is_type3 {
        let fm_scale = t3.as_ref().map(|t| t.font_matrix[0]).unwrap_or(0.001);
        type3_widths(doc, font, fm_scale)
    } else {
        simple_widths(doc, font)
    };

    // Vertical metrics /W2 + /DW2 for WMode 1 (PDF 9.7.4.3), consumed by the
    // vertical branch of `show_string`.
    let vert_desc = font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o)).and_then(|o| match o { Object::Array(a) => a.first(), _ => None }).and_then(|o| deref(doc, o)).and_then(|o| o.as_dict().ok()).cloned();
    let (vert_map, default_vert): (HashMap<u32, (f64, f64)>, (f64, f64)) = {
        let mut vm = HashMap::new();
        // /DW2 is [v_y, w1_y]; the spec default is [880 -1000].
        let mut dw2 = (0.880, -1.0);
        if let Some(ref df) = vert_desc {
            if let Some(Object::Array(arr)) = df.get(b"DW2").ok().and_then(|o| deref(doc, o)) {
                let v: Vec<f64> = arr.iter().filter_map(|o| deref(doc, o).and_then(num).or_else(|| num(o))).collect();
                if v.len() >= 2 {
                    dw2 = (v[0] / 1000.0, v[1] / 1000.0);
                }
            }
            // /W2 has two forms:
            //   c [w1y v1x v1y  w1y v1x v1y ...]   consecutive CIDs from c
            //   cFirst cLast w1y v1x v1y           one entry for the whole range
            // Per-CID v_y is not retained (the map holds `(w1_y, v_x)` and v_y
            // comes from /DW2); a CID-specific v_y is vanishingly rare and the
            // struct shape is shared with fixtures outside this module.
            if let Some(Object::Array(w2)) = df.get(b"W2").ok().and_then(|o| deref(doc, o)) {
                let mut i = 0;
                while i < w2.len() {
                    let c0 = match w2.get(i).and_then(|o| deref(doc, o)).and_then(num) {
                        Some(v) => v.max(0.0) as u32,
                        None => break,
                    };
                    match w2.get(i + 1).and_then(|o| deref(doc, o)) {
                        Some(Object::Array(list)) => {
                            let vals: Vec<f64> =
                                list.iter().filter_map(|o| deref(doc, o).and_then(num)).collect();
                            for (j, t) in vals.chunks(3).enumerate() {
                                if t.len() < 3 { break; }
                                // Same file-controlled overflow as /W's array form.
                                vm.insert(
                                    c0.saturating_add(j as u32).min(MAX_CID),
                                    (t[0] / 1000.0, t[1] / 1000.0),
                                );
                            }
                            i += 2;
                        }
                        _ => {
                            let c1 = w2.get(i + 1).and_then(|o| deref(doc, o)).and_then(num);
                            let w1y = w2.get(i + 2).and_then(|o| deref(doc, o)).and_then(num);
                            let v1x = w2.get(i + 3).and_then(|o| deref(doc, o)).and_then(num);
                            if let (Some(c1), Some(w1y), Some(v1x)) = (c1, w1y, v1x) {
                                let c1 = (c1.max(0.0) as u32).min(MAX_CID);
                                for cid in c0..=c1 {
                                    vm.insert(cid, (w1y / 1000.0, v1x / 1000.0));
                                }
                            }
                            i += 5;
                        }
                    }
                }
            }
        }
        (vm, dw2)
    };

    let (encoding, builtin_first) = if two_byte {
        (HashMap::new(), false)
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

    // PDF 9.6.6.1 resolution order for a symbolic font with no /Encoding and no
    // /BaseEncoding: the font program's BUILT-IN encoding outranks any implicit
    // base encoding. `encoding::build` left the base empty in that case, so
    // `encoding` currently holds only /Differences (which always wins) and the
    // built-in map in `cmap_uni` is consulted next by `push_code`. WinAnsi is
    // backfilled only for codes neither covers, so a font whose symbolic flag is
    // set spuriously and whose program yields no built-in encoding still decodes.
    let mut encoding = encoding;
    if builtin_first {
        for (code, ch) in encoding::win_ansi() {
            if !encoding.contains_key(&code) && !cmap_uni.contains_key(&code) {
                encoding.insert(code, ch);
            }
        }
    }

    // --- Font style detection for bold/italic synthesis ---
    let base_font_name = font.get(b"BaseFont").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_name().ok())
        .map(|n| String::from_utf8_lossy(n).to_string())
        .or_else(|| {
            // Try descendant for Type0
            font.get(b"DescendantFonts").ok().and_then(|o| deref(doc, o))
                .and_then(|o| match o { Object::Array(a) => a.first(), _ => None })
                .and_then(|o| deref(doc, o))
                .and_then(|o| o.as_dict().ok())
                .and_then(|d| d.get(b"BaseFont").ok())
                .and_then(|o| deref(doc, o))
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
        if let Some(flags) = desc.get(b"Flags").ok().and_then(|o| deref(doc, o)).and_then(num) {
            let f = flags as i64;
            // Bit 18 (1<<18 = 262144) = Italic per PDF spec 9.8.2
            if f & (1<<18) != 0 || f & 64 != 0 { italic = true; } // 64 is common non-spec but some generators
            // There is no bold flag but some files use bit 6? Actually force bold is 18? We'll rely on StemV/Weight
        }
        if let Some(angle) = desc.get(b"ItalicAngle").ok().and_then(|o| deref(doc, o)).and_then(num) {
            if angle.abs() > 0.5 { italic = true; }
        }
        if let Some(weight) = desc.get(b"FontWeight").ok().and_then(|o| deref(doc, o)).and_then(num) {
            if weight >= 600.0 { bold = true; }
        } else if let Some(name) = desc.get(b"FontWeight").ok().and_then(|o| deref(doc, o)).and_then(|o| o.as_name().ok()) {
            if String::from_utf8_lossy(name).to_lowercase().contains("bold") { bold = true; }
        }
        if let Some(stemv) = desc.get(b"StemV").ok().and_then(|o| deref(doc, o)).and_then(num) {
            if stemv.abs() > 140.0 { bold = true; }
        }
        if desc.get(b"FontName").ok().and_then(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).to_lowercase().contains("bold")).unwrap_or(false) { bold = true; }
    }

    // --- Embedded glyph outline program (real font rendering) ---
    // Type 3 fonts draw via CharProc streams, not outline programs.
    let glyph_program = if is_type3 {
        None
    } else {
        crate::outlines::build_glyph_program(doc, fd.as_ref())
    };
    let glyph_names = if two_byte || is_type3 {
        HashMap::new()
    } else {
        // A Type 1 or bare-CFF program selects glyphs BY NAME, so a named base
        // encoding has to be resolved to names here or 9.6.6.2's priority is
        // silently inverted and the program's built-in encoding wins: a font
        // whose built-in encoding is Standard, used with /WinAnsiEncoding, then
        // draws Oslash for eacute, ae for ntilde and germandbls for ucircumflex
        // — wrong glyphs rather than missing ones.
        //
        // Sfnt programs are excluded on purpose. 9.6.6.4 routes those through the
        // cmap and treats `post`-table names as a fallback, so seeding a name for
        // every code would put a subset font's often-garbage `post` table ahead
        // of its cmap.
        let mut m = match glyph_program.as_ref() {
            Some(crate::outlines::GlyphProgram::Type1(_))
            | Some(crate::outlines::GlyphProgram::Cff { .. }) => named_base_encoding_names(doc, font),
            _ => HashMap::new(),
        };
        m.extend(crate::outlines::encoding_differences(doc, font));
        m
    };

    // --- Standard-14 metrics fallback ---
    // A non-embedded standard font (Helvetica/Times/Courier/Symbol/ZapfDingbats)
    // may omit /Widths; use the Core-14 AFM widths (keyed by glyph name) so text
    // is spaced correctly instead of at a flat 0.5 em. Resolve code -> glyph name
    // via /Differences, falling back to StandardEncoding.
    if !two_byte && !is_type3 && widths.is_empty() {
        if let Some(afm) = crate::afm::standard_14_widths(&base_font_name) {
            // AFM metrics are keyed by GLYPH NAME, so the code -> name step has to
            // follow the font's real encoding (9.6.6.2), not StandardEncoding
            // unconditionally: for /WinAnsiEncoding, Standard names code 233
            // "Oslash" (611 units) where WinAnsi names it "eacute" (556), so every
            // accented character came out with another glyph's advance.
            let base_names = named_base_encoding_names(doc, font);
            // Symbol and ZapfDingbats have their own built-in encodings, whose AFM
            // names ("alpha", "a12") are in neither table. Their metrics are still
            // reachable by resolving the AFM name to Unicode and matching the
            // encoding's code -> Unicode map. Built in sorted-name order so a
            // Unicode collision resolves deterministically.
            //
            // Deterministic is NOT the same as correct, and the difference is worth
            // knowing before trusting a width that came from here. This map is keyed
            // by Unicode, so when several AFM glyphs share one character the code's
            // OWN glyph is not necessarily the one that wins — the alphabetically
            // first name does. Symbol's bracket build-up pieces are the live case:
            // if the encoding maps `parenlefttp` to '(' then that code inherits
            // `parenleft`'s advance. It stays a strictly better estimate than the
            // invented default this path exists to avoid, but a code whose true
            // advance is known should reach the exact-name lookup above instead of
            // arriving here.
            let by_char: HashMap<char, f64> = {
                let mut names: Vec<&String> = afm.keys().collect();
                names.sort();
                let mut m = HashMap::new();
                for n in names {
                    if let Some(c) = encoding::glyph_to_char(n) {
                        m.entry(c).or_insert(afm[n]);
                    }
                }
                m
            };
            for code in 0u32..=255 {
                let name = glyph_names
                    .get(&code)
                    .cloned()
                    .or_else(|| base_names.get(&code).cloned())
                    .or_else(|| {
                        // Last resort, and it runs for EVERY face — including Symbol
                        // and ZapfDingbats, whose built-in encodings have nothing to
                        // do with StandardEncoding. That makes this a cross-file
                        // invariant with afm.rs, not a local choice: a Standard name
                        // that an AFM table also defines AT A DIFFERENT CODE would be
                        // charged here, silently, in preference to the encoding-derived
                        // match below. r5-fontprog enumerated the collision set for
                        // Symbol in R5 and it is EMPTY — Standard and Symbol share only
                        // fraction, florin, bullet and ellipsis, at the same code in
                        // both — so the guess is safe today. It is afm.rs's table
                        // contents that keep it safe, so anyone ADDING rows there has
                        // to re-check against `STANDARD_ENCODING`'s 0xA0-0xFF names,
                        // and anyone reordering this chain has to re-check the reverse.
                        crate::type1::STANDARD_ENCODING
                            .iter()
                            .find(|(c, _)| *c as u32 == code)
                            .map(|(_, n)| (*n).to_string())
                    });
                if let Some(w) = name.as_deref().and_then(|n| afm.get(n)) {
                    widths.insert(code, *w);
                    continue;
                }
                // ZapfDingbats is the one face this cannot reach: its `aNNN` AFM
                // names carry no Unicode, so its codes stay on `default_width`.
                // Left as a known gap rather than approximated, because a wrong
                // advance for every dingbat is worse than one uniform one.
                if let Some(w) = encoding.get(&code).and_then(|c| by_char.get(c)) {
                    widths.insert(code, *w);
                }
            }
        }
    }

    // --- Generic family detection for substitute shaping (0 sans, 1 serif, 2 mono) ---
    // The embedded base font is not rendered directly; Kotlin picks a matching
    // system typeface, so we only need the broad family. BaseFont names (including
    // subset prefixes like `BCFRDE+Times-Roman`) give the strongest signal; the
    // FontDescriptor `/Flags` (PDF 9.8.2, Table 121: bit 1 FixedPitch, bit 2 Serif)
    // is authoritative when the name is generic.
    let is_mono_name = lower.contains("courier") || lower.contains("mono") || lower.contains("consol");
    let is_sans_name = lower.contains("arial") || lower.contains("helvetica")
        || lower.contains("verdana") || lower.contains("tahoma") || lower.contains("calibri")
        || lower.contains("segoe") || lower.contains("sans");
    let is_serif_name = !is_sans_name && (lower.contains("times") || lower.contains("georgia")
        || lower.contains("garamond") || lower.contains("minion") || lower.contains("palatino")
        || lower.contains("cambria") || lower.contains("antiqua") || lower.contains("serif")
        || lower.contains("roman"));
    let mut family: u8 = if is_mono_name { 2 } else if is_serif_name { 1 } else { 0 };
    if let Some(ref desc) = fd {
        if let Some(flags) = desc.get(b"Flags").ok().and_then(|o| deref(doc, o)).and_then(num) {
            let f = flags as i64;
            if f & 1 != 0 {
                family = 2; // FixedPitch -> monospace
            } else if f & 2 != 0 && !is_sans_name && !is_mono_name {
                family = 1; // Serif
            }
        }
    }

    FontInfo {
        two_byte,
        wmode: effective_wmode,
        vertical_metrics: Arc::new(vert_map),
        default_vertical: default_vert,
        cid_to_gid: cid_to_gid.map(Arc::new),
        to_unicode: to_unicode.map(Arc::new),
        encoding: Arc::new(encoding),
        cmap_uni: Arc::new(cmap_uni),
        cmap: encoding_cmap.map(Arc::new),
        widths: Arc::new(widths),
        default_width,
        t3,
        style: FontStyle { bold, italic },