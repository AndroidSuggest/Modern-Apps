/// §9.6.2.1: `/FirstChar` may be an indirect reference. The round-1 bug treated
/// an unresolved `/FirstChar` as 0, shifting every width in the array by 32 and
/// making all text overlap or fly apart.
#[test]
fn indirect_first_char_still_aligns_the_widths_array() {
    let mut doc = Document::with_version("1.5");
    let fc = doc.add_object(Object::Integer(65));
    let font = dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
        "FirstChar" => fc,
        "LastChar" => 66,
        "Widths" => vec![600.into(), 700.into()],
    };
    let fi = font_info(&doc, &font);
    let a = fi.widths.get(&0x41).copied();
    let b = fi.widths.get(&0x42).copied();
    assert!(
        a.map(|w| (w - 0.6).abs() < 1e-6).unwrap_or(false),
        "/Widths[0] must land on 'A' (65) when /FirstChar is indirect; got {a:?}"
    );
    assert!(
        b.map(|w| (w - 0.7).abs() < 1e-6).unwrap_or(false),
        "/Widths[1] must land on 'B' (66); got {b:?}"
    );
    assert!(
        !fi.widths.contains_key(&0),
        "an unresolved /FirstChar defaulting to 0 is the bug — code 0 must not be mapped"
    );
}

/// §9.4.3: a POSITIVE number in a `TJ` array moves the next glyph LEFT (the
/// value is subtracted from the displacement). Getting the sign wrong makes
/// kerned and justified text spread out instead of tightening up.
#[test]
fn positive_tj_number_moves_the_next_glyph_left() {
    let mut doc = Document::with_version("1.5");
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
    });
    let ops = vec![
        Operation::new("BT", vec![]),
        Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
        Operation::new("Td", vec![100.into(), 700.into()]),
        Operation::new(
            "TJ",
            vec![Object::Array(vec![
                Object::string_literal("A"),
                1000.into(), // one full em to the LEFT
                Object::string_literal("B"),
            ])],
        ),
        Operation::new("ET", vec![]),
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! { "Font" => dictionary! { "F1" => font_id } });

    let page = interpret_page(&doc, page_id).expect("interpret");
    let xs: Vec<f32> = page
        .prims
        .iter()
        .filter_map(|p| match p {
            Prim::Text { x, text, .. } if !text.trim().is_empty() => Some(*x),
            _ => None,
        })
        .collect();
    assert!(xs.len() >= 2, "both glyphs must be emitted, got {xs:?}");
    assert!(
        xs[1] < xs[0],
        "TJ 1000 at size 12 subtracts a full 12pt em, so 'B' must sit LEFT of 'A': {xs:?}"
    );
}

