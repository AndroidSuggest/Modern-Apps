            "Background" => vec![1.into(), 0.into(), 0.into()],
        });
        let (_, w, h, pat) =
            rasterize_shading_as_pattern(&doc, &sh, &IDENTITY, &HashMap::new(), 32, None)
                .expect("rasterizes");
        let px = |x: usize, y: usize| -> &[u8] {
            let i = (y * w as usize + x) * 4;
            &pat[i..i + 4]
        };
        let mid_y = (h as usize) / 2;
        // Inside the extent the shading has no colour, so the pixel stays CLEAR.
        assert_eq!(
            px(16, mid_y), &[0, 0, 0, 0],
            "a point INSIDE the extent must not take /Background"
        );
        // Outside it, /Background is exactly what Table 78 asks for.
        assert_eq!(px(0, mid_y), &[255, 0, 0, 255], "left of the axis is outside -> background");
        assert_eq!(px(31, mid_y), &[255, 0, 0, 255], "right of the axis is outside -> background");
        // And the whole raster must not be background, which is the flood symptom.
        let red = pat.chunks_exact(4).filter(|p| p[0] == 255 && p[3] == 255).count();
        assert!(
            red < (w as usize) * (h as usize),
            "the shading's own area must not be flooded with /Background"
        );
    }

    /// §7.4.6 Table 11: `/Rows` defaults to 0. Every other CCITT fixture in this file
    /// supplies it explicitly, so `images.rs`'s own `params.rows > 0` fallback at :1163
    /// and the `/Rows`-absent path end to end were unexercised. That is what this pins.
    ///
    /// It does NOT witness `filters::decode_ccitt`'s row-count derivation, and I claimed
    /// otherwise before measuring. a-file mutated that derivation and this test came back
    /// green; reading :1163 explains why, and the reason is structural rather than a
    /// fixture weakness: `rows_est` is `params.rows` or `/Height`, never anything derived
    /// from the data length, and `row_bytes` comes from `columns`. Extra rows the decoder
    /// may produce are never read.
    ///
    /// Precisely: this consumer depends on `decode_ccitt` returning `Some`, NOT on which
    /// row count it chose. Those are different, and "no dependency here" — which is what
    /// this comment said first — was too broad. The `Option` half IS a real dependency and
    /// is covered by `a_long_ccitt_payload_for_a_short_image_still_renders` below.
    /// (a-file's narrowing, after their measurement corrected my first claim.)
    #[test]
    fn a_ccitt_image_without_an_explicit_rows_parameter_still_gets_every_row() {
        let doc = Document::with_version("1.7");
        let (data, w) = half_black_g4();
        // /Rows deliberately ABSENT — the default per Table 11.
        let d = dictionary! {
            "Width" => w as i64, "Height" => 2, "BitsPerComponent" => 1,
            "ImageMask" => true,
            "Filter" => "CCITTFaxDecode",
            "DecodeParms" => dictionary! { "K" => -1, "Columns" => w as i64 },
        };
        let img = extract_image_inner(&doc, &Stream::new(d, data), 0xFF00_FF00, &HashMap::new())
            .expect("a G4 stencil with no /Rows must still decode");
        assert_eq!(
            (img.w, img.h), (8, 2),
            "with /Rows absent the raster must still be /Height rows tall, not truncated"
        );
        // Both rows must carry the fax's ink. A row count derived short leaves row 1
        // entirely unpainted, which renders as the bottom half of a scan going missing.
        let alpha = |x: usize, y: usize| img.data[(y * 8 + x) * 4 + 3];
        assert_eq!(alpha(0, 0), 255, "row 0, x=0 is a black pel - painted");
        assert_eq!(alpha(4, 0), 0, "row 0, x=4 is white - transparent");
        assert_eq!(alpha(0, 1), 255, "row 1, x=0 must be painted too");
        assert_eq!(alpha(4, 1), 0, "row 1, x=4 stays transparent");
    }

    /// The one dependency images.rs DOES have on `filters::decode_ccitt`'s row handling:
    /// the `Option`, not the count. `decode_ccitt` refuses over 20000 rows, and when its
    /// row estimate came from the PAYLOAD LENGTH rather than `/Height`, a long payload
    /// for a short image pushed the estimate past that ceiling, returned `None`, and
    /// skipped this consumer's entire CCITT branch at images.rs:1161 — total loss of the
    /// image through the `Option`, not a wrong raster. That is the invisible-hole symptom.
    ///
    /// WITNESSED: a-file re-applied the payload-derived estimate in filters.rs and this
    /// test FAILED at the raster assertion, alongside their own
    /// `ccitt_row_count_comes_from_the_height_not_the_compressed_length`, while the four
    /// polarity tests above stayed green. Their own test passing under fixed code and
    /// failing in that same invocation certifies the binary held the mutation, so the
    /// result does not rest on marker timing or binary mtime — a failure is consistent
    /// with exactly one compiled state. The seam is covered at this point.
    #[test]
    fn a_long_ccitt_payload_for_a_short_image_still_renders() {
        let doc = Document::with_version("1.7");
        // 8-wide rows, encoded far past the point where payload*8/columns exceeds the
        // decoder's 20000-row ceiling, while /Height stays 2. The phase alternates per
        // row so G4's vertical mode cannot collapse them — identical rows compress to a
        // couple of bits each and never reach the ceiling.
        let rows: Vec<Vec<bool>> = (0..20000)
            .map(|i: usize| (0..8).map(|x| (x + i) % 2 == 0).collect())
            .collect();
        let data = g4_encode(&rows, 8);
        assert!(
            data.len() > 20000,
            "fixture must exceed the row ceiling under a payload-derived estimate, got {} bytes",
            data.len()
        );
        let d = dictionary! {
            "Width" => 8, "Height" => 2, "BitsPerComponent" => 1,
            "ImageMask" => true,
            "Filter" => "CCITTFaxDecode",
            "DecodeParms" => dictionary! { "K" => -1, "Columns" => 8 },
        };
        let img = extract_image_inner(&doc, &Stream::new(d, data), 0xFF00_FF00, &HashMap::new())
            .expect("a long payload must not make the whole image vanish");
        assert_eq!((img.w, img.h), (8, 2), "the raster is sized by /Height, not the payload");
        // The fixture alternates phase per row: row i paints x where (x + i) is even.
        let alpha = |x: usize, y: usize| img.data[(y * 8 + x) * 4 + 3];
        assert_eq!(alpha(0, 0), 255, "row 0, x=0 is a black pel");
        assert_eq!(alpha(1, 0), 0, "row 0, x=1 is white");
        assert_eq!(alpha(0, 1), 0, "row 1 inverts the phase: x=0 is white");
        assert_eq!(alpha(1, 1), 255, "row 1, x=1 is black");
    }

    // ---------------------------------------------------------------------
    // R5: defects visible only with the filter chain and the image decoder in
    // view at once.
    // ---------------------------------------------------------------------

    /// `objects.rs:106` returns an EMPTY buffer when every decoder has failed,
    /// deliberately, so that still-encoded bytes are never handed on as samples. The
    /// image layer then read every absent byte as sample 0 and `image_samples_to_rgba`
    /// turned an all-zero DeviceRGB pixel into black at alpha 255 — so "render
    /// nothing" arrived as an OPAQUE BLACK RECTANGLE covering whatever the image was
    /// placed over. Neither half of that is wrong on its own, which is why it survived:
    /// the filter side's contract and the image side's zero-fill contract only conflict
    /// where they meet.
    #[test]
    fn a_contone_image_whose_filter_chain_fails_is_dropped_not_painted_black() {
        let doc = Document::with_version("1.7");
        let d = dictionary! {
            "Width" => 8, "Height" => 8, "BitsPerComponent" => 8,
            "ColorSpace" => "DeviceRGB",
            "Filter" => "FlateDecode",
        };
        // 0xFF opens a deflate block with the reserved BTYPE, so no inflate attempt
        // (zlib, raw, or skip-one-byte) yields a single byte.
        let junk = vec![0xFFu8; 64];
        assert!(
            crate::filters::decode_flate(&junk).is_none(),
            "fixture must actually be undecodable, or the test proves nothing"
        );
        assert!(
            extract_image_inner(&doc, &Stream::new(d, junk), 0xFF00_0000, &HashMap::new()).is_none(),
            "a failed Flate image must be dropped, not rendered as a black rectangle"
        );
    }

    /// The same seam on the inline path, where the buffer is empty rather than absent:
    /// `/AHx` over a lone EOD marker decodes SUCCESSFULLY to zero bytes, so the
    /// chain-failure guard above it does not fire. Unpacked as a stencil that is
    /// sample 0 everywhere, and sample 0 MARKS the page (§8.9.6.2) — a solid block of
    /// the current fill colour over the content.
    #[test]
    fn an_inline_stencil_that_decodes_to_no_bytes_paints_nothing() {
        let doc = Document::with_version("1.7");
        let d = dictionary! {
            "W" => 8, "H" => 8, "IM" => true, "F" => "AHx",
        };
        assert!(
            extract_inline_image_inner(&doc, &Stream::new(d, b">".to_vec()), 0xFF00_FF00, &HashMap::new())
                .is_none(),
            "an inline stencil with no samples must be dropped, not painted solid"
        );
    }

    /// A partially-decoded inline stencil must not paint the rows the data never
    /// reached. `unpack_samples_to_bytes` zero-fills them by design (§8.9.5.1 gives
    /// /Width x /Height authority over the sample count), and zero is the PAINT value
    /// for a stencil, so the two contracts combined to paint everything below the last
    /// real scanline. The XObject stencil branch already defaulted absent bytes to the
    /// no-paint value; the inline one went through the unpacker and lost the
    /// distinction.
    #[test]
    fn a_truncated_inline_stencil_does_not_paint_the_missing_rows() {
        let doc = Document::with_version("1.7");
        let d = dictionary! { "W" => 8, "H" => 4, "IM" => true };
        // Two rows of "all 1 bits" = nothing painted; rows 2 and 3 are simply absent.
        let img = extract_inline_image_inner(
            &doc,
            &Stream::new(d, vec![0xFFu8, 0xFF]),
            0xFF00_FF00,
            &HashMap::new(),
        )
        .expect("two good rows must still render");
        assert_eq!((img.w, img.h), (8, 4));
        for px in 0..(8 * 4) {
            assert_eq!(
                img.data[px * 4 + 3], 0,
                "pixel {px}: neither a decoded 1 bit nor an absent row may paint"
            );
        }
    }

    /// §8.9.7 Table 93 lists `/CCF` among the filters an INLINE image may name, and
    /// `decode_stream_chain` deliberately leaves every image codec encoded because only
    /// the image layer knows /Width and /Height (filters.rs:545). The inline path never
    /// implemented the other half of that contract, so a G4 codestream went straight
    /// into the sample unpacker and was read as one-bit samples: noise, and for
    /// `/IM true` a speckled block of fill colour over the page.
    #[test]
    fn an_inline_ccitt_stencil_is_decoded_rather_than_unpacked_as_samples() {
        let doc = Document::with_version("1.7");
        let (data, w) = half_black_g4();
        let d = dictionary! {
            "W" => w as i64, "H" => 2, "IM" => true,
            "F" => "CCF",
            "DP" => dictionary! { "K" => -1, "Columns" => w as i64, "Rows" => 2 },
        };
        let img = extract_inline_image_inner(&doc, &Stream::new(d, data), 0xFF00_FF00, &HashMap::new())
            .expect("an inline G4 stencil must decode");
        assert_eq!((img.w, img.h), (8, 2));
        // Same expectation as the XObject fixture: left half black (painted), right
        // half white (transparent), on both rows.
        let alpha = |x: usize, y: usize| img.data[(y * 8 + x) * 4 + 3];
        assert_eq!(alpha(0, 0), 255, "a black pel takes the fill colour");
        assert_eq!(alpha(3, 0), 255, "still ink at x=3");
        assert_eq!(alpha(4, 0), 0, "the white half is transparent");
        assert_eq!(alpha(0, 1), 255, "row 1 is painted the same way");
        assert_eq!(alpha(4, 1), 0);
        assert_eq!(&img.data[0..3], &[0, 255, 0], "and it is the CURRENT FILL COLOUR");
    }

    /// A JBIG2 image that is NOT a stencil still has a `/Decode` array (§8.9.5.2), and
    /// `jbig2.rs` returns finished RGB, so nothing downstream would ever apply it —
    /// `decode_inverts_1bit` was computed and then used only for the stencil arm.
    /// Pinned in both directions so a later change cannot apply it twice.
    #[test]
    fn a_jbig2_image_honours_its_decode_array_exactly_once() {
        let jbig2_image = |decode_inverts: bool| -> ImageData {
            let doc = Document::with_version("1.7");
            let (mmr, w) = half_black_g4();
            let data = jbig2_mmr_stream(w as u32, 2, &mmr);
            let mut d = dictionary! {
                "Width" => w as i64, "Height" => 2, "BitsPerComponent" => 1,
                "ColorSpace" => "DeviceGray",
                "Filter" => "JBIG2Decode",
            };
            if decode_inverts {
                d.set("Decode", vec![1.into(), 0.into()]);
            }
            extract_image(&doc, &Stream::new(d, data), 0xFF00_0000, &HashMap::new())
                .expect("an MMR generic region must decode")
        };
        let plain = jbig2_image(false);
        assert_eq!(plain.data[0], 0, "the left half is black ink");
        assert_eq!(plain.data[4 * 4], 255, "the right half is white paper");

        let inverted = jbig2_image(true);
        assert_eq!(inverted.data[0], 255, "/Decode [1 0] turns the ink white");
        assert_eq!(inverted.data[4 * 4], 0, "and the paper black");
        // Applying it twice would give back `plain`, which this catches.
        assert_ne!(plain.data, inverted.data);
        assert_eq!(
            inverted.data[3], 255,
            "inverting colour must not disturb alpha"
        );
    }
}
