#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn separation_uses_tint_transform() {
        // A Separation colorspace over DeviceRGB whose tint transform is a
        // Type 2 exponential mapping t -> (t, 0, 0): full tint => pure red.
        let doc = Document::with_version("1.7");
        let cs = CsKind::Separation {
            name: b"Spot".to_vec(),
            alt: Box::new(CsKind::DeviceRGB),
            tint_fn: Some(PdfFunction::Exponential {
                domain: [0.0, 1.0],
                range: Vec::new(),
                c0: vec![0.0, 0.0, 0.0],
                c1: vec![1.0, 0.0, 0.0],
                n: 1.0,
            }),
        };
        let res = HashMap::new();
        let argb = eval_cs_to_rgb(&doc, &cs, &[1.0], &res).unwrap();
        assert_eq!(argb & 0x00FF_FFFF, 0x00FF_0000, "full tint should be red");
        let half = eval_cs_to_rgb(&doc, &cs, &[0.5], &res).unwrap();
        let r = (half >> 16) & 0xFF;
        assert!(r > 100 && r < 160, "half tint red channel ~128, got {r}");
    }

    // A Separation with no tint transform must DARKEN as tint rises (§8.6.6.4: 0 is
    // no colorant, 1 is maximum). Feeding the tint straight into a DeviceGray
    // alternate inverted it, so maximum ink rendered WHITE and spot-colour artwork
    // was invisible on a white page.
    #[test]
    fn separation_without_tint_darkens_with_ink() {
        let doc = Document::with_version("1.7");
        let cs = CsKind::Separation {
            name: b"Spot".to_vec(),
            alt: Box::new(CsKind::DeviceGray),
            tint_fn: None,
        };
        let res = HashMap::new();
        let full = eval_cs_to_rgb(&doc, &cs, &[1.0], &res).unwrap() & 0xFF;
        let none = eval_cs_to_rgb(&doc, &cs, &[0.0], &res).unwrap() & 0xFF;
        assert_eq!(full, 0, "maximum ink must be dark, not white");
        assert_eq!(none, 255, "zero tint leaves the page white");
    }

    // A tint transform that returns fewer components than the alternate space needs
    // is broken; it must fall back to the subtractive grey ramp rather than paint a
    // wrong colour or an inverted one.
    #[test]
    fn separation_short_tint_output_falls_back() {
        let doc = Document::with_version("1.7");
        let cs = CsKind::Separation {
            name: b"Spot".to_vec(),
            // DeviceCMYK needs 4 components; this Type 2 yields only 1.
            alt: Box::new(CsKind::DeviceCMYK),
            tint_fn: Some(PdfFunction::Exponential {
                domain: [0.0, 1.0],
                range: Vec::new(),
                c0: vec![0.0],
                c1: vec![1.0],
                n: 1.0,
            }),
        };
        let res = HashMap::new();
        let full = eval_cs_to_rgb(&doc, &cs, &[1.0], &res).unwrap() & 0xFF;
        assert_eq!(full, 0, "short tint output must still darken");
    }

    // §8.6.5.5: ICCBased /N is required, but when it is missing the alternate space's
    // component count is a far better guess than defaulting to 1 (gray) — a wrong
    // count shifts every sample in an image.
    #[test]
    fn iccbased_without_n_infers_from_alternate() {
        use lopdf::{dictionary, Object, Stream};
        let mut doc = Document::with_version("1.7");
        let icc_id = doc.add_object(Stream::new(
            dictionary! { "Alternate" => Object::Name(b"DeviceRGB".to_vec()) },
            vec![0u8; 4],
        ));
        let arr = vec![Object::Name(b"ICCBased".to_vec()), Object::Reference(icc_id)];
        let empty = HashMap::new();
        let kind = parse_cs_array_at(&doc, &arr, &empty, 0).expect("iccbased");
        assert_eq!(cs_kind_ncomp(&kind), 3, "must infer 3 from /Alternate, not default to 1");
    }

    // An Indexed image sample is ONE palette index (§8.6.6.3), not base_ncomp colour
    // components. cs_kind_ncomp still reports base_ncomp, which is what the palette
    // decode below needs — mesh shadings special-case Indexed themselves.
    #[test]
    fn indexed_image_ncomp_is_one() {
        let cs = CsKind::Indexed {
            base: Box::new(CsKind::DeviceRGB),
            lookup: vec![0, 0, 0, 255, 255, 255],
            base_ncomp: 3,
            hival: 1,
        };
        assert_eq!(cs_kind_image_ncomp(&cs), 1, "one index per image sample");
        assert_eq!(cs_kind_ncomp(&cs), 3, "the palette decode still needs the base arity");
    }

    // A named ICCBased colorspace stored as an INDIRECT array in the resource id
    // map must resolve to RGB — not collapse to DeviceGray (the old
    // get_dictionary check rejected arrays, turning colors gray).
    #[test]
    fn named_iccbased_array_resolves_to_rgb_not_gray() {
        use lopdf::{dictionary, Object, Stream};
        let mut doc = Document::with_version("1.7");
        let icc_id = doc.add_object(Stream::new(dictionary! { "N" => 3 }, vec![0u8; 4]));
        let cs_id = doc.add_object(Object::Array(vec![
            Object::Name(b"ICCBased".to_vec()),
            Object::Reference(icc_id),
        ]));
        let mut res = HashMap::new();
        res.insert(b"CS0".to_vec(), cs_id);
        let kind = parse_cs_kind(&doc, Some(&Object::Name(b"CS0".to_vec())), &res)
            .expect("named ICCBased should resolve");
        let argb = eval_cs_to_rgb(&doc, &kind, &[0.0, 1.0, 0.0], &res).unwrap();
        assert_eq!(argb & 0x00FF_FFFF, 0x0000_FF00, "ICCBased/RGB green must stay green");
    }

    // A named colorspace stored as a DIRECT array (not an indirect reference) is
    // not in the pre-built id map, so it must be resolved via the raw resource
    // ColorSpace dict by parse_named_cs.
    #[test]
    fn direct_array_colorspace_resolves_via_resources() {
        use lopdf::{dictionary, Object, Stream};
        let mut doc = Document::with_version("1.7");
        let icc_id = doc.add_object(Stream::new(dictionary! { "N" => 3 }, vec![0u8; 4]));
        let resources = dictionary! {
            "ColorSpace" => dictionary! {
                "CS0" => Object::Array(vec![
                    Object::Name(b"ICCBased".to_vec()),
                    Object::Reference(icc_id),
                ])
            }
        };
        let empty = HashMap::new();
        let kind = parse_named_cs(&doc, &Object::Name(b"CS0".to_vec()), Some(&resources), &empty)
            .expect("direct-array colorspace should resolve");
        let argb = eval_cs_to_rgb(&doc, &kind, &[1.0, 0.0, 0.0], &empty).unwrap();
        assert_eq!(argb & 0x00FF_FFFF, 0x00FF_0000, "direct-array ICCBased red must stay red");
    }

    // Indexed index must clamp to hival, not a hard-coded 255. With hival=1 an
    // out-of-range index (5) should clamp to entry 1, not read past the table.
    #[test]
    fn indexed_clamps_to_hival() {
        let doc = Document::with_version("1.7");
        // Two-entry palette over DeviceRGB: [black, white].
        let cs = CsKind::Indexed {
            base: Box::new(CsKind::DeviceRGB),
            lookup: vec![0, 0, 0, 255, 255, 255],
            base_ncomp: 3,
            hival: 1,
        };
        let res = HashMap::new();
        let over = eval_cs_to_rgb(&doc, &cs, &[5.0], &res).unwrap();
        assert_eq!(over & 0x00FF_FFFF, 0x00FF_FFFF, "index 5 clamps to hival=1 (white)");
    }

    // DeviceN without a tint transform must consider all components, not just the
    // first: two fully-inked colorants should be darker than one.
    #[test]
    fn devicen_without_tint_uses_all_components() {
        let doc = Document::with_version("1.7");
        let cs = CsKind::DeviceN {
            names: vec![b"A".to_vec(), b"B".to_vec()],
            alt: Box::new(CsKind::DeviceGray),
            tint_fn: None,
        };
        let res = HashMap::new();
        let one = eval_cs_to_rgb(&doc, &cs, &[0.5, 0.0], &res).unwrap() & 0xFF;
        let both = eval_cs_to_rgb(&doc, &cs, &[0.5, 0.5], &res).unwrap() & 0xFF;
        assert!(both < one, "two inks ({both}) must be darker than one ({one})");
    }

    // An uncolored Pattern colorspace [/Pattern base] must record its base space
    // so SCN operands resolve in it rather than by arity guessing.
    #[test]
    fn pattern_records_base_colorspace() {
        let doc = Document::with_version("1.7");
        let arr = vec![
            Object::Name(b"Pattern".to_vec()),
            Object::Name(b"DeviceCMYK".to_vec()),
        ];
        let empty = HashMap::new();
        let kind = parse_cs_array_at(&doc, &arr, &empty, 0).expect("pattern cs");
        match kind {
            CsKind::Pattern { base: Some(b) } => {
                assert!(matches!(*b, CsKind::DeviceCMYK), "base must be DeviceCMYK");
            }
            _ => panic!("expected Pattern with base colorspace"),
        }
    }

    // §8.6.6.3: an Indexed palette may be a STREAM, and the palette must actually be
    // decoded. lopdf 0.36's `decompressed_content` implements only Flate/LZW/ASCII85, and
    // the old `unwrap_or_else(|_| s.content.clone())` fallback handed back the still-
    // ENCODED bytes as the palette — so every colour in the image was wrong.
    #[test]
    fn indexed_palette_stream_with_an_unsupported_filter_still_decodes() {
        let mut doc = Document::with_version("1.7");
        // Palette: black, red, green, blue as ASCIIHex.
        let lookup = doc.add_object(Stream::new(
            dictionary! { "Filter" => "ASCIIHexDecode" },
            b"000000FF0000 00FF00 0000FF>".to_vec(),
        ));
        let arr = vec![
            Object::Name(b"Indexed".to_vec()),
            Object::Name(b"DeviceRGB".to_vec()),
            Object::Integer(3),
            Object::Reference(lookup),
        ];
        let empty = HashMap::new();
        let kind = parse_cs_array_at(&doc, &arr, &empty, 0).expect("indexed cs");
        match kind {
            CsKind::Indexed { lookup, base_ncomp, .. } => {
                assert_eq!(base_ncomp, 3);
                assert_eq!(
                    lookup,
                    vec![0, 0, 0, 0xFF, 0, 0, 0, 0xFF, 0, 0, 0, 0xFF],
                    "the palette must be DECODED, not the raw ASCIIHex bytes"
                );
            }
            _ => panic!("expected Indexed"),
        }
    }

    // The same stream through `colorspace_info`, which has its own copy of the palette
    // read and had the same defect.
    #[test]
    fn colorspace_info_indexed_palette_stream_also_decodes() {
        let mut doc = Document::with_version("1.7");
        let lookup = doc.add_object(Stream::new(
            dictionary! { "Filter" => "ASCIIHexDecode" },
            b"0000FF>".to_vec(),
        ));
        let cs = Object::Array(vec![
            Object::Name(b"Indexed".to_vec()),
            Object::Name(b"DeviceRGB".to_vec()),
            Object::Integer(0),
            Object::Reference(lookup),
        ]);
        let (ncomp, indexed) = colorspace_info(&doc, Some(&cs));
        assert_eq!(ncomp, 1, "an Indexed image sample is one palette index");
        let (base_n, palette) = indexed.expect("indexed info");
        assert_eq!(base_n, 3);
        assert_eq!(palette, vec![0x00, 0x00, 0xFF], "palette must be decoded");
    }

    // §8.6.6.5 defers to §8.6.6.4 for the tint transform, which "shall produce one
    // value for each component of the alternate space". The Separation arm has checked
    // that since a previous round; the DeviceN arm did not, so a broken transform fed
    // `eval_cs_to_rgb` a short tuple. Over an RGB/CMYK alternate that returns None,
    // which every caller reads as "leave the current colour alone" — the fill silently
    // keeps whatever colour was set before, which is not a colour anyone chose.
    #[test]
    fn devicen_with_a_short_tint_output_falls_back_instead_of_vanishing() {
        let doc = Document::with_version("1.7");
        let cs = CsKind::DeviceN {
            names: vec![b"A".to_vec(), b"B".to_vec()],
            // DeviceCMYK needs 4 components; this Type 2 yields only 1.
            alt: Box::new(CsKind::DeviceCMYK),
            tint_fn: Some(PdfFunction::Exponential {
                domain: [0.0, 1.0],
                range: Vec::new(),
                c0: vec![0.0],
                c1: vec![1.0],
                n: 1.0,
            }),
        };
        let res = HashMap::new();
        let full = eval_cs_to_rgb(&doc, &cs, &[1.0, 1.0], &res)
            .expect("a broken tint transform must still yield a colour");
        assert_eq!(full & 0xFF, 0, "full ink darkens");
        let none = eval_cs_to_rgb(&doc, &cs, &[0.0, 0.0], &res).expect("resolves");
        assert_eq!(none & 0xFF, 255, "no ink leaves the page white");

        // A well-formed transform is untouched: 2 inks -> DeviceGray, t -> 1-t.
        let ok = CsKind::DeviceN {
            names: vec![b"A".to_vec(), b"B".to_vec()],
            alt: Box::new(CsKind::DeviceGray),
            tint_fn: Some(PdfFunction::Exponential {
                domain: [0.0, 1.0],
                range: Vec::new(),
                c0: vec![1.0],
                c1: vec![0.0],
                n: 1.0,
            }),
        };
        assert_eq!(eval_cs_to_rgb(&doc, &ok, &[0.0, 0.0], &res).unwrap() & 0xFF, 255);
        assert_eq!(eval_cs_to_rgb(&doc, &ok, &[1.0, 1.0], &res).unwrap() & 0xFF, 0);
    }

    // §8.6.5.4 Table 66: /Range bounds a* and b*, and a value outside it "shall be
    // adjusted to the nearest valid value". The image path arrives in range via
    // /Decode, but `sc`/`scn` operands and a tint transform with no /Range of its own
    // do not — and an unbounded a*/b* drives fx/fz far outside the cube-root branch,
    // producing a saturated primary instead of the nearest in-gamut colour.
    #[test]
    fn lab_clamps_a_and_b_to_the_spaces_range() {
        let doc = Document::with_version("1.7");
        let cs = CsKind::Lab {
            white: [0.9505, 1.0, 1.0890],
            range: [[-20.0, 20.0], [-20.0, 20.0]],
        };
        let res = HashMap::new();
        let at_edge = eval_cs_to_rgb(&doc, &cs, &[50.0, 20.0, 0.0], &res).unwrap();
        let beyond = eval_cs_to_rgb(&doc, &cs, &[50.0, 90.0, 0.0], &res).unwrap();
        assert_eq!(at_edge, beyond, "a* past /Range clamps to the edge of /Range");
        let below = eval_cs_to_rgb(&doc, &cs, &[50.0, -90.0, 0.0], &res).unwrap();
        assert_eq!(
            below,
            eval_cs_to_rgb(&doc, &cs, &[50.0, -20.0, 0.0], &res).unwrap()
        );
        // The clamp is not a blanket flattening: inside /Range the colour still varies.
        let inside = eval_cs_to_rgb(&doc, &cs, &[50.0, 0.0, 0.0], &res).unwrap();
        assert_ne!(inside, at_edge, "in-range a* is untouched");
        // And the DEFAULT range (-100..100) leaves ordinary Lab values alone.
        let wide = CsKind::Lab {
            white: [0.9505, 1.0, 1.0890],
            range: [[-100.0, 100.0], [-100.0, 100.0]],
        };
        assert_ne!(
            eval_cs_to_rgb(&doc, &wide, &[50.0, 60.0, 0.0], &res).unwrap(),
            eval_cs_to_rgb(&doc, &wide, &[50.0, 20.0, 0.0], &res).unwrap()
        );
    }

    // `parse_cs_array_at`'s MAX_CS_DEPTH comment describes the exact cycle
    // `[/Indexed 5 0 R 255 <..>]`-stored-as-object-5 creates. `colorspace_info` is a
    // second, parallel walk of the same object graph and did NOT have the guard, so the
    // same file that was safe through one entry point overflowed the stack through the
    // other. A stack overflow is not catchable, so the process dies.
    #[test]
    fn colorspace_info_survives_a_self_referential_indexed_base() {
        let mut doc = Document::with_version("1.7");
        let id = doc.new_object_id();
        doc.set_object(
            id,
            Object::Array(vec![
                Object::Name(b"Indexed".to_vec()),
                Object::Reference(id),
                Object::Integer(255),
                Object::String(vec![0, 0, 0], lopdf::StringFormat::Literal),
            ]),
        );
        let (n, indexed) = colorspace_info(&doc, Some(&Object::Reference(id)));
        assert_eq!(n, 1, "an Indexed image sample is still one index");
        assert!(indexed.is_some());
        // And the other entry point stays consistent.
        assert!(parse_cs_kind(&doc, Some(&Object::Reference(id)), &HashMap::new()).is_some());
    }

    // A malformed Cal*/Lab entry must mean exactly what an ABSENT one means — the spec
    // default — not propagate into the conversion. A real like 1e40 overflows the f32
    // `Object::Real` holds, and an infinite /WhitePoint reaches `adapt_to_d65` where
    // inf/inf is NaN; `rgb_to_argb` saturates NaN to 0, so a CalRGB image rendered as a
    // solid BLACK rectangle. (Same class as the non-finite path operands and shading
    // matrices found this round, on the colour side.)
    #[test]
    fn non_finite_cal_entries_fall_back_to_the_spec_defaults() {
        use lopdf::dictionary;
        let inf = Object::Real(f32::INFINITY);
        let bad_wp = dictionary! {
            "WhitePoint" => vec![inf.clone(), Object::Real(1.0), Object::Real(1.089)]
        };
        assert!(read_white_point(&bad_wp).is_none(), "non-finite /WhitePoint is refused");
        // A zero component breaks the adaptation just as badly.
        let zero_wp = dictionary! {
            "WhitePoint" => vec![Object::Real(0.0), Object::Real(1.0), Object::Real(1.089)]
        };
        assert!(read_white_point(&zero_wp).is_none(), "a zero /WhitePoint component is refused");
        let good_wp = dictionary! {
            "WhitePoint" => vec![Object::Real(0.9505), Object::Real(1.0), Object::Real(1.089)]
        };
        assert!(read_white_point(&good_wp).is_some(), "a sane /WhitePoint still reads");

        assert!(read_gamma_rgb(&dictionary! {
            "Gamma" => vec![inf.clone(), Object::Real(1.0), Object::Real(1.0)]
        }).is_none());
        assert!(read_lab_range(&dictionary! {
            "Range" => vec![Object::Real(-100.0), inf.clone(), Object::Real(-100.0), Object::Real(100.0)]
        }).is_none());
        let mut m: Vec<Object> = (0..9).map(|_| Object::Real(1.0)).collect();
        m[4] = inf;
        assert!(read_matrix_cal(&dictionary! { "Matrix" => m }).is_none());

        // End to end: a CalRGB space whose /WhitePoint overflowed must still render a
        // recognisable colour rather than collapsing to black.
        let doc = Document::with_version("1.7");
        let arr = vec![
            Object::Name(b"CalRGB".to_vec()),
            Object::Dictionary(dictionary! {
                "WhitePoint" => vec![Object::Real(f32::INFINITY), Object::Real(1.0), Object::Real(1.089)]
            }),
        ];
        let empty = HashMap::new();
        let kind = parse_cs_array_at(&doc, &arr, &empty, 0).expect("calrgb");
        let white = eval_cs_to_rgb(&doc, &kind, &[1.0, 1.0, 1.0], &empty).unwrap();
        assert!(
            (white & 0xFF) > 200,
            "CalRGB white must stay light, got {:#010X} - a NaN whitepoint renders black",
            white
        );
    }
}
