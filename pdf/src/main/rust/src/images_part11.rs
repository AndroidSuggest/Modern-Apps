#[cfg(test)]
mod mask_tests2 {
    use super::*;
    use super::mask_tests::{ccitt_stencil, half_black_g4};
    /// `/Decode [1 0]` reverses a CCITT stencil EXACTLY ONCE. The raster loop
    /// applies it via `black_bit` and `stencilize` is then called with
    /// `invert = false`; if a future change also passes `mask_invert` here, the two
    /// cancel and this test sees the un-inverted image.
    #[test]
    fn ccitt_stencil_decode_array_inverts_exactly_once() {
        let plain = ccitt_stencil(false, false);
        let inverted = ccitt_stencil(true, false);
        // Only alpha is asserted for an unpainted pixel: `stencilize` zeroes alpha and
        // leaves RGB as the raster left it, so the colour under a transparent pixel is
        // not part of the contract. Here it is the white the raster is initialised to,
        // because `/Decode [1 0]` makes the loop skip the black pels rather than write
        // them — pinning it would pin which of the two stages inverts, not that exactly
        // one does.
        assert_eq!(
            inverted.data[3], 0,
            "/Decode [1 0] must stop painting the black pels"
        );
        assert_eq!(
            &inverted.data[4 * 4..4 * 4 + 4], &[0, 255, 0, 255],
            "and must paint the white half instead"
        );
        // Stated as a whole-raster complement so a partial inversion (one row, or
        // only the fast path) cannot pass.
        for px in 0..(8 * 2) {
            assert_ne!(
                plain.data[px * 4 + 3], inverted.data[px * 4 + 3],
                "pixel {px} must flip under /Decode [1 0]"
            );
        }
    }

    /// `/BlackIs1` is the CCITT path's SECOND inversion source, and it lives in the
    /// filter: §7.4.6 Table 11 says 1 bits are black when it is true, "the reverse of
    /// the normal PDF convention", and `decode_ccitt` emits that polarity. The image
    /// layer then reads sample 0 as black per §8.9.5.2 without re-applying the flag,
    /// so the decoded stencil comes out reversed — which is what a `/Decode [1 0]`
    /// alongside `/BlackIs1 true` exists to undo. Pinned because it is the one
    /// polarity input NOT applied where the others are, so a well-meaning "fix" that
    /// folds it into `black_bit` as well would double-invert and silently return this
    /// to the un-reversed raster.
    #[test]
    fn ccitt_black_is1_reverses_the_stencil_and_decode_restores_it() {
        let plain = ccitt_stencil(false, false);
        let black_is1 = ccitt_stencil(false, true);
        for px in 0..(8 * 2) {
            assert_ne!(
                plain.data[px * 4 + 3], black_is1.data[px * 4 + 3],
                "pixel {px}: /BlackIs1 true reverses the decoded samples"
            );
        }
        let restored = ccitt_stencil(true, true);
        assert_eq!(
            plain.data, restored.data,
            "/BlackIs1 true with /Decode [1 0] is the same image as neither"
        );
    }

