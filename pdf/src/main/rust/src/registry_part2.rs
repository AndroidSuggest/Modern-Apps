#[cfg(test)]
mod recovery_tests {
    use super::*;

    /// 7.3.8: the `stream` keyword follows the dictionary's `>>`, so a NAME that
    /// merely ends in `stream` is not the start of a body. Without that boundary
    /// check the scan jumps to the next `endstream` and every object header in
    /// between is missing from the rebuilt cross-reference table.
    #[test]
    fn a_name_ending_in_stream_does_not_hide_the_objects_after_it() {
        let bytes: &[u8] = b"%PDF-1.7\n\
            1 0 obj\n<< /Type /Catalog /Substream 1 /Pages 2 0 R >>\nendobj\n\
            2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n\
            3 0 obj\n<< /Len 1 >>\nstream\nx\nendstream\nendobj\n";
        let objs = scan_indirect_objects(bytes);
        assert!(objs.contains_key(&1), "object 1 must be found: {objs:?}");
        assert!(
            objs.contains_key(&2),
            "`/Substream` must not be taken for a stream body, hiding object 2: {objs:?}"
        );
        assert!(objs.contains_key(&3), "object 3 must be found: {objs:?}");
        // And a real stream body is still skipped: `4 0 obj` inside one is not a header.
        let mut body = Vec::from(&b"%PDF-1.7\n1 0 obj\n<< /Length 30 >>\nstream\n"[..]);
        body.extend_from_slice(b"junk 4 0 obj << >> endobj junk\nendstream\nendobj\n");
        let objs = scan_indirect_objects(&body);
        assert!(objs.contains_key(&1));
        assert!(!objs.contains_key(&4), "a header inside a stream body must be skipped");
    }

    /// A19: an xref-stream file has no `trailer` keyword, so /Root must be read from
    /// the cross-reference stream's own dictionary or recovery fails outright.
    #[test]
    fn root_comes_from_the_xref_stream_dictionary() {
        let bytes: &[u8] = b"%PDF-1.7\n\
            4 0 obj\n<< /Type /ObjStm /N 2 /First 12 /Length 20 >>\nstream\n\
            \x01\x02binary/Root junk\nendstream\nendobj\n\
            9 0 obj\n<< /Type /XRef /Size 10 /Root 7 0 R /W [1 2 1] /Length 8 >>\nstream\n\
            \x00\x00\x00\x00\x00\x00\x00\x00\nendstream\nendobj\n\
            startxref\n9\n%%EOF\n";
        let objs = scan_indirect_objects(bytes);
        assert_eq!(
            root_from_xref_stream(&objs, bytes),
            Some((7, 0)),
            "the /Root reference in the /Type /XRef dictionary must be recovered"
        );
        // There is no `trailer` keyword, which is precisely why the old path failed.
        assert!(last_trailer_dict(bytes).is_none());
    }

    #[test]
    fn xref_stream_body_is_not_scanned_for_root() {
        // /Root appears only inside the binary stream body, never in a dictionary, so
        // nothing may be recovered from it.
        let bytes: &[u8] = b"%PDF-1.7\n\
            3 0 obj\n<< /Type /XRef /Size 4 /Length 16 >>\nstream\n\
            /Root 5 0 R \x00\x01\x02\x03\nendstream\nendobj\n";
        let objs = scan_indirect_objects(bytes);
        assert_eq!(root_from_xref_stream(&objs, bytes), None);
    }

    #[test]
    fn later_xref_stream_wins_over_the_one_it_superseded() {
        // An incrementally-updated file keeps its old xref stream; the highest object
        // id is the newer one.
        let bytes: &[u8] = b"%PDF-1.7\n\
            2 0 obj\n<< /Type /XRef /Root 1 0 R /Length 2 >>\nstream\n\x00\x00\nendstream\nendobj\n\
            8 0 obj\n<< /Type /XRef /Root 6 0 R /Length 2 >>\nstream\n\x00\x00\nendstream\nendobj\n";
        let objs = scan_indirect_objects(bytes);
        assert_eq!(root_from_xref_stream(&objs, bytes), Some((6, 0)));
    }

    #[test]
    fn a_bogus_high_object_id_does_not_size_the_xref_table() {
        // A single `999999999 0 obj` header used to produce ~20 GB of free entries.
        let bytes: &[u8] = b"%PDF-1.7\n\
            1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n\
            999999999 0 obj\n<< >>\nendobj\n";
        assert!(
            rebuild_with_scanned_xref(bytes).is_none(),
            "an implausible max object id must abort recovery, not allocate for it"
        );
    }

    /// The pre-scan's whole job: nesting deep enough to overflow lopdf's recursive
    /// object parser must be refused before the parser is entered.
    #[test]
    fn deep_object_nesting_is_refused_before_lopdf_recurses() {
        let deep = |n: usize| -> Vec<u8> {
            let mut v = Vec::from(&b"1 0 obj\n"[..]);
            v.extend_from_slice(&b"<< /K ".repeat(n));
            v.push(b'0');
            v.extend_from_slice(&b" >>".repeat(n));
            v.extend_from_slice(b"\nendobj\n");
            v
        };
        assert!(!nesting_exceeds(&deep(MAX_RAW_NESTING as usize), MAX_RAW_NESTING));
        assert!(nesting_exceeds(&deep(MAX_RAW_NESTING as usize + 1), MAX_RAW_NESTING));
        // Arrays count the same, and openers alone are enough - the crash does not
        // require the file to be balanced.
        assert!(nesting_exceeds(&b"[".repeat(MAX_RAW_NESTING as usize + 1), MAX_RAW_NESTING));
        assert!(nesting_exceeds(&b"<<".repeat(MAX_RAW_NESTING as usize + 1), MAX_RAW_NESTING));
        assert!(load_document_lenient(&deep(500)).is_none());
    }

