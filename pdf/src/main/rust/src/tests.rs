use crate::*;
use lopdf::content::{Content, Operation};
use lopdf::{dictionary, Object, Stream};

/// Build a one-page PDF in memory with a filled rectangle and one text run,
/// then check the interpreted page size and primitives.
#[test]
fn interprets_rect_and_text() {
    let mut doc = Document::with_version("1.5");

    let content = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
            Operation::new("re", vec![100.into(), 100.into(), 50.into(), 40.into()]),
            Operation::new("f", vec![]),
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
            Operation::new("Td", vec![72.into(), 700.into()]),
            Operation::new("Tj", vec![Object::string_literal("Hi")]),
            Operation::new("ET", vec![]),
        ],
    };
    let content_data = content.encode().unwrap();
    let content_id = doc.add_object(Stream::new(dictionary! {}, content_data));

    let font_id = doc.add_object(dictionary! {
        "Type" => "Font",
        "Subtype" => "Type1",
        "BaseFont" => "Helvetica",
    });
    let resources = dictionary! {
        "Font" => dictionary! { "F1" => font_id },
    };

    let pages_id = doc.new_object_id();
    let page_id = doc.add_object(dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => content_id,
        "Resources" => resources,
    });
    let pages = dictionary! {
        "Type" => "Pages",
        "Kids" => vec![page_id.into()],
        "Count" => 1,
    };
    doc.objects.insert(pages_id, Object::Dictionary(pages));
    let catalog_id = doc.add_object(dictionary! {
        "Type" => "Catalog",
        "Pages" => pages_id,
    });
    doc.trailer.set("Root", catalog_id);

    let page = interpret_page(&doc, page_id).expect("interpret should succeed");
    assert_eq!(page.width, 612.0);
    assert_eq!(page.height, 792.0);

    let fills: Vec<&Prim> = page
        .prims
        .iter()
        .filter(|p| matches!(p, Prim::Fill { .. }))
        .collect();
    assert_eq!(fills.len(), 1, "expected one filled rectangle");
    if let Prim::Fill { argb, contours, .. } = fills[0] {
        assert_eq!(*argb, 0xFFFF0000, "fill should be red");
        assert_eq!(contours.len(), 1, "rectangle is a single contour");
        let pts = &contours[0];
        assert!(pts.len() >= 4, "rectangle should have >=4 points");
        assert_eq!(pts[0], (100.0, 100.0));
    }

    let texts: Vec<&Prim> = page
        .prims
        .iter()
        .filter(|p| matches!(p, Prim::Text { .. }))
        .collect();
    // Per-glyph emission: "Hi" -> two glyph primitives.
    assert_eq!(texts.len(), 2, "expected two glyph runs for \"Hi\"");
    if let Prim::Text { x, y, size, text, .. } = texts[0] {
        assert_eq!(text, "H");
        assert_eq!(*x, 72.0);
        assert_eq!(*y, 700.0);
        assert_eq!(*size, 12.0);
    }
    if let Prim::Text { text, .. } = texts[1] {
        assert_eq!(text, "i");
    }
}

