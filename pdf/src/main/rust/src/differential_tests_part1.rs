/// A page touching as many subsystems as one fixture reasonably can: a filled
/// path, a dashed stroke, text through a simple font, a Form XObject, an image
/// XObject, an axial shading, and an ExtGState luminosity soft mask.
///
/// Breadth is the point. A determinism check over a single filled rectangle
/// would pass even if the font cache were badly broken, so the fixture has to
/// reach the caches, the shading sampler and the mask bracketing code.
fn rich_page(doc: &mut Document) -> ObjectId {
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1",
        "BaseFont" => "Helvetica", "Encoding" => "WinAnsiEncoding",
    });

    let form_ops = Content {
        operations: vec![
            Operation::new("0 1 0 rg", vec![]),
            Operation::new("re", vec![0.into(), 0.into(), 20.into(), 20.into()]),
            Operation::new("f", vec![]),
        ],
    }
    .encode()
    .unwrap();
    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 20.into(), 20.into()],
        },
        form_ops,
    ));

    // 2x2 DeviceRGB image.
    let img_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 2,
            "ColorSpace" => "DeviceRGB", "BitsPerComponent" => 8,
        },
        vec![255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 0],
    ));

    // Luminosity soft-mask group.
    let mask_ops = Content {
        operations: vec![
            Operation::new("0.5 g", vec![]),
            Operation::new("re", vec![0.into(), 0.into(), 100.into(), 100.into()]),
            Operation::new("f", vec![]),
        ],
    }
    .encode()
    .unwrap();
    let mask_group_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            "Group" => dictionary! {
                "S" => "Transparency", "CS" => "DeviceGray", "I" => true,
            },
        },
        mask_ops,
    ));
    let gs_id = doc.add_object(dictionary! {
        "Type" => "ExtGState",
        "SMask" => dictionary! { "S" => "Luminosity", "G" => mask_group_id },
    });

    let shading = dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![0.into(), 0.into(), 120.into(), 0.into()],
        "Function" => dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![1.into(), 0.into(), 0.into()],
            "C1" => vec![0.into(), 0.into(), 1.into()],
            "N" => 1,
        },
    };

    let resources = dictionary! {
        "Font" => dictionary! { "F1" => font_id },
        "XObject" => dictionary! { "Fm0" => form_id, "Im0" => img_id },
        "ExtGState" => dictionary! { "GS0" => gs_id },
        "Shading" => dictionary! { "Sh0" => shading },
    };

    let ops = vec![
        // Filled path.
        Operation::new("rg", vec![0.9.into(), 0.1.into(), 0.2.into()]),
        Operation::new("re", vec![10.into(), 10.into(), 60.into(), 40.into()]),
        Operation::new("f", vec![]),
        // Dashed stroke with a bezier.
        Operation::new("RG", vec![0.into(), 0.into(), 1.into()]),
        Operation::new("w", vec![3.into()]),
        Operation::new("d", vec![vec![4.into(), 2.into()].into(), 1.into()]),
        Operation::new("m", vec![10.into(), 120.into()]),
        Operation::new("c", vec![40.into(), 160.into(), 80.into(), 80.into(), 120.into(), 120.into()]),
        Operation::new("S", vec![]),
        // Text.
        Operation::new("BT", vec![]),
        Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 14.into()]),
        Operation::new("Td", vec![20.into(), 70.into()]),
        Operation::new("Tj", vec![Object::string_literal("Diff Wg")]),
        Operation::new("ET", vec![]),
        // Form XObject under a translation.
        Operation::new("q", vec![]),
        Operation::new("cm", vec![1.into(), 0.into(), 0.into(), 1.into(), 200.into(), 20.into()]),
        Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())]),
        Operation::new("Q", vec![]),
        // Image XObject.
        Operation::new("q", vec![]),
        Operation::new("cm", vec![40.into(), 0.into(), 0.into(), 40.into(), 220.into(), 90.into()]),
        Operation::new("Do", vec![Object::Name(b"Im0".to_vec())]),
        Operation::new("Q", vec![]),
        // Soft-masked shading inside a clip.
        Operation::new("q", vec![]),
        Operation::new("gs", vec![Object::Name(b"GS0".to_vec())]),
        Operation::new("re", vec![100.into(), 150.into(), 120.into(), 40.into()]),
        Operation::new("W", vec![]),
        Operation::new("n", vec![]),
        Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())]),
        Operation::new("Q", vec![]),
    ];

    let bytes = Content { operations: ops }.encode().unwrap();
    assemble(
        doc,
        bytes,
        resources,
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 300.into(), 200.into()] },
    )
}

