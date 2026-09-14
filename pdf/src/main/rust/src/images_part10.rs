#[cfg(test)]
mod mask_tests {
    use super::*;

    // P0-1: a stream one byte short must still produce a full raster. Returning None
    // here discarded the entire image, which is the "image just isn't there" symptom.
    #[test]
    fn truncated_stream_still_unpacks() {
        // 4x2 RGB at 8bpc needs 24 bytes; supply 20.
        let short = vec![0x7Fu8; 20];
        let out = unpack_samples_to_bytes(&short, 4, 2, 3, 8).expect("must not drop the image");
        assert_eq!(out.len(), 4 * 2 * 3, "raster must be /Width x /Height sized");
        assert_eq!(out[0], 0x7F, "supplied bytes are preserved");
        assert_eq!(out[23], 0, "absent bytes read as zero");
    }

    // An unsupported /BitsPerComponent is read as 8 rather than dropping the image.
    #[test]
    fn unsupported_bpc_falls_back_to_eight() {
        let data = vec![0x11u8; 6];
        let out = unpack_samples_to_bytes(&data, 3, 2, 1, 7).expect("bpc 7 must not drop");
        assert_eq!(out.len(), 6);
    }

    // Scanline padding: 3 pixels of 1bpc occupy one padded byte per row, so row 1
    // starts at byte 1. Getting this wrong shears the image diagonally.
    #[test]
    fn rows_are_byte_padded() {
        // row0 = 1110_0000, row1 = 0000_0000
        let data = vec![0b1110_0000u8, 0b0000_0000];
        let out = unpack_samples_to_bytes(&data, 3, 2, 1, 1).unwrap();
        assert_eq!(&out[..3], &[255, 255, 255], "row 0 all set");
        assert_eq!(&out[3..], &[0, 0, 0], "row 1 reads from the padded byte boundary");
    }

    // P1-4: averaging straight RGBA lets transparent pixels bleed their colour into
    // visible neighbours. One opaque red among three transparent whites must stay red.
    #[test]
    fn premultiplied_downscale_does_not_bleed() {
        let mut data = vec![0u8; 2 * 2 * 4];
        data[0..4].copy_from_slice(&[255, 0, 0, 255]); // opaque red
        for i in 1..4 {
            data[i * 4..i * 4 + 4].copy_from_slice(&[255, 255, 255, 0]); // transparent white
        }
        let (w, h, out) = downscale_rgba(&data, 2, 2, 1, true).expect("must downscale");
        assert_eq!((w, h), (1, 1));
        assert_eq!(out[0], 255, "red channel preserved");
        assert_eq!(out[1], 0, "transparent white must not bleed into green");
        assert_eq!(out[2], 0, "transparent white must not bleed into blue");
        assert_eq!(out[3], 63, "alpha is the straight average of 255,0,0,0");
    }

    // A pixel the codec never wrote (alpha 0, undefined RGB) must not be painted with
    // the fill colour — that turned a truncated JBIG2 stencil into a solid block.
    #[test]
    fn stencilize_leaves_undecoded_pixels_alone() {
        let mut rgba = vec![
            0, 0, 0, 0, // never decoded: RGB undefined, alpha 0
            0, 0, 0, 255, // decoded black -> painted
            255, 255, 255, 255, // decoded white -> transparent
        ];
        stencilize(&mut rgba, 0xFF00_FF00, false);
        assert_eq!(rgba[3], 0, "undecoded pixel stays transparent");
        assert_eq!(&rgba[4..8], &[0, 255, 0, 255], "dark pixel takes the fill colour");
        assert_eq!(rgba[11], 0, "light pixel becomes transparent");
    }

    #[test]
    fn adobe_app14_transform_detected() {
        // SOI + APP14 "Adobe" (transform=2) + SOS.
        let data = vec![
            0xFF, 0xD8,
            0xFF, 0xEE, 0x00, 0x0E, // APP14, len=14
            b'A', b'd', b'o', b'b', b'e', 0x00, 0x64, 0x00, 0x00, 0x00, 0x00, 0x02,
            0xFF, 0xDA,
        ];
        assert_eq!(jpeg_adobe_transform(&data), Some(2));
        // A plain JPEG without the Adobe marker returns None.
        let plain = vec![0xFF, 0xD8, 0xFF, 0xDA];
        assert_eq!(jpeg_adobe_transform(&plain), None);
    }