    /// One JBIG2 segment header (embedded organisation, §7.2): number, flags
    /// carrying the type, an empty referred-to list, a 1-byte page association and
    /// the data length.
    fn jbig2_segment(number: u32, seg_type: u8, page: u8, data: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&number.to_be_bytes());
        out.push(seg_type); // page-association size bit clear => 1 byte
        out.push(0x00); // referred-to count 0, no retain flags
        out.push(page);
        out.extend_from_slice(&(data.len() as u32).to_be_bytes());
        out.extend_from_slice(data);
        out
    }

    /// An embedded JBIG2 stream holding one immediate lossless generic region coded
    /// with MMR (which is T.6, so the same G4 bytes a fax uses).
    pub(super) fn jbig2_mmr_stream(width: u32, height: u32, mmr: &[u8]) -> Vec<u8> {
        let mut page = Vec::new();
        page.extend_from_slice(&width.to_be_bytes());
        page.extend_from_slice(&height.to_be_bytes());
        page.extend_from_slice(&0u32.to_be_bytes()); // x resolution
        page.extend_from_slice(&0u32.to_be_bytes()); // y resolution
        page.push(0x01); // lossless, default pixel 0 (white)
        page.extend_from_slice(&0u16.to_be_bytes()); // striping

        let mut region = Vec::new();
        region.extend_from_slice(&width.to_be_bytes());
        region.extend_from_slice(&height.to_be_bytes());
        region.extend_from_slice(&0u32.to_be_bytes()); // x
        region.extend_from_slice(&0u32.to_be_bytes()); // y
        region.push(0x00); // external combination operator OR
        region.push(0x01); // generic region flags: MMR = 1, so no AT pixels follow
        region.extend_from_slice(mmr);

        let mut out = jbig2_segment(0, 48, 1, &page);
        out.extend_from_slice(&jbig2_segment(1, 39, 1, &region));
        out
    }

    fn jbig2_stencil(decode_inverts: bool) -> Option<ImageData> {
        let doc = Document::with_version("1.7");
        let (mmr, w) = half_black_g4();
        let data = jbig2_mmr_stream(w as u32, 2, &mmr);
        let mut d = dictionary! {
            "Width" => w as i64, "Height" => 2, "BitsPerComponent" => 1,
            "ImageMask" => true,
            "Filter" => "JBIG2Decode",
        };
        if decode_inverts {
            d.set("Decode", vec![1.into(), 0.into()]);
        }
        extract_image(&doc, &Stream::new(d, data), 0xFF00_FF00, &HashMap::new())
    }

    /// The JBIG2 stencil branch is a different route to `stencilize` from CCITT's:
    /// the decoder hands back an already-black-on-white RGBA raster and `/Decode` is
    /// applied ONLY by `stencilize`'s `invert`. Both directions are pinned because
    /// this branch, unlike CCITT's, would silently paint nothing at all if the
    /// polarity were reversed on a mostly-white scan.
    #[test]
    fn jbig2_stencil_paints_the_black_pixels_and_decode_inverts_it() {
        let plain = jbig2_stencil(false).expect("an MMR generic region must decode");
        assert_eq!((plain.w, plain.h, plain.format), (8, 2, 0));
        assert_eq!(
            &plain.data[0..4], &[0, 255, 0, 255],
            "a black JBIG2 pixel takes the fill colour"
        );
        assert_eq!(plain.data[4 * 4 + 3], 0, "the white half is transparent");

        let inverted = jbig2_stencil(true).expect("decodes");
        for px in 0..(8 * 2) {
            assert_ne!(
                plain.data[px * 4 + 3], inverted.data[px * 4 + 3],
                "pixel {px} must flip under /Decode [1 0]"
            );
        }
    }

    // §7.4.9 Table 89: all three /SMaskInData values must behave DIFFERENTLY. The old
    // code applied alpha whenever the channel count suggested one, which made value 0
    // (and an absent entry, whose default is 0) wrongly transparent, and never undid
    // value 2's premultiplication, which left a dark fringe on every soft edge.
    #[test]
    fn smask_in_data_distinguishes_all_three_values() {
        use super::jp2::resolve_alpha;
        // 4-channel sRGB+alpha codestream, no cdef box.
        assert_eq!(resolve_alpha(0, 4, 3, None), (None, false), "0 ignores the alpha channel");
        assert_eq!(resolve_alpha(1, 4, 3, None), (Some(3), false), "1 uses it as the soft mask");
        assert_eq!(resolve_alpha(2, 4, 3, None), (Some(3), true), "2 also un-premultiplies");
        // No extra channel to use: nonzero /SMaskInData cannot invent one, and must not
        // report un-premultiplication for an alpha that does not exist.
        assert_eq!(resolve_alpha(2, 3, 3, None), (None, false), "3-channel RGB has no alpha");
        // A cdef box naming channel 3 as opacity is honoured; one naming a COLOUR
        // channel is rejected in favour of the conventional trailing position.
        assert_eq!(resolve_alpha(1, 4, 3, Some(3)), (Some(3), false));
        assert_eq!(resolve_alpha(1, 4, 3, Some(1)), (Some(3), false));
        // Gray+alpha is two channels, not four.
        assert_eq!(resolve_alpha(1, 2, 1, None), (Some(1), false));
        assert_eq!(resolve_alpha(0, 2, 1, None), (None, false));
    }

    // §7.4.9: the PDF's /ColorSpace overrides the codestream's — except for sYCC, which
    // is a channel encoding the decoder must still convert, not a PDF colour space.
    #[test]
    fn pdf_colorspace_overrides_codestream_except_ycc() {
        use super::jp2::{resolve_interp, Interp};
        assert!(resolve_interp(Some(Interp::Rgb), Some(Interp::Cmyk), 4) == Interp::Cmyk,
            "the PDF /ColorSpace wins over the codestream's");
        assert!(resolve_interp(Some(Interp::Ycc), Some(Interp::Rgb), 3) == Interp::Ycc,
            "sYCC must still be converted, so a /DeviceRGB hint cannot suppress it");
        assert!(resolve_interp(None, None, 1) == Interp::Gray, "fall back to channel count");
        assert!(resolve_interp(None, None, 4) == Interp::Cmyk);
        assert!(resolve_interp(Some(Interp::Gray), None, 1) == Interp::Gray);
    }

    fn solid_fill(argb: u32, pts: &[(f32, f32)]) -> Prim {
        Prim::Fill {
            argb,
            even_odd: false,
            contours: vec![pts.to_vec()],
            blend: BlendMode::Normal,
        }
    }

    // The tiling-pattern cell rasterizer (8.7.3.3): a cell must land in the raster with
    // the right colour, the right ORIENTATION, and transparency everywhere the cell does
    // not paint. Row 0 is the TOP of the image (unit-square v=1), per 8.9.5.2's
    // upper-left first sample and the convention real decoded scanlines already follow.
    // Getting this backwards mirrors every cell and every shading vertically.
    #[test]
    fn tile_raster_places_row_zero_at_high_y() {
        // Paint only the BOTTOM half of a unit box.
        let prims = vec![solid_fill(
            0xFF00_00FF,
            &[(0.0, 0.0), (1.0, 0.0), (1.0, 0.5), (0.0, 0.5)],
        )];
        let out = rasterize_prims_to_rgba(&prims, [0.0, 0.0, 1.0, 1.0], 4, 4).expect("rasterizes");
        assert_eq!(out.len(), 4 * 4 * 4);
        // Row 0 = TOP = high y = the unpainted half, transparent rather than black.
        assert_eq!(out[3], 0, "row 0 is the box's HIGH y edge, which is unpainted here");
        // Row 3 = bottom = low y = painted.
        let bottom = 3 * 4 * 4;
        assert_eq!(&out[bottom..bottom + 4], &[0, 0, 255, 255], "the last row is low y");
    }

    // A hairline narrower than one pixel must survive as partial coverage. Without
    // antialiasing a hatch rule drops out of a small cell raster entirely, which looks
    // exactly like the blank-region bug the raster is meant to cure.
    #[test]
    fn tile_raster_antialiases_a_subpixel_hairline() {
        // A 0.1-unit-wide vertical bar in a 1x1 box rendered at 4x4: a tenth of a unit is
        // 0.4 px, so no pixel centre lands inside it.
        let prims = vec![solid_fill(
            0xFF00_0000,
            &[(0.45, 0.0), (0.55, 0.0), (0.55, 1.0), (0.45, 1.0)],
        )];
        let out = rasterize_prims_to_rgba(&prims, [0.0, 0.0, 1.0, 1.0], 4, 4).expect("rasterizes");
        let painted: u32 = out.chunks_exact(4).map(|p| p[3] as u32).sum();
        assert!(painted > 0, "a subpixel hairline must not vanish");
        let (row, col) = (0usize, 1usize);
        let mid = out[(row * 4 + col) * 4 + 3];
        assert!(mid > 0 && mid < 255, "partial coverage, got alpha {mid}");
    }

    // Strokes are outlined and unioned with nonzero winding. Overlapping segment quads
    // must not composite twice — that darkens every join of a translucent stroke — and
    // opposite quad orientations must not cancel to nothing.
    #[test]
    fn tile_raster_strokes_union_without_double_compositing() {
        let prims = vec![Prim::Stroke {
            argb: 0x8000_0000, // half-opaque black
            width: 0.4,
            dash: Vec::new(),
            dash_phase: 0.0,
            cap: 0,
            join: 0,
            miter: 10.0,
            // An L: the two segments overlap at the corner vertex.
            pts: vec![(0.2, 0.5), (0.5, 0.5), (0.5, 0.2)],
            blend: BlendMode::Normal,
        }];
        let out = rasterize_prims_to_rgba(&prims, [0.0, 0.0, 1.0, 1.0], 16, 16).expect("rasterizes");
        // The corner pixel is covered by both quads and both vertex squares.
        let corner = out[(8 * 16 + 8) * 4 + 3];
        assert!(corner > 0, "the stroke must paint at all");
        assert!(
            corner <= 130,
            "a half-opaque stroke must stay half-opaque at a join, got alpha {corner}"
        );
    }

    // The cap is on the RASTER, not the tile count — that inversion is the point of the
    // whole exercise, so an over-budget request must be refused rather than allocated.
    #[test]
    fn tile_raster_refuses_an_over_budget_request() {
        let prims = vec![solid_fill(0xFFFF_0000, &[(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)])];
        let side = MAX_TILE_RASTER_BYTES; // wildly over any sane cell size
        assert!(rasterize_prims_to_rgba(&prims, [0.0, 0.0, 1.0, 1.0], side, side).is_none());
        // A degenerate box is refused too, rather than dividing by zero.
        assert!(rasterize_prims_to_rgba(&prims, [0.0, 0.0, 0.0, 1.0], 8, 8).is_none());
    }

    // CORRECTION 1 from viewer: a BitmapShader in REPEAT mode has a period equal to the
    // BITMAP's dimensions, and PDF's /XStep is independent of the /BBox. So the cell must
    // be rasterized at the STEP, with transparent padding out to it. A bbox-sized cell
    // would retile at the wrong spacing, which shows as a pattern at subtly wrong density.
    #[test]
    fn pattern_cell_is_rasterized_at_the_step_not_the_bbox() {
        // A 10x10 bbox fully painted, on a 20x20 lattice: half the cell must be padding.
        let prims = vec![solid_fill(
            0xFFFF_0000,
            &[(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0)],
        )];
        let (w, h, data) =
            rasterize_pattern_cell(&prims, [0.0, 0.0, 10.0, 10.0], 20.0, 20.0, 2.0)
                .expect("non-overlapping pattern rasterizes");
        // 20 step units x 2 px/unit = 40, NOT the bbox's 10 x 2 = 20.
        assert_eq!((w, h), (40, 40), "the raster covers /XStep x /YStep");
        assert_eq!(data.len(), 40 * 40 * 4);
        // Row 0 is the TOP (high y), which is beyond the bbox: transparent padding.
        assert_eq!(data[3], 0, "step-beyond-bbox margin must be transparent");
        // The last row is the bottom (low y), where the bbox content sits.
        let bottom = (39 * 40) * 4;
        assert_eq!(
            &data[bottom..bottom + 4],
            &[255, 0, 0, 255],
            "the cell content is present at the bbox origin"
        );
    }

    // CORRECTION 2 from viewer was that a single-cell repeat cannot express 8.7.3.1
    // overlap. True, but the pattern IS still periodic with period (xstep, ystep) - so
    // instead of refusing, the cell is drawn at every lattice offset that can reach one
    // period window. This test isolates that: the cell paints ONLY its top-right quadrant,
    // which lies entirely OUTSIDE the period window, so a single-cell raster would be
    // fully transparent. The neighbour at offset (-1,-1) is what fills it.
    #[test]
    fn overlapping_pattern_composites_reaching_neighbours() {
        // bbox 20x20, step 10 -> each period window is reached by 2x2 cells.
        let prims = vec![solid_fill(
            0xFFFF_0000,
            &[(10.0, 10.0), (20.0, 10.0), (20.0, 20.0), (10.0, 20.0)],
        )];
        let bbox = [0.0, 0.0, 20.0, 20.0];
        let (w, h, data) = rasterize_pattern_cell(&prims, bbox, 10.0, 10.0, 2.0)
            .expect("an overlapping pattern is still periodic");
        assert_eq!((w, h), (20, 20), "the period is the STEP, 10 units at scale 2");
        // Every pixel of the period must be opaque: cell (-1,-1)'s top-right quadrant
        // lands exactly on the window. Without neighbour compositing this is all zero.
        let opaque = data.chunks_exact(4).filter(|p| p[3] == 255).count();
        assert_eq!(
            opaque,
            (w * h) as usize,
            "the reaching neighbour must fill the whole period; got {opaque} of {}",
            w * h
        );
        assert_eq!(&data[0..3], &[255, 0, 0], "and carry the cell's colour");
    }

    // The non-overlapping case must be unaffected, and a pathological bbox/step ratio
    // must still fall back rather than compositing an absurd number of copies.
    #[test]
    fn pattern_cell_gates_only_pathological_overlap() {
        let prims = vec![solid_fill(
            0xFFFF_0000,
            &[(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0)],
        )];
        let bbox = [0.0, 0.0, 10.0, 10.0];
        // Exactly abutting: one copy, no neighbours.
        assert!(rasterize_pattern_cell(&prims, bbox, 10.0, 10.0, 2.0).is_some());
        // Mild overlap is now supported rather than refused.
        assert!(rasterize_pattern_cell(&prims, bbox, 6.0, 10.0, 2.0).is_some());
        assert!(rasterize_pattern_cell(&prims, bbox, 10.0, 6.0, 2.0).is_some());
        // A bbox 100x the step in both axes would need 10,000 copies per period.
        assert!(
            rasterize_pattern_cell(&prims, bbox, 0.1, 0.1, 2.0).is_none(),
            "a pathological bbox/step ratio must fall back to the per-tile path"
        );
        // Degenerate steps are refused rather than dividing by zero.
        assert!(rasterize_pattern_cell(&prims, bbox, 0.0, 10.0, 2.0).is_none());
        assert!(rasterize_pattern_cell(&prims, bbox, 10.0, 10.0, 0.0).is_none());
    }

    // A cell over budget is SCALED, not refused: the tiling stays exact because the
    // renderer's period is the bitmap whatever its resolution, so downscaling only softens
    // the cell. Refusing would drop back to the per-tile path for no reason.
    #[test]
    fn oversized_pattern_cell_scales_to_fit_its_budget() {
        let prims = vec![solid_fill(0xFF00_FF00, &[(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)])];
        let (w, h, data) =
            rasterize_pattern_cell(&prims, [0.0, 0.0, 1.0, 1.0], 1.0, 1.0, 4000.0)
                .expect("must scale rather than refuse");
        assert!(
            (w as usize) * (h as usize) * 4 <= MAX_TILE_RASTER_BYTES,
            "{w}x{h} exceeds MAX_TILE_RASTER_BYTES"
        );
        assert_eq!(w, h, "a square period stays square");
        assert_eq!(data.len(), (w as usize) * (h as usize) * 4);
    }

    // §8.9.7 Table 93 abbreviates the stencil flag to `/IM`, which this path did not
    // check — so an inline image mask was decoded as a one-bit DeviceGray image and
    // painted as an opaque black-and-white rectangle instead of stencilling the fill
    // colour, hiding whatever was underneath.
    #[test]
    fn inline_image_mask_stencils_the_fill_colour() {
        let doc = Document::with_version("1.7");
        // 8x1, bits 1,0,1,0,0,0,0,0. Default /Decode: sample 0 MARKS the page.
        let stream = Stream::new(
            dictionary! { "W" => 8, "H" => 1, "IM" => true },
            vec![0b1010_0000u8],
        );
        let img = extract_inline_image(&doc, &stream, 0xFF00_FF00, &HashMap::new())
            .expect("inline stencil decodes");
        assert_eq!((img.w, img.h, img.format), (8, 1, 0));
        // Bit 1 -> sample 255 -> not marked -> transparent.
        assert_eq!(img.data[3], 0, "a 1 bit leaves the page alone");
        // Bit 0 -> sample 0 -> marked -> painted with the fill colour, opaque.
        assert_eq!(&img.data[4..8], &[0, 255, 0, 255], "a 0 bit paints the fill colour");
        assert_eq!(img.data[11], 0, "and the next 1 bit is transparent again");
    }

    // Same path, /D [1 0]: the inline abbreviation for /Decode must reverse the sense.
    #[test]
    fn inline_image_mask_honours_abbreviated_decode() {
        let doc = Document::with_version("1.7");
        let stream = Stream::new(
            dictionary! { "W" => 8, "H" => 1, "IM" => true, "D" => vec![1.into(), 0.into()] },
            vec![0b1010_0000u8],
        );
        let img = extract_inline_image(&doc, &stream, 0xFF00_FF00, &HashMap::new()).unwrap();
        assert_eq!(&img.data[0..4], &[0, 255, 0, 255], "/D [1 0] paints where the 1 bits are");
        assert_eq!(img.data[7], 0);
    }

    // An inline image whose /CS names a resource entry must decode at the resolved
    // component count. `colorspace_info` reports 1 for every name, so a 3-component
    // space decoded at 1/3 stride — the classic sheared-grey-garbage look.
    #[test]
    fn inline_image_resolves_named_colorspace_stride() {
        let mut doc = Document::with_version("1.7");
        let cs_id = doc.add_object(Object::Array(vec![
            Object::Name(b"CalRGB".to_vec()),
            Object::Dictionary(dictionary! {
                "WhitePoint" => vec![0.9505.into(), 1.0.into(), 1.089.into()],
            }),
        ]));
        let mut res = HashMap::new();
        res.insert(b"Cs0".to_vec(), cs_id);
        // 2x1 pixels of 3 components: red then green.
        let stream = Stream::new(
            dictionary! { "W" => 2, "H" => 1, "BPC" => 8, "CS" => "Cs0" },
            vec![255u8, 0, 0, 0, 255, 0],
        );
        let img = extract_inline_image(&doc, &stream, 0xFF00_0000, &res).expect("decodes");
        assert_eq!((img.w, img.h), (2, 1));
        assert!(img.data[0] > img.data[1], "pixel 0 is red-dominant, got {:?}", &img.data[0..3]);
        assert!(img.data[5] > img.data[4], "pixel 1 is green-dominant, got {:?}", &img.data[4..7]);
    }

    // The colour-key range bounds must pass through the SAME scaling the unpacker
    // applied to the samples. A generic `v*255/maxval` agrees for 1/2/4/8 bpc but not
    // for 12 or 16, where the unpacker keeps the high bits — so a range could miss the
    // very value it names.
    #[test]
    fn color_key_ranges_match_the_unpacker_at_16bpc() {
        // One 16-bpc gray pixel, raw value 0x8000. The unpacker keeps the high byte: 0x80.
        let samples = vec![0x80u8, 0x00];
        let comps = unpack_samples_to_bytes(&samples, 1, 1, 1, 16).unwrap();
        assert_eq!(comps[0], 0x80);
        let mut rgba = vec![9u8, 9, 9, 255];
        // A range naming exactly that raw value must mask the pixel.
        apply_color_key_mask_samples(&mut rgba, &comps, 1, &[(0x8000, 0x8000)], 16);
        assert_eq!(rgba[3], 0, "the named 16-bit sample value must be masked");
    }

    // §8.9.5.1 Table 89: /Interpolate defaults to false. Bilevel art must never be
    // smoothed on magnification unless the file explicitly asks — that is what makes a
    // magnified QR code or stencil unreadable — while a contone photo should be.
    #[test]
    fn interpolation_is_refused_for_bilevel_art() {
        let doc = Document::with_version("1.7");
        let contone = dictionary! { "Width" => 8, "Height" => 8, "BitsPerComponent" => 8 };
        assert!(image_should_interpolate(&doc, &contone), "smooth a contone photo");

        let stencil = dictionary! { "Width" => 8, "Height" => 8, "ImageMask" => true };
        assert!(!image_should_interpolate(&doc, &stencil), "never smooth a stencil");

        let one_bit = dictionary! { "Width" => 8, "Height" => 8, "BitsPerComponent" => 1 };
        assert!(!image_should_interpolate(&doc, &one_bit), "never smooth 1-bit art");

        let fax = dictionary! { "Width" => 8, "Height" => 8, "Filter" => "CCITTFaxDecode" };
        assert!(!image_should_interpolate(&doc, &fax), "never smooth a scanned fax");

        // An explicit request wins over all of that.
        let asked = dictionary! {
            "Width" => 8, "Height" => 8, "BitsPerComponent" => 1, "Interpolate" => true,
        };
        assert!(image_should_interpolate(&doc, &asked), "/Interpolate true is honoured");
        // And so does the inline abbreviation `/I`.
        let asked_inline = dictionary! { "W" => 8, "H" => 8, "IM" => true, "I" => true };
        assert!(image_should_interpolate(&doc, &asked_inline), "/I true is honoured");
    }
}