/// §9.3.3: word spacing (`Tw`) applies to the single-byte code 32 and to nothing
/// else — not to a 2-byte code 32 in a composite font, and not to NBSP (0xA0).
/// Applying it to a CID whose low byte is 32 tears CJK text apart.
#[test]
fn word_spacing_applies_only_to_the_single_byte_code_32() {
    let mut doc = Document::with_version("1.5");
    let simple = dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
        "FirstChar" => 32, "LastChar" => 160,
        // Uniform 500/1000 so any advance difference is purely Tw.
        "Widths" => Object::Array(vec![500.into(); 129]),
    };
    let desc = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "CIDFontType2", "BaseFont" => "Sub",
        "CIDSystemInfo" => dictionary! {
            "Registry" => Object::string_literal("Adobe"),
            "Ordering" => Object::string_literal("Identity"),
            "Supplement" => 0,
        },
        "DW" => 500,
    });
    let composite = dictionary! {
        "Type" => "Font", "Subtype" => "Type0", "BaseFont" => "Sub",
        "Encoding" => "Identity-H",
        "DescendantFonts" => vec![desc.into()],
    };

    let mut fonts = HashMap::new();
    fonts.insert(b"S".to_vec(), font_info(&doc, &simple));
    let cf = font_info(&doc, &composite);
    assert!(cf.two_byte, "Identity-H must be recognised as a 2-byte encoding");
    fonts.insert(b"C".to_vec(), cf);

    let advance = |key: &[u8], bytes: &[u8], tw: f64| -> f64 {
        let gs = GraphicsState {
            font_key: key.to_vec(),
            font_size: 10.0,
            word_spacing: tw,
            ..Default::default()
        };
        let mut prims = Vec::new();
        show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, bytes, 0)
    };

    let d_space = advance(b"S", b" ", 7.0) - advance(b"S", b" ", 0.0);
    assert!(
        (d_space - 7.0).abs() < 1e-4,
        "Tw must add 7 units to a single-byte space, added {d_space}"
    );
    let d_nbsp = advance(b"S", b"\xA0", 7.0) - advance(b"S", b"\xA0", 0.0);
    assert!(
        d_nbsp.abs() < 1e-4,
        "Tw must not apply to NBSP (0xA0), it changed the advance by {d_nbsp}"
    );
    let d_cid = advance(b"C", b"\x00\x20", 7.0) - advance(b"C", b"\x00\x20", 0.0);
    assert!(
        d_cid.abs() < 1e-4,
        "§9.3.3: Tw must not apply to a 2-byte code 32 in a composite font, \
         it changed the advance by {d_cid}"
    );
}

/// §11.6.5.2: the mask value must be passed through the soft mask's `/TR`
/// transfer function before use. An INVERTING `/TR` is the standard idiom for
/// "mask out where the group is bright", so ignoring it does not soften the
/// result — it hides exactly the wrong half of the content.
///
/// End-to-end plumbing check, because every individual piece of this chain
/// already exists and only the link between them is missing, which is precisely
/// the failure mode that reading code cannot catch: `functions::read_transfer_lut`
/// samples the LUT correctly (tested in functions.rs), `interpret.rs` stores it
/// into `GraphicsState.tr` and carries it into `SoftMask`/`MaskKey`, `model.rs`
/// declares `Prim::SoftMaskTransfer`, `wire.rs` can serialise it as tag 13, and
/// the Kotlin decoder can parse tag 13 — but nothing ever CONSTRUCTS the prim, so
/// the LUT is computed, threaded through three structs and then dropped.
#[test]
fn soft_mask_transfer_function_reaches_the_primitive_stream() {
    let mut doc = Document::with_version("1.7");
    let mask_content = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 1.0.into(), 1.0.into()]),
            Operation::new("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let mask_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 200.into(), 200.into()],
            "Group" => dictionary! { "S" => "Transparency" },
        },
        mask_content.encode().unwrap(),
    ));
    // { 1 exch sub } — the canonical inverter, so this is unambiguously non-identity.
    let tr_id = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 4,
            "Domain" => vec![0.into(), 1.into()],
            "Range" => vec![0.into(), 1.into()],
        },
        b"{ 1 exch sub }".to_vec(),
    ));
    let gs_id = doc.add_object(dictionary! {
        "SMask" => dictionary! {
            "S" => "Luminosity",
            "G" => Object::Reference(mask_id),
            "TR" => Object::Reference(tr_id),
        },
    });
    let ops = vec![
        Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
        Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
        Operation::new("re", vec![10.into(), 10.into(), 50.into(), 50.into()]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(
        &mut doc,
        ops,
        dictionary! { "ExtGState" => dictionary! { "GS1" => gs_id } },
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let push = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::SoftMaskPush { .. }))
        .expect("a soft-mask bracket must be emitted");
    let pop = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::SoftMaskPop))
        .expect("the soft-mask bracket must be closed");
    let lut = page.prims[push..pop].iter().find_map(|p| match p {
        Prim::SoftMaskTransfer(lut) => Some(lut.clone()),
        _ => None,
    });
    let lut = lut.expect(
        "§11.6.5.2: a soft mask with a non-identity /TR must emit a \
         Prim::SoftMaskTransfer inside its bracket, or the transfer function is \
         silently ignored and an inverting /TR hides the wrong half of the page",
    );
    assert_eq!(lut[0], 255, "the inverting /TR must survive to the renderer: 0 -> 255");
    assert_eq!(lut[255], 0, "and 255 -> 0");
}

