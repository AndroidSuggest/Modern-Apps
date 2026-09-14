/// Content invoked through a Form XObject with an identity `/Matrix` and a
/// `/BBox` large enough not to clip must paint the same ink as the same content
/// written inline (§8.10.2). The form path runs a different code route —
/// resource inheritance, `q`/`Q` bracketing, bbox clipping — so agreement here
/// is a real check on that route rather than a tautology.
#[test]
fn form_xobject_paints_the_same_ink_as_inline_content() {
    let draw = vec![
        Operation::new("rg", vec![0.1.into(), 0.4.into(), 0.9.into()]),
        Operation::new("re", vec![25.into(), 35.into(), 50.into(), 30.into()]),
        Operation::new("f", vec![]),
        Operation::new("RG", vec![0.into(), 0.5.into(), 0.into()]),
        Operation::new("w", vec![4.into()]),
        Operation::new("m", vec![30.into(), 120.into()]),
        Operation::new("l", vec![140.into(), 150.into()]),
        Operation::new("S", vec![]),
    ];

    let mut inline_doc = Document::with_version("1.5");
    let inline_page = page_from_ops(&mut inline_doc, draw.clone(), dictionary! {});
    let inline = interpret_page(&inline_doc, inline_page).expect("inline");

    let mut form_doc = Document::with_version("1.5");
    let form_bytes = Content { operations: draw }.encode().unwrap();
    let form_id = form_doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            // Covers the whole page, so /BBox clipping cannot be what differs.
            "BBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Matrix" => vec![1.into(), 0.into(), 0.into(), 1.into(), 0.into(), 0.into()],
        },
        form_bytes,
    ));
    let form_page = page_from_ops(
        &mut form_doc,
        vec![Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())])],
        dictionary! { "XObject" => dictionary! { "Fm0" => form_id } },
    );
    let via_form = interpret_page(&form_doc, form_page).expect("form");

    assert_eq!(
        inline.width, via_form.width,
        "form and inline disagree on page width"
    );

    // Compare the ink only: the form route legitimately adds clip bracketing
    // primitives for /BBox, which are not ink and must not count as a
    // difference.
    let ink = |page: &PageData| -> Vec<String> {
        page.prims
            .iter()
            .filter(|p| matches!(p, Prim::Fill { .. } | Prim::Stroke { .. } | Prim::Text { .. }))
            .map(fp_prim)
            .collect()
    };
    let a = ink(&inline);
    let b = ink(&via_form);
    assert!(!a.is_empty(), "inline fixture emitted no ink");
    assert_eq!(
        a, b,
        "Form XObject ink differs from the same content inline:\n  inline: {a:#?}\n  form:   {b:#?}"
    );
}
