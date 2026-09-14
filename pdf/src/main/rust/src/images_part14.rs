#[cfg(test)]
mod shading_raster_tests {
    use super::*;

    fn ramp_fn(doc: &mut Document) -> ObjectId {
        doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into(), 0.into(), 0.into()],
            "C1" => vec![1.into(), 1.into(), 1.into()],
            "N" => 1,
        })
    }

    // Round 1's E-12: the raster used to be `let w = size; let h = size;`, so a
    // 600x5pt gradient bar allocated size^2 pixels — ~100x what it covers, and tripled
    // across `prims`, the wire buffer and a Kotlin Bitmap. The placement CTM maps the
    // unit square, so a non-square raster is geometrically identical. This landed in
    // round 1 with no test; pin it.
    #[test]
    fn axial_shading_raster_follows_bbox_aspect_ratio() {
        let mut doc = Document::with_version("1.7");
        let f = ramp_fn(&mut doc);
        let sh = Object::Dictionary(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceRGB",
            "Coords" => vec![0.into(), 0.into(), 600.into(), 0.into()],
            "BBox" => vec![0.into(), 0.into(), 600.into(), 5.into()],
            "Function" => Object::Reference(f),
        });
        let (_, w, h, data) =
            rasterize_shading(&doc, &sh, &IDENTITY, &HashMap::new(), 512, None).expect("rasterizes");
        assert_eq!(w, 512, "the long axis takes the requested size");
        assert!(h < 20, "a 600x5 bbox must not allocate a square raster; got {w}x{h}");
        assert_eq!(data.len(), (w as usize) * (h as usize) * 4);
    }

    // Axial/radial colour comes from a 256-entry LUT over one scalar, so the raster can
    // hold at most 256 distinct colours; MAX_GRADIENT_RASTER_BYTES trims the bytes that
    // cannot encode anything. A near-square gradient must land inside it.
    #[test]
    fn near_square_gradient_stays_within_the_byte_budget() {
        let mut doc = Document::with_version("1.7");
        let f = ramp_fn(&mut doc);
        let sh = Object::Dictionary(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceRGB",
            "Coords" => vec![0.into(), 0.into(), 1000.into(), 1000.into()],
            "BBox" => vec![0.into(), 0.into(), 1000.into(), 1000.into()],
            "Function" => Object::Reference(f),
        });
        let (_, w, h, data) =
            rasterize_shading(&doc, &sh, &IDENTITY, &HashMap::new(), 1024, None).expect("rasterizes");
        assert!(
            (w as usize) * (h as usize) * 4 <= MAX_GRADIENT_RASTER_BYTES,
            "{w}x{h} = {} bytes exceeds the gradient budget",
            (w as usize) * (h as usize) * 4
        );
        // The square aspect must survive the scale-down.
        assert_eq!(w, h, "scaling to fit the budget must preserve the aspect ratio");
        assert_eq!(data.len(), (w as usize) * (h as usize) * 4);
    }

    // Round 2 found that every synthesised raster was mirrored vertically: row 0 was
    // sampled at the bbox's LOW y while the placement CTM's positive `d` and 8.9.5.2's
    // upper-left-first-sample rule put row 0 at the HIGH y edge. An axial ramp therefore
    // ran backwards on the page. Nothing pinned the orientation, which is why it survived
    // a whole round of review - so pin it with an asymmetric gradient.
    #[test]
    fn axial_shading_row_zero_is_the_high_y_edge() {
        let mut doc = Document::with_version("1.7");
        // Black at t=0 -> white at t=1, running along +y over the bbox.
        let f = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into(), 0.into(), 0.into()],
            "C1" => vec![1.into(), 1.into(), 1.into()],
            "N" => 1,
        });
        let sh = Object::Dictionary(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceRGB",
            // Axis from y=0 to y=100, so t grows with y: dark at the bottom, light at top.
            "Coords" => vec![0.into(), 0.into(), 0.into(), 100.into()],
            "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
            "Function" => Object::Reference(f),
        });
        let (_, w, h, data) =
            rasterize_shading(&doc, &sh, &IDENTITY, &HashMap::new(), 64, None).expect("rasterizes");
        let row = |r: u32| data[(r as usize * w as usize) * 4];
        // Row 0 is the TOP of the image = HIGH y = t near 1 = WHITE.
        assert!(row(0) > 200, "row 0 must be the high-y (light) end, got {}", row(0));
        // The last row is the bottom = low y = t near 0 = BLACK.
        assert!(
            row(h - 1) < 55,
            "the last row must be the low-y (dark) end, got {}",
            row(h - 1)
        );
        assert!(row(0) > row(h - 1), "the ramp must not be mirrored");
    }

    // Type 1 (function-based) was MISSED by round 1's E-12 and was still square, with no
    // byte cap at all. Its raster must follow the device extent of /Domain through
    // /Matrix, exactly as types 2/3 follow their bbox.
    #[test]
    fn function_based_shading_raster_follows_domain_aspect_ratio() {
        let mut doc = Document::with_version("1.7");
        // A 2-in/3-out sampled function is awkward to build; a Type 4 is not.
        let f = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 4,
                "Domain" => vec![0.into(), 600.into(), 0.into(), 5.into()],
                "Range" => vec![0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into()],
            },
            // x y -> (0, 0, 0.5): ignore both inputs, emit a constant colour.
            b"{ pop pop 0 0 0.5 }".to_vec(),
        ));
        let sh = Object::Dictionary(dictionary! {
            "ShadingType" => 1,
            "ColorSpace" => "DeviceRGB",
            "Domain" => vec![0.into(), 600.into(), 0.into(), 5.into()],
            "Function" => Object::Reference(f),
        });
        let (_, w, h, data) =
            rasterize_shading(&doc, &sh, &IDENTITY, &HashMap::new(), 512, None).expect("rasterizes");
        assert_eq!(w, 512, "the long axis takes the requested size");
        assert!(h < 20, "a 600x5 /Domain must not allocate a square raster; got {w}x{h}");
        assert_eq!(data.len(), (w as usize) * (h as usize) * 4);
        // And the reported dimensions must match the buffer — the old code returned
        // (size, size) regardless of what it had actually filled.
        assert_eq!(data[3], 255, "the raster is actually painted");
    }

    /// §8.7.4.3 Table 78: `/BBox` is a COMMON shading entry, "applied as a temporary
    /// clipping boundary when the shading is painted", not a type 2/3 one. Type 1 was
    /// the only shading that ignored it, so a `/BBox` covering part of the `/Domain`
    /// still painted the whole domain. Its coordinates are in the shading's TARGET
    /// space, so the test has to be made after `/Matrix` — a `/Matrix` translation is
    /// included here precisely so a version that tested in domain space fails.
    /// (Reported by r5-color, task 5.)
    #[test]
    fn function_based_shading_is_clipped_by_its_bbox_in_target_space() {
        let mut doc = Document::with_version("1.7");
        let f = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 4,
                "Domain" => vec![0.into(), 100.into(), 0.into(), 100.into()],
                "Range" => vec![0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into()],
            },
            b"{ pop pop 1 0 0 }".to_vec(),
        ));
        // /Matrix translates domain space by +1000 in x, so the box below covers the
        // LEFT half of the domain only once /Matrix has been applied. Testing the raw
        // domain coordinate against it would clip everything away instead.
        let shading = |bbox: Option<Vec<Object>>| -> (u32, u32, Vec<u8>) {
            let mut d = dictionary! {
                "ShadingType" => 1,
                "ColorSpace" => "DeviceRGB",
                "Domain" => vec![0.into(), 100.into(), 0.into(), 100.into()],
                "Matrix" => vec![1.into(), 0.into(), 0.into(), 1.into(), 1000.into(), 0.into()],
                "Function" => Object::Reference(f),
            };
            if let Some(b) = bbox {
                d.set("BBox", b);
            }
            let (_, w, h, data) =
                rasterize_shading(&doc, &Object::Dictionary(d), &IDENTITY, &HashMap::new(), 64, None)
                    .expect("rasterizes");
            (w, h, data)
        };

        let (w, h, unclipped) = shading(None);
        let alpha = |data: &[u8], x: u32, y: u32| data[((y * w + x) as usize) * 4 + 3];
        assert_eq!(alpha(&unclipped, 1, h / 2), 255, "no /BBox: the left edge paints");
        assert_eq!(alpha(&unclipped, w - 2, h / 2), 255, "and so does the right edge");

        // Target-space x in [1000, 1050] = the left half of the domain.
        let (_, _, clipped) = shading(Some(vec![
            1000.into(),
            0.into(),
            1050.into(),
            100.into(),
        ]));
        assert_eq!(alpha(&clipped, 1, h / 2), 255, "inside the box still paints");
        assert_eq!(
            alpha(&clipped, w - 2, h / 2),
            0,
            "outside the box must be clipped, not painted"
        );
    }

    /// A zero-area `/BBox` is well-formed data meaning "clip everything", not an
    /// unparseable value to fall back from, so it must paint NOTHING — matching
    /// `shading.rs`'s existing answer for types 4-7 and, more importantly, matching what
    /// a /BBox that merely fails to INTERSECT the domain already does. Ignoring only the
    /// exactly-zero case would make the behaviour discontinuous in the data.
    ///
    /// The reversed-corner half is the other side of the same test: §7.9.5 permits "any
    /// two diagonally opposite corners", so `[100 100 0 0]` is a legitimate 100x100 clip
    /// and must NOT be mistaken for a degenerate one. `read_rect` does no normalising, so
    /// this only holds if the caller min/maxes before the width test.
    /// (Argued out with r5-color, task 5; their position, not my first one.)
    #[test]
    fn function_based_shading_bbox_degeneracy_is_honoured_but_corner_order_is_not() {
        let mut doc = Document::with_version("1.7");
        let f = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 4,
                "Domain" => vec![0.into(), 100.into(), 0.into(), 100.into()],
                "Range" => vec![0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into()],
            },
            b"{ pop pop 1 0 0 }".to_vec(),
        ));
        let shading = |bbox: Vec<Object>| {
            let mut d = dictionary! {
                "ShadingType" => 1,
                "ColorSpace" => "DeviceRGB",
                "Domain" => vec![0.into(), 100.into(), 0.into(), 100.into()],
                "Function" => Object::Reference(f),
            };
            d.set("BBox", bbox);
            rasterize_shading(&doc, &Object::Dictionary(d), &IDENTITY, &HashMap::new(), 32, None)
        };

        // Zero area: a clip that excludes everything.
        assert!(
            shading(vec![50.into(), 50.into(), 50.into(), 50.into()]).is_none(),
            "a zero-area /BBox clips everything away"
        );
        // Zero-width but not zero-height is just as empty.
        assert!(
            shading(vec![50.into(), 0.into(), 50.into(), 100.into()]).is_none(),
            "a zero-WIDTH /BBox is equally empty"
        );
        // A box that does not intersect the domain must agree with the above, which is
        // the continuity argument that decided the degenerate case.
        let (_, _, _, far) = shading(vec![500.into(), 500.into(), 600.into(), 600.into()])
            .expect("non-intersecting still produces a raster");
        assert!(
            far.chunks_exact(4).all(|px| px[3] == 0),
            "a /BBox that misses the domain must paint nothing either"
        );

        // Reversed corners: a legitimate 100x100 box covering the whole domain.
        let (_, _, _, whole) = shading(vec![100.into(), 100.into(), 0.into(), 0.into()])
            .expect("[100 100 0 0] is a valid 100x100 box, not a degenerate one");
        assert!(
            whole.chunks_exact(4).all(|px| px[3] == 255),
            "a reversed-corner /BBox must clip nothing, not everything"
        );
    }

    /// Table 78 scopes `/Background` to the area "outside the bounds of the shading
    /// object". A type 1 raster IS the `/Domain` rect, so it has no such area: a pixel
    /// left at alpha 0 is one whose colour could not be computed while INSIDE the
    /// extent. Filling those with `/Background` puts an opaque rectangle over the page.
    /// (Reported by r5-color, task 5.)
    #[test]
    fn function_based_background_does_not_fill_an_uncomputable_in_domain_pixel() {
        let mut doc = Document::with_version("1.7");
        // A Type 4 whose output arity (2) does not match DeviceRGB, so `eval_cs_to_rgb`
        // refuses every pixel and the whole raster stays clear.
        let f = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 4,
                "Domain" => vec![0.into(), 1.into(), 0.into(), 1.into()],
                "Range" => vec![0.into(), 1.into(), 0.into(), 1.into()],
            },
            b"{ pop pop 0 0 }".to_vec(),
        ));
        let sh = Object::Dictionary(dictionary! {
            "ShadingType" => 1,
            "ColorSpace" => "DeviceRGB",
            "Domain" => vec![0.into(), 1.into(), 0.into(), 1.into()],
            "Background" => vec![1.into(), 0.into(), 0.into()],
            "Function" => Object::Reference(f),
        });
        // The PATTERN entry point is the one that honours /Background at all (`sh`
        // ignores it), so this is the path that could over-fill.
        let (_, w, h, data) =
            rasterize_shading_as_pattern(&doc, &sh, &IDENTITY, &HashMap::new(), 16, None)
                .expect("rasterizes");
        assert!(
            data.chunks_exact(4).all(|px| px[3] == 0),
            "every pixel is in-domain, so /Background must not paint any of them"
        );
        assert_eq!(data.len(), (w as usize) * (h as usize) * 4);
    }
}

