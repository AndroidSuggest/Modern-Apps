/// Self-check on the mechanism ~30 assertions in this file depend on. A shape can
/// COVER a region without having any vertex inside it, so vertex sampling reports
/// "no ink" for a page-sized fill queried at a small interior rectangle. For a
/// `!ink_in_region(...)` assertion that is a false negative — a vacuous pass, the
/// exact failure this whole module exists to eliminate. `ink_in_region` must
/// therefore be area-based; do not revert it to point containment.
#[test]
fn ink_in_region_detects_a_shape_that_covers_without_a_vertex_inside() {
    let mut doc = Document::with_version("1.5");
    // One fill spanning the whole page: no vertex anywhere near the middle.
    let ops = vec![
        Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
        Operation::new("re", vec![0.into(), 0.into(), 612.into(), 792.into()]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! {});
    let page = interpret_page(&doc, page_id).expect("interpret");

    assert!(
        ink_in_region(&page.prims, 300.0, 400.0, 310.0, 410.0),
        "a page-covering fill must register as ink in a small interior region, even \
         though none of its vertices lie inside it"
    );
    assert!(
        !ink_in_region(&page.prims, 700.0, 900.0, 750.0, 950.0),
        "and must NOT register outside its own extent, or every negative assertion \
         in this file becomes unfalsifiable"
    );
}


/// §9.3.6: `3 Tr` neither fills nor strokes. End-to-end guard on top of the
/// `show_string`-level ones in `tests.rs`: a whole page whose only content is a
/// mode-3 show-text must put NO ink down, while the glyphs still reach the text
/// index so a scanned page's OCR layer stays searchable. If mode 3 ever paints,
/// every scanned PDF overprints its own OCR layer.
#[test]
fn mode3_page_is_searchable_but_emits_no_ink() {
    let mut doc = Document::with_version("1.5");
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
    });
    let ops = vec![
        Operation::new("BT", vec![]),
        Operation::new("Tr", vec![3.into()]),
        Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
        Operation::new("Td", vec![72.into(), 700.into()]),
        Operation::new("Tj", vec![Object::string_literal("Scan")]),
        Operation::new("ET", vec![]),
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! { "Font" => dictionary! { "F1" => font_id } });

    let page = interpret_page(&doc, page_id).expect("interpret");
    let inked = count(&page.prims, is_ink);
    assert_eq!(inked, 0, "a mode-3 page must emit no visible ink, got {inked} inking prims");
    assert_eq!(text_of(&page.prims), "Scan", "mode-3 glyphs must stay searchable");
    for p in &page.prims {
        if let Prim::Text { render_mode, argb, .. } = p {
            assert_eq!(*render_mode, 3, "the Text record must declare Tr 3 for the Kotlin paint guard");
            assert_eq!(*argb, 0, "and be fully transparent as a second, independent guard");
        }
    }
}

// ===========================================================================
// 3. Page robustness — §7.7.3.3

fn annot_square(doc: &mut Document) -> ObjectId {
    doc.add_object(dictionary! {
        "Type" => "Annot", "Subtype" => "Square",
        "Rect" => vec![100.into(), 100.into(), 200.into(), 160.into()],
        "IC" => vec![1.0.into(), 0.0.into(), 0.0.into()],
        "C" => vec![0.0.into(), 0.0.into(), 0.0.into()],
    })
}

/// §7.7.3.3: `/Contents` is optional. A page without it is still a page — right
/// size, and its annotations still render. It must not become a lost page.
#[test]
fn page_without_contents_keeps_its_size_and_renders_annotations() {
    let mut doc = Document::with_version("1.5");
    let square = annot_square(&mut doc);
    let mut page = dictionary! {
        "MediaBox" => vec![0.into(), 0.into(), 300.into(), 400.into()],
        "Annots" => vec![square.into()],
    };
    let page_id = assemble_with_contents(&mut doc, Object::Null, dictionary! {}, &mut page, dictionary! {});

    let page = interpret_page(&doc, page_id).expect("a page with no /Contents must still interpret");
    assert_eq!((page.width, page.height), (300.0, 400.0), "size comes from /MediaBox, not from content");
    assert!(
        ink_in_region(&page.prims, 100.0, 100.0, 201.0, 161.0),
        "the annotation must still render on a page with no /Contents"
    );
}