// ---------------------------------------------------------------------------
// Invariant: rendering is a pure function of (document, page)

/// Rendering the same page repeatedly must produce byte-identical primitives.
///
/// This is the primary guard on the `FontCacheScope` thread-local in
/// `fonts.rs`: the cache is installed and torn down inside `interpret_page`, so
/// the second render must repopulate it from scratch and reach exactly the same
/// answer. A cache that returned a partly-initialised or mutated `FontInfo` on
/// a hit, or a sampler that accumulated state in a static, shows up here as a
/// divergence. Ten repeats rather than two so an alternating (even/odd) fault
/// cannot hide.
#[test]
fn same_page_rendered_repeatedly_is_identical() {
    let mut doc = Document::with_version("1.5");
    let page_id = rich_page(&mut doc);

    let baseline = fingerprint(&interpret_page(&doc, page_id).expect("render"));
    assert!(
        baseline.lines().count() > 8,
        "fixture must emit a substantial number of primitives, else this test is vacuous; got:\n{baseline}"
    );

    for i in 1..10 {
        let again = fingerprint(&interpret_page(&doc, page_id).expect("render"));
        assert_eq!(
            baseline,
            again,
            "render {i} diverged from render 0: {}",
            first_difference(&baseline, &again)
        );
    }
}

/// The same invariant one level up, through the handle API and the wire
/// serializer, so a non-determinism introduced by `wire::serialize` (map
/// iteration order, uninitialised padding) is caught too.
#[test]
fn wire_bytes_are_identical_across_renders() {
    let mut doc = Document::with_version("1.5");
    rich_page(&mut doc);
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();

    let handle = open_document(&bytes);
    assert_ne!(handle, 0, "fixture must open");
    let first = render_page(handle, 0).expect("render 0");
    assert!(first.len() > 64, "wire buffer suspiciously small: {}", first.len());
    for i in 1..5 {
        let again = render_page(handle, 0).expect("render again");
        assert_eq!(first.len(), again.len(), "wire length changed on render {i}");
        assert!(first == again, "wire bytes changed on render {i}");
    }
    close_document(handle);
}

/// Two independent `Document`s parsed from the same bytes must render
/// identically. Distinguishes a genuinely pure pipeline from one that happens
/// to be stable only because it keeps hitting the same warm cache: here the
/// second document has different object addresses and a cold cache, so any
/// dependence on either would surface.
#[test]
fn two_documents_from_the_same_bytes_agree() {
    let mut doc = Document::with_version("1.5");
    rich_page(&mut doc);
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();

    let a = load_document_lenient(&bytes).expect("load a");
    let b = load_document_lenient(&bytes).expect("load b");
    let pa = nth_page_id(&a, 0).expect("page a");
    let pb = nth_page_id(&b, 0).expect("page b");

    let fa = fingerprint(&interpret_page(&a, pa).expect("render a"));
    let fb = fingerprint(&interpret_page(&b, pb).expect("render b"));
    assert_eq!(fa, fb, "reparsed document differs: {}", first_difference(&fa, &fb));
}

// ---------------------------------------------------------------------------
// Invariant: page independence

/// A four-page document where every page has its OWN font object bound to the
/// SAME resource name `/F1`, and draws different text.
///
/// This is the adversarial shape for a font cache: if the cache key were the
/// resource name, or the page's resource dictionary, page 1 would be served
/// page 0's font. It is keyed on `(document address, font ObjectId)`
/// (`fonts.rs:220`), which should be immune — this fixture is what proves it
/// rather than assuming it.
fn distinct_font_per_page_doc() -> (Document, Vec<ObjectId>) {
    const FACES: [&str; 4] = ["Helvetica", "Courier", "Times-Roman", "Helvetica-Bold"];
    multi_page_doc(4, |doc, i| {
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1",
            "BaseFont" => FACES[i], "Encoding" => "WinAnsiEncoding",
        });
        let ops = vec![
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), (12 + i as i64).into()]),
            Operation::new("Td", vec![20.into(), (30 + 25 * i as i64).into()]),
            Operation::new("Tj", vec![Object::string_literal(format!("page{i} WAV"))]),
            Operation::new("ET", vec![]),
            Operation::new("rg", vec![(0.2 * i as f64).into(), 0.4.into(), 0.6.into()]),
            Operation::new("re", vec![(10 * i as i64).into(), 5.into(), 30.into(), 12.into()]),
            Operation::new("f", vec![]),
        ];
        (ops, dictionary! { "Font" => dictionary! { "F1" => font_id } })
    })
}