    #[test]
    fn sof_component_count_read() {
        // SOI + SOF0 declaring 3 components, padded to the segment length.
        let mut data = vec![
            0xFF, 0xD8,
            0xFF, 0xC0, 0x00, 0x11, // SOF0, len=17
            0x08, 0x00, 0x01, 0x00, 0x01, 0x03, // prec, h, w, ncomp=3
        ];
        data.extend(std::iter::repeat_n(0u8, 9)); // 3 component specs
        assert_eq!(jpeg_num_components(&data), Some(3));
    }

    #[test]
    fn color_key_masks_cmyk_in_range() {
        // Two CMYK pixels: first inside the key range, second outside.
        let mut rgba = vec![10u8, 20, 30, 255,  40, 50, 60, 255];
        let comps = vec![5u8, 5, 5, 5,  200, 200, 200, 200];
        let ranges = [(0u32, 10), (0, 10), (0, 10), (0, 10)];
        apply_color_key_mask_samples(&mut rgba, &comps, 4, &ranges, 8);
        assert_eq!(rgba[3], 0, "in-range CMYK pixel becomes transparent");
        assert_eq!(rgba[7], 255, "out-of-range pixel stays opaque");
    }

    #[test]
    fn matte_un_premultiplies() {
        // Black matte: c = c' / alpha. c'=100, alpha=128/255 -> ~199.
        let mut rgba = vec![100u8, 100, 100, 128];
        apply_matte(&mut rgba, [0.0, 0.0, 0.0]);
        assert!((198..=201).contains(&rgba[0]), "un-premult ~199, got {}", rgba[0]);
        assert_eq!(rgba[3], 128, "alpha is preserved");
    }

    #[test]
    fn bilevel_downscale_keeps_two_colours() {
        // A 4x1 black/white checker halved. Averaging blends each pair to mid grey and is
        // what makes a downscaled QR code unreadable; nearest keeps the pixels it picks.
        let row = vec![
            0u8, 0, 0, 255,
            255, 255, 255, 255,
            0, 0, 0, 255,
            255, 255, 255, 255,
        ];
        let (w, _, smooth) = downscale_rgba(&row, 4, 1, 2, true).unwrap();
        assert_eq!(w, 2);
        assert!(smooth[0] > 100 && smooth[0] < 155, "averaged to grey, got {}", smooth[0]);

        let (_, _, nearest) = downscale_rgba(&row, 4, 1, 2, false).unwrap();
        assert_eq!(nearest[0], 0, "first block keeps its black sample");
        assert_eq!(nearest[4], 0, "second block keeps its black sample");
        assert_eq!(nearest[3], 255, "alpha is not blended either");
    }

    /// A 1-bit mask stream, uncompressed: bits 1,0,1,0,0,0,0,0.
    fn one_bit_mask_stream(image_mask: bool, decode_inverted: bool) -> Stream {
        let mut d = dictionary! {
            "Type" => "XObject",
            "Subtype" => "Image",
            "Width" => 8,
            "Height" => 1,
            "BitsPerComponent" => 1,
            "ColorSpace" => "DeviceGray",
        };
        if image_mask {
            d.set("ImageMask", true);
        }
        if decode_inverted {
            d.set("Decode", vec![1.into(), 0.into()]);
        }
        Stream::new(d, vec![0b1010_0000u8])
    }

    // §11.6.5.3 (/SMask) and §8.9.6.4 + §8.9.6.2 (explicit stencil /Mask) require
    // OPPOSITE polarities from the same 1-bit samples, and round 1's rework routed both
    // through one `decode_mask_stream_gray`. Pin the two directions against each other:
    // if a future change collapses them again, exactly one of these must fail.
    #[test]
    fn smask_and_stencil_mask_have_opposite_polarity() {
        let mut doc = Document::with_version("1.7");
        let sm = doc.add_object(one_bit_mask_stream(false, false));
        let sm_dict = dictionary! { "SMask" => Object::Reference(sm) };
        // /SMask: the sample IS the alpha, so bit 1 (white) is OPAQUE.
        assert_eq!(
            read_smask(&doc, &sm_dict, 8, 1).expect("smask decodes"),
            vec![255, 0, 255, 0, 0, 0, 0, 0],
            "/SMask bit 1 must be opaque"
        );

        let mk = doc.add_object(one_bit_mask_stream(true, false));
        let mk_dict = dictionary! { "Mask" => Object::Reference(mk) };
        // Stencil /Mask: sample 1 marks the area MASKED OUT, so bit 1 is alpha 0.
        assert_eq!(
            read_explicit_mask(&doc, &mk_dict, 8, 1).expect("mask decodes"),
            vec![0, 255, 0, 255, 255, 255, 255, 255],
            "stencil /Mask bit 1 must be masked out"
        );
    }