/// §7.7.3.3: a content stream the tokenizer cannot parse must not lose the page.
/// The size must still be right and the annotations must still render.
#[test]
fn page_with_corrupt_content_stream_keeps_its_size_and_renders_annotations() {
    let mut doc = Document::with_version("1.5");
    let square = annot_square(&mut doc);
    // Deliberately unparseable: an unterminated string and stray delimiters.
    let corrupt = b"q 1 0 0 1 0 0 cm >> ] ) BT /F1 (unterminated".to_vec();
    let mut page = dictionary! {
        "MediaBox" => vec![0.into(), 0.into(), 300.into(), 400.into()],
        "Annots" => vec![square.into()],
    };
    let cid = doc.add_object(Stream::new(dictionary! {}, corrupt));
    let page_id = assemble_with_contents(
        &mut doc,
        Object::Reference(cid),
        dictionary! {},
        &mut page,
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("a corrupt content stream must not lose the page");
    assert_eq!((page.width, page.height), (300.0, 400.0));
    assert!(
        ink_in_region(&page.prims, 100.0, 100.0, 201.0, 161.0),
        "annotations must survive a content stream that fails to tokenize"
    );
}

// ===========================================================================
// 4. Clipping — §8.5.4

fn clips(prims: &[Prim]) -> Vec<(bool, Vec<(f32, f32)>)> {
    prims
        .iter()
        .filter_map(|p| match p {
            Prim::ClipPush { even_odd, pts, .. } => Some((*even_odd, pts.clone())),
            _ => None,
        })
        .collect()
}

fn bbox_of(pts: &[(f32, f32)]) -> [f32; 4] {
    let mut b = [f32::MAX, f32::MAX, f32::MIN, f32::MIN];
    for &(x, y) in pts {
        b[0] = b[0].min(x);
        b[1] = b[1].min(y);
        b[2] = b[2].max(x);
        b[3] = b[3].max(y);
    }
    b
}

fn axial_shading(doc: &mut Document) -> ObjectId {
    let func_id = doc.add_object(dictionary! {
        "FunctionType" => 2,
        "Domain" => vec![0.into(), 1.into()],
        "C0" => vec![1.0.into(), 0.0.into(), 0.0.into()],
        "C1" => vec![0.0.into(), 0.0.into(), 1.0.into()],
        "N" => 1,
    });
    doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![0.into(), 0.into(), 400.into(), 0.into()],
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
        "Function" => func_id,
    })
}

/// §8.5.4: `W n` intersects with the clip already in force — it does not replace
/// it. Asserted on meaning: an `sh` painted under two nested clips is confined
/// to their intersection, so its device extent is the inner 100x100 box and not
/// the outer 400x500 one.
#[test]
fn nested_w_n_clips_intersect_and_confine_a_shading() {
    let mut doc = Document::with_version("1.5");
    let sh_id = axial_shading(&mut doc);
    let ops = vec![
        Operation::new("re", vec![0.into(), 0.into(), 400.into(), 500.into()]),
        Operation::new("W", vec![]),
        Operation::new("n", vec![]),
        Operation::new("re", vec![100.into(), 100.into(), 100.into(), 100.into()]),
        Operation::new("W", vec![]),
        Operation::new("n", vec![]),
        Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())]),
    ];
    let bytes = Content { operations: ops }.encode().unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! { "Shading" => dictionary! { "Sh0" => sh_id } },
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 400.into(), 500.into()] },
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert_eq!(clips(&page.prims).len(), 2, "each W n commits one clip");
    let ctm = page
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Image { ctm, .. } => Some(*ctm),
            _ => None,
        })
        .expect("the shading must be painted");
    let w = ctm[0].abs() + ctm[2].abs();
    let h = ctm[1].abs() + ctm[3].abs();
    assert!(
        w < 200.0 && h < 200.0,
        "the shading must be confined to the intersected clip, got {w:.0}x{h:.0} \
         (the outer clip alone would be 400x500)"
    );
}

