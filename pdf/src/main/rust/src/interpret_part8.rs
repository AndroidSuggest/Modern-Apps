#[cfg(test)]
mod coverage_gap_tests {
    use crate::*;
    use lopdf::content::Operation;
    use lopdf::{dictionary, Stream};

    /// §8.7.4.1: `sh` shall fill the ENTIRE current clipping region. Because a
    /// shading with no `/BBox` has no extent of its own, `images::rasterize_shading`
    /// deliberately refuses to guess one and returns `None` — painting nothing —
    /// when the caller supplies no clip extent either. Only the page-level caller
    /// ever did; every nested stream went through `interpret_content`, which passes
    /// `None`. So `/Sh sh` inside a form XObject emitted no primitive at all, which
    /// is a fully-implemented operator rendering nothing. §8.10.1 makes the form's
    /// `/BBox` the clip its content is drawn under, so that box is the extent.
    #[test]
    fn sh_inside_a_form_xobject_paints_without_a_shading_bbox() {
        let mut doc = Document::with_version("1.7");
        let func = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![1.0.into(), 0.0.into(), 0.0.into()],
            "C1" => vec![0.0.into(), 0.0.into(), 1.0.into()],
            "N" => 1,
        });
        // Deliberately NO /BBox on the shading: the whole point of the test.
        let shading = doc.add_object(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceRGB",
            "Coords" => vec![0.into(), 0.into(), 100.into(), 0.into()],
            "Function" => Object::Reference(func),
        });
        let form = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
                "Resources" => dictionary! {
                    "Shading" => dictionary! { "Sh" => Object::Reference(shading) },
                },
            },
            b"/Sh sh".to_vec(),
        ));
        let res = dictionary! {
            "XObject" => dictionary! { "Fm" => Object::Reference(form) },
        };
        let ops = vec![Operation::new("Do", vec![Object::Name(b"Fm".to_vec())])];
        let mut prims = Vec::new();
        // Mirrors `interpret_page`: the page box is the starting clip extent.
        interpret_content_seeded(
            &doc,
            &ops,
            Some(&res),
            GraphicsState::default(),
            &mut prims,
            0,
            false,
            Some([0.0, 0.0, 200.0, 200.0]),
        );
        let images = prims.iter().filter(|p| matches!(p, Prim::Image { .. })).count();
        assert_eq!(images, 1, "the gradient must be painted, not silently dropped");
        // A 1x1 raster would technically satisfy the count while showing nothing, so
        // check the shading was actually rasterized over the form's box.
        for p in &prims {
            if let Prim::Image { w, h, data, .. } = p {
                assert!(*w > 1 && *h > 1, "raster collapsed to {w}x{h}");
                assert_eq!(data.len(), (*w as usize) * (*h as usize) * 4);
                assert!(data.chunks(4).any(|px| px[3] > 0), "raster is fully transparent");
            }
        }
    }

    /// Same hazard one level deeper: a luminosity soft mask whose group paints
    /// nothing is uniformly black, which hides ALL of the masked content rather
    /// than merely mis-toning it. §11.6.5.2 makes the mask group a form XObject,
    /// so its `/BBox` is the extent for a `sh` inside it.
    #[test]
    fn sh_inside_a_soft_mask_group_paints_without_a_shading_bbox() {
        let mut doc = Document::with_version("1.7");
        let func = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.0.into()],
            "C1" => vec![1.0.into()],
            "N" => 1,
        });
        let shading = doc.add_object(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceGray",
            "Coords" => vec![0.into(), 0.into(), 100.into(), 0.into()],
            "Function" => Object::Reference(func),
        });
        let group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
                "Group" => dictionary! { "S" => "Transparency" },
                "Resources" => dictionary! {
                    "Shading" => dictionary! { "Sh" => Object::Reference(shading) },
                },
            },
            b"/Sh sh".to_vec(),
        ));
        let mask = SoftMask {
            group_id: group,
            mask_type: 1,
            ctm: IDENTITY,
            backdrop: None,
            tr: None,
        };
        let mut prims = Vec::new();
        render_soft_mask_group(&doc, None, &mask, &mut prims, 0, None);
        assert!(
            prims.iter().any(|p| matches!(p, Prim::Image { .. })),
            "an all-black mask hides everything it should reveal"
        );
    }

    /// §8.11.2: content in a disabled optional-content group shall not be drawn.
    /// The `Do` arm gated the Image branch, the `/BBox` ClipPush and the
    /// `GroupPush` on the marked-content hidden flag but NOT the form recursion,
    /// and `oc_stack` is a local that no nested stream inherits — so a hidden
    /// form painted its entire contents, and painted them UNCLIPPED, because the
    /// clip that would have bounded them was suppressed by the very flag the
    /// recursion ignored. Reported by `residuals`' cross-round interaction review.
    #[test]
    fn a_form_xobject_in_a_disabled_oc_group_paints_nothing() {
        // `text_only` = false is the render path; true is `search::build_index`,
        // which must still descend so a hidden layer's text stays searchable.
        let run = |text_only: bool| -> (usize, usize) {
            let mut doc = Document::with_version("1.7");
            let ocg = doc.add_object(dictionary! {
                "Type" => "OCG",
                "Name" => Object::string_literal("hidden layer"),
            });
            // The OCG is OFF in the default configuration.
            let catalog = doc.add_object(dictionary! {
                "Type" => "Catalog",
                "OCProperties" => dictionary! {
                    "OCGs" => vec![Object::Reference(ocg)],
                    "D" => dictionary! { "OFF" => vec![Object::Reference(ocg)] },
                },
            });
            doc.trailer.set("Root", Object::Reference(catalog));
            let font = doc.add_object(dictionary! {
                "Type" => "Font",
                "Subtype" => "Type1",
                "BaseFont" => "Helvetica",
                "FirstChar" => 65,
                "LastChar" => 66,
                "Widths" => vec![1000.into(), 1000.into()],
            });
            let form = doc.add_object(Stream::new(
                dictionary! {
                    "Type" => "XObject",
                    "Subtype" => "Form",
                    "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
                    "Resources" => dictionary! {
                        "Font" => dictionary! { "F1" => Object::Reference(font) },
                    },
                },
                b"0 0 50 50 re f BT /F1 12 Tf (AB) Tj ET".to_vec(),
            ));
            let res = dictionary! {
                "XObject" => dictionary! { "Fm" => Object::Reference(form) },
                "Properties" => dictionary! { "P1" => Object::Reference(ocg) },
            };
            let ops = vec![
                Operation::new(
                    "BDC",
                    vec![Object::Name(b"OC".to_vec()), Object::Name(b"P1".to_vec())],
                ),
                Operation::new("Do", vec![Object::Name(b"Fm".to_vec())]),
                Operation::new("EMC", vec![]),
            ];
            let mut prims = Vec::new();
            interpret_content(
                &doc,
                &ops,
                Some(&res),
                GraphicsState::default(),
                &mut prims,
                0,
                text_only,
            );
            (
                prims.iter().filter(|p| matches!(p, Prim::Fill { .. })).count(),
                prims.iter().filter(|p| matches!(p, Prim::Text { .. })).count(),
            )
        };

        let (fills, _) = run(false);
        assert_eq!(fills, 0, "a hidden layer's form must not paint");

        // Sanity check that the fixture would paint at all when the layer is ON,
        // so a zero above cannot come from a broken fixture.
        let mut doc = Document::with_version("1.7");
        let form = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            },
            b"0 0 50 50 re f".to_vec(),
        ));
        let res = dictionary! {
            "XObject" => dictionary! { "Fm" => Object::Reference(form) },
        };
        let ops = vec![Operation::new("Do", vec![Object::Name(b"Fm".to_vec())])];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        assert!(
            prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
            "fixture must paint when the layer is not hidden"
        );

        // The search-index path still descends: hidden is not absent (§8.11.2 bars
        // DRAWING, not indexing), matching the `Tj` arm's render-mode-3 policy.
        let (_, texts) = run(true);
        assert!(texts > 0, "a hidden layer's text must stay searchable");
    }

    /// §11.6.6: on entering a transparency group the alpha constants reset to 1.0,
    /// because `/ca` applies ONCE to the group's composited result. That reset is
    /// gated on `pushed_group`, so making the group push mutually exclusive with
    /// the soft-mask bracket silently disabled it — precisely the failure the
    /// comment above it warns about. A form with BOTH `/ca` < 1 and an `/SMask`
    /// then applied `ca` to every element inside, over-darkening wherever that
    /// content overlaps itself. Reported by `residuals`' interaction review.
    #[test]
    fn group_alpha_resets_even_when_a_soft_mask_is_active() {
        let mut doc = Document::with_version("1.7");
        let mask_group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Form",
                "Group" => dictionary! { "S" => "Transparency", "CS" => "DeviceGray" },
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            },
            b"1 g 0 0 100 100 re f".to_vec(),
        ));
        let egs = doc.add_object(dictionary! {
            "Type" => "ExtGState",
            "ca" => 0.5,
            "SMask" => dictionary! {
                "S" => "Luminosity",
                "G" => Object::Reference(mask_group),
            },
        });
        // Two OVERLAPPING fills: per-element and per-group alpha agree everywhere
        // else, so overlap is the only shape that can witness the bug.
        let form = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Form",
                "Group" => dictionary! { "S" => "Transparency" },
                "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            },
            b"0 0 60 60 re f 40 40 60 60 re f".to_vec(),
        ));
        let res = dictionary! {
            "ExtGState" => dictionary! { "GS" => Object::Reference(egs) },
            "XObject" => dictionary! { "Fm" => Object::Reference(form) },
        };
        let ops = vec![
            Operation::new("gs", vec![Object::Name(b"GS".to_vec())]),
            Operation::new("Do", vec![Object::Name(b"Fm".to_vec())]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);

        let mask = prims
            .iter()
            .position(|p| matches!(p, Prim::SoftMaskPush { .. }))
            .expect("the soft mask must bracket the form");
        let push = prims
            .iter()
            .position(|p| matches!(p, Prim::GroupPush { .. }))
            .expect("a transparency group must still be pushed when a soft mask is active");
        // §11.6.5.1 order: the group composites first, inside the masked layer.
        assert!(mask < push, "SoftMaskPush at {mask} must precede GroupPush at {push}");
        let Prim::GroupPush { alpha, .. } = &prims[push] else { unreachable!() };
        assert!((*alpha - 0.5).abs() < 1e-6, "group carries alpha {alpha}, expected /ca 0.5");

        // ...so `ca` must NOT also be baked into the elements inside the group.
        // Alpha rides in the top byte of `argb`.
        let group_end = prims[push..]
            .iter()
            .position(|p| matches!(p, Prim::GroupPop))
            .map(|i| push + i)
            .expect("the group must be popped");
        let inner: Vec<u8> = prims[push..group_end]
            .iter()
            .filter_map(|p| match p {
                Prim::Fill { argb, .. } => Some((argb >> 24) as u8),
                _ => None,
            })
            .collect();
        assert_eq!(inner.len(), 2, "both fills must reach the group");
        for a in inner {
            assert_eq!(a, 0xFF, "element alpha {a:#x} — /ca was applied twice");
        }
    }

    /// (§10.6.2 flatness tolerance), and `bezier_steps_for_flatness` already
    /// consumes it — it was the one Table 58 key with plumbing but no parser, so a
    /// document that set flatness through `gs` got the default tolerance.
    #[test]
    fn extgstate_fl_changes_curve_flattening() {
        let flatten = |fl: Option<f64>| -> usize {
            let mut doc = Document::with_version("1.7");
            let gs_id = doc.add_object(match fl {
                Some(v) => dictionary! { "FL" => v },
                None => dictionary! {},
            });
            let res = dictionary! {
                "ExtGState" => dictionary! { "GS1" => Object::Reference(gs_id) },
            };
            let ops = vec![
                Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
                Operation::new("m", vec![0.into(), 0.into()]),
                Operation::new(
                    "c",
                    vec![0.into(), 800.into(), 800.into(), 800.into(), 800.into(), 0.into()],
                ),
                Operation::new("S", vec![]),
            ];
            let mut prims = Vec::new();
            interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
            prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Stroke { pts, .. } => Some(pts.len()),
                    _ => None,
                })
                .max()
                .unwrap_or(0)
        };
        let fine = flatten(None);
        let coarse = flatten(Some(3.0));
        assert!(fine > 1, "the curve must be flattened at all");
        assert!(
            coarse < fine,
            "a coarser /FL must flatten to fewer segments, got {coarse} vs {fine}"
        );
    }

    /// §8.4.5 Table 58 `/Font` is `[font size]` with `font` an indirect reference to
    /// a font dictionary — the graphics-state equivalent of `Tf`, referencing a font
    /// the resource dictionary need not name. Unparsed, `gs.font_key` stayed empty
    /// and `show_string` took its no-metrics branch: the whole run collapsed to ONE
    /// primitive at the origin with a guessed advance, instead of one per glyph
    /// placed from `/Widths`.
    #[test]
    fn extgstate_font_selects_a_font_without_a_resource_name() {
        let mut doc = Document::with_version("1.7");
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font",
            "Subtype" => "Type1",
            "BaseFont" => "Helvetica",
            "FirstChar" => 65,
            "LastChar" => 66,
            "Widths" => vec![1000.into(), 1000.into()],
        });
        let gs_id = doc.add_object(dictionary! {
            "Font" => vec![Object::Reference(font_id), 12.into()],
        });
        // No /Font entry in the resources at all: `/Font` is precisely for this.
        let res = dictionary! {
            "ExtGState" => dictionary! { "GS1" => Object::Reference(gs_id) },
        };
        let ops = vec![
            Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
            Operation::new("BT", vec![]),
            Operation::new("Tj", vec![Object::string_literal("AB")]),
            Operation::new("ET", vec![]),
        ];
        let mut prims = Vec::new();
        interpret_content(&doc, &ops, Some(&res), GraphicsState::default(), &mut prims, 0, false);
        let texts: Vec<&Prim> = prims.iter().filter(|p| matches!(p, Prim::Text { .. })).collect();
        assert_eq!(texts.len(), 2, "one primitive per glyph, placed from /Widths");
        // /Widths 1000 at size 12 is a 12-unit advance, not the 0.5-em-per-byte guess.
        for t in &texts {
            if let Prim::Text { size, advance, .. } = t {
                assert!((*size - 12.0).abs() < 1e-3, "size comes from /Font, got {size}");
                assert!((*advance - 12.0).abs() < 0.5, "advance from /Widths, got {advance}");
            }
        }
        // A dangling /Font reference must leave the previous selection alone rather
        // than blanking it, so the text does not disappear on a malformed file.
        let bad_gs = doc.add_object(dictionary! {
            "Font" => vec![Object::Reference((9999, 0)), 12.into()],
        });
        let res2 = dictionary! {
            "Font" => dictionary! { "F1" => Object::Reference(font_id) },
            "ExtGState" => dictionary! { "GS2" => Object::Reference(bad_gs) },
        };
        let ops2 = vec![
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
            Operation::new("gs", vec![Object::Name(b"GS2".to_vec())]),
            Operation::new("BT", vec![]),
            Operation::new("Tj", vec![Object::string_literal("AB")]),
            Operation::new("ET", vec![]),
        ];
        let mut prims2 = Vec::new();
        interpret_content(&doc, &ops2, Some(&res2), GraphicsState::default(), &mut prims2, 0, false);
        assert_eq!(
            prims2.iter().filter(|p| matches!(p, Prim::Text { .. })).count(),
            2,
            "a dangling /Font must not discard the font Tf already selected"
        );
    }
}