/// Rendering page N must not depend on which pages were rendered before it.
///
/// Checked against three orders: forward, reverse, and each page rendered in
/// isolation by a freshly reparsed document. The isolated pass is the real
/// control — it is the only one where no other page has ever been touched, so
/// agreement with it means "no page leaked into any other".
#[test]
fn page_rendering_is_independent_of_order() {
    let (mut doc, page_ids) = distinct_font_per_page_doc();
    let n = page_ids.len();

    let forward: Vec<String> = (0..n)
        .map(|i| fingerprint(&interpret_page(&doc, page_ids[i]).expect("fwd")))
        .collect();

    let mut reverse = vec![String::new(); n];
    for i in (0..n).rev() {
        reverse[i] = fingerprint(&interpret_page(&doc, page_ids[i]).expect("rev"));
    }

    // Isolated: a brand-new Document per page, so nothing else has been rendered.
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();
    let isolated: Vec<String> = (0..n)
        .map(|i| {
            let fresh = load_document_lenient(&bytes).expect("reload");
            let pid = nth_page_id(&fresh, i as i32).expect("page");
            fingerprint(&interpret_page(&fresh, pid).expect("iso"))
        })
        .collect();

    for i in 0..n {
        assert_eq!(
            forward[i], reverse[i],
            "page {i} depends on render order: {}",
            first_difference(&forward[i], &reverse[i])
        );
        assert_eq!(
            forward[i], isolated[i],
            "page {i} differs when rendered alone, i.e. another page leaked into it: {}",
            first_difference(&forward[i], &isolated[i])
        );
    }

    // Guard against a vacuous pass: the pages must actually differ from each
    // other, or "order does not matter" would be trivially true.
    for i in 1..n {
        assert_ne!(forward[0], forward[i], "fixture pages 0 and {i} are indistinguishable");
    }
}

/// The narrow form of the same property, stated the way a bug report would be:
/// render page 3 first, then render it again after rendering pages 0..3.
#[test]
fn rendering_a_page_first_or_last_agrees() {
    let (doc, page_ids) = distinct_font_per_page_doc();
    let last = *page_ids.last().unwrap();

    let alone = fingerprint(&interpret_page(&doc, last).expect("alone"));
    for pid in &page_ids[..page_ids.len() - 1] {
        let _ = interpret_page(&doc, *pid).expect("warm the caches");
    }
    let after = fingerprint(&interpret_page(&doc, last).expect("after"));
    assert_eq!(
        alone, after,
        "rendering earlier pages changed this page: {}",
        first_difference(&alone, &after)
    );
}

/// The text index is built for every page under ONE `FontCacheScope`
/// (`search.rs:49`), unlike rendering which scopes per page. Building it twice
/// must agree, and it must agree with a build from a reparsed document.
#[test]
fn text_index_is_reproducible() {
    let (mut doc, _) = distinct_font_per_page_doc();
    let first: Vec<String> = crate::search::build_index(&doc).iter().map(|p| p.text.clone()).collect();
    let second: Vec<String> = crate::search::build_index(&doc).iter().map(|p| p.text.clone()).collect();
    assert_eq!(first, second, "build_index is not deterministic");

    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();
    let fresh = load_document_lenient(&bytes).expect("reload");
    let third: Vec<String> = crate::search::build_index(&fresh).iter().map(|p| p.text.clone()).collect();
    assert_eq!(first, third, "build_index differs on a reparsed document");

    assert!(
        first.iter().any(|t| !t.trim().is_empty()),
        "fixture produced no indexed text, so this test would be vacuous: {first:?}"
    );
}

