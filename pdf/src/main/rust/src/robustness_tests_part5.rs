/// HAZARD: a PostScript calculator function (Type 4) can loop or recurse without
/// bound unless token, step and depth budgets hold.
#[test]
fn postscript_calculator_functions_are_step_bounded() {
    let programs: [&[u8]; 4] = [
        // Deeply nested procedures.
        b"{ 0 { { { { { { { { { { pop 1 } if } if } if } if } if } if } if } if } if } if }",
        // Very long token stream.
        b"{ 0 dup dup dup dup dup dup dup dup pop pop pop pop pop pop pop pop }",
        // Unbalanced braces.
        b"{ { { { { { { 1 ",
        // Stack growth.
        b"{ 1 dup dup dup dup dup dup dup dup dup dup dup dup dup dup dup dup }",
    ];
    for (i, prog) in programs.iter().enumerate() {
        let pdf = raw_one_page(
            "/Resources << /Shading << /Sh0 5 0 R >> >>",
            b"q 0 0 600 700 re W n /Sh0 sh Q",
            vec![
                (
                    5,
                    b"<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 600 700] \
                      /Function 6 0 R /Extend [true true] >>"
                        .to_vec(),
                ),
                (
                    6,
                    raw_stream(
                        &format!(
                            "<< /FunctionType 4 /Domain [0 1] /Range [0 1 0 1 0 1] /Length {} >>",
                            prog.len()
                        ),
                        prog,
                    ),
                ),
            ],
        );
        assert_construct_survives(&format!("Type 4 PostScript function #{i}"), pdf);
    }

    // A very large generated program, to hit the token budget rather than the
    // depth budget.
    let mut big = Vec::from(&b"{ 0 "[..]);
    for _ in 0..200_000 {
        big.extend_from_slice(b"dup pop ");
    }
    big.extend_from_slice(b"0 0 }");
    let pdf = raw_one_page(
        "/Resources << /Shading << /Sh0 5 0 R >> >>",
        b"q 0 0 600 700 re W n /Sh0 sh Q",
        vec![
            (
                5,
                b"<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 600 700] \
                  /Function 6 0 R /Extend [true true] >>"
                    .to_vec(),
            ),
            (
                6,
                raw_stream(
                    &format!(
                        "<< /FunctionType 4 /Domain [0 1] /Range [0 1 0 1 0 1] /Length {} >>",
                        big.len()
                    ),
                    &big,
                ),
            ),
        ],
    );
    assert_construct_survives("Type 4 function with 400k tokens", pdf);
}

/// HAZARD: a Type 3 font whose glyph procedure paints text in the same font is
/// glyph-level recursion, distinct from `Do` recursion.
#[test]
fn self_referential_type3_glyph_terminates() {
    let pdf = raw_one_page(
        "/Resources << /Font << /T3 5 0 R >> >>",
        b"BT /T3 24 Tf 50 700 Td (AAA) Tj ET",
        vec![
            (
                5,
                b"<< /Type /Font /Subtype /Type3 /FontBBox [0 0 1000 1000] \
                  /FontMatrix [0.001 0 0 0.001 0 0] /CharProcs 6 0 R \
                  /Encoding << /Differences [65 /square] >> \
                  /FirstChar 65 /LastChar 65 /Widths [1000] \
                  /Resources << /Font << /T3 5 0 R >> >> >>"
                    .to_vec(),
            ),
            (6, b"<< /square 7 0 R >>".to_vec()),
            (
                7,
                raw_stream(
                    "<< /Length 46 >>",
                    b"1000 0 d0 BT /T3 24 Tf 0 0 Td (A) Tj ET   ",
                ),
            ),
        ],
    );
    assert_construct_survives("Type 3 glyph that shows text in its own font", pdf);
}