/// A transparency-group form painted under ca=0.05 must composite the group
/// at alpha 0.05 (not ca*CA = 0.0025), and elements inside the group keep
/// full alpha — the group alpha is applied once, at composite time. This is
/// the "semi-transparent circles vanished" regression.
#[test]
fn transparency_group_alpha_applied_once() {
    let mut doc = Document::with_version("1.7");

    let form_content = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
            Operation::new("re", vec![0.into(), 0.into(), 100.into(), 100.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject",
            "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            "Group" => dictionary! { "Type" => "Group", "S" => "Transparency", "I" => true },
        },
        form_content.encode().unwrap(),
    ));
    let egs_id = doc.add_object(dictionary! { "Type" => "ExtGState", "ca" => 0.05, "CA" => 0.05 });
    let content = Content {
        operations: vec![
            Operation::new("gs", vec![Object::Name(b"GS0".to_vec())]),
            Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())]),
        ],
    };
    let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
    let resources = dictionary! {
        "ExtGState" => dictionary! { "GS0" => egs_id },
        "XObject" => dictionary! { "Fm0" => form_id },
    };
    let pages_id = doc.new_object_id();
    let page_id = doc.add_object(dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        "Contents" => content_id,
        "Resources" => resources,
    });
    doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
        "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
    }));
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);

    let page = interpret_page(&doc, page_id).expect("interpret");

    let group_alpha = page.prims.iter().find_map(|p| match p {
        Prim::GroupPush { alpha, .. } => Some(*alpha),
        _ => None,
    }).expect("a transparency GroupPush should be emitted");
    assert!((group_alpha - 0.05).abs() < 1e-4, "group alpha should be ca=0.05, got {group_alpha}");

    let fill_alpha = page.prims.iter().find_map(|p| match p {
        Prim::Fill { argb, .. } => Some((argb >> 24) & 0xFF),
        _ => None,
    }).expect("a fill should be emitted inside the group");
    assert_eq!(fill_alpha, 0xFF, "inner fill keeps full alpha (0.05 applied once via the group)");
}

/// A path with an inner subpath (a hole, e.g. a glyph counter) must emit a
/// SINGLE fill primitive carrying both contours, so the winding rule cuts the
/// hole out instead of filling it in as a second solid polygon.
#[test]
fn fill_with_hole_is_single_multicontour_prim() {
    let mut prims: Vec<Prim> = Vec::new();
    let outer = vec![(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0)];
    let hole = vec![(3.0, 3.0), (7.0, 3.0), (7.0, 7.0), (3.0, 7.0)];
    emit_fill(&mut prims, &[outer, hole], 0xFF000000, false, 1.0, BlendMode::Normal);
    assert_eq!(prims.len(), 1, "a path with a hole is one fill primitive");
    match &prims[0] {
        Prim::Fill { contours, .. } => {
            assert_eq!(contours.len(), 2, "outer + hole contours preserved");
        }
        _ => panic!("expected a Fill primitive"),
    }
}

/// Two consecutive `Tj` runs on one line must not stack at the same x: the
/// second run is offset by the first run's glyph-width advance.
#[test]
fn text_advances_by_glyph_widths() {
    let doc = Document::with_version("1.5");
    let fi = FontInfo {
        two_byte: false,
        wmode: 0,
        vertical_metrics: std::sync::Arc::default(),
        default_vertical: (0.0, -1000.0),
        cid_to_gid: None,
        to_unicode: None,
        encoding: std::sync::Arc::default(),
        cmap_uni: std::sync::Arc::default(),
        cmap: None,
        // 'A' (0x41) and 'B' (0x42) each 500 glyph units => 0.5.
        widths: std::sync::Arc::new(HashMap::from([(0x41, 0.5), (0x42, 0.5)])),
        default_width: 0.5,
        t3: None,
        style: FontStyle::default(),
        family: 0,
        base_font: String::new(),
        glyph_program: None,
        glyph_names: std::sync::Arc::default(),
    };
    let mut fonts = HashMap::new();
    fonts.insert(b"F1".to_vec(), fi);

    let gs = GraphicsState {
        font_key: b"F1".to_vec(),
        font_size: 10.0,
        ..Default::default()
    };

    let mut prims = Vec::new();
    let mut tm = translate(0.0, 100.0);

    let adv1 = show_string(&doc, &mut prims, &gs, &fonts, &tm, b"AB", 0);
    tm = mat_mul(&translate(adv1, 0.0), &tm);
    let _adv2 = show_string(&doc, &mut prims, &gs, &fonts, &tm, b"AB", 0);

    // Per-glyph emission: run "AB" -> 2 prims; advance = 2*0.5*10 = 10.
    assert!((adv1 - 10.0).abs() < 1e-6, "advance was {adv1}");
    let xs: Vec<f32> = prims
        .iter()
        .filter_map(|p| match p {
            Prim::Text { x, .. } => Some(*x),
            _ => None,
        })
        .collect();
    assert_eq!(xs.len(), 4, "expected 4 glyphs across 2 runs");
    assert_eq!(xs[0], 0.0); // first 'A'
    assert_eq!(xs[1], 5.0); // 'B' advanced by 0.5*10
    assert!((xs[2] - 10.0).abs() < 1e-4, "second run 'A' x was {}", xs[2]);
}