    /// The false-positive direction, which matters more than the true positives:
    /// rejecting a legitimate document would be worse than the crash being fixed.
    #[test]
    fn ordinary_nesting_and_string_context_do_not_trip_the_pre_scan() {
        // 7.3.4.2: a literal string is opaque. Brackets, `<<` and `>` inside one
        // must not move the count, and `\)` must not end it early.
        let mut s = Vec::from(&b"1 0 obj\n<< /T ("[..]);
        for _ in 0..500 {
            s.extend_from_slice(b"[<< >(\\) ");
        }
        s.extend_from_slice(b") >>\nendobj\n");
        assert!(!nesting_exceeds(&s, MAX_RAW_NESTING));
        // 7.3.4.3 hex strings, and 7.2.4 comments, likewise.
        assert!(!nesting_exceeds(&b"<< /H <5B5B5B> >>".repeat(500), MAX_RAW_NESTING));
        assert!(!nesting_exceeds(&b"% [[[ << << <<\n".repeat(500), MAX_RAW_NESTING));
        // 7.3.8 stream bodies are binary and are dense in these bytes; they are not
        // parsed as objects here, so they must not be counted.
        let mut body = Vec::from(&b"1 0 obj\n<< /Length 2000 >>\nstream\n"[..]);
        body.extend_from_slice(&b"[".repeat(2000));
        body.extend_from_slice(b"\nendstream\nendobj\n");
        assert!(!nesting_exceeds(&body, MAX_RAW_NESTING));
        // A name that merely ends in `stream` is not the start of a body.
        assert!(nesting_exceeds(
            &[&b"<< /Substream 1 >>\n"[..], &b"[".repeat(MAX_RAW_NESTING as usize + 1)].concat(),
            MAX_RAW_NESTING
        ));
        // Residual depth from one unbalanced object must not leak into the next.
        let mut leak = Vec::new();
        for i in 0..500 {
            leak.extend_from_slice(format!("{i} 0 obj\n<< /A [ [ [\nendobj\n").as_bytes());
        }
        assert!(!nesting_exceeds(&leak, MAX_RAW_NESTING));
        // And a real document, written by lopdf itself, still loads - including a
        // content stream full of the bytes the scanner has to treat as opaque.
        let mut doc = Document::with_version("1.7");
        let pages_id = doc.new_object_id();
        let contents_id = doc.add_object(Stream::new(
            dictionary! {},
            b"BT /F1 12 Tf (a ( nested ) string with [ and << and > ) Tj ET 0 0 9 9 re f".to_vec(),
        ));
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => contents_id,
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => vec![page_id.into()],
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", catalog_id);
        let mut buf = Vec::new();
        doc.save_to(&mut buf).expect("the fixture must serialize");
        let loaded = load_document_lenient(&buf)
            .expect("a valid document must not be refused by the pre-scan");
        assert_eq!(loaded.get_pages().len(), 1);
    }

    /// 7.5.8: `/W` gives the byte width of each entry field, so a zero total means
    /// lopdf's reader consumes no input per entry and `/Index` alone bounds the loop.
    #[test]
    fn degenerate_xref_stream_widths_are_detected_from_the_raw_bytes() {
        let xref = |w: &str, index: &str| -> Vec<u8> {
            format!(
                "%PDF-1.7\n4 0 obj\n<< /Type /XRef /Size 5 /Root 1 0 R /W {w} \
                 /Index {index} /Length 8 >>\nstream\n\0\0\0\0\0\0\0\0\nendstream\nendobj\n"
            )
            .into_bytes()
        };
        assert!(xref_stream_is_degenerate(&xref("[0 0 0]", "[0 2147483647]")));
        assert!(xref_stream_is_degenerate(&xref("[0 0 0]", "[0 16]")));
        // Declaring more entries than the file has bytes: no object can be that cheap.
        assert!(xref_stream_is_degenerate(&xref("[1 2 1]", "[0 4294967295]")));
        // Sane layouts must pass, including a zero /W[0] (7.5.8 defaults the type
        // field to 1) and a zero generation width, both of which occur in real files.
        assert!(!xref_stream_is_degenerate(&xref("[1 2 1]", "[0 5]")));
        assert!(!xref_stream_is_degenerate(&xref("[0 2 1]", "[0 5]")));
        assert!(!xref_stream_is_degenerate(&xref("[1 2 0]", "[0 5]")));
        // `/W` must be matched as a whole name, not as the prefix of `/Widths`.
        assert!(!xref_stream_is_degenerate(
            b"%PDF-1.7\n4 0 obj\n<< /Type /XRef /Widths [0 0 0] /W [1 2 1] /Index [0 5] >>\nstream\n\0\nendstream\n"
        ));
    }

    /// Every `/Index` count is a number read from the file, so summing them with `+`
    /// panics in debug and WRAPS in release - and a wrap to a negative total makes the
    /// very file this check exists to catch look harmless.
    #[test]
    fn a_huge_index_count_neither_panics_nor_wraps_past_the_check() {
        let bytes = format!(
            "%PDF-1.7\n4 0 obj\n<< /Type /XRef /Size 5 /Root 1 0 R /W [1 2 1] \
             /Index [0 {max} 0 {max} 0 {max}] /Length 8 >>\nstream\n\0\0\0\0\0\0\0\0\nendstream\nendobj\n",
            max = i64::MAX
        )
        .into_bytes();
        assert!(
            xref_stream_is_degenerate(&bytes),
            "a file cannot describe more xref entries than it has bytes"
        );
    }
}