/// HAZARD: an /Annots array that is enormous, self-referential, or whose
/// appearance stream is the page, must be bounded by MAX_ANNOTATIONS.
#[test]
fn hostile_annotation_arrays_are_bounded() {
    // 50_000 references to one annotation, against a 10_000 cap.
    let refs: String = (0..50_000).map(|_| "5 0 R ").collect();
    let pdf = raw_one_page(
        &format!("/Annots [{refs}]"),
        b"0 0 10 10 re f",
        vec![(
            5,
            b"<< /Type /Annot /Subtype /Square /Rect [0 0 50 50] /F 4 /AP << /N 6 0 R >> >>"
                .to_vec(),
        ),
        (
            6,
            raw_stream(
                "<< /Type /XObject /Subtype /Form /BBox [0 0 50 50] /Length 16 >>",
                b"0 0 50 50 re f  ",
            ),
        )],
    );
    assert_construct_survives("50k-entry /Annots array against a 10k cap", pdf);

    // Annotation whose appearance stream is the page's own content stream.
    let pdf = raw_one_page(
        "/Annots [5 0 R]",
        b"0 0 10 10 re f",
        vec![(
            5,
            b"<< /Type /Annot /Subtype /Widget /Rect [0 0 600 700] /F 4 \
              /AP << /N 4 0 R >> >>"
                .to_vec(),
        )],
    );
    assert_construct_survives("annotation appearance stream is the page content", pdf);

    // Non-finite and inverted /Rect values.
    for rect in ["[nan nan nan nan]", "[1e400 1e400 -1e400 -1e400]", "[600 700 0 0]", "[]"] {
        let pdf = raw_one_page(
            "/Annots [5 0 R]",
            b"0 0 10 10 re f",
            vec![(
                5,
                format!(
                    "<< /Type /Annot /Subtype /Square /Rect {rect} /F 4 \
                     /AP << /N 6 0 R >> >>"
                )
                .into_bytes(),
            ),
            (
                6,
                raw_stream(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 50 50] /Length 16 >>",
                    b"0 0 50 50 re f  ",
                ),
            )],
        );
        assert_construct_survives(&format!("annotation /Rect {rect}"), pdf);
    }
}

/// HAZARD: non-finite and extreme numbers in the graphics state (matrices, line
/// widths, colours, text params) propagate into the wire buffer, where the
/// Kotlin side feeds them straight to a Canvas transform.
#[test]
fn non_finite_and_extreme_numbers_do_not_reach_the_wire_buffer() {
    let contents: [&[u8]; 9] = [
        b"1e400 0 0 1e400 0 0 cm 0 0 10 10 re f",
        b"0 0 0 0 0 0 cm 0 0 10 10 re f",
        b"-1e400 -1e400 -1e400 -1e400 -1e400 -1e400 cm 0 0 10 10 re f",
        b"1e400 w 0 0 m 10 10 l S",
        b"1e400 1e400 1e400 rg 0 0 10 10 re f",
        b"BT 1e400 Tf 1e400 1e400 Td (x) Tj ET",
        b"BT /F1 1e400 Tf 0 Tc 1e400 Tz 1e400 TL (x) Tj ET",
        b"0 0 m 1e400 1e400 1e400 1e400 1e400 1e400 c S",
        b"[1e400 1e400] 1e400 d 0 0 m 10 10 l S",
    ];
    for (i, content) in contents.iter().enumerate() {
        let pdf = raw_one_page("", content, vec![]);
        assert_construct_survives(&format!("non-finite graphics-state numbers #{i}"), pdf);
    }

    // A MediaBox that is itself non-finite or inverted: page size flows straight
    // into the wire header.
    for mb in [
        "[0 0 1e400 1e400]",
        "[nan nan nan nan]",
        "[0 0 0 0]",
        "[1e400 1e400 -1e400 -1e400]",
        "[]",
        "[0 0 612]",
    ] {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    format!(
                        "<< /Type /Page /Parent 2 0 R /MediaBox {mb} /Contents 4 0 R >>"
                    )
                    .into_bytes(),
                ),
                (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("MediaBox {mb}"), pdf);
    }
}