/// §9.3.6: render mode 3 paints nothing, but the glyphs must still reach the
/// text index. The name of this test used to be `invisible_text_not_emitted`,
/// which described the OBSOLETE contract — dropping the glyphs entirely — and is
/// exactly why the old assertion (`prims.is_empty()`) was wrong. Renamed so a
/// future reader does not "restore" it: a scanned page's OCR layer is drawn in
/// mode 3, and discarding it is why such documents had no selectable text.
#[test]
fn mode3_text_emits_no_ink_but_stays_searchable() {
    let doc = Document::with_version("1.5");
    let fi = FontInfo {
        two_byte: false,
        wmode: 0,
        vertical_metrics: std::sync::Arc::default(),
        default_vertical: (0.0, -1000.0),
        cid_to_gid: None,
        to_unicode: None,
        encoding: std::sync::Arc::default(),
        cmap_uni: std::sync::Arc::default(),
        cmap: None,
        widths: std::sync::Arc::default(),
        default_width: 0.5,
        t3: None,
        style: FontStyle::default(),
        family: 0,
        base_font: String::new(),
        glyph_program: None,
        glyph_names: std::sync::Arc::default(),
    };
    let mut fonts = HashMap::new();
    fonts.insert(b"F1".to_vec(), fi);
    let gs = GraphicsState {
        font_key: b"F1".to_vec(),
        font_size: 10.0,
        render_mode: 3,
        ..Default::default()
    };
    let mut prims = Vec::new();
    let adv = show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"hidden", 0);
    assert!(adv > 0.0);
    // Tr 3 paints nothing (§9.3.6), but the glyphs must still reach the text index:
    // a scanned page's OCR layer is drawn in mode 3, and dropping it is why such
    // documents had no selectable or searchable text. So a Text record IS expected
    // here — what must hold is that it carries nothing paintable.
    //
    // Two independent mechanisms keep it invisible, and the assertion below pins
    // the Rust half of both: the record declares Tr 3, and its colour is fully
    // transparent. On the Kotlin side it is the render-mode guard
    // (`if (rm != 3 && rm != 7)`, SafePdfViewerScreen.kt:2885) that suppresses the
    // paint — mode 3 never reaches the fill/stroke calls at all. The `rm == 1 ||
    // rm == 5` test at :2855 is a different mechanism (it suppresses the FILL for
    // stroke-only modes) and does not apply to mode 3.
    let non_conforming = prims
        .iter()
        .filter(|p| !matches!(p, Prim::Text { render_mode: 3, argb: 0, .. }))
        .count();
    assert_eq!(
        non_conforming,
        0,
        "mode-3 must emit only non-painting, fully transparent Text records; \
         {non_conforming} of {} prims violate that",
        prims.len()
    );
    let recovered: String = prims
        .iter()
        .filter_map(|p| match p {
            Prim::Text { text, .. } => Some(text.as_str()),
            _ => None,
        })
        .collect();
    assert_eq!(recovered, "hidden", "mode-3 text must be recoverable for search");
}

