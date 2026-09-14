/// HAZARD: a Flate stream that decompresses to far more than the decoded-bytes
/// cap must be refused, not inflated into RAM. filters.rs caps decoded output at
/// 256 MB; this bomb declares 320 MB.
#[test]
fn flate_decompression_bomb_is_capped_not_inflated() {
    let bomb = zero_bomb(320 * 1024 * 1024);
    assert!(
        bomb.len() < 4 * 1024 * 1024,
        "the bomb fixture itself must stay small relative to what it expands to \
         ({} bytes compressed vs 320 MB decompressed)",
        bomb.len()
    );
    // As page content... The flate bomb gets FLOOD_BUDGET rather than
    // CONSTRUCT_BUDGET: even when correctly capped, walking up to the 256 MB
    // `MAX_DECODED_BYTES` limit (filters.rs:348) is real work in a debug build,
    // and it is slower still when the suite runs in parallel. Measured ~7s alone.
    // A cap failure would show up as an OOM abort or a multi-minute run, not as a
    // borderline time, so a generous budget does not weaken the test.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
            (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>".to_vec(),
            ),
            (
                4,
                raw_stream(
                    &format!("<< /Length {} /Filter /FlateDecode >>", bomb.len()),
                    &bomb,
                ),
            ),
        ],
        "/Root 1 0 R",
    );
    let v = guarded("flate bomb as content", flood_budget(), move || exercise(&pdf));
    assert!(
        !v.is_failure(),
        "ROBUSTNESS FAILURE — 320 MB flate bomb as page content stream: {v:?}"
    );

    // ...and as image data, which takes a different decode path.
    let pdf = raw_one_page(
        "/Resources << /XObject << /Im0 5 0 R >> >>",
        b"q 600 0 0 700 0 0 cm /Im0 Do Q",
        vec![(
            5,
            raw_stream(
                &format!(
                    "<< /Type /XObject /Subtype /Image /Width 8192 /Height 8192 \
                     /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode \
                     /Length {} >>",
                    bomb.len()
                ),
                &bomb,
            ),
        )],
    );
    let v = guarded("flate bomb as image", flood_budget(), move || exercise(&pdf));
    assert!(
        !v.is_failure(),
        "ROBUSTNESS FAILURE — 320 MB flate bomb as image XObject data: {v:?}"
    );
}