/// HAZARD: unbalanced `q`/`Q`, `BT`/`ET`, `W n` and marked-content operators.
/// The interpreter drains its clip/group stacks at end-of-stream, and an
/// unbalanced drain is how unmatched Push/Pop primitives reach the wire buffer.
#[test]
fn unbalanced_state_operators_leave_a_balanced_primitive_stream() {
    let mut deep_q = Vec::new();
    for _ in 0..5000 {
        deep_q.extend_from_slice(b"q 1 0 0 1 1 1 cm ");
    }
    deep_q.extend_from_slice(b"0 0 10 10 re f");

    let mut deep_qq = Vec::new();
    for _ in 0..5000 {
        deep_qq.extend_from_slice(b"Q ");
    }

    let mut deep_clip = Vec::new();
    for _ in 0..5000 {
        deep_clip.extend_from_slice(b"q 0 0 100 100 re W n ");
    }

    let mut deep_bdc = Vec::new();
    for _ in 0..5000 {
        deep_bdc.extend_from_slice(b"/OC /MC0 BDC ");
    }

    let cases: Vec<(&str, Vec<u8>)> = vec![
        ("5000 unmatched q", deep_q),
        ("5000 unmatched Q", deep_qq),
        ("5000 nested clips", deep_clip),
        ("5000 unmatched BDC", deep_bdc),
        ("unmatched BT", b"BT BT BT (x) Tj".to_vec()),
        ("unmatched ET", b"ET ET ET (x) Tj".to_vec()),
        ("W with no path", b"W n W n W n".to_vec()),
        ("EMC with no BDC", b"EMC EMC EMC 0 0 10 10 re f".to_vec()),
        ("Q before q", b"Q Q q 0 0 10 10 re f".to_vec()),
    ];
    for (name, content) in cases {
        let pdf = raw_one_page("/Resources << /Font << /F1 5 0 R >> >>", &content, vec![(
            5,
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_vec(),
        )]);
        assert_construct_survives(&format!("unbalanced operators: {name}"), pdf);
    }
}

/// HAZARD: an operator flood must be bounded by MAX_CONTENT_OPS /
/// MAX_PRIMITIVES rather than by memory.
///
/// This asserts the primitive cap DIRECTLY rather than via the wall clock,
/// because the wall clock here measures the debug build, not the renderer's
/// boundedness. Measured cost is ~110 microseconds per operator in debug
/// (1.5M operators complete in 110.9s), so a flood large enough to make
/// `MAX_PRIMITIVES` = 300_000 bind necessarily takes tens of seconds in debug.
/// An earlier version of this test failed a 10s budget for exactly that reason;
/// the work is bounded, so that was a mis-sized test and not a renderer defect.
/// Hence `flood_budget()`.
#[test]
fn operator_and_primitive_floods_are_capped() {
    // 350k path-painting operators, just above MAX_PRIMITIVES = 300_000, so the
    // primitive cap is what binds.
    let mut flood = Vec::with_capacity(16 * 350_000);
    for i in 0..350_000u32 {
        flood.extend_from_slice(format!("{} 0 1 1 re f ", i % 600).as_bytes());
    }
    let pdf = raw_one_page("", &flood, vec![]);
    let v = guarded("350k fill operators", flood_budget(), move || {
        let doc = load_document_lenient(&pdf).expect("flood fixture must load");
        let page_id = *doc.get_pages().values().next().unwrap();
        let page = interpret_page(&doc, page_id).expect("flood must interpret");
        eprintln!(
            "[robustness] 350k fill operators -> {} primitives (cap {})",
            page.prims.len(),
            MAX_PRIMITIVES
        );
        assert!(
            page.prims.len() <= MAX_PRIMITIVES,
            "the primitive cap did not bind: {} primitives emitted, cap is {}",
            page.prims.len(),
            MAX_PRIMITIVES
        );
        let _ = crate::wire::serialize(&page);
    });
    assert!(
        !v.is_failure(),
        "ROBUSTNESS FAILURE — 350k-operator flood: {v:?}"
    );

    // A single path with a huge number of subpaths.
    let mut subpaths = Vec::new();
    for i in 0..200_000u32 {
        subpaths.extend_from_slice(format!("{} 0 m {} 10 l ", i % 600, i % 600).as_bytes());
    }
    subpaths.extend_from_slice(b"S");
    let pdf = raw_one_page("", &subpaths, vec![]);
    let v = guarded("200k subpaths", flood_budget(), move || exercise(&pdf));
    assert!(!v.is_failure(), "ROBUSTNESS FAILURE — 200k subpaths in one path: {v:?}");

    // A large text run through a substitute font.
    let mut text = Vec::from(&b"BT /F1 1 Tf "[..]);
    for _ in 0..20_000 {
        text.extend_from_slice(b"(abcdefghijklmnopqrstuvwxyz) Tj 0 -1 Td ");
    }
    text.extend_from_slice(b"ET");
    let pdf = raw_one_page(
        "/Resources << /Font << /F1 5 0 R >> >>",
        &text,
        vec![(
            5,
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_vec(),
        )],
    );
    let v = guarded("520k glyphs", flood_budget(), move || exercise(&pdf));
    assert!(!v.is_failure(), "ROBUSTNESS FAILURE — 520k glyphs of text: {v:?}");
}

