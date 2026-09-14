/// HAZARD: CCITT, JBIG2 and DCT streams are third-party-ish decoders fed
/// attacker bytes; garbage and lying parameters must not panic.
#[test]
fn image_codec_streams_with_garbage_bodies_are_rejected_cleanly() {
    let garbage: Vec<u8> = (0..512u32).map(|i| (i.wrapping_mul(2654435761) >> 13) as u8).collect();
    let cases: [(&str, String); 6] = [
        (
            "ccitt-k-absurd",
            "/CCITTFaxDecode /DecodeParms << /K -2147483648 /Columns 2147483647 /Rows 2147483647 /BlackIs1 true >>".to_string(),
        ),
        (
            "ccitt-zero-columns",
            "/CCITTFaxDecode /DecodeParms << /K 0 /Columns 0 /Rows 0 >>".to_string(),
        ),
        ("jbig2-garbage", "/JBIG2Decode".to_string()),
        ("dct-garbage", "/DCTDecode".to_string()),
        ("jpx-garbage", "/JPXDecode".to_string()),
        (
            "runlength-truncated",
            "/RunLengthDecode".to_string(),
        ),
    ];
    for (name, filter) in cases {
        let pdf = raw_one_page(
            "/Resources << /XObject << /Im0 5 0 R >> >>",
            b"q 600 0 0 700 0 0 cm /Im0 Do Q",
            vec![(
                5,
                raw_stream(
                    &format!(
                        "<< /Type /XObject /Subtype /Image /Width 256 /Height 256 \
                         /BitsPerComponent 8 /ColorSpace /DeviceGray /Filter {filter} \
                         /Length {} >>",
                        garbage.len()
                    ),
                    &garbage,
                ),
            )],
        );
        assert_construct_survives(&format!("image codec {name}"), pdf);
    }
}

/// HAZARD: an /SMask or /Mask image whose dimensions disagree with the base
/// image drives an index into the wrong buffer.
#[test]
fn mismatched_image_masks_do_not_index_out_of_bounds() {
    let base = flate(&[0x80u8; 16 * 16 * 3]);
    let cases: [(&str, &str); 4] = [
        ("smask-much-larger", "/SMask 6 0 R"),
        ("mask-as-array", "/Mask [0 0 0 0 0 0]"),
        ("mask-image", "/Mask 6 0 R"),
        ("smask-is-self", "/SMask 5 0 R"),
    ];
    for (name, extra) in cases {
        let mask = flate(&[0xFFu8; 4]);
        let pdf = raw_one_page(
            "/Resources << /XObject << /Im0 5 0 R >> >>",
            b"q 600 0 0 700 0 0 cm /Im0 Do Q",
            vec![
                (
                    5,
                    raw_stream(
                        &format!(
                            "<< /Type /XObject /Subtype /Image /Width 16 /Height 16 \
                             /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /FlateDecode \
                             {extra} /Length {} >>",
                            base.len()
                        ),
                        &base,
                    ),
                ),
                (
                    6,
                    raw_stream(
                        &format!(
                            "<< /Type /XObject /Subtype /Image /Width 4096 /Height 4096 \
                             /BitsPerComponent 8 /ColorSpace /DeviceGray /Filter /FlateDecode \
                             /Length {} >>",
                            mask.len()
                        ),
                        &mask,
                    ),
                ),
            ],
        );
        assert_construct_survives(&format!("image mask {name}"), pdf);
    }
}