/// HAZARD: a bogus `999999999 0 obj` header in a file that needs xref rebuilding
/// used to size the rebuilt table by max object id, allocating ~20 GB.
#[test]
fn bogus_high_object_number_does_not_allocate_a_giant_xref() {
    // No xref at all, so recovery is forced.
    let mut pdf = Vec::from(&b"%PDF-1.7\n"[..]);
    pdf.extend_from_slice(b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
    pdf.extend_from_slice(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
    pdf.extend_from_slice(
        b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n",
    );
    pdf.extend_from_slice(b"4294967295 0 obj\n<< >>\nendobj\n");
    pdf.extend_from_slice(b"999999999 0 obj\n<< >>\nendobj\n");
    pdf.extend_from_slice(b"%%EOF\n");
    assert_construct_survives("bogus high object id during xref rebuild", pdf);
}

/// HAZARD: /W in a CIDFont, and a ToUnicode bfrange, declaring billions of CIDs
/// must be clamped to MAX_CID rather than sized from the declared range.
#[test]
fn absurd_cid_ranges_in_w_and_tounicode_are_clamped() {
    let tounicode = b"/CIDInit /ProcSet findresource begin\n\
        12 dict begin begincmap\n\
        1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n\
        3 beginbfrange\n\
        <0000> <FFFFFFFF> <0041>\n\
        <0000> <7FFFFFFF> [<0042>]\n\
        <0001> <0000> <0043>\n\
        endbfrange\nendcmap end end\n"
        .to_vec();
    let pdf = raw_one_page(
        "/Resources << /Font << /F1 5 0 R >> >>",
        b"BT /F1 24 Tf 50 700 Td <00410042> Tj ET",
        vec![
            (
                5,
                b"<< /Type /Font /Subtype /Type0 /BaseFont /Test /Encoding /Identity-H \
                  /DescendantFonts [6 0 R] /ToUnicode 7 0 R >>"
                    .to_vec(),
            ),
            (
                6,
                b"<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Test \
                  /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> \
                  /DW 1000 \
                  /W [0 [500] 1 4294967295 600 0 2147483647 [700 700 700]] >>"
                    .to_vec(),
            ),
            (
                7,
                raw_stream(&format!("<< /Length {} >>", tounicode.len()), &tounicode),
            ),
        ],
    );
    assert_construct_survives("/W and bfrange declaring billions of CIDs", pdf);
}

/// HAZARD: a TrueType cmap format 12/13 subtable whose group count is absurd
/// must not drive an allocation or loop sized from the declared count.
#[test]
fn truetype_cmap_format_12_absurd_group_count_is_rejected() {
    // Minimal sfnt: one `cmap` table, format 12 with nGroups = 0xFFFFFFFF and no
    // group data at all.
    fn sfnt_with_cmap(format: u16, n_groups: u32) -> Vec<u8> {
        let mut cmap = Vec::new();
        cmap.extend_from_slice(&0u16.to_be_bytes()); // version
        cmap.extend_from_slice(&1u16.to_be_bytes()); // numTables
        cmap.extend_from_slice(&3u16.to_be_bytes()); // platformID
        cmap.extend_from_slice(&10u16.to_be_bytes()); // encodingID (UCS-4)
        cmap.extend_from_slice(&12u32.to_be_bytes()); // offset to subtable
        cmap.extend_from_slice(&format.to_be_bytes()); // format 12 or 13
        cmap.extend_from_slice(&0u16.to_be_bytes()); // reserved
        cmap.extend_from_slice(&0xFFFF_FFFFu32.to_be_bytes()); // length (lies)
        cmap.extend_from_slice(&0u32.to_be_bytes()); // language
        cmap.extend_from_slice(&n_groups.to_be_bytes()); // nGroups (absurd)
        // No group records follow — the table ends here.

        let mut font = Vec::new();
        font.extend_from_slice(&0x0001_0000u32.to_be_bytes()); // sfnt version
        font.extend_from_slice(&1u16.to_be_bytes()); // numTables
        font.extend_from_slice(&16u16.to_be_bytes()); // searchRange
        font.extend_from_slice(&0u16.to_be_bytes()); // entrySelector
        font.extend_from_slice(&0u16.to_be_bytes()); // rangeShift
        font.extend_from_slice(b"cmap");
        font.extend_from_slice(&0u32.to_be_bytes()); // checksum
        font.extend_from_slice(&(12u32 + 16).to_be_bytes()); // offset
        font.extend_from_slice(&(cmap.len() as u32).to_be_bytes()); // length
        font.extend_from_slice(&cmap);
        font
    }

    for format in [12u16, 13] {
        for n_groups in [0xFFFF_FFFFu32, 0x7FFF_FFFF, 0x0010_0000] {
            let font = sfnt_with_cmap(format, n_groups);
            let pdf = raw_one_page(
                "/Resources << /Font << /F1 5 0 R >> >>",
                b"BT /F1 24 Tf 50 700 Td (ABC) Tj ET",
                vec![
                    (
                        5,
                        b"<< /Type /Font /Subtype /TrueType /BaseFont /Bogus \
                          /FirstChar 65 /LastChar 67 /Widths [500 500 500] \
                          /FontDescriptor 6 0 R >>"
                            .to_vec(),
                    ),
                    (
                        6,
                        b"<< /Type /FontDescriptor /FontName /Bogus /Flags 4 \
                          /ItalicAngle 0 /Ascent 700 /Descent -200 /CapHeight 700 \
                          /StemV 80 /FontBBox [0 0 1000 1000] /FontFile2 7 0 R >>"
                            .to_vec(),
                    ),
                    (
                        7,
                        raw_stream(&format!("<< /Length1 {} /Length {} >>", font.len(), font.len()), &font),
                    ),
                ],
            );
            assert_construct_survives(
                &format!("TrueType cmap format {format} with nGroups={n_groups}"),
                pdf,
            );
        }
    }
}

/// HAZARD: a Type 0 (sampled) function's /Size and /BitsPerSample drive a
/// product and a 2^n interpolation loop; both must be bounded independently of
/// the declared values.
#[test]
fn sampled_function_absurd_size_and_bits_do_not_drive_a_2n_loop() {
    let cases: [(&str, &str, &str); 5] = [
        ("huge-2d", "[2000000000 2000000000]", "32"),
        ("eight-inputs", "[64 64 64 64 64 64 64 64]", "32"),
        ("bits-overflow", "[4294967295]", "32"),
        ("zero-size", "[0 0]", "8"),
        ("bad-bits", "[16 16]", "4294967295"),
    ];
    for (name, size, bits) in cases {
        let domain: String = if size.split_whitespace().count() > 2 {
            // One [min max] pair per input dimension.
            let dims = size.trim_start_matches('[').trim_end_matches(']').split_whitespace().count();
            (0..dims).map(|_| "0 1 ").collect()
        } else {
            "0 1 0 1".to_string()
        };
        let pdf = raw_one_page(
            "/Resources << /Shading << /Sh0 5 0 R >> >>",
            b"q 0 0 600 700 re W n /Sh0 sh Q",
            vec![
                (
                    5,
                    b"<< /ShadingType 2 /ColorSpace /DeviceRGB \
                      /Coords [0 0 600 700] /Function 6 0 R /Extend [true true] >>"
                        .to_vec(),
                ),
                (
                    6,
                    raw_stream(
                        &format!(
                            "<< /FunctionType 0 /Domain [{domain}] /Range [0 1 0 1 0 1] \
                             /Size {size} /BitsPerSample {bits} /Length 8 >>"
                        ),
                        &[0u8; 8],
                    ),
                ),
            ],
        );
        assert_construct_survives(&format!("sampled function {name}: /Size {size} /BitsPerSample {bits}"), pdf);
    }
}

/// HAZARD: a font program truncated mid-structure must abort parsing, not index
/// past the end of the buffer.
#[test]
fn truncated_font_programs_abort_instead_of_indexing_past_the_end() {
    // A CFF header + a partial INDEX, cut at every plausible boundary.
    let cff: Vec<u8> = vec![
        0x01, 0x00, 0x04, 0x01, // major, minor, hdrSize, offSize
        0x00, 0x01, 0x01, 0x01, 0x04, // Name INDEX: count 1, offSize 1, offsets
        b'T', b'e', b's', // partial name data
        0x00, 0x01, 0x01, 0x01, 0x1E, // TopDICT INDEX header, truncated body
        0x0F, 0x1C, // partial charset operator
    ];
    // A Type 1 program: plain-text prologue then eexec with a truncated body.
    let mut t1: Vec<u8> = b"%!PS-AdobeFont-1.0: Bogus\n/FontName /Bogus def\n\
        /Encoding 256 array\n0 1 255 {1 index exch /.notdef put} for\n\
        dup 65 /A put\nreadonly def\ncurrentdict end\ncurrentfile eexec\n"
        .to_vec();
    t1.extend_from_slice(&[0x11, 0x22, 0x33, 0x44, 0xAA, 0xBB, 0xCC]);

    for (name, prog, kind) in [
        ("CFF (FontFile3)", cff.clone(), "/FontFile3 7 0 R"),
        ("Type1 (FontFile)", t1.clone(), "/FontFile 7 0 R"),
        ("TrueType (FontFile2)", cff.clone(), "/FontFile2 7 0 R"),
    ] {
        // Every prefix length, so the cut lands mid-INDEX, mid-DICT, mid-charstring.
        for cut in 0..=prog.len() {
            let part = &prog[..cut];
            let pdf = raw_one_page(
                "/Resources << /Font << /F1 5 0 R >> >>",
                b"BT /F1 24 Tf 50 700 Td (ABC) Tj ET",
                vec![
                    (
                        5,
                        b"<< /Type /Font /Subtype /Type1 /BaseFont /Bogus \
                          /FirstChar 65 /LastChar 67 /Widths [500 500 500] \
                          /FontDescriptor 6 0 R >>"
                            .to_vec(),
                    ),
                    (
                        6,
                        format!(
                            "<< /Type /FontDescriptor /FontName /Bogus /Flags 4 \
                             /ItalicAngle 0 /Ascent 700 /Descent -200 /CapHeight 700 \
                             /StemV 80 /FontBBox [0 0 1000 1000] {kind} >>"
                        )
                        .into_bytes(),
                    ),
                    (
                        7,
                        raw_stream(
                            &format!(
                                "<< /Length {} /Length1 {} /Length2 0 /Length3 0 /Subtype /Type1C >>",
                                part.len(),
                                part.len()
                            ),
                            part,
                        ),
                    ),
                ],
            );
            assert_construct_survives(&format!("{name} truncated to {cut}/{} bytes", prog.len()), pdf);
        }
    }
}

/// HAZARD: a Form XObject that paints itself, and a pattern that paints itself,
/// are unbounded recursion unless the depth guards hold.
#[test]
fn self_referential_form_and_pattern_terminate() {
    // Form XObject whose resources name itself.
    let pdf = raw_one_page(
        "/Resources << /XObject << /Fm0 5 0 R >> >>",
        b"/Fm0 Do",
        vec![(
            5,
            raw_stream(
                "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
                 /Resources << /XObject << /Fm0 5 0 R >> >> /Length 8 >>",
                b"/Fm0 Do",
            ),
        )],
    );
    assert_construct_survives("Form XObject that paints itself", pdf);

    // Two forms that paint each other.
    let pdf = raw_one_page(
        "/Resources << /XObject << /A 5 0 R >> >>",
        b"/A Do",
        vec![
            (
                5,
                raw_stream(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
                     /Resources << /XObject << /B 6 0 R >> >> /Length 6 >>",
                    b"/B Do",
                ),
            ),
            (
                6,
                raw_stream(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
                     /Resources << /XObject << /A 5 0 R >> >> /Length 6 >>",
                    b"/A Do",
                ),
            ),
        ],
    );
    assert_construct_survives("mutually recursive Form XObjects", pdf);

    // Tiling pattern whose cell fills with itself.
    let pdf = raw_one_page(
        "/Resources << /Pattern << /P0 5 0 R >> >>",
        b"/Pattern cs /P0 scn 0 0 400 400 re f",
        vec![(
            5,
            raw_stream(
                "<< /Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 \
                 /BBox [0 0 8 8] /XStep 8 /YStep 8 \
                 /Resources << /Pattern << /P0 5 0 R >> >> /Length 40 >>",
                b"/Pattern cs /P0 scn 0 0 8 8 re f       ",
            ),
        )],
    );
    assert_construct_survives("tiling pattern that fills with itself", pdf);

    // Shading pattern whose function references itself.
    let pdf = raw_one_page(
        "/Resources << /Pattern << /P0 5 0 R >> >>",
        b"/Pattern cs /P0 scn 0 0 400 400 re f",
        vec![
            (
                5,
                b"<< /Type /Pattern /PatternType 2 /Shading 6 0 R >>".to_vec(),
            ),
            (
                6,
                b"<< /ShadingType 3 /ColorSpace /DeviceRGB \
                  /Coords [200 200 0 200 200 200] /Function 6 0 R >>"
                    .to_vec(),
            ),
        ],
    );
    assert_construct_survives("shading whose /Function references the shading", pdf);
}

/// HAZARD: a soft mask whose transparency group is the page itself (or a form
/// that reaches back to the page) is a cycle through a different code path than
/// plain `Do` recursion.
#[test]
fn soft_mask_group_referencing_the_page_terminates() {
    // /SMask /G points at a form whose resources contain the same ExtGState, so
    // applying the mask re-enters mask rendering.
    let pdf = raw_one_page(
        "/Resources << /ExtGState << /GS0 5 0 R >> /XObject << /Fm0 6 0 R >> >>",
        b"/GS0 gs /Fm0 Do 0 0 100 100 re f",
        vec![
            (
                5,
                b"<< /Type /ExtGState /SMask << /S /Luminosity /G 6 0 R >> >>".to_vec(),
            ),
            (
                6,
                raw_stream(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] \
                     /Group << /Type /Group /S /Transparency /CS /DeviceGray >> \
                     /Resources << /ExtGState << /GS0 5 0 R >> /XObject << /Fm0 6 0 R >> >> \
                     /Length 30 >>",
                    b"/GS0 gs /Fm0 Do 0 0 9 9 re f  ",
                ),
            ),
        ],
    );
    assert_construct_survives("soft-mask group that re-enters itself", pdf);

    // /SMask /G pointing at the PAGE object rather than a form.
    let pdf = raw_one_page(
        "/Resources << /ExtGState << /GS0 5 0 R >> >>",
        b"/GS0 gs 0 0 400 400 re f",
        vec![(
            5,
            b"<< /Type /ExtGState /SMask << /S /Luminosity /G 3 0 R >> >>".to_vec(),
        )],
    );
    assert_construct_survives("soft-mask /G pointing at the page object", pdf);
}