/// HAZARD: a tiling pattern with a near-zero /XStep over a large fill area is a
/// tile-count explosion — a pure slowdown with no panic, i.e. the
/// "slowdownexample" shape.
#[test]
fn degenerate_pattern_steps_do_not_explode_the_tile_count() {
    for (name, xstep, ystep, bbox) in [
        ("tiny-steps", "0.0001", "0.0001", "[0 0 0.0001 0.0001]"),
        ("zero-steps", "0", "0", "[0 0 1 1]"),
        ("negative-steps", "-1", "-1", "[0 0 1 1]"),
        ("nan-steps", "nan", "nan", "[0 0 1 1]"),
        ("huge-bbox", "1", "1", "[0 0 1e400 1e400]"),
    ] {
        let pdf = raw_one_page(
            "/Resources << /Pattern << /P0 5 0 R >> >>",
            b"/Pattern cs /P0 scn 0 0 612 792 re f",
            vec![(
                5,
                raw_stream(
                    &format!(
                        "<< /Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 \
                         /BBox {bbox} /XStep {xstep} /YStep {ystep} \
                         /Resources << >> /Length 16 >>"
                    ),
                    b"0 0 1 1 re f   ",
                ),
            )],
        );
        assert_construct_survives(&format!("tiling pattern {name}"), pdf);
    }
}

/// HAZARD: an /Encrypt dictionary with absurd or missing fields is parsed before
/// anything else, on fully untrusted bytes.
#[test]
fn hostile_encrypt_dictionaries_are_rejected_cleanly() {
    let cases: [&str; 7] = [
        "<< /Filter /Standard /V 2 /R 3 /Length 4294967295 /O <00> /U <00> /P -1 >>",
        "<< /Filter /Standard /V 2147483647 /R 2147483647 /Length 128 /O <00> /U <00> /P -1 >>",
        "<< /Filter /Standard /V 5 /R 6 /Length 256 >>",
        "<< /Filter /Standard >>",
        "<< /Filter /Standard /V 4 /R 4 /CF << /StdCF << /CFM /AESV2 /Length 4294967295 >> >> /StmF /StdCF /StrF /StdCF /O <00> /U <00> /P -1 >>",
        "<< /Filter /Standard /V 1 /R 2 /Length -8 /O () /U () /P 0 >>",
        "<< /Filter /Weird /V 99 >>",
    ];
    for (i, enc) in cases.iter().enumerate() {
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
                (5, enc.as_bytes().to_vec()),
            ],
            "/Root 1 0 R /Encrypt 5 0 R /ID [<00> <00>]",
        );
        assert_construct_survives(&format!("hostile /Encrypt dictionary #{i}"), pdf);
    }
}

/// HAZARD: filter chains that are absurdly long, unknown, or self-contradictory.
#[test]
fn hostile_filter_chains_are_rejected_cleanly() {
    let payload = flate(b"0 0 10 10 re f");
    let cases: [(&str, String); 6] = [
        (
            "64-deep-flate-chain",
            format!("[{}]", "/FlateDecode ".repeat(64)),
        ),
        ("unknown-filter", "/NoSuchDecode".to_string()),
        (
            "flate-then-ascii85",
            "[/FlateDecode /ASCII85Decode]".to_string(),
        ),
        ("filter-is-a-number", "42".to_string()),
        ("filter-is-a-dict", "<< /A 1 >>".to_string()),
        (
            "128-mixed-chain",
            format!("[{}]", "/ASCIIHexDecode /RunLengthDecode ".repeat(64)),
        ),
    ];
    for (name, filter) in cases {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"
                        .to_vec(),
                ),
                (
                    4,
                    raw_stream(
                        &format!("<< /Length {} /Filter {filter} >>", payload.len()),
                        &payload,
                    ),
                ),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("filter chain {name}"), pdf);
    }
}