    // /Decode [1 0] reverses each convention exactly once (§8.9.6.2). Applying it twice
    // (or in the resample arm only) is the failure mode round 1 was chasing.
    #[test]
    fn decode_inverts_each_mask_convention_once() {
        let mut doc = Document::with_version("1.7");
        let sm = doc.add_object(one_bit_mask_stream(false, true));
        let sm_dict = dictionary! { "SMask" => Object::Reference(sm) };
        assert_eq!(
            read_smask(&doc, &sm_dict, 8, 1).expect("smask decodes"),
            vec![0, 255, 0, 255, 255, 255, 255, 255],
            "/SMask with /Decode [1 0] is the inverse of the default"
        );

        let mk = doc.add_object(one_bit_mask_stream(true, true));
        let mk_dict = dictionary! { "Mask" => Object::Reference(mk) };
        assert_eq!(
            read_explicit_mask(&doc, &mk_dict, 8, 1).expect("mask decodes"),
            vec![255, 0, 255, 0, 0, 0, 0, 0],
            "stencil /Mask with /Decode [1 0] is the inverse of the default"
        );
    }

    // /Decode must survive RESAMPLING, not just the same-size fast path: request the
    // mask at 4x1 (a decimated base raster) and the inverted sense must still hold.
    #[test]
    fn inverted_mask_survives_resampling() {
        let mut doc = Document::with_version("1.7");
        let mk = doc.add_object(one_bit_mask_stream(true, true));
        let mk_dict = dictionary! { "Mask" => Object::Reference(mk) };
        let a = read_explicit_mask(&doc, &mk_dict, 4, 1).expect("mask decodes");
        assert_eq!(a.len(), 4);
        assert_eq!(a[0], 255, "first sample keeps the inverted sense after resampling");
        assert_eq!(a[3], 0, "trailing zero bits stay masked-out under /Decode [1 0]");
    }

