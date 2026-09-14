            for (x, y) in &baselines {
                assert!(
                    *x >= -0.01 && *x <= 100.01 && *y >= -0.01 && *y <= 40.01,
                    "/Rect {rect:?} put a line at ({x}, {y}), outside the box"
                );
            }
        }
    }

    /// The sticky-note marker is kept — losing it loses the only sign in the page
    /// view that a comment exists — but it must not obscure. `s` came from the
    /// rect WIDTH alone, so a wide, short /Rect got a marker up to 18pt tall over
    /// a rect a fraction of that; and the unconditional black ring read as an
    /// authored box rather than viewer chrome. A degenerate /Rect still gets the
    /// nominal marker, since §12.5.6.4's icon does not scale with the page.
    #[test]
    fn the_sticky_note_marker_stays_inside_its_rect_and_draws_no_black_ring() {
        let marker_height = |rect: [f64; 4]| -> f32 {
            let prims = synth(&annot("Text"), rect);
            assert!(
                !prims.iter().any(|p| matches!(p, Prim::Stroke { .. })),
                "no hard-black ring around the marker"
            );
            let fills: Vec<&Prim> = prims.iter().filter(|p| matches!(p, Prim::Fill { .. })).collect();
            assert_eq!(fills.len(), 1, "exactly one marker");
            let Prim::Fill { contours, .. } = fills[0] else { unreachable!() };
            let ys: Vec<f32> = contours.iter().flatten().map(|p| p.1).collect();
            ys.iter().copied().fold(f32::MIN, f32::max) - ys.iter().copied().fold(f32::MAX, f32::min)
        };
        // Wide and short: the marker follows the SHORTER axis, not the width.
        let h = marker_height([0.0, 0.0, 200.0, 14.0]);
        assert!((h - 14.0).abs() < 0.01, "expected a 14pt marker in a 14pt-tall rect, got {h}");
        // Ordinary square note: capped at the 18pt nominal size.
        let h = marker_height([0.0, 0.0, 40.0, 40.0]);
        assert!((h - 18.0).abs() < 0.01, "expected the 18pt nominal marker, got {h}");
        // Zero-size /Rect: still drawn, at the 12pt floor, so the note is visible.
        let h = marker_height([10.0, 10.0, 10.0, 10.0]);
        assert!((h - 12.0).abs() < 0.01, "a degenerate /Rect must still show a marker, got {h}");
    }

    /// §7.3.10 lets ANY object be indirect. `/AS` read as a plain name yields
    /// None for an indirect one, which drops into the no-`/AS` branch — so a
    /// checkbox whose state is set indirectly renders as its `/Off` appearance,
    /// i.e. unchecked. The file already dereferences `/F`, `/Subtype`, `/C` and
    /// `/CA`; `/AS` was the outlier.
    #[test]
    fn an_indirect_appearance_state_selects_the_right_appearance() {
        for indirect in [false, true] {
            let mut doc = Document::with_version("1.7");
            let mk = |doc: &mut Document, colour: &str| {
                doc.add_object(Stream::new(
                    dictionary! {
                        "Type" => name_obj("XObject"),
                        "Subtype" => name_obj("Form"),
                        "BBox" => Object::Array(vec![0.into(), 0.into(), 10.into(), 10.into()]),
                    },
                    format!("{colour} 0 0 10 10 re f").into_bytes(),
                ))
            };
            let off = mk(&mut doc, "1 0 0 rg");
            let on = mk(&mut doc, "0 1 0 rg");
            let mut w = annot("Widget");
            w.set("Rect", rect_obj([0.0, 0.0, 10.0, 10.0]));
            w.set("AP", dictionary! { "N" => dictionary! { "Off" => off, "On" => on } });
            let as_obj = name_obj("On");
            w.set(
                "AS",
                if indirect { Object::Reference(doc.add_object(as_obj)) } else { as_obj },
            );

            let mut prims = Vec::new();
            render_annotation(&doc, &w, &IDENTITY, &mut prims);
            let fills: Vec<u32> = prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Fill { argb, .. } => Some(*argb),
                    _ => None,
                })
                .collect();
            assert_eq!(fills.len(), 1, "one appearance state must paint");
            assert_eq!(
                fills[0], 0xFF00_FF00,
                "indirect={indirect}: /AS must select the \"On\" state, not fall back to \"Off\""
            );
        }
    }

    /// An indirect `/Subtype` read as a plain name gives None, and
    /// `synthesize_annotation_appearance` returns immediately — so the whole
    /// annotation draws NOTHING, which is the total loss its arms exist to
    /// prevent. `annot_visible_on_screen_with` already dereferenced this key.
    #[test]
    fn an_indirect_subtype_still_selects_a_synthesis_arm() {
        let mut doc = Document::with_version("1.7");
        let subtype = doc.add_object(name_obj("Square"));
        let mut sq = Dictionary::new();
        sq.set("Type", name_obj("Annot"));
        sq.set("Subtype", Object::Reference(subtype));
        sq.set("C", Object::Array(vec![1.into(), 0.into(), 0.into()]));

        let mut prims = Vec::new();
        synthesize_annotation_appearance(&doc, &sq, [0.0, 0.0, 20.0, 20.0], &IDENTITY, &mut prims);
        assert!(
            prims.iter().any(|p| matches!(p, Prim::Stroke { .. })),
            "an indirect /Subtype must still reach the Square arm"
        );
    }

    /// The `/NeedAppearances` gate reads `/Subtype` too, so an indirect one there
    /// silently skips regeneration and leaves the stale `/AP` — reintroducing the
    /// blank-field symptom for exactly the files that set the flag.
    #[test]
    fn need_appearances_regenerates_even_with_an_indirect_subtype() {
        let mut doc = form_doc(true);
        let subtype = doc.add_object(name_obj("Widget"));
        let ap = fill_ap(&mut doc);
        let mut w = text_widget("Ada Lovelace");
        w.set("Subtype", Object::Reference(subtype));
        w.set("AP", Object::Dictionary(ap));

        let mut prims = Vec::new();
        render_annotation(&doc, &w, &IDENTITY, &mut prims);
        assert_eq!(painted_text(&prims), "Ada Lovelace", "indirect /Subtype must still regenerate");
    }

    /// §12.5.6.8 Table 177: `/RD` is "the numerical differences between ... the
    /// Rect entry of the annotation and the actual boundaries of the underlying
    /// square or circle". It was read nowhere in the crate, so the shape was
    /// drawn out to the full `/Rect`. Absent `/RD` the shape is inset by half the
    /// border width instead, because §8.4.3.2 centres a stroke on its path and a
    /// path laid on `/Rect` puts half the border outside the annotation.
    #[test]
    fn square_and_circle_honour_rd_and_the_border_inset() {
        let bounds = |prims: &[Prim]| -> [f32; 4] {
            let pts: Vec<(f32, f32)> = prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Fill { contours, .. } => Some(contours.iter().flatten().copied()),
                    _ => None,
                })
                .flatten()
                .collect();
            assert!(!pts.is_empty(), "expected a filled shape");
            [
                pts.iter().map(|p| p.0).fold(f32::MAX, f32::min),
                pts.iter().map(|p| p.1).fold(f32::MAX, f32::min),
                pts.iter().map(|p| p.0).fold(f32::MIN, f32::max),
                pts.iter().map(|p| p.1).fold(f32::MIN, f32::max),
            ]
        };
        let rect = [0.0, 0.0, 100.0, 50.0];

        for kind in ["Square", "Circle"] {
            let mut a = annot(kind);
            a.set("IC", Object::Array(vec![0.into(), 0.into(), 1.into()]));
            // /RD [left top right bottom] — top comes off the TOP edge.
            a.set("RD", Object::Array(vec![10.into(), 4.into(), 20.into(), 6.into()]));
            let got = bounds(&synth(&a, rect));
            for (g, want) in got.iter().zip([10.0, 6.0, 80.0, 46.0]) {
                assert!(
                    (g - want).abs() < 0.5,
                    "{kind} with /RD: got {got:?}, expected [10, 6, 80, 46]"
                );
            }

            // No /RD: inset by half the border width.
            a.remove(b"RD");
            a.set("BS", dictionary! { "W" => 8 });
            let got = bounds(&synth(&a, rect));
            for (g, want) in got.iter().zip([4.0, 4.0, 96.0, 46.0]) {
                assert!(
                    (g - want).abs() < 0.5,
                    "{kind} with /BS /W 8: got {got:?}, expected [4, 4, 96, 46]"
                );
            }
        }
    }

    /// A malformed `/RD` must not grow the shape past `/Rect` or collapse it —
    /// the plain rect is the better-defined answer than a clamped guess.
    #[test]
    fn a_malformed_rd_falls_back_to_the_plain_rect() {
        for rd in [
            vec![(-5).into(), 0.into(), 0.into(), 0.into()],   // negative: would grow
            vec![60.into(), 0.into(), 60.into(), 0.into()],    // wider than the rect
            vec![1.into(), 2.into()],                          // too short
        ] {
            let mut a = annot("Square");
            a.set("IC", Object::Array(vec![0.into(), 0.into(), 1.into()]));
            a.set("BS", dictionary! { "W" => 0 });
            a.set("RD", Object::Array(rd.clone()));
            let prims = synth(&a, [0.0, 0.0, 100.0, 50.0]);
            let xs: Vec<f32> = prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Fill { contours, .. } => Some(contours.iter().flatten().map(|q| q.0)),
                    _ => None,
                })
                .flatten()
                .collect();
            let (x0, x1) = (
                xs.iter().copied().fold(f32::MAX, f32::min),
                xs.iter().copied().fold(f32::MIN, f32::max),
            );
            assert!(
                (x0 - 0.0).abs() < 0.01 && (x1 - 100.0).abs() < 0.01,
                "/RD {rd:?} must fall back to the plain /Rect, got [{x0}, {x1}]"
            );
        }
    }

    /// §12.5.5 defines no fallback for an `/AS` naming a state the `/N`
    /// dictionary lacks. `/Off` was used for every such case, so a widget whose
    /// `/AS` says it is ON but whose only state is stored under a different
    /// export name (`/Yes` vs `/On` vs `/1`) rendered unchecked — or, with no
    /// `/Off` entry at all, drew NOTHING. An `/AS` of `/Off` still draws nothing,
    /// because a missing `/Off` entry IS the blank off appearance.
    #[test]
    fn an_as_naming_a_missing_state_resolves_by_whether_it_says_off() {
        let paint = |states: Vec<(&str, &str)>, as_name: &str| -> Vec<u32> {
            let mut doc = Document::with_version("1.7");
            let mut n = Dictionary::new();
            for (name, colour) in states {
                let s = doc.add_object(Stream::new(
                    dictionary! {
                        "Type" => name_obj("XObject"),
                        "Subtype" => name_obj("Form"),
                        "BBox" => Object::Array(vec![0.into(), 0.into(), 10.into(), 10.into()]),
                    },
                    format!("{colour} 0 0 10 10 re f").into_bytes(),
                ));
                n.set(name, Object::Reference(s));
            }
            let mut w = annot("Widget");
            w.set("Rect", rect_obj([0.0, 0.0, 10.0, 10.0]));
            w.set("AP", dictionary! { "N" => n });
            w.set("AS", name_obj(as_name));
            let mut prims = Vec::new();
            render_annotation(&doc, &w, &IDENTITY, &mut prims);
            prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Fill { argb, .. } => Some(*argb),
                    _ => None,
                })
                .collect()
        };
        const RED: u32 = 0xFFFF_0000;
        const GREEN: u32 = 0xFF00_FF00;

        // The on-art under a different export name: use it rather than /Off.
        assert_eq!(paint(vec![("Off", "1 0 0 rg"), ("On", "0 1 0 rg")], "Yes"), vec![GREEN]);
        // No /Off entry at all: previously drew nothing.
        assert_eq!(paint(vec![("On", "0 1 0 rg")], "Yes"), vec![GREEN]);
        // Ambiguous — two non-Off states, so fall back to /Off.
        assert_eq!(
            paint(vec![("Off", "1 0 0 rg"), ("On", "0 1 0 rg"), ("Two", "0 1 0 rg")], "Yes"),
            vec![RED]
        );
        // /AS says Off and /Off is absent: blank IS the off appearance.
        assert!(paint(vec![("On", "0 1 0 rg")], "Off").is_empty());
        // An exact match always wins.
        assert_eq!(paint(vec![("Off", "1 0 0 rg"), ("On", "0 1 0 rg")], "Off"), vec![RED]);
    }

    /// §8.4.3.2 centres a pen on its path, so the C6 stroke inset is not
    /// specific to Square and Circle — FreeText and Redact ring their `/Rect`
    /// too, and left half the border outside the annotation.
    ///
    /// FreeText must NOT reuse `shape_rect`: §12.5.6.6 Table 174 gives it a
    /// `/RD` that insets the TEXT AREA, not the shape, so passing it through the
    /// Table 177 path would apply a text inset as a border inset.
    #[test]
    fn freetext_and_redact_keep_their_border_inside_the_rect() {
        let extent = |prims: &[Prim]| -> [f32; 4] {
            let pts: Vec<(f32, f32)> = prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Stroke { pts, .. } => Some(pts.iter().copied()),
                    _ => None,
                })
                .flatten()
                .collect();
            assert!(!pts.is_empty(), "expected a stroked ring");
            [
                pts.iter().map(|p| p.0).fold(f32::MAX, f32::min),
                pts.iter().map(|p| p.1).fold(f32::MAX, f32::min),
                pts.iter().map(|p| p.0).fold(f32::MIN, f32::max),
                pts.iter().map(|p| p.1).fold(f32::MIN, f32::max),
            ]
        };
        for kind in ["FreeText", "Redact"] {
            let mut a = annot(kind);
            a.set("C", Object::Array(vec![1.into(), 0.into(), 0.into()]));
            a.set("BS", dictionary! { "W" => 6 });
            let got = extent(&synth(&a, [0.0, 0.0, 100.0, 40.0]));
            for (g, want) in got.iter().zip([3.0, 3.0, 97.0, 37.0]) {
                assert!(
                    (g - want).abs() < 0.01,
                    "{kind}: ring at {got:?}, expected inset by half of /BS /W 6"
                );
            }
        }
    }

    /// §12.5.6.6 Table 174: a FreeText `/RD` says where the TEXT goes, and it
    /// was read nowhere — so an author asking for a wide text inset had the text
    /// run under the border instead. The box itself must NOT move with it, which
    /// is what distinguishes this from Table 177's shape `/RD`.
    #[test]
    fn a_freetext_rd_moves_the_text_but_not_the_box() {
        let mut a = annot("FreeText");
        a.set("C", Object::Array(vec![1.into(), 0.into(), 0.into()]));
        a.set("BS", dictionary! { "W" => 0 });
        a.set("Contents", Object::string_literal("hi"));

        let baseline_of = |prims: &[Prim]| -> (f32, f32) {
            prims
                .iter()
                .find_map(|p| match p {
                    Prim::Text { x, y, .. } => Some((*x, *y)),
                    _ => None,
                })
                .expect("a text line")
        };
        let ring_of = |prims: &[Prim]| -> f32 {
            prims
                .iter()
                .filter_map(|p| match p {
                    Prim::Stroke { pts, .. } => {
                        Some(pts.iter().map(|q| q.0).fold(f32::MAX, f32::min))
                    }
                    _ => None,
                })
                .fold(f32::MAX, f32::min)
        };
        let plain = synth(&a, [0.0, 0.0, 100.0, 40.0]);
        // /RD [left top right bottom]
        a.set("RD", Object::Array(vec![12.into(), 5.into(), 0.into(), 0.into()]));
        let inset = synth(&a, [0.0, 0.0, 100.0, 40.0]);

        let (px, py) = baseline_of(&plain);
        let (ix, iy) = baseline_of(&inset);
        assert!((ix - px - 12.0).abs() < 0.01, "text x must move right by /RD left: {px} -> {ix}");
        assert!((iy - py + 5.0).abs() < 0.01, "text y must drop by /RD top: {py} -> {iy}");
        assert!(
            (ring_of(&plain) - ring_of(&inset)).abs() < 0.01,
            "the box must NOT move — Table 174 /RD insets only the text"
        );
    }

    /// §7.9.5 lets a /Rect be given by ANY two diagonally opposite corners and
    /// `read_rect` does not reorder them. The marker was sized with `.abs()` but
    /// anchored on the raw `rect[3]`, which for an inverted rect is the BOTTOM
    /// edge — so the opaque square landed entirely outside the annotation, over
    /// unrelated page content.
    #[test]
    fn the_sticky_note_marker_anchors_on_a_normalized_rect() {
        let bounds = |rect: [f64; 4]| -> [f32; 4] {
            let prims = synth(&annot("Text"), rect);
            let fills: Vec<&Prim> = prims.iter().filter(|p| matches!(p, Prim::Fill { .. })).collect();
            assert_eq!(fills.len(), 1, "exactly one marker");
            let Prim::Fill { contours, .. } = fills[0] else { unreachable!() };
            let pts: Vec<(f32, f32)> = contours.iter().flatten().copied().collect();
            [
                pts.iter().map(|p| p.0).fold(f32::MAX, f32::min),
                pts.iter().map(|p| p.1).fold(f32::MAX, f32::min),
                pts.iter().map(|p| p.0).fold(f32::MIN, f32::max),
                pts.iter().map(|p| p.1).fold(f32::MIN, f32::max),
            ]
        };
        // The same box described by each of the four corner orderings must place
        // the marker identically, at the box's top-left.
        let want = bounds([10.0, 20.0, 50.0, 60.0]);
        for rect in [
            [50.0, 60.0, 10.0, 20.0], // both axes inverted
            [10.0, 60.0, 50.0, 20.0], // y inverted
            [50.0, 20.0, 10.0, 60.0], // x inverted
        ] {
            let got = bounds(rect);
            for i in 0..4 {
                assert!(
                    (got[i] - want[i]).abs() < 0.01,
                    "/Rect {rect:?} placed the marker at {got:?}, expected {want:?}"
                );
            }
        }
        // And it really is inside the box, not merely consistent.
        assert!(
            want[0] >= 9.99 && want[1] >= 19.99 && want[2] <= 50.01 && want[3] <= 60.01,
            "marker {want:?} escaped the /Rect"
        );
    }
}