/// §8.5.4: `W` establishes a clip but does not paint; the painting operator that
/// ends the path still runs. So `re W f` must BOTH fill the rectangle AND clip.
#[test]
fn w_followed_by_fill_both_fills_and_clips() {
    let mut doc = Document::with_version("1.5");
    let ops = vec![
        Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
        Operation::new("re", vec![10.into(), 10.into(), 50.into(), 50.into()]),
        Operation::new("W", vec![]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! {});

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        ink_in_region(&page.prims, 10.0, 10.0, 61.0, 61.0),
        "`W f` must still fill the path"
    );
    assert_eq!(clips(&page.prims).len(), 1, "`W f` must also commit the clip");
}

/// §8.5.4 / Table 60: `W` uses the nonzero winding rule and `W*` the even-odd
/// rule. The chosen rule has to reach the clip primitive, or a path with a hole
/// clips to the wrong region.
#[test]
fn w_and_w_star_record_different_winding_rules() {
    for (op, expect_even_odd) in [("W", false), ("W*", true)] {
        let mut doc = Document::with_version("1.5");
        let ops = vec![
            Operation::new("re", vec![0.into(), 0.into(), 100.into(), 100.into()]),
            Operation::new(op, vec![]),
            Operation::new("n", vec![]),
        ];
        let page_id = page_from_ops(&mut doc, ops, dictionary! {});
        let page = interpret_page(&doc, page_id).expect("interpret");
        let cl = clips(&page.prims);
        assert_eq!(cl.len(), 1, "{op} n commits exactly one clip");
        assert_eq!(cl[0].0, expect_even_odd, "{op} must record even_odd={expect_even_odd}");
    }
}

/// §8.4.4: `Q` restores the clip that was in force at the matching `q`, and a
/// clip that was still only *pending* at that point is discarded with it. The
/// round-1 bug let the pending `W` survive the `Q` and clip the whole rest of
/// the page — so the second rectangle here vanished.
#[test]
fn pending_clip_does_not_survive_the_matching_q_restore() {
    let mut doc = Document::with_version("1.5");
    let ops = vec![
        Operation::new("q", vec![]),
        Operation::new("re", vec![0.into(), 0.into(), 10.into(), 10.into()]),
        Operation::new("W", vec![]), // pending, never committed by a path-painting op
        Operation::new("Q", vec![]),
        Operation::new("rg", vec![0.0.into(), 1.0.into(), 0.0.into()]),
        Operation::new("re", vec![200.into(), 200.into(), 100.into(), 100.into()]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(&mut doc, ops, dictionary! {});

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert_eq!(
        clips(&page.prims).len(),
        0,
        "a `W` that was never committed must be dropped by `Q`, not applied afterwards"
    );
    assert!(
        ink_in_region(&page.prims, 200.0, 200.0, 301.0, 301.0),
        "the rectangle painted after `Q` must survive"
    );
}

// ===========================================================================
// 5. Annotation appearances — §12.5.5

/// §8.10.2: a form XObject's `/BBox` clip is mandatory. An `/AP /N` stream whose
/// content draws far outside its BBox must be clipped to it, so the clip has to
/// be emitted (mapped onto the annotation `/Rect`) around the appearance.
#[test]
fn ap_form_drawing_outside_its_bbox_is_clipped_to_it() {
    let mut doc = Document::with_version("1.7");
    // BBox is 10x10 but the content fills 1000x1000 centred well outside it.
    let ap_content = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
            Operation::new("re", vec![(-500).into(), (-500).into(), 1000.into(), 1000.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let ap_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
        },
        ap_content.encode().unwrap(),
    ));
    let annot = doc.add_object(dictionary! {
        "Type" => "Annot", "Subtype" => "Stamp",
        "Rect" => vec![100.into(), 100.into(), 200.into(), 200.into()],
        "AP" => dictionary! { "N" => ap_id },
    });
    let mut page = dictionary! { "Annots" => vec![annot.into()] };
    let page_id = assemble_with_contents(
        &mut doc,
        Object::Null,
        dictionary! {},
        &mut page,
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let push = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::ClipPush { .. }))
        .expect("§8.10.2: the /BBox clip is mandatory and must be emitted");
    let fill = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::Fill { .. }))
        .expect("the appearance content must be drawn");
    let pop = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::ClipPop))
        .expect("the BBox clip must be popped again");
    assert!(push < fill && fill < pop, "the appearance must be drawn inside the BBox clip");

    let b = bbox_of(&clips(&page.prims)[0].1);
    for (got, want, name) in [(b[0], 100.0, "x0"), (b[1], 100.0, "y0"), (b[2], 200.0, "x1"), (b[3], 200.0, "y1")] {
        assert!(
            (got - want as f32).abs() < 0.5,
            "the BBox clip must map onto the annotation /Rect; {name} was {got}, expected {want}"
        );
    }
}