/// Directly probes the documented weak point of the font cache key.
///
/// The key is `(doc as *const Document as usize, font ObjectId)`
/// (`fonts.rs:220`), and `fonts.rs:169` argues that is safe because a
/// `FontCacheScope` is short-lived and never spans two documents. Address
/// reuse is normally hard to force, so this test removes the chance element:
/// it renders document A through a boxed pointer, then overwrites THAT SAME
/// BOX in place with document B. B is therefore guaranteed to live at the exact
/// address A did, with colliding object ids, while an outer scope is still
/// open.
///
/// If the cache leaks, B's `/F1` resolves to A's cached font and B renders with
/// the wrong face.
///
/// HISTORY. This test originally FAILED and was `#[ignore]`d as a documented
/// tripwire: the key was `(doc as *const Document as usize, font ObjectId)`, and
/// document B (Courier, monospaced) rendered with document A's Helvetica metrics
/// — advances 19.99/6.67/5.33 for `M`/space/`i`, which are 833/278/222 per mille
/// at 24pt, instead of Courier's uniform 14.4 — with `font_family` flipping from
/// 2 (mono) to 0 (sans). `residuals` then removed the raw pointer from the key
/// entirely: it is now the `ObjectId` alone, and a hit additionally requires the
/// stored font dictionary to still equal the one that id resolves to. The test
/// passes and is enabled.
///
/// It is kept because the reproduction is the cheap part and the guarantee is
/// worth pinning: an address is not a stable document identity, and nothing else
/// in the suite enforces that.
#[test]
fn font_cache_does_not_leak_between_documents_at_one_address() {
    // Two documents, same object numbering, deliberately different faces.
    let build = |face: &str, label: &str| {
        let mut doc = Document::with_version("1.5");
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1",
            "BaseFont" => face, "Encoding" => "WinAnsiEncoding",
        });
        let ops = vec![
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 24.into()]),
            Operation::new("Td", vec![10.into(), 40.into()]),
            Operation::new("Tj", vec![Object::string_literal(label)]),
            Operation::new("ET", vec![]),
        ];
        let page_id = page_from_ops(&mut doc, ops, dictionary! {
            "Font" => dictionary! { "F1" => font_id }
        });
        (doc, page_id)
    };

    // Control renders, each with its own scope, no sharing possible.
    let (doc_a, pa) = build("Helvetica", "MMM iii");
    let (doc_b, pb) = build("Courier", "MMM iii");
    let want_a = fingerprint(&interpret_page(&doc_a, pa).expect("a"));
    let want_b = fingerprint(&interpret_page(&doc_b, pb).expect("b"));
    // Courier is monospaced and Helvetica is not, so "MMM iii" must advance
    // differently. Without this the test could pass by both being wrong.
    assert_ne!(
        want_a, want_b,
        "fixture fonts are indistinguishable; pick faces with different metrics"
    );
    drop(doc_a);
    drop(doc_b);

    // Now the adversarial arrangement: one outer scope spanning both documents,
    // with B forced to occupy A's address.
    let (got_a, got_b) = {
        let _outer = crate::FontCacheScope::new();

        let (doc_a2, pa2) = build("Helvetica", "MMM iii");
        let mut slot = Box::new(doc_a2);
        let addr_a = &*slot as *const Document as usize;
        let got_a = fingerprint(&interpret_page(&slot, pa2).expect("a2"));

        let (doc_b2, pb2) = build("Courier", "MMM iii");
        *slot = doc_b2; // same heap address, different document
        let addr_b = &*slot as *const Document as usize;
        assert_eq!(
            addr_a, addr_b,
            "in-place overwrite failed to reuse the address; test cannot conclude"
        );
        let got_b = fingerprint(&interpret_page(&slot, pb2).expect("b2"));
        (got_a, got_b)
    };

    assert_eq!(got_a, want_a, "document A rendered differently under an outer cache scope");
    assert_eq!(
        got_b, want_b,
        "STALE FONT CACHE: document B at document A's address was served A's cached font. {}",
        first_difference(&want_b, &got_b)
    );
}

// ---------------------------------------------------------------------------
// Metamorphic: page rotation