    // The bug this pins: `stream_data` is lopdf's decoder, which implements only
    // Flate/LZW/ASCII85 and returns Err for everything else — and maps that Err to an
    // EMPTY buffer whenever a /Filter is present. The unpacker then read absent bytes as
    // sample 0, which for a /SMask is alpha 0, so the base image vanished completely.
    // DCTDecode, RunLengthDecode and ASCIIHexDecode all hit that path; a DCT soft mask
    // beside a DCT image is the commonest /SMask in the wild.
    #[test]
    fn smask_with_filter_lopdf_cannot_decode_still_masks() {
        let mut doc = Document::with_version("1.7");
        let sm = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject",
                "Subtype" => "Image",
                "Width" => 2,
                "Height" => 2,
                "ColorSpace" => "DeviceGray",
                "BitsPerComponent" => 8,
                "Filter" => "ASCIIHexDecode",
            },
            b"00FF7F40>".to_vec(),
        ));
        let dict = dictionary! { "SMask" => Object::Reference(sm) };
        let alpha = read_smask(&doc, &dict, 2, 2).expect("mask must decode, not vanish");
        assert_eq!(
            alpha,
            vec![0x00, 0xFF, 0x7F, 0x40],
            "the /SMask sample value IS the alpha (§11.6.5.3)"
        );
    }

    // A mask stream that decodes to nothing must leave the base image UNMASKED rather
    // than fully transparent: the old fallthrough read every absent sample as 0.
    #[test]
    fn undecodable_mask_leaves_image_unmasked() {
        let mut doc = Document::with_version("1.7");
        let sm = doc.add_object(Stream::new(
            dictionary! {
                "Width" => 2,
                "Height" => 2,
                "BitsPerComponent" => 8,
                // Not a filter any decoder here implements, so the chain yields nothing.
                "Filter" => "NoSuchDecode",
            },
            vec![1u8, 2, 3, 4],
        ));
        let dict = dictionary! { "SMask" => Object::Reference(sm) };
        assert!(
            read_smask(&doc, &dict, 2, 2).is_none(),
            "no mask is better than an all-transparent mask"
        );
    }

    /// Build a `/SMask` whose /JBIG2Globals is reachable only via an ARRAY /DecodeParms
    /// paired index-by-index with `/Filter [/FlateDecode /JBIG2Decode]`.
    fn jbig2_mask_with_array_decodeparms(doc: &mut Document) -> Dictionary {
        let globals = doc.add_object(Stream::new(dictionary! {}, vec![0u8; 8]));
        let sm = doc.add_object(Stream::new(
            dictionary! {
                "Width" => 8,
                "Height" => 8,
                "BitsPerComponent" => 1,
                "Filter" => vec![Object::Name(b"FlateDecode".to_vec()), Object::Name(b"JBIG2Decode".to_vec())],
                "DecodeParms" => vec![
                    Object::Null,
                    Object::Dictionary(dictionary! { "JBIG2Globals" => Object::Reference(globals) }),
                ],
            },
            vec![0u8; 4],
        ));
        dictionary! { "SMask" => Object::Reference(sm) }
    }

    // The mask path resolved /JBIG2Globals by matching only a DIRECT `Object::Dictionary`
    // /DecodeParms on the stream dict, so `/Filter [/FlateDecode /JBIG2Decode]` with
    // `/DecodeParms [null <</JBIG2Globals 5 0 R>>]` — the common shape — never found it.
    // Without the shared symbol dictionary a JBIG2 mask decodes to nothing, and a failed
    // mask renders as a silently transparent region. `specs` pairs the array with the
    // filter chain index-by-index, which is how the main image path already does it.
    //
    // This asserts the LOOKUP, not a successful decode: the payload is not real JBIG2, so
    // what matters is that the globals are found and the failure is reported as "no mask"
    // rather than an all-transparent one.
    #[test]
    fn jbig2_mask_globals_are_found_through_array_decodeparms() {
        let mut doc = Document::with_version("1.7");
        let dict = jbig2_mask_with_array_decodeparms(&mut doc);
        // Must not come back as a fully-transparent mask, which would delete the image.
        if let Some(alpha) = read_smask(&doc, &dict, 8, 8) {
            assert!(
                alpha.iter().any(|v| *v != 0),
                "an undecodable JBIG2 mask must not mask the whole image away"
            );
        }
    }

    // §7.4.7: both JBIG2 paths must resolve /JBIG2Globals identically. `specs` pairs an
    // array /DecodeParms with the filter chain index-by-index, but producers also emit the
    // array MISALIGNED with the chain, and matching only a direct `Object::Dictionary` —
    // which both call sites did as their fallback — dropped the globals. A JBIG2 decode
    // without them fails, and a failed decode renders the region silently transparent.
    #[test]
    fn jbig2_globals_are_found_in_every_decodeparms_shape() {
        let mut doc = Document::with_version("1.7");
        let globals = doc.add_object(Object::Stream(Stream::new(
            lopdf::dictionary! {},
            b"GLOBALS".to_vec(),
        )));
        let parms = || lopdf::dictionary! { "JBIG2Globals" => Object::Reference(globals) };
        let expect = |d: Dictionary, why: &str| {
            let specs = filters::filter_specs_from_dict(&doc, &d);
            assert_eq!(
                super::jbig2_globals(&doc, &d, &specs).as_deref(),
                Some(&b"GLOBALS"[..]),
                "{why}"
            );
        };
        // Paired through the filter chain: a single filter, and an array whose second
        // element carries the parameters.
        expect(
            lopdf::dictionary! { "Filter" => Object::Name(b"JBIG2Decode".to_vec()), "DecodeParms" => parms() },
            "single /Filter with a dict /DecodeParms",
        );
        expect(
            lopdf::dictionary! {
                "Filter" => Object::Array(vec![Object::Name(b"FlateDecode".to_vec()), Object::Name(b"JBIG2Decode".to_vec())]),
                "DecodeParms" => Object::Array(vec![Object::Null, Object::Dictionary(parms())]),
            },
            "/Filter [/FlateDecode /JBIG2Decode] with an aligned array /DecodeParms",
        );
        // Misaligned: the globals sit at the index of the OTHER filter, so `specs` pairs
        // `None` with JBIG2 and only an array-scanning fallback finds them.
        expect(
            lopdf::dictionary! {
                "Filter" => Object::Array(vec![Object::Name(b"FlateDecode".to_vec()), Object::Name(b"JBIG2Decode".to_vec())]),
                "DecodeParms" => Object::Array(vec![Object::Dictionary(parms()), Object::Null]),
            },
            "an array /DecodeParms misaligned with the filter chain",
        );
    }

    // ---- Compressed-codec stencil paths -----------------------------------
    // The polarity tests above all drive the UNCOMPRESSED 1-bit path. The codec
    // branches reach `stencilize` by a different route and each has its own
    // inversion source, so they need their own pins. CCITT is the worst case: it
    // folds `/Decode` into `black_bit` while building the raster and then calls
    // `stencilize(.., false)` precisely so the same `/Decode` is not applied a
    // second time. Nothing caught a regression there before these.

    /// Group 4 (T.6) encode `rows` of `true` = black pels, as `/CCITTFaxDecode`
    /// with `/K -1` expects.
    pub(super) fn g4_encode(rows: &[Vec<bool>], width: u32) -> Vec<u8> {
        let mut enc = fax::encoder::Encoder::new(fax::VecWriter::new());
        for row in rows {
            let pels = row
                .iter()
                .map(|&b| if b { fax::Color::Black } else { fax::Color::White });
            enc.encode_line(pels, width).expect("VecWriter is infallible");
        }
        enc.finish().expect("infallible").finish()
    }

    /// 8x2, left half black. Returned with the row pattern so a test can state
    /// the expectation in terms of ink rather than bits.
    pub(super) fn half_black_g4() -> (Vec<u8>, usize) {
        let row: Vec<bool> = (0..8).map(|x| x < 4).collect();
        (g4_encode(&[row.clone(), row], 8), 8)
    }

    pub(super) fn ccitt_stencil(decode_inverts: bool, black_is1: bool) -> ImageData {
        let doc = Document::with_version("1.7");
        let (data, w) = half_black_g4();
        let mut parms = dictionary! { "K" => -1, "Columns" => w as i64, "Rows" => 2 };
        if black_is1 {
            parms.set("BlackIs1", true);
        }
        let mut d = dictionary! {
            "Width" => w as i64, "Height" => 2, "BitsPerComponent" => 1,
            "ImageMask" => true,
            "Filter" => "CCITTFaxDecode",
            "DecodeParms" => parms,
        };
        if decode_inverts {
            d.set("Decode", vec![1.into(), 0.into()]);
        }
        extract_image(&doc, &Stream::new(d, data), 0xFF00_FF00, &HashMap::new())
            .expect("a G4 stencil must decode")
    }

    /// A `/ImageMask` CCITT stencil paints the fill colour where the fax has BLACK
    /// pels and leaves the rest of the page alone (§8.9.6.2). Rendering the
    /// complement is the "solid dark block over a scanned page" symptom.
    #[test]
    fn ccitt_stencil_paints_the_black_pels() {
        let img = ccitt_stencil(false, false);
        assert_eq!((img.w, img.h, img.format), (8, 2, 0));
        assert_eq!(
            &img.data[0..4], &[0, 255, 0, 255],
            "a black pel takes the fill colour, opaque"
        );
        assert_eq!(img.data[4 * 3 + 3], 255, "still black at x=3");
        assert_eq!(img.data[4 * 4 + 3], 0, "the white half is transparent");
        assert_eq!(img.data[4 * 7 + 3], 0, "and stays transparent to the row end");
        // Row 1 is the same pattern: a polarity that depended on the row would be
        // a reference-line bug rather than a polarity bug.
        assert_eq!(img.data[4 * 8 + 3], 255, "row 1, x=0 is painted");
        assert_eq!(img.data[4 * 12 + 3], 0, "row 1, x=4 is transparent");
    }

}