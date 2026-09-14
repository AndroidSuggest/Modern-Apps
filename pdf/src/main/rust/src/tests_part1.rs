/// §8.7.4.1: an unclipped `sh` paints the whole page, so `interpret_page` must
/// seed the clip extent with the page box rather than leaving the shading to fall
/// back to a small guessed square (item 1).
#[test]
fn unclipped_sh_covers_page() {
    let mut doc = Document::with_version("1.5");
    let func_id = doc.add_object(dictionary! {
        "FunctionType" => 2,
        "Domain" => vec![0.into(), 1.into()],
        "C0" => vec![1.0.into(), 0.0.into(), 0.0.into()],
        "C1" => vec![0.0.into(), 0.0.into(), 1.0.into()],
        "N" => 1,
    });
    // Axial shading, deliberately with no /BBox.
    let sh_id = doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![0.into(), 0.into(), 400.into(), 0.into()],
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
        "Function" => func_id,
    });
    let content = Content {
        operations: vec![Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())])],
    };
    let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
    let pages_id = doc.new_object_id();
    let page_id = doc.add_object(dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 400.into(), 500.into()],
        "Contents" => content_id,
        "Resources" => dictionary! { "Shading" => dictionary! { "Sh0" => sh_id } },
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

    let page = interpret_page(&doc, page_id).expect("page should interpret");
    let ctm = page
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Image { ctm, .. } => Some(*ctm),
            _ => None,
        })
        .expect("unclipped sh must emit an Image prim");
    // The image unit square maps through `ctm`; its device width/height must span
    // the page, not a ~100x100 patch near the origin.
    let w = (ctm[0].abs() + ctm[2].abs()) as f64;
    let h = (ctm[1].abs() + ctm[3].abs()) as f64;
    assert!(w >= 399.0, "shading device width {w} should cover the 400pt page");
    assert!(h >= 499.0, "shading device height {h} should cover the 500pt page");
}