#[cfg(test)]
mod radial_tests {
    use super::radial_shading_param;

    // A concentric radial gradient (same center, r0=0..r1=50) must vary with
    // distance from the center — the old axial approximation collapsed it to a
    // single value because the centers coincide.
    #[test]
    fn concentric_radial_varies_with_radius() {
        let coords = [50.0, 50.0, 0.0, 50.0, 50.0, 50.0];
        let center = radial_shading_param(&coords, true, true, 50.0, 50.0).unwrap();
        let mid = radial_shading_param(&coords, true, true, 75.0, 50.0).unwrap();
        let edge = radial_shading_param(&coords, true, true, 100.0, 50.0).unwrap();
        assert!((center - 0.0).abs() < 1e-6, "center s={center}");
        assert!((mid - 0.5).abs() < 1e-6, "mid s={mid}");
        assert!((edge - 1.0).abs() < 1e-6, "edge s={edge}");
        assert!(center < mid && mid < edge);
    }

    // Outside the outer circle with Extend[1]=false there is no covering circle.
    #[test]
    fn outside_without_extend_is_none() {
        let coords = [50.0, 50.0, 0.0, 50.0, 50.0, 50.0];
        assert!(radial_shading_param(&coords, false, false, 200.0, 50.0).is_none());
        // With Extend[1]=true the far point still maps (s>1 allowed).
        let s = radial_shading_param(&coords, false, true, 200.0, 50.0).unwrap();
        assert!(s > 1.0, "expected extended s>1, got {s}");
    }

    // Offset circles (different centers) still resolve on the axis.
    #[test]
    fn offset_circles_resolve_on_axis() {
        let coords = [0.0, 0.0, 10.0, 100.0, 0.0, 10.0];
        // On the segment between centers, near the start circle boundary.
        let s = radial_shading_param(&coords, true, true, 10.0, 0.0).unwrap();
        assert!((0.0..=1.0).contains(&s), "s={s}");
    }
}
