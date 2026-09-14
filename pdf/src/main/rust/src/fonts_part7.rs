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

    #[test]
    fn rksj_codespace_segments_mixed_width_codes() {
        // 90ms-RKSJ-H interleaves 1-byte and 2-byte codes. Decoding as fixed
        // 2-byte codes would pair 'A' with the kanji lead byte and desynchronize
        // the rest of the string.
        let cs = cmap::predefined_codespace("90ms-RKSJ-H").expect("RKSJ recognized");
        let cm = cmap::EncodingCMap { codespace: cs, ..Default::default() };
        assert_eq!(cm.code_len(b'A'), 1, "ASCII is single-byte");
        assert_eq!(cm.code_len(0x82), 2, "kanji lead byte is double-byte");
        assert_eq!(cm.code_len(0xB0), 1, "half-width katakana is single-byte");
        assert_eq!(cm.code_len(0xE0), 2, "second kanji lead range is double-byte");
    }

    #[test]
    fn ucs2_cmaps_are_not_given_a_codespace() {
        // Pure 2-byte families already decode correctly via the Identity path.
        assert!(cmap::predefined_codespace("UniJIS-UCS2-H").is_none());
        assert!(cmap::predefined_codespace("UniGB-UCS2-H").is_none());
        assert!(cmap::predefined_codespace("UniKS-UCS2-H").is_none());
        assert!(cmap::predefined_codespace("Identity-H").is_none());
        assert!(cmap::predefined_codespace("Identity-V").is_none());
        // The ISO-2022 families are <2121>-<7E7E>, i.e. pure 2-byte.
        assert!(cmap::predefined_codespace("Add-H").is_none());
        assert!(cmap::predefined_codespace("Ext-V").is_none());
    }

    #[test]
    fn mixed_width_cmap_families_are_recognized() {
        // Each of these has 1-byte ranges alongside its 2-byte ranges, so a fixed
        // 2-byte decode desynchronizes the byte stream (PDF 9.7.6.2).
        let len = |name: &str, b: u8| {
            let cs = cmap::predefined_codespace(name).unwrap_or_else(|| panic!("{name} recognized"));
            cmap::EncodingCMap { codespace: cs, ..Default::default() }.code_len(b)
        };
        // Big5, GBK, EUC-CN, UHC, EUC-KR: ASCII single-byte, lead byte double.
        for (name, lead) in [
            ("ETen-B5-H", 0xA1u8),
            ("B5pc-H", 0xA1),
            ("GBK-EUC-H", 0x81),
            ("GB-EUC-H", 0xA1),
            ("GBpc-EUC-V", 0xA1),
            ("KSCms-UHC-H", 0x81),
            ("KSC-EUC-H", 0x81),
            ("KSCpc-EUC-H", 0x81),
        ] {
            assert_eq!(len(name, b'A'), 1, "{name}: ASCII must be single-byte");
            assert_eq!(len(name, lead), 2, "{name}: lead byte must be double-byte");
        }
        // Japanese EUC: 1-byte ASCII, 2-byte for both the 0x8E single-shift form
        // and the standard 0xA1.. plane.
        assert_eq!(len("EUC-H", b'A'), 1);
        assert_eq!(len("EUC-H", 0x8E), 2);
        assert_eq!(len("EUC-H", 0xA1), 2);
    }

    #[test]
    fn utf8_and_utf16_cmaps_segment_by_lead_byte() {
        // UniJIS-UTF8-H etc. are 1-4 bytes; a fixed 2-byte read desynchronizes on
        // the first ASCII character.
        let cs = cmap::predefined_codespace("UniJIS-UTF8-H").expect("UTF8 recognized");
        let cm = cmap::EncodingCMap { codespace: cs, ..Default::default() };
        assert_eq!(cm.code_len(b'A'), 1);
        assert_eq!(cm.code_len(0xC3), 2);
        assert_eq!(cm.code_len(0xE3), 3);
        assert_eq!(cm.code_len(0xF0), 4);
        // UTF-16 is 2 bytes except surrogate pairs, which are 4.
        let cs = cmap::predefined_codespace("UniGB-UTF16-H").expect("UTF16 recognized");
        let cm = cmap::EncodingCMap { codespace: cs, ..Default::default() };
        assert_eq!(cm.code_len(0x00), 2);
        assert_eq!(cm.code_len(0xD8), 4, "high surrogate starts a 4-byte code");
        assert_eq!(cm.code_len(0xE0), 2);
    }

    #[test]
    fn gb18030_four_byte_plane_needs_the_second_byte() {
        // GBK2K-H is GB18030: <81 30 81 30>-<FE 39 FE 39> is a FOUR-byte code, and it is
        // distinguished from the two-byte plane only by the second byte being 0x30-0x39.
        // Reading it as two 2-byte codes desynchronizes the rest of the string.
        let cs = cmap::predefined_codespace("GBK2K-H").expect("GBK2K recognized");
        let cm = cmap::EncodingCMap { codespace: cs, ..Default::default() };
        assert_eq!(cm.code_len_at(&[0x81, 0x30, 0x81, 0x30]), 4);
        assert_eq!(cm.code_len_at(&[0x81, 0x40]), 2, "the two-byte plane is untouched");
        assert_eq!(cm.code_len_at(&[0x41]), 1, "ASCII is still single-byte");
        // The lookahead is a strict refinement: every other predefined codespace answers
        // exactly what the first-byte-only `code_len` answers.
        for name in ["GBK-EUC-H", "90ms-RKSJ-H", "UniJIS-UTF8-H", "UniGB-UTF16-H", "ETen-B5-H"] {
            let cs = cmap::predefined_codespace(name).unwrap();
            let cm = cmap::EncodingCMap { codespace: cs, ..Default::default() };
            for b1 in 0u16..=255 {
                for b2 in [0x00u8, 0x30, 0x39, 0x40, 0x80, 0xA0, 0xFE, 0xFF] {
                    assert_eq!(
                        cm.code_len_at(&[b1 as u8, b2]),
                        cm.code_len(b1 as u8),
                        "{name} changed segmentation at {b1:#04x} {b2:#04x}"
                    );
                }
            }
        }
    }

    #[test]
    fn for_each_code_resegments_a_mixed_width_string() {
        // End-to-end: the whole point of the codespace is that a 1-byte code in
        // the middle of a CJK string does not shift every following code by one
        // byte. "A" + 2-byte kanji + "B" must yield exactly three codes.
        let cs = cmap::predefined_codespace("90ms-RKSJ-H").unwrap();
        let fi = FontInfo {
            two_byte: true,
            cmap: Some(Arc::new(cmap::EncodingCMap { codespace: cs, ..Default::default() })),
            ..simple_font_with_encoding()
        };
        let mut got = Vec::new();
        fi.for_each_code(&[0x41, 0x82, 0xA0, 0x42], |c, sp| got.push((c, sp)));
        assert_eq!(got, vec![(0x41, false), (0x82A0, false), (0x42, false)]);
        // A single-byte code 32 still reports as a word-spacing space, and a
        // 2-byte code whose value happens to be 0x20 does not (PDF 9.3.3).
        let mut spaces = Vec::new();
        fi.for_each_code(&[0x20, 0x82, 0x20], |c, sp| spaces.push((c, sp)));
        assert_eq!(spaces, vec![(0x20, true), (0x8220, false)]);
    }

    #[test]
    fn w_range_form_cannot_allocate_unbounded_widths() {
        // A hostile `cLast` must not drive a multi-billion-iteration insert loop.
        let mut doc = Document::new();
        let desc = doc.add_object(lopdf::dictionary! {
            "Type" => "Font",
            "Subtype" => "CIDFontType2",
            "W" => vec![0.into(), 4_000_000_000u32.into(), 500.into()],
        });
        let font = lopdf::dictionary! {
            "Type" => "Font",
            "Subtype" => "Type0",
            "DescendantFonts" => vec![desc.into()],
        };
        let (widths, _) = cid_widths(&doc, &font);
        assert!(widths.len() <= MAX_CID as usize + 1);
        assert_eq!(widths.get(&0), Some(&0.5));
        assert_eq!(widths.get(&MAX_CID), Some(&0.5));
    }

    #[test]
    fn bfrange_cannot_allocate_unbounded_strings() {
        // A 4-byte `hi` in a bfrange must be clamped, not expanded to 4 billion
        // entries.
        let map = cmap::parse(b"1 beginbfrange\n<00000000> <FFFFFFFF> <0041>\nendbfrange");
        assert!(map.len() <= MAX_CID as usize + 1);
        assert_eq!(map.get(&0).map(String::as_str), Some("A"));
    }

    #[test]
    fn tw_applies_only_to_single_byte_code_32() {
        // PDF 9.3.3: Tw applies to code 32 and nothing else -- notably not NBSP,
        // which must not stretch under justification.
        let fi = simple_font_with_encoding();
        let mut seen = Vec::new();
        fi.for_each_code(&[32, 0xA0, b'A'], |code, is_space| seen.push((code, is_space)));
        assert_eq!(seen, vec![(32, true), (0xA0, false), (65, false)]);
    }

    #[test]
    fn font_cache_shares_parsed_fonts_only_inside_a_scope() {
        // The cache exists because `fonts_from_resources` runs per page and
        // re-parses each font's embedded program every time. Assert on Arc
        // identity rather than on equality: equal contents would also hold if the
        // font had been re-parsed, which is exactly the bug being fixed.
        let mut doc = Document::with_version("1.7");
        let tu = doc.add_object(Stream::new(
            dictionary! {},
            b"1 beginbfchar\n<41> <0041>\nendbfchar".to_vec(),
        ));
        let font = doc.add_object(dictionary! {
            "Type" => "Font",
            "Subtype" => "TrueType",
            "BaseFont" => "Helvetica",
            "ToUnicode" => tu,
        });
        let res = dictionary! { "Font" => dictionary! { "F1" => font } };

        // No scope active: the default must stay uncached, so nothing can ever go
        // stale in a mutated document.
        let a = fonts_from_resources(&doc, &res);
        let b = fonts_from_resources(&doc, &res);
        let (au, bu) = (
            a[b"F1".as_ref()].to_unicode.as_ref().unwrap(),
            b[b"F1".as_ref()].to_unicode.as_ref().unwrap(),
        );
        assert_eq!(au.get(&0x41).map(String::as_str), Some("A"), "parse still works");
        assert!(!Arc::ptr_eq(au, bu), "must not cache without an active scope");

        // Inside a scope the second lookup reuses the first parse.
        let _scope = FontCacheScope::new();
        let c = fonts_from_resources(&doc, &res);
        let d = fonts_from_resources(&doc, &res);
        assert!(
            Arc::ptr_eq(
                c[b"F1".as_ref()].to_unicode.as_ref().unwrap(),
                d[b"F1".as_ref()].to_unicode.as_ref().unwrap()
            ),
            "font should be parsed once per scope"
        );
    }

    // The font cache is keyed by the font's OBJECT ID, not by its resource name. Two
    // pages may each bind /F1 to a different font object, so a name-keyed cache would
    // render page 2's text in page 1's font. This is the cross-page leak the interaction
    // review was looking for; it asserts the key, since a name-keyed regression here
    // would be silent.
    #[test]
    fn font_cache_does_not_confuse_two_fonts_that_share_a_resource_name() {
        let mut doc = Document::with_version("1.7");
        let mut font_named_f1 = |ch: u8, uni: &str| {
            let tu = doc.add_object(Stream::new(
                dictionary! {},
                format!("1 beginbfchar\n<{ch:02X}> <{uni}>\nendbfchar").into_bytes(),
            ));
            let font = doc.add_object(dictionary! {
                "Type" => "Font",
                "Subtype" => "TrueType",
                "BaseFont" => "Helvetica",
                "ToUnicode" => tu,
            });
            dictionary! { "Font" => dictionary! { "F1" => font } }
        };
        let res_a = font_named_f1(0x41, "0041");
        let res_b = font_named_f1(0x41, "0042");
        let _scope = FontCacheScope::new();
        let a = fonts_from_resources(&doc, &res_a);
        let b = fonts_from_resources(&doc, &res_b);
        let (au, bu) = (
            a[b"F1".as_ref()].to_unicode.as_ref().unwrap(),
            b[b"F1".as_ref()].to_unicode.as_ref().unwrap(),
        );
        assert_eq!(au.get(&0x41).map(String::as_str), Some("A"));
        assert_eq!(
            bu.get(&0x41).map(String::as_str),
            Some("B"),
            "the second /F1 is a different font object and must not be served from the cache"
        );
        assert!(!Arc::ptr_eq(au, bu));
    }

    // A colliding object id from a DIFFERENT document must not be served. The cache used
    // to key on the `Document`'s raw ADDRESS, which is not an identity: overwriting a
    // `Box<Document>` in place puts the replacement at the same address, and a scope
    // spanning both then rendered the second document with the first one's font metrics.
    // A hit now also has to match the dictionary the id resolves to.
    #[test]
    fn font_cache_rejects_a_colliding_id_from_another_document() {
        let build = |base: &str| {
            let mut doc = Document::with_version("1.7");
            // Burn object 1 so the font lands on the same id in both documents.
            doc.add_object(Object::Null);
            let font = doc.add_object(dictionary! {
                "Type" => "Font",
                "Subtype" => "Type1",
                "BaseFont" => base,
            });
            let res = dictionary! { "Font" => dictionary! { "F1" => font } };
            (doc, res, font)
        };
        let (doc_a, res_a, id_a) = build("Helvetica");
        let (doc_b, res_b, id_b) = build("Courier");
        assert_eq!(id_a, id_b, "precondition: the two fonts share an object id");

        let _scope = FontCacheScope::new();
        let a = fonts_from_resources(&doc_a, &res_a);
        let b = fonts_from_resources(&doc_b, &res_b);
        // Courier is monospaced, Helvetica is not: a stale hit shows up as `i` and `M`
        // having different widths, which is what the address-keyed cache produced.
        let wi = b[b"F1".as_ref()].widths.get(&(b'i' as u32)).copied();
        let wm = b[b"F1".as_ref()].widths.get(&(b'M' as u32)).copied();
        assert_eq!(wi, wm, "the second document must get Courier's uniform advance");
        assert_ne!(
            a[b"F1".as_ref()].widths.get(&(b'i' as u32)).copied(),
            a[b"F1".as_ref()].widths.get(&(b'M' as u32)).copied(),
            "precondition: Helvetica is proportional, so the two are distinguishable"
        );
    }

    fn simple_font_with_encoding() -> FontInfo {
        FontInfo {
            two_byte: false,
            wmode: 0,
            vertical_metrics: Arc::default(),
            default_vertical: (0.880, -1.0),
            cid_to_gid: None,
            to_unicode: None,
            encoding: Arc::new(encoding::win_ansi()),
            cmap_uni: Arc::default(),
            cmap: None,
            widths: Arc::default(),
            default_width: 0.5,
            t3: None,
            style: FontStyle::default(),
            family: 0,
            base_font: String::new(),
            glyph_program: None,
            glyph_names: Arc::default(),
        }
    }
}

// ---------------------------------------------------------------------------
// ToUnicode CMap parsing
// ---------------------------------------------------------------------------