/// A Type 3 glyph whose CharProc fills a rectangle must emit Fill prims.
#[test]
fn type3_glyph_emits_prims() {
    let mut doc = Document::with_version("1.5");
    // CharProc: paint a filled rectangle in glyph space.
    let proc_content = Content {
        operations: vec![
            Operation::new("re", vec![0.into(), 0.into(), 700.into(), 700.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let proc_data = proc_content.encode().unwrap();
    let proc_id = doc.add_object(Stream::new(dictionary! {}, proc_data));
    let char_procs = doc.add_object(dictionary! { "a" => proc_id });
    let encoding = doc.add_object(dictionary! {
        "Type" => "Encoding",
        "Differences" => vec![65.into(), "a".into()],
    });
    let font = dictionary! {
        "Type" => "Font",
        "Subtype" => "Type3",
        "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
        "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
        "CharProcs" => char_procs,
        "Encoding" => encoding,
        "FirstChar" => 65,
        "LastChar" => 65,
        "Widths" => vec![700.into()],
        "Resources" => dictionary! {},
    };
    let fi = font_info(&doc, &font);
    assert!(fi.t3.is_some(), "should parse as Type3");
    let mut fonts = HashMap::new();
    fonts.insert(b"F1".to_vec(), fi);
    let gs = GraphicsState {
        font_key: b"F1".to_vec(),
        font_size: 12.0,
        ..Default::default()
    };
    let mut prims = Vec::new();
    let adv = show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"A", 0);
    assert!(adv > 0.0, "advance should be positive");
    let fills = prims.iter().filter(|p| matches!(p, Prim::Fill { .. })).count();
    assert!(fills >= 1, "type3 glyph should emit at least one Fill prim");
}

/// Type 3 render mode 3 must paint nothing yet still emit the non-painting Text
/// record — a scan's OCR layer can be set in a Type 3 font (item 3).
#[test]
fn type3_mode3_emits_invisible_text_only() {
    let mut doc = Document::with_version("1.5");
    let proc_content = Content {
        operations: vec![
            Operation::new("re", vec![0.into(), 0.into(), 700.into(), 700.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let proc_data = proc_content.encode().unwrap();
    let proc_id = doc.add_object(Stream::new(dictionary! {}, proc_data));
    let char_procs = doc.add_object(dictionary! { "a" => proc_id });
    let encoding = doc.add_object(dictionary! {
        "Type" => "Encoding",
        "Differences" => vec![65.into(), "a".into()],
    });
    let font = dictionary! {
        "Type" => "Font",
        "Subtype" => "Type3",
        "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
        "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
        "CharProcs" => char_procs,
        "Encoding" => encoding,
        "FirstChar" => 65,
        "LastChar" => 65,
        "Widths" => vec![700.into()],
        "Resources" => dictionary! {},
    };
    let mut fonts = HashMap::new();
    fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
    let gs = GraphicsState {
        font_key: b"F1".to_vec(),
        font_size: 12.0,
        render_mode: 3,
        ..Default::default()
    };
    let mut prims = Vec::new();
    let adv = show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"A", 0);
    assert!(adv > 0.0, "mode 3 still advances the pen");
    // The CharProc must not be interpreted: no ink of any kind.
    let non_conforming = prims
        .iter()
        .filter(|p| !matches!(p, Prim::Text { render_mode: 3, argb: 0, .. }))
        .count();
    assert_eq!(
        non_conforming, 0,
        "Type 3 mode 3 must emit only non-painting transparent Text; \
         {non_conforming} of {} prims violate that",
        prims.len()
    );
    let texts: Vec<&str> = prims
        .iter()
        .filter_map(|p| match p {
            Prim::Text { text, .. } => Some(text.as_str()),
            _ => None,
        })
        .collect();
    assert!(
        !texts.is_empty() && texts.iter().all(|t| !t.is_empty()),
        "Type 3 mode 3 glyph must still reach the text index"
    );
}

/// The no-font-metrics fallback path must also carry mode-3 text (item 3).
#[test]
fn no_metrics_mode3_emits_invisible_text() {
    let doc = Document::with_version("1.5");
    let fonts: HashMap<Vec<u8>, FontInfo> = HashMap::new();
    let gs = GraphicsState {
        font_key: b"Missing".to_vec(),
        font_size: 10.0,
        render_mode: 3,
        ..Default::default()
    };
    let mut prims = Vec::new();
    let adv = show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"ocr", 0);
    assert!(adv > 0.0);
    let non_conforming = prims
        .iter()
        .filter(|p| !matches!(p, Prim::Text { render_mode: 3, argb: 0, .. }))
        .count();
    assert_eq!(non_conforming, 0, "no-metrics mode 3 must not paint");
    let recovered: String = prims
        .iter()
        .filter_map(|p| match p {
            Prim::Text { text, .. } => Some(text.as_str()),
            _ => None,
        })
        .collect();
    assert_eq!(recovered, "ocr");
}

include!("tests_part1.rs");