/// §12.5.6.10: `/QuadPoints` are stored UL, UR, LL, LR — a "Z", not a ring.
/// Consuming them in file order traces a self-intersecting bow-tie whose area
/// collapses to nearly nothing, which is why highlights rendered as two thin
/// triangles. Assert the filled quad really is the rectangle.
#[test]
fn highlight_quadpoints_in_spec_order_fill_a_rectangle_not_a_bowtie() {
    let mut doc = Document::with_version("1.7");
    // 100 wide x 30 tall, given in the spec's UL, UR, LL, LR order.
    let quad = vec![
        100.into(), 130.into(), // UL
        200.into(), 130.into(), // UR
        100.into(), 100.into(), // LL
        200.into(), 100.into(), // LR
    ];
    let annot = doc.add_object(dictionary! {
        "Type" => "Annot", "Subtype" => "Highlight",
        "Rect" => vec![100.into(), 100.into(), 200.into(), 130.into()],
        "QuadPoints" => quad,
        "C" => vec![1.0.into(), 1.0.into(), 0.0.into()],
    });
    let mut page = dictionary! { "Annots" => vec![annot.into()] };
    let page_id = assemble_with_contents(&mut doc, Object::Null, dictionary! {}, &mut page, dictionary! {});

    let page = interpret_page(&doc, page_id).expect("interpret");
    let contour = page
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Fill { contours, .. } => contours.first().cloned(),
            _ => None,
        })
        .expect("the highlight must be filled");
    let area = polygon_area(&contour);
    assert!(
        (area - 3000.0).abs() < 1.0,
        "the quad must enclose the full 100x30 = 3000 area; got {area:.1} \
         (a bow-tie from file-order vertices collapses to ~0)"
    );
}

/// §12.5.3 Table 165: only Hidden (bit 2, value 2) and NoView (bit 6, value 32)
/// suppress on-screen display. Print, NoZoom, NoRotate and friends must not.
#[test]
fn only_hidden_and_noview_flags_suppress_an_annotation() {
    // (flag value, must be visible on screen)
    let cases = [
        (0i64, true),
        (2, false),   // Hidden
        (4, true),    // Print
        (8, true),    // NoZoom
        (16, true),   // NoRotate
        (32, false),  // NoView
        (36, false),  // Print | NoView
        (64, true),   // ReadOnly
        (128, true),  // Locked
    ];
    for (flags, expect_visible) in cases {
        let mut doc = Document::with_version("1.7");
        let annot = doc.add_object(dictionary! {
            "Type" => "Annot", "Subtype" => "Square",
            "Rect" => vec![100.into(), 100.into(), 200.into(), 160.into()],
            "IC" => vec![1.0.into(), 0.0.into(), 0.0.into()],
            "F" => flags,
        });
        let mut page = dictionary! { "Annots" => vec![annot.into()] };
        let page_id = assemble_with_contents(&mut doc, Object::Null, dictionary! {}, &mut page, dictionary! {});
        let page = interpret_page(&doc, page_id).expect("interpret");
        let visible = ink_in_region(&page.prims, 100.0, 100.0, 201.0, 161.0);
        assert_eq!(
            visible, expect_visible,
            "/F {flags}: expected on-screen visible={expect_visible}, got {visible}"
        );
    }
}

// ===========================================================================
// 6. Page geometry — §7.7.3.3, §14.11.2

/// Every point a primitive touches, ink or not — used to prove content landed on
/// the canvas rather than off it.
fn all_points(prims: &[Prim]) -> Vec<(f32, f32)> {
    let mut out = Vec::new();
    for p in prims {
        match p {
            Prim::Fill { contours, .. } => out.extend(contours.iter().flatten().copied()),
            Prim::Stroke { pts, .. } => out.extend(pts.iter().copied()),
            Prim::Text { x, y, .. } => out.push((*x, *y)),
            _ => {}
        }
    }
    out
}
