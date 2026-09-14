// ---------------------------------------------------------------------------
// Wire serialization
// ---------------------------------------------------------------------------

#[cfg(test)]
mod shows_any_glyph_tests {
    use super::*;

    fn font(two_byte: bool, cmap: Option<Arc<cmap::EncodingCMap>>) -> FontInfo {
        FontInfo {
            two_byte,
            wmode: 0,
            vertical_metrics: Arc::default(),
            default_vertical: (0.880, -1.0),
            cid_to_gid: None,
            to_unicode: None,
            encoding: Arc::new(encoding::win_ansi()),
            cmap_uni: Arc::default(),
            cmap,
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

    /// The property `latches_text_clip` needs is an ITERATION property, not an
    /// operand property. These differ in exactly one place, and that difference
    /// blanked pages: a 1-byte string in an Identity-H font is non-empty yet shows
    /// no glyph, so latching on `!bytes.is_empty()` produced a §9.4.3 text clip with
    /// no outlines behind it, which clips everything after the `ET` away.
    #[test]
    fn a_lone_byte_in_an_identity_h_font_shows_nothing() {
        let f = font(true, None);
        assert!(!f.shows_any_glyph(&[]), "empty shows nothing");
        assert!(
            !f.shows_any_glyph(&[0x41]),
            "Identity-H consumes codes in pairs, so one byte shows NO glyph even \
             though the string is non-empty — this is the case that must not latch"
        );
        assert!(f.shows_any_glyph(&[0x00, 0x41]), "a complete 2-byte code shows a glyph");
        assert!(
            f.shows_any_glyph(&[0x00, 0x41, 0x42]),
            "an odd length above 1 still shows the codes that ARE complete"
        );
    }

    /// The other two arms always consume a non-empty slice, so for them
    /// `shows_any_glyph` and `!bytes.is_empty()` agree. Asserted so the Identity-H
    /// case above is understood as the specific exception it is.
    #[test]
    fn the_other_encodings_show_a_glyph_for_any_non_empty_string() {
        let simple = font(false, None);
        assert!(!simple.shows_any_glyph(&[]));
        assert!(simple.shows_any_glyph(&[0x41]), "a simple font shows one glyph per byte");

        // A mixed-width CMap advances by `min(bytes.len() - i, ..)`, so it always
        // consumes a trailing remainder rather than dropping it.
        let cs = cmap::predefined_codespace("90ms-RKSJ-H").expect("RKSJ recognized");
        let mixed = font(true, Some(Arc::new(cmap::EncodingCMap { codespace: cs, ..Default::default() })));
        assert!(!mixed.shows_any_glyph(&[]));
        assert!(
            mixed.shows_any_glyph(&[0x82]),
            "a lone kanji LEAD byte is still consumed by the codespace arm, unlike \
             Identity-H — the defect is encoding-specific, not general to 2-byte fonts"
        );
    }
}

#[cfg(test)]
mod type3_encoding_tests {
    use super::*;
    use lopdf::{dictionary, Stream};

    /// Build a Type 3 font whose single CharProc is named `/a` — a name that
    /// StandardEncoding and WinAnsiEncoding both map from code 97 — and hand it the
    /// given `/Encoding` object.
    fn type3_with_encoding(doc: &mut Document, encoding: Option<Object>) -> lopdf::Dictionary {
        let proc_id = doc.add_object(Stream::new(
            dictionary! {},
            b"1000 0 d0 0 0 750 750 re f".to_vec(),
        ));
        let mut font = dictionary! {
            "Type" => "Font", "Subtype" => "Type3",
            "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
            "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
            "CharProcs" => doc.add_object(dictionary! { "a" => proc_id }),
            "FirstChar" => 97, "LastChar" => 97, "Widths" => vec![1000.into()],
        };
        if let Some(enc) = encoding {
            font.set("Encoding", enc);
        }
        font
    }

    /// §9.6.5 requires a Type 3 `/Encoding` to be a dictionary whose `/Differences`
    /// gives the complete encoding, so a NAME `/Encoding` is non-conformant. But the
    /// failure was total and silent: no code mapped to any CharProc, `char_procs`
    /// came out empty, and `show_string_type3` matched nothing — the entire text
    /// block painted no ink while the pen still advanced, so the layout looked
    /// perfectly right and only the letters were missing.
    ///
    /// Recovering through the simple font's §9.6.6.2 order can only ever match a
    /// CharProc already named for a standard glyph, which is exactly the intent of
    /// a file that named a base encoding in the first place.
    #[test]
    fn a_type3_font_with_a_name_encoding_still_finds_its_charprocs() {
        // The conformant shape, as a control: /Differences must keep working.
        let mut doc = Document::with_version("1.7");
        let diffs = Object::Dictionary(dictionary! {
            "Type" => "Encoding",
            "Differences" => vec![97.into(), "a".into()],
        });
        let font = type3_with_encoding(&mut doc, Some(diffs));
        let t3 = font_info(&doc, &font).t3.expect("Type 3 must parse");
        assert!(t3.char_procs.contains_key(&97), "control: /Differences must map 97 -> /a");

        // /Encoding /WinAnsiEncoding — a bare name.
        let mut doc = Document::with_version("1.7");
        let font = type3_with_encoding(&mut doc, Some(Object::Name(b"WinAnsiEncoding".to_vec())));
        let t3 = font_info(&doc, &font).t3.expect("Type 3 must parse");
        assert!(
            t3.char_procs.contains_key(&97),
            "a name /Encoding must fall back to that base encoding, not render nothing"
        );

        // A dictionary carrying only /BaseEncoding and no /Differences.
        let mut doc = Document::with_version("1.7");
        let base = Object::Dictionary(dictionary! {
            "Type" => "Encoding", "BaseEncoding" => "WinAnsiEncoding",
        });
        let font = type3_with_encoding(&mut doc, Some(base));
        let t3 = font_info(&doc, &font).t3.expect("Type 3 must parse");
        assert!(
            t3.char_procs.contains_key(&97),
            "/BaseEncoding with no /Differences must not render nothing"
        );

        // No /Encoding at all: StandardEncoding is the last resort, and it also
        // names code 97 `/a`.
        let mut doc = Document::with_version("1.7");
        let font = type3_with_encoding(&mut doc, None);
        let t3 = font_info(&doc, &font).t3.expect("Type 3 must parse");
        assert!(
            t3.char_procs.contains_key(&97),
            "an absent /Encoding must fall back to StandardEncoding"
        );
    }

    /// The fallback must not override a real `/Differences`. A font that
    /// deliberately maps `/a` to code 65 must not ALSO answer at code 97 just
    /// because StandardEncoding calls 97 `/a` — that would invent a glyph the file
    /// never encoded, and the fallback exists to recover nothing, not to add.
    #[test]
    fn an_explicit_differences_is_not_padded_by_the_fallback() {
        let mut doc = Document::with_version("1.7");
        let diffs = Object::Dictionary(dictionary! {
            "Type" => "Encoding",
            "Differences" => vec![65.into(), "a".into()],
        });
        let font = type3_with_encoding(&mut doc, Some(diffs));
        let t3 = font_info(&doc, &font).t3.expect("Type 3 must parse");
        assert!(t3.char_procs.contains_key(&65), "/Differences must map 65 -> /a");
        assert!(
            !t3.char_procs.contains_key(&97),
            "the base-encoding fallback must not add codes to a font that encoded its own"
        );
    }
}