/// HAZARD: an AcroForm whose field tree is cyclic, or whose /DA is hostile, is
/// walked by the form-field enumeration path.
#[test]
fn cyclic_acroform_field_trees_terminate() {
    let pdf = raw_pdf(
        &[
            (
                1,
                b"<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [5 0 R] /NeedAppearances true >> >>"
                    .to_vec(),
            ),
            (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R \
                  /Annots [5 0 R 6 0 R] >>"
                    .to_vec(),
            ),
            (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
            (
                5,
                b"<< /Type /Annot /Subtype /Widget /FT /Tx /T (a) /Rect [0 0 100 20] /F 4 \
                  /Parent 6 0 R /Kids [6 0 R] /DA (/NoSuchFont 1e400 Tf 1e400 1e400 1e400 rg) >>"
                    .to_vec(),
            ),
            (
                6,
                b"<< /Type /Annot /Subtype /Widget /FT /Tx /T (b) /Rect [0 0 100 20] /F 4 \
                  /Parent 5 0 R /Kids [5 0 R] /DA () >>"
                    .to_vec(),
            ),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("cyclic AcroForm field tree with hostile /DA", pdf);
}

/// HAZARD: an outline (bookmark) tree with cyclic /Next, /First and /Parent
/// links is walked by `list_outline`, and a /Dest name tree can also cycle.
#[test]
fn cyclic_outline_and_name_trees_terminate() {
    let pdf = raw_pdf(
        &[
            (
                1,
                b"<< /Type /Catalog /Pages 2 0 R /Outlines 5 0 R \
                  /Names << /Dests 7 0 R >> >>"
                    .to_vec(),
            ),
            (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>".to_vec(),
            ),
            (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
            (
                5,
                b"<< /Type /Outlines /First 6 0 R /Last 6 0 R /Count 2147483647 >>".to_vec(),
            ),
            (
                6,
                b"<< /Title (loop) /Parent 5 0 R /First 6 0 R /Last 6 0 R \
                  /Next 6 0 R /Prev 6 0 R /Dest (self) /Count -2147483648 >>"
                    .to_vec(),
            ),
            (
                7,
                b"<< /Kids [7 0 R] /Names [(self) 7 0 R] >>".to_vec(),
            ),
        ],
        "/Root 1 0 R",
    );
    // list_outline / named destinations are handle-based, so this one goes
    // through the registry deliberately.
    let v = guarded("cyclic outline tree", construct_budget(), move || {
        let handle = open_document(&pdf);
        if handle == 0 {
            return;
        }
        let _ = list_outline(handle);
        let _ = list_links(handle, 0);
        let _ = list_form_fields(handle, 0);
        let _ = render_page(handle, 0);
        close_document(handle);
    });
    assert!(
        !v.is_failure(),
        "ROBUSTNESS FAILURE — hazard: cyclic outline / name tree: {v:?}"
    );
}

/// HAZARD: an object stream (/ObjStm) with a lying /N and /First, and an xref
/// stream with a hostile /W, are both parsed before any page exists.
#[test]
fn hostile_object_and_xref_streams_are_rejected_cleanly() {
    let body = b"1 0 2 20 << /Type /Catalog >> << /Type /Pages >>";
    let cases: [(&str, String); 5] = [
        ("N-absurd", "/N 4294967295 /First 0".to_string()),
        ("First-past-end", "/N 2 /First 4294967295".to_string()),
        ("First-negative", "/N 2 /First -1".to_string()),
        ("N-zero-First-huge", "/N 0 /First 2147483647".to_string()),
        ("N-negative", "/N -2147483648 /First 0".to_string()),
    ];
    for (name, params) in cases {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"
                        .to_vec(),
                ),
                (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
                (
                    5,
                    raw_stream(
                        &format!("<< /Type /ObjStm {params} /Length {} >>", body.len()),
                        body,
                    ),
                ),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("/ObjStm {name}"), pdf);
    }

    // xref stream with a hostile /W and /Index. `index` is varied too, because
    // a zero total entry width means the reader consumes no input per entry, so
    // whether the loop is bounded at all depends on /Index rather than the data.
    for (w, index) in [
        ("[1 2 1]", "[0 2147483647]"),
        ("[4294967295 4294967295 4294967295]", "[0 2147483647]"),
        ("[0 0 0]", "[0 16]"),
        ("[0 0 0]", "[0 2147483647]"),
        ("[-1 -1 -1]", "[0 2147483647]"),
        ("[]", "[0 2147483647]"),
        ("[1 2 1]", "[0 4294967295]"),
    ] {
        let mut pdf = Vec::from(&b"%PDF-1.7\n"[..]);
        pdf.extend_from_slice(b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        pdf.extend_from_slice(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        pdf.extend_from_slice(
            b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n",
        );
        let xref_at = pdf.len();
        pdf.extend_from_slice(
            format!(
                "4 0 obj\n<< /Type /XRef /Size 5 /Root 1 0 R /W {w} \
                 /Index {index} /Length 8 >>\nstream\n"
            )
            .as_bytes(),
        );
        pdf.extend_from_slice(&[0xFFu8; 8]);
        pdf.extend_from_slice(b"\nendstream\nendobj\n");
        pdf.extend_from_slice(format!("startxref\n{xref_at}\n%%EOF\n").as_bytes());
        assert_construct_survives(&format!("xref stream with /W {w} /Index {index}"), pdf);
    }
}

/// HAZARD: /Rotate and /UserUnit feed the page base matrix, which every
/// primitive is transformed by.
#[test]
fn hostile_rotate_and_userunit_produce_a_finite_page() {
    for (rotate, uu) in [
        ("2147483647", "1"),
        ("-2147483648", "1"),
        ("45", "1"),
        ("90", "1e400"),
        ("0", "0"),
        ("0", "-1"),
        ("0", "nan"),
    ] {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    format!(
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] \
                         /Rotate {rotate} /UserUnit {uu} /Contents 4 0 R >>"
                    )
                    .into_bytes(),
                ),
                (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("/Rotate {rotate} /UserUnit {uu}"), pdf);
    }
}

/// HAZARD: an inherited-attribute chain (/Resources, /MediaBox) hidden behind a
/// long /Parent chain must be bounded, and a /Resources that references the page
/// must not recurse.
#[test]
fn long_parent_chains_and_self_referential_resources_terminate() {
    // A 200-deep /Parent chain, against a 32-hop bound.
    let mut objs: Vec<(u32, Vec<u8>)> = vec![
        (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
        (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
        (3, b"<< /Type /Page /Parent 5 0 R /Contents 4 0 R >>".to_vec()),
        (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
    ];
    for id in 5u32..205 {
        objs.push((
            id,
            format!("<< /Type /Pages /Parent {} 0 R /Kids [3 0 R] /Count 1 >>", id + 1).into_bytes(),
        ));
    }
    objs.push((
        205,
        b"<< /Type /Pages /MediaBox [0 0 612 792] /Kids [3 0 R] /Count 1 >>".to_vec(),
    ));
    assert_construct_survives(
        "200-deep /Parent chain hiding /MediaBox",
        raw_pdf(&objs, "/Root 1 0 R"),
    );

    // /Resources that is the page dictionary itself.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
            (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R \
                  /Resources 3 0 R >>"
                    .to_vec(),
            ),
            (4, raw_stream("<< /Length 26 >>", b"/Fm0 Do 0 0 10 10 re f    ")),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("/Resources pointing at the page dictionary", pdf);
}

/// HAZARD: operand-free operators amplify tiny content streams into large heap.
///
/// Reported by `bench` from live-heap measurement, and it is a hazard class none
/// of my other tests reach. Their numbers: a parsed `Operation` costs a ~544-byte
/// floor REGARDLESS of operand count — `re` with 4 operands, `l` with 2, and a
/// bare `q` or `Q` with ZERO operands all cost 544 B. So `"q\nQ\n"` (4 bytes of
/// content) retains ~1088 bytes: **~272x amplification from post-filter bytes to
/// heap**. They measured 400k `q`/`Q` operations from ~800 KB of content retaining
/// 207 MiB.
///
/// Why the existing caps do not bound this, which is the whole point:
///   * `MAX_DECODED_BYTES` (filters.rs:348) bounds FILTER output. The
///     amplification happens later, in `content.rs` building `Vec<Operation>`, so
///     a decompression-bomb cap cannot see it. My
///     `flate_decompression_bomb_is_capped_not_inflated` is a different stage and
///     remains valid.
///   * `MAX_PRIMITIVES` (300_000) cannot bind: bare `q`/`Q` emit ZERO primitives,
///     so the primitive cap never engages.
///   * `MAX_CONTENT_OPS` (1_000_000) is therefore the ONLY thing standing between
///     a ~2 MB content stream and 1_000_000 x 544 B = ~544 MB of live heap.
///
/// This test asserts the one bound that actually applies — that the operation
/// count is capped — because that is the invariant, and because I cannot measure
/// heap here: `perf_tests.rs` already installs the crate's single permitted
/// `#[global_allocator]`, and a binary may only have one. So this covers
/// boundedness and termination; the 544 B/op constant itself is `bench`'s
/// measurement, not something this test re-derives.
#[test]
fn operand_free_operator_floods_are_bounded_by_the_operation_cap() {
    // 600k bare q/Q pairs = 1.2M operators from ~2.4 MB of content, past the
    // 1M MAX_CONTENT_OPS cap. At bench's 544 B/op an uncapped parse would retain
    // over 600 MB.
    let pairs = 600_000usize;
    let mut content = Vec::with_capacity(pairs * 4);
    for _ in 0..pairs {
        content.extend_from_slice(b"q\nQ\n");
    }
    let declared = content.len();
    let v = guarded("4M bare q/Q operators", flood_budget(), move || {
        let ops = crate::content::parse_operations_lenient(&content);
        eprintln!(
            "[robustness] {declared} bytes of bare q/Q -> {} operations (cap {}), \
             ~{} MiB at bench's measured 544 B/op",
            ops.len(),
            MAX_CONTENT_OPS,
            ops.len() * 544 / (1024 * 1024)
        );
        assert!(
            ops.len() <= MAX_CONTENT_OPS,
            "the operation cap did not bind: {} operations parsed from {} bytes \
             of operand-free content, cap is {}. At the measured 544 B/op floor \
             that is ~{} MiB of live heap, and no other cap can bound it: \
             MAX_PRIMITIVES cannot engage because q/Q emit zero primitives, and \
             MAX_DECODED_BYTES only bounds filter output, not Vec<Operation>.",
            ops.len(),
            declared,
            MAX_CONTENT_OPS,
            ops.len() * 544 / (1024 * 1024)
        );
    });
    assert!(
        !v.is_failure(),
        "ROBUSTNESS FAILURE — operand-free operator flood: {v:?}"
    );

    // The same shape through the real page path, and with other zero/one-operand
    // operators, so the cap is not specific to q/Q.
    for (name, unit) in [
        ("q/Q", &b"q Q "[..]),
        ("BT/ET", &b"BT ET "[..]),
        ("W n", &b"W n "[..]),
        ("BMC/EMC", &b"BMC EMC "[..]),
        ("gs-no-such", &b"/NoSuch gs "[..]),
        ("h", &b"h "[..]),
    ] {
        let mut content = Vec::new();
        while content.len() < 1024 * 1024 {
            content.extend_from_slice(unit);
        }
        let pdf = raw_one_page("", &content, vec![]);
        let v = guarded(
            &format!("1 MiB of {name}"),
            flood_budget(),
            move || exercise(&pdf),
        );
        assert!(
            !v.is_failure(),
            "ROBUSTNESS FAILURE — 1 MiB of operand-free `{name}` operators: {v:?}"
        );
    }
}

// ---------------------------------------------------------------------------
// LAYER 3 — budget / timing
// ---------------------------------------------------------------------------
