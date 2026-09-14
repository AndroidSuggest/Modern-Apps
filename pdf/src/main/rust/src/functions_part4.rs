        let f3 = PdfFunction::parse(&doc, &Object::Reference(t3)).expect("parses");
        assert!(f3.eval(&[0.5])[0].is_finite());
    }

    // A non-monotonic /TR must be carried faithfully; only the FIRST output component is
    // used, per 11.6.5.2's one-in/one-out requirement.
    #[test]
    fn transfer_lut_uses_only_the_first_output() {
        let f = PdfFunction::Exponential {
            domain: [0.0, 1.0],
            range: Vec::new(),
            c0: vec![1.0, 0.0, 0.0],
            c1: vec![0.0, 1.0, 1.0],
            n: 1.0,
        };
        let lut = f.to_lut256();
        assert_eq!(lut[0], 255, "first component starts at 1.0");
        assert_eq!(lut[255], 0, "and ends at 0.0");
    }

    // SEAM TEST: dictionary -> `PdfFunction::parse` -> output arity -> `eval_cs_to_rgb`
    // for a Separation. Neither side of this join was witnessed: my own arity tests stop
    // at `out.len()`, and color.rs's Separation tests hand-BUILD a `PdfFunction` rather
    // than parsing one, so nothing exercised parse feeding a real colour conversion.
    //
    // It matters because the failure is silent and total. §8.6.6.4's tint transform must
    // yield `cs_kind_ncomp(alt)` components; when it yields fewer, `eval_cs_to_rgb` falls
    // back to a subtractive grey ramp. So an arity regression in Type 2 parsing does not
    // produce a slightly wrong colour — every spot colour on the page turns grey, which
    // is 7.10's "function bugs show up as wrong Separation colours" in its worst form.
    #[test]
    fn a_parsed_tint_transform_drives_a_separation_to_a_real_colour() {
        let mut doc = Document::with_version("1.7");
        // Type 2 over DeviceCMYK: t=1 -> (0, 1, 1, 0), i.e. red.
        let good = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into(), 0.into(), 0.into(), 0.into()],
            "C1" => vec![0.into(), 1.into(), 1.into(), 0.into()],
            "N" => 1,
        });
        let cs = CsKind::Separation {
            name: b"Spot".to_vec(),
            alt: Box::new(CsKind::DeviceCMYK),
            tint_fn: PdfFunction::parse(&doc, &Object::Reference(good)),
        };
        let res = HashMap::new();
        let argb = eval_cs_to_rgb(&doc, &cs, &[1.0], &res).expect("separation resolves");
        let (r, g, b) = ((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
        assert!(r > 200 && g < 60 && b < 60, "full tint is red, got #{r:02x}{g:02x}{b:02x}");

        // The same Separation whose tint transform parses to the WRONG arity takes the
        // grey-ramp fallback. Asserting the two differ pins the seam without this test
        // needing to know the fallback's formula.
        let short = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into()],
            "C1" => vec![1.into()],
            "N" => 1,
        });
        let cs_short = CsKind::Separation {
            name: b"Spot".to_vec(),
            alt: Box::new(CsKind::DeviceCMYK),
            tint_fn: PdfFunction::parse(&doc, &Object::Reference(short)),
        };
        let fallback = eval_cs_to_rgb(&doc, &cs_short, &[1.0], &res).expect("fallback resolves");
        assert_ne!(
            argb, fallback,
            "a correctly-parsed 4-component tint transform must not land on the grey ramp"
        );
    }
}
