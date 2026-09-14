                })
        };

        // Precondition: an explicit /BC still produces its own colour, so this
        // fixture really does reach the backdrop path.
        assert_eq!(
            backdrops(Some(Object::Array(vec![Object::Real(1.0)]))),
            Some(rgb_to_argb(1.0, 1.0, 1.0)),
            "an explicit white /BC must still paint white"
        );
        assert_eq!(
            backdrops(None),
            Some(0xFF00_0000),
            "an absent /BC defaults to zero luminosity, not to no backdrop at all"
        );
    }

    /// Residual of the `/BC` fix, found by `hunt-wrong2`: the backdrop block was
    /// still wrapped in `if let Some(rect) = ...BBox...`, but `rect` is only used
    /// on the no-extent FALLBACK path. So a mask group with no `/BBox` got no
    /// backdrop even when `masked_extent` was `Some` and the extent needed to
    /// paint one was right there — the pre-fix behaviour surviving in a narrower
    /// case. §8.10.2 makes `/BBox` required, but producers omit it.
    #[test]
    fn a_luminosity_mask_without_a_bbox_still_gets_its_backdrop() {
        let mut doc = Document::with_version("1.7");
        // Deliberately NO /BBox on the group: the point of the test.
        let group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "Group" => dictionary! { "S" => "Transparency", "CS" => "DeviceGray" },
            },
            b"1 g 0 0 50 50 re f".to_vec(),
        ));
        let extg = doc.add_object(dictionary! {
            "Type" => "ExtGState",
            "SMask" => dictionary! { "S" => "Luminosity", "G" => Object::Reference(group) },
        });
        let res = dictionary! { "ExtGState" => dictionary! { "GS" => Object::Reference(extg) } };
        let ops = vec![
            op("gs", vec![Object::Name(b"GS".to_vec())]),
            op("re", vec![0.into(), 0.into(), 200.into(), 200.into()]),
            op("f", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content_seeded(
            &doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false,
            Some([0.0, 0.0, 200.0, 200.0]),
        );
        let content = prims
            .iter()
            .position(|p| matches!(p, Prim::SoftMaskContent))
            .expect("the mask bracket must have been emitted");
        assert_eq!(
            prims[content..].iter().find_map(|p| match p {
                Prim::Fill { argb, .. } => Some(*argb),
                _ => None,
            }),
            Some(0xFF00_0000),
            "the masked extent supplies the area, so a missing /BBox must not skip the backdrop"
        );
    }
}
