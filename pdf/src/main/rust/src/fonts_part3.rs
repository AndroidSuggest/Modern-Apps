/// Minimal TrueType `cmap` parser: recovers a character-code → Unicode map by
/// composing a code→glyph subtable (Mac 1,0 or Symbol 3,0) with the reverse of
/// a Unicode subtable (3,1 / 0,3 / 3,10). All reads are bounds-checked so
/// malformed font data can never panic.
pub(crate) mod ttf {
    use std::collections::HashMap;

    fn u16b(b: &[u8], o: usize) -> u16 {
        ((*b.get(o).unwrap_or(&0) as u16) << 8) | *b.get(o + 1).unwrap_or(&0) as u16
    }
    fn u32b(b: &[u8], o: usize) -> u32 {
        ((u16b(b, o) as u32) << 16) | u16b(b, o + 2) as u32
    }

    /// Group count for the range-based cmap subtable formats, clamped to the
    /// groups that actually fit in `b`. Out-of-bounds reads return 0 rather than
    /// failing, so an unclamped count from corrupt data would otherwise spin for
    /// billions of iterations appending junk entries.
    fn group_count(b: &[u8], count_off: usize, groups_off: usize, group_size: usize) -> usize {
        let declared = u32b(b, count_off) as usize;
        let fits = b.len().saturating_sub(groups_off) / group_size;
        declared.min(fits)
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

    /// Upper bound on the pairs one `cmap` subtable may yield. Formats 8, 12 and
    /// 13 are group lists where each group expands to a code RANGE, so the
    /// existing per-group clamps (group count x 65536 codes each) still multiply
    /// out to billions of entries for a subtable that is only a few KB on disk.
    /// A Unicode-complete cmap needs ~0x110000 pairs, so this cannot truncate a
    /// legitimate font.
    const MAX_CMAP_PAIRS: usize = 0x20_0000;

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
                                // idDelta is modulo-65536 arithmetic (OpenType `cmap`,
                                // format 2/4). A plain `+` panics in debug on a crafted
                                // delta; format 4 below already wraps.
                                (sbyte as i16).wrapping_add(delta) as u16
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
                            ((first_code + low) as i16).wrapping_add(delta) as u16
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
                            c.wrapping_add(delta)
                        } else {
                            let addr = range_o + i * 2 + range as usize + 2 * (c - start) as usize;
                            let g = u16b(b, addr);
                            if g == 0 {
                                0
                            } else {
                                g.wrapping_add(delta)
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
                    if go + 12 > b.len() || out.len() >= MAX_CMAP_PAIRS {
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
                        // `first` is a file-supplied u32: `first + i` panics in debug.
                        out.push((first.saturating_add(i as u32), g));
                    }
                }
            }
            12 => {
                let ngroups = group_count(b, off + 12, off + 16, 12);
                for i in 0..ngroups {
                    if out.len() >= MAX_CMAP_PAIRS {
                        break;
                    }
                    let g = off + 16 + i * 12;
                    let sc = u32b(b, g);
                    let ec = u32b(b, g + 4);
                    let sg = u32b(b, g + 8);
                    if sc > ec || ec - sc > 65535 {
                        continue;
                    }
                    for c in sc..=ec {
                        // `sg` is a file-supplied u32 and startGlyphID is modulo
                        // arithmetic once truncated to a glyph id; a plain `+`
                        // panics in debug near u32::MAX.
                        out.push((c, sg.wrapping_add(c - sc) as u16));
                    }
                }
            }
            13 => {
                // Many-to-one range mappings: every code in a group maps to the
                // same glyph (used for e.g. "last resort" fonts).
                let ngroups = group_count(b, off + 12, off + 16, 12);
                for i in 0..ngroups {
                    if out.len() >= MAX_CMAP_PAIRS {
                        break;
                    }
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

    /// `gid -> unicode` recovered from the `post` table's glyph names via the
    /// Adobe Glyph List. Only used when the font has no Unicode `cmap` subtable.
    /// Parsing is delegated to `ttf-parser` (the `glyph-names` feature) rather
    /// than hand-rolling another untrusted-binary reader.
    fn gid_names_to_unicode(b: &[u8]) -> HashMap<u16, u32> {
        let mut m = HashMap::new();
        let face = match ttf_parser::Face::parse(b, 0) {
            Ok(f) => f,
            Err(_) => return m,
        };
        for gid in 0..face.number_of_glyphs() {
            if let Some(name) = face.glyph_name(ttf_parser::GlyphId(gid)) {
                if let Some(c) = super::encoding::glyph_to_char(name) {
                    m.insert(gid, c as u32);
                }
            }
        }
        m
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
            // A symbolic font may carry only a (3,0) Symbol and/or (1,0)
            // Macintosh subtable and no Unicode subtable at all. Bailing here left
            // the whole map empty, so such a font contributed nothing to selection
            // or search even though its glyph names say exactly what the glyphs
            // are. Recover `gid -> unicode` from the `post` table's glyph names
            // through the Adobe Glyph List: names are an authoritative Unicode
            // source, unlike guessing Unicode from the raw character code, which
            // is what would actually pollute the text index.
            None => gid_names_to_unicode(b),
        };
        if gid_to_uni.is_empty() {
            return result;
        }

        // code -> glyph (from Symbol and/or Mac subtables), then -> unicode.
        // PDF 9.6.6.4: a symbolic TrueType font is looked up through the (3,0)
        // Microsoft Symbol subtable in preference to (1,0) Macintosh, and the
        // presence of a (3,0) table is itself the strongest symbolic signal we
        // have here. `or_insert` makes the first source win, so (3,0) leads.
        for sub in [sym_sub, mac_sub].into_iter().flatten() {
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

        /// A `cmap` comes from an untrusted embedded font program, and the code
        /// arithmetic in formats 2, 10 and 12 is all file-supplied. Debug builds
        /// panic on integer overflow, so an unchecked `+` here is reachable by a
        /// crafted (or merely corrupt) /FontFile2. Format 4 was already hardened
        /// with `wrapping_add`; these three were not.
        #[test]
        fn hostile_cmap_subtables_do_not_overflow() {
            // Format 10 with startCharCode = u32::MAX.
            let mut b = Vec::new();
            b.extend_from_slice(&be16(10));
            b.extend_from_slice(&be16(0));
            b.extend_from_slice(&be32(0));
            b.extend_from_slice(&be32(0));
            b.extend_from_slice(&be32(u32::MAX));
            b.extend_from_slice(&be32(3));
            for g in 1..=3u16 {
                b.extend_from_slice(&be16(g));
            }
            assert!(!parse_subtable(&b, 0).is_empty());

            // Format 12 with startGlyphID = u32::MAX over a multi-code group.
            let mut b = Vec::new();
            b.extend_from_slice(&be16(12));
            b.extend_from_slice(&be16(0));
            b.extend_from_slice(&be32(0));
            b.extend_from_slice(&be32(0));
            b.extend_from_slice(&be32(1));
            b.extend_from_slice(&be32(0x41));
            b.extend_from_slice(&be32(0x43));
            b.extend_from_slice(&be32(u32::MAX));
            assert_eq!(parse_subtable(&b, 0).len(), 3);

            // Format 2 with an idDelta that overflows i16 for the mapped code.
            let mut b = vec![0u8; 6];
            b[0..2].copy_from_slice(&be16(2));
            b.extend_from_slice(&[0u8; 512]); // subHeaderKeys: all single-byte
            b.extend_from_slice(&be16(0));    // firstCode 0
            b.extend_from_slice(&be16(0));    // entryCount
            b.extend_from_slice(&be16(0x7FFF)); // idDelta
            b.extend_from_slice(&be16(0));    // idRangeOffset
            b.extend_from_slice(&[0u8; 8]);   // glyphIndexArray
            let _ = parse_subtable(&b, 0);
        }
    }
}