/// Round-trip a full open -> count -> render -> close cycle via the byte API.
#[test]
fn open_render_close_roundtrip() {
    let mut doc = Document::with_version("1.5");
    let content = Content {
        operations: vec![
            Operation::new("re", vec![0.into(), 0.into(), 10.into(), 10.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let content_data = content.encode().unwrap();
    let content_id = doc.add_object(Stream::new(dictionary! {}, content_data));
    let pages_id = doc.new_object_id();
    let page_id = doc.add_object(dictionary! {
        "Type" => "Page",
        "Parent" => pages_id,
        "MediaBox" => vec![0.into(), 0.into(), 200.into(), 300.into()],
        "Contents" => content_id,
        "Resources" => dictionary! {},
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

    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).unwrap();

    let handle = open_document(&bytes);
    assert_ne!(handle, 0);
    assert_eq!(page_count(handle), 1);
    let buf = render_page(handle, 0).expect("render should succeed");
    // Header v2: MAGIC, VERSION, width, height, count.
    let magic = u32::from_le_bytes(buf[0..4].try_into().unwrap());
    assert_eq!(magic, 0x50444657);
    let width = f32::from_le_bytes(buf[8..12].try_into().unwrap());
    let height = f32::from_le_bytes(buf[12..16].try_into().unwrap());
    assert_eq!(width, 200.0);
    assert_eq!(height, 300.0);
    close_document(handle);
    assert_eq!(page_count(handle), 0);
}

#[cfg(test)]
mod edit_render_tests {
use crate::*;
    use lopdf::{dictionary, Stream};
    use lopdf::content::{Content, Operation};

    fn one_page_pdf() -> Vec<u8> {
        let mut doc = Document::with_version("1.5");
        let content = lopdf::content::Content {
            operations: vec![lopdf::content::Operation::new("re", vec![0.into(), 0.into(), 10.into(), 10.into()])],
        };
        let cid = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => cid, "Resources" => dictionary! {},
        });
        doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }));
        let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", cat);
        let mut bytes = Vec::new();
        doc.save_to(&mut bytes).unwrap();
        bytes
    }

    #[test]
    fn added_rect_annotation_renders() {
        let bytes = one_page_pdf();
        let handle = open_document(&bytes);
        assert_ne!(handle, 0);
        let id = add_square(handle, 0, [100.0, 100.0, 300.0, 250.0], 0xFFFF0000, 2.0, false);
        assert!(id.is_some() && id != Some(0), "add_square failed: {id:?}");

        let buf = render_page(handle, 0).expect("render");
        // The JNI-facing path must produce a non-empty page.
        let count = u32::from_le_bytes(buf[16..20].try_into().unwrap());
        assert!(count >= 1, "expected primitives on the page, got {count}");

        // Assert on the primitives themselves rather than hand-decoding the wire
        // buffer. The previous inline decoder duplicated the v10 layout and had
        // drifted out of date (it omitted Stroke's v5 blend byte, Image's v9 alpha
        // and v10 blend, Fill's v6 multi-contour count and ClipPush's v4 path ops),
        // so it desynced and panicked with a bogus "bad tag" on any page whose prim
        // mix changed. Wire layout is covered by wire::tests::round_trips_all_primitives.
        let strokes = {
            let reg = registry()
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let doc = reg.get(&handle).expect("document still open");
            let page_id = *doc.get_pages().get(&1).expect("one page");
            let page = interpret_page(doc, page_id).expect("interpret");
            page.prims
                .iter()
                .filter(|p| matches!(p, Prim::Stroke { .. }))
                .count()
        };
        assert!(strokes >= 1, "expected the annotation stroke to render, got {strokes}");
        close_document(handle);
    }

    /// A luminosity soft mask set via ExtGState must bracket a subsequent fill
    /// with SoftMaskPush ... Fill ... SoftMaskContent ... SoftMaskPop.
    #[test]
    fn soft_mask_brackets_a_fill() {
        let mut doc = Document::with_version("1.7");
        // Soft-mask group form XObject: fills a white rectangle (full luminance).
        let mask_content = Content { operations: vec![
            Operation::new("rg", vec![1.0.into(), 1.0.into(), 1.0.into()]),
            Operation::new("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
            Operation::new("f", vec![]),
        ]};
        let mask_id = doc.add_object(Stream::new(dictionary! {
            "Type" => "XObject",
            "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 200.into(), 200.into()],
            "Group" => dictionary! { "S" => "Transparency" },
        }, mask_content.encode().unwrap()));
        let gs_id = doc.add_object(dictionary! {
            "SMask" => dictionary! {
                "S" => "Luminosity",
                "G" => Object::Reference(mask_id),
            },
        });
        let content = Content { operations: vec![
            Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
            Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
            Operation::new("re", vec![10.into(), 10.into(), 50.into(), 50.into()]),
            Operation::new("f", vec![]),
        ]};
        let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
        let resources = dictionary! {
            "ExtGState" => dictionary! { "GS1" => gs_id },
        };
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => content_id, "Resources" => resources,
        });
        doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }));
        let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", catalog_id);

        let page = interpret_page(&doc, page_id).expect("interpret");
        let kinds: Vec<u8> = page.prims.iter().map(|p| match p {
            Prim::SoftMaskPush { .. } => 10,
            Prim::SoftMaskContent => 11,
            Prim::SoftMaskPop => 12,
            Prim::Fill { .. } => 2,
            _ => 0,
        }).collect();
        let push = kinds.iter().position(|&k| k == 10).expect("SoftMaskPush");
        let content_marker = kinds.iter().position(|&k| k == 11).expect("SoftMaskContent");
        let pop = kinds.iter().position(|&k| k == 12).expect("SoftMaskPop");
        let masked_fill = kinds[push..content_marker].contains(&2);
        assert!(push < content_marker && content_marker < pop, "bracket order");
        assert!(masked_fill, "the red fill must sit inside the mask bracket");
    }

    /// Overprint (`/op true`) must NOT change how a fill is composited. Per ISO
    /// 32000-1 8.6.7 overprint control governs how ink is applied to individual
    /// colorants and has no effect on a device with one colorant or an additive
    /// (RGB) device — which this rasterizer is. The previous Multiply
    /// approximation actively broke pages, because `white MULTIPLY dst == dst`
    /// turns the white knockout rectangles that editors emit to cover content
    /// into no-ops, so the content underneath reappears. Do not "restore" this.
    #[test]
    fn overprint_fill_ignored_on_rgb_device() {
        let mut doc = Document::with_version("1.7");
        let gs_id = doc.add_object(dictionary! { "op" => true });
        let content = Content { operations: vec![
            Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
            Operation::new("rg", vec![0.0.into(), 0.0.into(), 1.0.into()]),
            Operation::new("re", vec![10.into(), 10.into(), 50.into(), 50.into()]),
            Operation::new("f", vec![]),
        ]};
        let content_id = doc.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
        let resources = dictionary! { "ExtGState" => dictionary! { "GS1" => gs_id } };
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => content_id, "Resources" => resources,
        });
        doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }));
        let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", catalog_id);

        let page = interpret_page(&doc, page_id).expect("interpret");
        let fill = page.prims.iter().find_map(|p| match p {
            Prim::Fill { blend, .. } => Some(*blend),
            _ => None,
        }).expect("a fill");
        assert!(
            fill == BlendMode::Normal,
            "overprint has no effect on an additive RGB device (8.6.7); got {:?}",
            fill as u8
        );
    }

    /// Turning one radio-button widget on must clear its siblings' `/AS` to Off
    /// and record the chosen export value on the parent field's `/V`.
    #[test]
    fn radio_group_clears_siblings() {
        let mut doc = Document::with_version("1.7");
        let parent_id = doc.new_object_id();
        let ap_a = dictionary! { "N" => dictionary! { "A" => dictionary!{}, "Off" => dictionary!{} } };
        let ap_b = dictionary! { "N" => dictionary! { "B" => dictionary!{}, "Off" => dictionary!{} } };
        let w1 = doc.add_object(dictionary! {
            "Type" => "Annot", "Subtype" => "Widget", "Parent" => parent_id,
            "AS" => Object::Name(b"Off".to_vec()), "AP" => ap_a,
            "Rect" => vec![0.into(), 0.into(), 10.into(), 10.into()],
        });
        let w2 = doc.add_object(dictionary! {
            "Type" => "Annot", "Subtype" => "Widget", "Parent" => parent_id,
            "AS" => Object::Name(b"Off".to_vec()), "AP" => ap_b,
            "Rect" => vec![0.into(), 0.into(), 10.into(), 10.into()],
        });
        doc.objects.insert(parent_id, Object::Dictionary(dictionary! {
            "FT" => "Btn", "Ff" => (1i64 << 15), // radio flag
            "Kids" => vec![w1.into(), w2.into()],
            "V" => Object::Name(b"Off".to_vec()),
        }));
        let handle = crate::registry::next_handle();
        crate::registry::registry().lock().unwrap().insert(handle, doc);

        assert!(crate::forms::set_checkbox(handle, crate::annotations::encode_id(w1), true));
        {
            let reg = crate::registry::registry().lock().unwrap();
            let d = reg.get(&handle).unwrap();
            assert_eq!(d.get_dictionary(w1).unwrap().get(b"AS").unwrap().as_name().unwrap(), b"A");
            assert_eq!(d.get_dictionary(w2).unwrap().get(b"AS").unwrap().as_name().unwrap(), b"Off");
            assert_eq!(d.get_dictionary(parent_id).unwrap().get(b"V").unwrap().as_name().unwrap(), b"A");
        }
        // Selecting the second widget flips exclusivity.
        assert!(crate::forms::set_checkbox(handle, crate::annotations::encode_id(w2), true));
        {
            let reg = crate::registry::registry().lock().unwrap();
            let d = reg.get(&handle).unwrap();
            assert_eq!(d.get_dictionary(w1).unwrap().get(b"AS").unwrap().as_name().unwrap(), b"Off");
            assert_eq!(d.get_dictionary(w2).unwrap().get(b"AS").unwrap().as_name().unwrap(), b"B");
            assert_eq!(d.get_dictionary(parent_id).unwrap().get(b"V").unwrap().as_name().unwrap(), b"B");
        }
        crate::registry::close_document(handle);
    }

    /// A Square annotation with no /AP stream must still render (synthesized
    /// appearance) — here an interior-colored fill.
    #[test]
    fn annotation_without_ap_is_synthesized() {
        let mut doc = Document::with_version("1.7");
        let content_id = doc.add_object(Stream::new(dictionary! {}, Vec::new()));
        let square = doc.add_object(dictionary! {
            "Type" => "Annot",
            "Subtype" => "Square",
            "Rect" => vec![100.into(), 100.into(), 200.into(), 160.into()],
            "IC" => vec![1.0.into(), 0.0.into(), 0.0.into()], // red interior
            "C" => vec![0.0.into(), 0.0.into(), 0.0.into()],
        });
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "Contents" => content_id,
            "Resources" => dictionary! {},
            "Annots" => vec![square.into()],
        });
        doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }));
        let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", catalog_id);

        let page = interpret_page(&doc, page_id).expect("interpret");
        let has_red_fill = page.prims.iter().any(|p| matches!(p, Prim::Fill { argb, .. } if (*argb & 0x00FF_FFFF) == 0x00FF_0000));
        assert!(has_red_fill, "square annotation without /AP should synthesize a red fill");
    }
}