/// A soft mask carrying a `/TR` must still produce a well-formed bracket. Pinned
/// separately from the test above so that if `/TR` is genuinely unsupported the
/// failure is "the transfer was dropped", not "the whole mask was dropped" —
/// those are very different bugs and should not share one assertion.
#[test]
fn soft_mask_with_a_transfer_function_still_brackets_its_content() {
    let mut doc = Document::with_version("1.7");
    let mask_content = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 1.0.into(), 1.0.into()]),
            Operation::new("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let mask_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 200.into(), 200.into()],
            "Group" => dictionary! { "S" => "Transparency" },
        },
        mask_content.encode().unwrap(),
    ));
    let tr_id = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 4,
            "Domain" => vec![0.into(), 1.into()],
            "Range" => vec![0.into(), 1.into()],
        },
        b"{ 1 exch sub }".to_vec(),
    ));
    let gs_id = doc.add_object(dictionary! {
        "SMask" => dictionary! {
            "S" => "Luminosity",
            "G" => Object::Reference(mask_id),
            "TR" => Object::Reference(tr_id),
        },
    });
    let ops = vec![
        Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
        Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
        Operation::new("re", vec![10.into(), 10.into(), 50.into(), 50.into()]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(
        &mut doc,
        ops,
        dictionary! { "ExtGState" => dictionary! { "GS1" => gs_id } },
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let kind = |want: fn(&Prim) -> bool| page.prims.iter().position(want);
    let push = kind(|p| matches!(p, Prim::SoftMaskPush { .. })).expect("SoftMaskPush");
    let sep = kind(|p| matches!(p, Prim::SoftMaskContent)).expect("SoftMaskContent");
    let pop = kind(|p| matches!(p, Prim::SoftMaskPop)).expect("SoftMaskPop");
    assert!(push < sep && sep < pop, "a /TR must not break the bracket ordering");
    let masked_fill = page.prims[push..sep]
        .iter()
        .any(|p| matches!(p, Prim::Fill { argb, .. } if (*argb & 0x00FF_FFFF) == 0x00FF_0000));
    assert!(masked_fill, "the red fill must still sit inside the mask bracket");
}

/// §9.6.5: a Type 3 glyph's CharProc is its own content stream, so an inline
/// image in one must not cost the glyph its other content. Covers the
/// `draw.rs` CharProc decode site.
#[test]
fn inline_image_inside_a_type3_charproc_keeps_the_rest_of_the_glyph() {
    let mut doc = Document::with_version("1.5");
    let mut proc_bytes: Vec<u8> = b"q 400 0 0 400 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    proc_bytes.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    proc_bytes.extend_from_slice(b" EI\nQ\n0 0 700 700 re f\n");
    let proc_id = doc.add_object(Stream::new(dictionary! {}, proc_bytes));
    let char_procs = doc.add_object(dictionary! { "a" => proc_id });
    let encoding = doc.add_object(dictionary! {
        "Type" => "Encoding",
        "Differences" => vec![65.into(), "a".into()],
    });
    let font = dictionary! {
        "Type" => "Font", "Subtype" => "Type3",
        "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
        "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
        "CharProcs" => char_procs, "Encoding" => encoding,
        "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
        "Resources" => dictionary! {},
    };
    let mut fonts = HashMap::new();
    fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
    let gs = GraphicsState {
        font_key: b"F1".to_vec(),
        font_size: 100.0,
        ..Default::default()
    };
    let mut prims = Vec::new();
    show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"A", 0);

    assert!(
        prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
        "the CharProc's own rectangle, drawn AFTER the inline image, must still \
         paint — one BI must not blank the whole glyph"
    );
    assert!(
        prims.iter().any(|p| matches!(p, Prim::Image { w: 2, h: 2, .. })),
        "the inline image inside the CharProc must decode"
    );
}

/// §9.6.5 + §8.5.4: the per-glyph primitive bound must cut only where the glyph's
/// own brackets are CLOSED — a blind `truncate` could sever a ClipPush from its
/// ClipPop and unbalance the renderer's save/restore stack for the whole rest of
/// the page. But "cut at a balanced point" must not degenerate into "drop the
/// glyph": a CharProc that wraps ALL its drawing in one `q … W n … Q` (the natural
/// way to bound a glyph) has NO balanced point before the cap, so searching
/// backwards for one finds only the start and the glyph vanishes entirely.
///
/// The contract that satisfies both: keep the capped prims and CLOSE the brackets
/// left open, rather than discarding everything back to the last balanced point.
#[test]
fn type3_per_glyph_cap_keeps_the_glyph_and_stays_balanced() {
    let mut doc = Document::with_version("1.5");
    let mut proc_src = String::from("q\n0 0 700 700 re W n\n");
    // Well past MAX_TYPE3_PRIMS_PER_GLYPH, so the cap lands inside the clip — and
    // the clip closes only at the very end, so there is no earlier balanced point.
    let wanted = MAX_TYPE3_PRIMS_PER_GLYPH * 3;
    for i in 0..wanted {
        let y = (i % 600) as i64;
        proc_src.push_str(&format!("0 {y} 10 10 re f\n"));
    }
    proc_src.push_str("Q\n");
    let proc_id = doc.add_object(Stream::new(dictionary! {}, proc_src.into_bytes()));
    let char_procs = doc.add_object(dictionary! { "a" => proc_id });
    let encoding = doc.add_object(dictionary! {
        "Type" => "Encoding",
        "Differences" => vec![65.into(), "a".into()],
    });
    let font = dictionary! {
        "Type" => "Font", "Subtype" => "Type3",
        "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
        "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
        "CharProcs" => char_procs, "Encoding" => encoding,
        "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
        "Resources" => dictionary! {},
    };
    let mut fonts = HashMap::new();
    fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
    let gs = GraphicsState {
        font_key: b"F1".to_vec(),
        font_size: 100.0,
        ..Default::default()
    };
    let mut prims = Vec::new();
    // Three glyphs, so a mis-cut on the first corrupts the two that follow.
    show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"AAA", 0);

    let fills = count(&prims, |p| matches!(p, Prim::Fill { .. }));
    println!(
        "type3 per-glyph cap: {} prims, {fills} fills for 3 glyphs \
         (MAX_TYPE3_PRIMS_PER_GLYPH={MAX_TYPE3_PRIMS_PER_GLYPH})",
        prims.len()
    );
    assert!(
        fills > 0,
        "the glyph vanished entirely. The per-glyph cut searched backwards for a \
         balanced point and found none, because this CharProc's clip closes only at \
         the very end — so it discarded all {wanted} prims instead of the surplus. \
         Cut at the cap and append the closers for the brackets still open."
    );
    assert!(
        fills < wanted * 3,
        "the per-glyph bound must actually bind, or this proves nothing"
    );
    let mut clip = 0i32;
    let mut group = 0i32;
    for (i, p) in prims.iter().enumerate() {
        match p {
            Prim::ClipPush { .. } => clip += 1,
            Prim::ClipPop => {
                clip -= 1;
                assert!(
                    clip >= 0,
                    "prim {i}: a ClipPop with no matching ClipPush — the per-glyph cut \
                     severed a bracket and the canvas will over-restore"
                );
            }
            Prim::GroupPush { .. } => group += 1,
            Prim::GroupPop => {
                group -= 1;
                assert!(group >= 0, "prim {i}: unmatched GroupPop");
            }
            _ => {}
        }
    }
    assert_eq!(
        clip, 0,
        "{clip} clip level(s) left open by the per-glyph cut — everything after this \
         glyph on the page is clipped away"
    );
    assert_eq!(group, 0, "{group} group level(s) left open by the per-glyph cut");
}
