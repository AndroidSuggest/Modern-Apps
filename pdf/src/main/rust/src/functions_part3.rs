#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exponential_eval() {
        let f = PdfFunction::Exponential {
            domain: [0.0, 1.0],
            range: Vec::new(),
            c0: vec![0.0, 0.0, 0.0],
            c1: vec![1.0, 0.5, 0.0],
            n: 1.0,
        };
        let out = f.eval(&[0.5]);
        assert_eq!(out.len(), 3);
        assert!((out[0] - 0.5).abs() < 1e-9);
        assert!((out[1] - 0.25).abs() < 1e-9);
        assert!((out[2] - 0.0).abs() < 1e-9);
    }

    // 7.10.4: subdomain i is [Bounds_i-1, Bounds_i) (with Domain_0 / Domain_1 at the
    // ends), and x is then mapped LINEARLY from that subdomain onto Encode_i. Asserting
    // only "somewhere strictly between 0 and 1" let an off-by-one in either the
    // selection or the sub-interval through; those produce a hard colour discontinuity
    // at a stop, so pin the exact values.
    #[test]
    fn stitching_selects_subfunction() {
        let ramp = |lo: f64, hi: f64| PdfFunction::Exponential {
            domain: [0.0, 1.0],
            range: Vec::new(),
            c0: vec![lo],
            c1: vec![hi],
            n: 1.0,
        };
        let f = PdfFunction::Stitching {
            domain: [0.0, 1.0],
            range: Vec::new(),
            functions: vec![ramp(0.0, 1.0), ramp(1.0, 0.0)],
            bounds: vec![0.5],
            encode: vec![[0.0, 1.0], [0.0, 1.0]],
        };
        // x=0.25 sits at the midpoint of subdomain 0 = [0, 0.5) -> encoded 0.5 -> 0.5.
        assert!((f.eval(&[0.25])[0] - 0.5).abs() < 1e-9);
        // x=0.75 sits at the midpoint of subdomain 1 = [0.5, 1] -> encoded 0.5 -> 0.5.
        assert!((f.eval(&[0.75])[0] - 0.5).abs() < 1e-9);
        // The bound itself belongs to the UPPER subdomain (half-open below), so x=0.5
        // is the START of function 1, which ramps 1 -> 0.
        assert!((f.eval(&[0.5])[0] - 1.0).abs() < 1e-9);
        // Just below it is the END of function 0, which ramps 0 -> 1. The two agree,
        // i.e. the stitch is continuous rather than stepping.
        assert!((f.eval(&[0.5 - 1e-9])[0] - 1.0).abs() < 1e-6);
        assert!((f.eval(&[0.0])[0] - 0.0).abs() < 1e-9);
        assert!((f.eval(&[1.0])[0] - 0.0).abs() < 1e-9);

        // Three subdomains: the MIDDLE one must use [Bounds_0, Bounds_1), not Domain.
        let g = PdfFunction::Stitching {
            domain: [0.0, 1.0],
            range: Vec::new(),
            functions: vec![ramp(0.0, 0.0), ramp(0.0, 1.0), ramp(0.0, 0.0)],
            bounds: vec![0.25, 0.75],
            encode: vec![[0.0, 1.0], [0.0, 1.0], [0.0, 1.0]],
        };
        assert!((g.eval(&[0.5])[0] - 0.5).abs() < 1e-9, "midpoint of [0.25, 0.75)");
        assert!((g.eval(&[0.25])[0] - 0.0).abs() < 1e-9, "lower edge of the middle");
        assert!((g.eval(&[0.75 - 1e-9])[0] - 1.0).abs() < 1e-6, "upper edge");
    }

    // 7.10.1 Table 38: when /Range is present its outputs SHALL be clipped to it. A
    // Type 2 whose /Domain runs past 1 evaluates outside the C0..C1 interval, so this
    // is not vacuous.
    #[test]
    fn exponential_clips_to_range() {
        let mut doc = Document::with_version("1.7");
        let id = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 2.into()],
            "Range" => vec![0.into(), 1.into()],
            "C0" => vec![0.into()],
            "C1" => vec![1.into()],
            "N" => 1,
        });
        let f = PdfFunction::parse(&doc, &Object::Reference(id)).expect("parses");
        assert!((f.eval(&[0.5])[0] - 0.5).abs() < 1e-9, "in range, untouched");
        assert!((f.eval(&[2.0])[0] - 1.0).abs() < 1e-9, "t=2 gives 2.0, clipped to 1");
    }

    #[test]
    fn sampled_linear_1d() {
        // 2 samples, 1 in, 1 out, 8 bps: [0, 255] -> decodes to [0,1].
        let f = PdfFunction::Sampled {
            domain: vec![[0.0, 1.0]],
            range: vec![[0.0, 1.0]],
            size: vec![2],
            bps: 8,
            encode: vec![[0.0, 1.0]],
            decode: vec![[0.0, 1.0]],
            samples: vec![0, 255],
            n_in: 1,
            n_out: 1,
        };
        assert!((f.eval(&[0.0])[0] - 0.0).abs() < 1e-6);
        assert!((f.eval(&[1.0])[0] - 1.0).abs() < 1e-6);
        assert!((f.eval(&[0.5])[0] - 0.5).abs() < 1e-2);
    }

    #[test]
    fn postscript_arith_and_ifelse() {
        // { 2 mul 1 sub dup 0 lt { pop 0 } if }
        let prog = parse_ps_program(b"{ 2 mul 1 sub dup 0 lt { pop 0 } if }").unwrap();
        // input 1.0 -> 2*1-1 = 1.0 (not < 0)
        let out = eval_ps(&prog, &[1.0]).unwrap();
        assert!((out[0] - 1.0).abs() < 1e-9);
        // input 0.0 -> 2*0-1 = -1 < 0 -> 0
        let out2 = eval_ps(&prog, &[0.0]).unwrap();
        assert!((out2[0] - 0.0).abs() < 1e-9);
    }

    // 7.10.5 Table 42 operand ORDER and rounding/truncation semantics. A bug in any of
    // these shows up simultaneously as wrong gradient colours and wrong Separation
    // colours, because both go through the same evaluator.
    #[test]
    fn postscript_operator_semantics_match_table_42() {
        let run = |src: &[u8]| -> Vec<f64> {
            eval_ps(&parse_ps_program(src).expect("parses"), &[]).expect("runs")
        };
        let one = |src: &[u8]| run(src)[0];

        // `num1 num2 sub/div/idiv/mod` take num1 from BELOW num2 on the stack.
        assert_eq!(one(b"{ 7 2 sub }"), 5.0);
        assert_eq!(one(b"{ 7 2 div }"), 3.5);
        // idiv and mod truncate toward zero and keep the dividend's sign.
        assert_eq!(one(b"{ 7 2 idiv }"), 3.0);
        assert_eq!(one(b"{ -7 2 idiv }"), -3.0);
        assert_eq!(one(b"{ -7 2 mod }"), -1.0);
        // `base exponent exp`, not the other way round.
        assert_eq!(one(b"{ 2 10 exp }"), 1024.0);
        // A negative base with a fractional exponent is NaN; it must not escape.
        assert!(one(b"{ -8 0.5 exp }").is_finite());

        // Angles are DEGREES, and `num den atan` returns 0..360.
        assert!((one(b"{ 90 sin }") - 1.0).abs() < 1e-12);
        assert!((one(b"{ 180 cos }") + 1.0).abs() < 1e-12);
        assert!((one(b"{ 0 1 atan }") - 0.0).abs() < 1e-9);
        assert!((one(b"{ 1 0 atan }") - 90.0).abs() < 1e-9);
        assert!((one(b"{ -1 0 atan }") - 270.0).abs() < 1e-9, "never negative");

        // truncate/cvi cut toward zero; round breaks a tie toward +infinity (PLRM),
        // which f64::round does NOT do.
        assert_eq!(one(b"{ -1.7 truncate }"), -1.0);
        assert_eq!(one(b"{ -1.7 cvi }"), -1.0);
        assert_eq!(one(b"{ -1.5 round }"), -1.0);
        assert_eq!(one(b"{ 1.5 round }"), 2.0);
        assert_eq!(one(b"{ -1.7 floor }"), -2.0);
        assert_eq!(one(b"{ -1.7 ceiling }"), -1.0);

        // `n j roll` moves the bottom of the n-group UPWARD for positive j.
        assert_eq!(run(b"{ 1 2 3 3 1 roll }"), vec![3.0, 1.0, 2.0]);
        assert_eq!(run(b"{ 1 2 3 3 -1 roll }"), vec![2.0, 3.0, 1.0]);
        // `n index` counts down from the top, 0 being the top itself.
        assert_eq!(run(b"{ 10 20 30 2 index }"), vec![10.0, 20.0, 30.0, 10.0]);
        assert_eq!(run(b"{ 10 20 2 copy }"), vec![10.0, 20.0, 10.0, 20.0]);
        // `int shift bitshift`, negative shift = right.
        assert_eq!(one(b"{ 1 4 bitshift }"), 16.0);
        assert_eq!(one(b"{ 16 -4 bitshift }"), 1.0);
    }

    // 7.10.5 Table 42's integer operators take their operands from a `f64 as i64`
    // cast, which SATURATES — so any real large enough reaches i64::MIN and
    // `i64::MIN / -1`, `i64::MIN % -1` and `-i64::MIN` all overflow. Rust panics on
    // signed division overflow in RELEASE as well as debug, and the panic unwinds out
    // of the whole page render. This is the sharpest example of the shared-evaluator
    // blast radius: the same six lines serve gradient colours (§8.7.4) and
    // Separation/DeviceN tint transforms (§8.6.6.4), so one crafted Type 4 kills both.
    #[test]
    fn postscript_integer_operators_saturate_instead_of_panicking() {
        let one = |src: &[u8]| eval_ps(&parse_ps_program(src).unwrap(), &[]).unwrap()[0];
        assert!(one(b"{ -1e300 -1 idiv }").is_finite());
        assert!(one(b"{ -1e300 -1 mod }").is_finite());
        assert!(one(b"{ 1 -1e300 bitshift }").is_finite());
        assert!(one(b"{ 1 1e300 bitshift }").is_finite());
        // Division by zero still yields 0 rather than a trap.
        assert_eq!(one(b"{ 7 0 idiv }"), 0.0);
        assert_eq!(one(b"{ 7 0 mod }"), 0.0);
        assert_eq!(one(b"{ 7 0 div }"), 0.0);
        // And the ordinary cases are unchanged.
        assert_eq!(one(b"{ -7 2 idiv }"), -3.0);
        assert_eq!(one(b"{ -7 2 mod }"), -1.0);
    }

    // 7.10.4's `/Functions` array and the array-of-functions form both recurse through
    // `parse`, and nothing stops `5 0 R` naming a Type 3 whose `/Functions` is `[5 0 R]`.
    // That recursed until the stack overflowed — which is NOT a panic, so `catch_unwind`
    // cannot contain it and the process dies rather than the page failing to render.
    #[test]
    fn a_self_referential_function_is_rejected_rather_than_overflowing_the_stack() {
        let mut doc = Document::with_version("1.7");
        let id = doc.new_object_id();
        doc.set_object(
            id,
            dictionary! {
                "FunctionType" => 3,
                "Domain" => vec![0.into(), 1.into()],
                "Functions" => vec![Object::Reference(id)],
                "Bounds" => Vec::<Object>::new(),
                "Encode" => vec![0.into(), 1.into()],
            },
        );
        assert!(PdfFunction::parse(&doc, &Object::Reference(id)).is_none());

        // An array of functions that contains itself takes the other recursive path.
        let arr = doc.new_object_id();
        doc.set_object(arr, Object::Array(vec![Object::Reference(arr)]));
        assert!(PdfFunction::parse(&doc, &Object::Reference(arr)).is_none());

        // A mutual cycle between a Type 3 and an array goes through both arms.
        let a = doc.new_object_id();
        let b = doc.new_object_id();
        doc.set_object(a, Object::Array(vec![Object::Reference(b)]));
        doc.set_object(
            b,
            dictionary! {
                "FunctionType" => 3,
                "Domain" => vec![0.into(), 1.into()],
                "Functions" => vec![Object::Reference(a)],
                "Bounds" => Vec::<Object>::new(),
                "Encode" => vec![0.into(), 1.into()],
            },
        );
        assert!(PdfFunction::parse(&doc, &Object::Reference(a)).is_none());

        // A legitimate one-level Type 3 over two Type 2s still parses.
        let leaf = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into()],
            "C1" => vec![1.into()],
            "N" => 1,
        });
        let ok = doc.add_object(dictionary! {
            "FunctionType" => 3,
            "Domain" => vec![0.into(), 1.into()],
            "Functions" => vec![Object::Reference(leaf), Object::Reference(leaf)],
            "Bounds" => vec![Object::Real(0.5)],
            "Encode" => vec![0.into(), 1.into(), 0.into(), 1.into()],
        });
        assert!(PdfFunction::parse(&doc, &Object::Reference(ok)).is_some());
    }

    // 7.10.2: sample data is ordered with the FIRST input dimension varying fastest,
    // and the reconstruction is multilinear. Nearest-neighbour (or a transposed index)
    // passes a 1-D two-sample test but fails here, and shows as banded gradients.
    #[test]
    fn sampled_is_bilinear_with_first_dimension_fastest() {
        // 2x2 grid, one output. Sample order is (x0,y0) (x1,y0) (x0,y1) (x1,y1).
        let f = PdfFunction::Sampled {
            domain: vec![[0.0, 1.0], [0.0, 1.0]],
            range: vec![[0.0, 1.0]],
            size: vec![2, 2],
            bps: 8,
            encode: vec![[0.0, 1.0], [0.0, 1.0]],
            decode: vec![[0.0, 1.0]],
            samples: vec![0, 255, 0, 0],
            n_in: 2,
            n_out: 1,
        };
        // Corners come straight back.
        assert!((f.eval(&[0.0, 0.0])[0] - 0.0).abs() < 1e-6);
        assert!((f.eval(&[1.0, 0.0])[0] - 1.0).abs() < 1e-6, "x varies fastest");
        assert!((f.eval(&[0.0, 1.0])[0] - 0.0).abs() < 1e-6);
        assert!((f.eval(&[1.0, 1.0])[0] - 0.0).abs() < 1e-6);
        // Interior is the bilinear blend, not a nearest corner.
        assert!((f.eval(&[0.5, 0.0])[0] - 0.5).abs() < 1e-2);
        assert!((f.eval(&[0.5, 0.5])[0] - 0.25).abs() < 1e-2);
        // A quarter step must land a quarter of the way, which nearest-neighbour
        // would snap to 0 or 1.
        assert!((f.eval(&[0.25, 0.0])[0] - 0.25).abs() < 1e-2);
    }

    // A truncated sample stream must read as 0, not as a partially shifted value
    // that looks like a plausible sample.
    #[test]
    fn read_sample_past_end_is_zero() {
        let data = [0xFFu8]; // one byte = one 8-bit sample
        assert!((read_sample(&data, 0, 8) - 255.0).abs() < 1e-9);
        assert_eq!(read_sample(&data, 1, 8), 0.0, "sample past the end reads 0");
        // A 16-bit sample straddling the end must also read 0, not 0xFF00.
        assert_eq!(read_sample(&data, 0, 16), 0.0, "partial sample reads 0");
    }

    // A Type 2 whose C0/C1 are absent must broadcast its scalar defaults to the
    // arity implied by /Range (7.10.3 Table 40 + Table 38), otherwise a spot colour
    // over a 4-component alternate space receives one component and degrades.
    #[test]
    fn exponential_broadcasts_defaults_to_range_arity() {
        let mut doc = Document::with_version("1.7");
        let id = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "N" => 1,
            // 4 pairs => j = 4, with no C0/C1 given.
            "Range" => vec![0.into(), 1.into(), 0.into(), 1.into(),
                            0.into(), 1.into(), 0.into(), 1.into()],
        });
        let f = PdfFunction::parse(&doc, &Object::Reference(id)).expect("type 2 parses");
        let out = f.eval(&[0.5]);
        assert_eq!(out.len(), 4, "arity comes from /Range when C0/C1 are absent");
        for v in &out {
            assert!((v - 0.5).abs() < 1e-9, "each component ramps 0 -> 1");
        }
    }

    // /BitsPerSample outside Table 39's set is rejected at parse. bps == 0 used to
    // divide by zero and produce NaN components that survived the Range clamp.
    #[test]
    fn sampled_rejects_illegal_bits_per_sample() {
        let mk = |bps: i64| {
            let mut doc = Document::with_version("1.7");
            let id = doc.add_object(Stream::new(
                dictionary! {
                    "FunctionType" => 0,
                    "Domain" => vec![0.into(), 1.into()],
                    "Range" => vec![0.into(), 1.into()],
                    "Size" => vec![2.into()],
                    "BitsPerSample" => bps,
                },
                vec![0u8, 255u8],
            ));
            PdfFunction::parse(&doc, &Object::Reference(id)).is_some()
        };
        assert!(mk(8), "8 bps is legal");
        assert!(!mk(0), "0 bps must be rejected");
        assert!(!mk(5), "5 bps is not in Table 39");
    }

    // A sampled function's input arity is taken from /Size and drives a 2^m corner
    // loop per evaluation, so an absurd /Size must be rejected rather than hang.
    #[test]
    fn sampled_rejects_absurd_input_arity() {
        let mut doc = Document::with_version("1.7");
        let size: Vec<Object> = (0..32).map(|_| Object::Integer(2)).collect();
        let id = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 0,
                "Domain" => vec![0.into(), 1.into()],
                "Range" => vec![0.into(), 1.into()],
                "Size" => size,
                "BitsPerSample" => 8,
            },
            vec![0u8; 64],
        ));
        assert!(
            PdfFunction::parse(&doc, &Object::Reference(id)).is_none(),
            "32 input dimensions would mean 2^32 corner evaluations per call"
        );
    }

    // §11.6.5.2: an INVERTING /TR is the standard idiom for "mask out where the group is
    // bright", so it must survive sampling into the LUT exactly. Ignoring it hides the
    // wrong half of the content, which is the visible symptom this LUT exists to fix.
    #[test]
    fn inverting_transfer_function_becomes_an_inverting_lut() {
        let mut doc = Document::with_version("1.7");
        // { 1 exch sub } — the canonical inverter.
        let id = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 4,
                "Domain" => vec![0.into(), 1.into()],
                "Range" => vec![0.into(), 1.into()],
            },
            b"{ 1 exch sub }".to_vec(),
        ));
        let lut = read_transfer_lut(&doc, &Object::Reference(id)).expect("inverting /TR is not identity");
        assert_eq!(lut[0], 255, "0 maps to 255");
        assert_eq!(lut[255], 0, "255 maps to 0");
        assert!(lut[128].abs_diff(127) <= 1, "midpoint stays mid, got {}", lut[128]);
    }

    // /Identity, and anything indistinguishable from it at 8-bit precision, must report
    // None so callers do not pay to carry or apply a no-op table.
    #[test]
    fn identity_transfer_function_is_none() {
        let mut doc = Document::with_version("1.7");
        assert!(
            read_transfer_lut(&doc, &Object::Name(b"Identity".to_vec())).is_none(),
            "/Identity has no effect"
        );
        // A Type 2 ramp 0 -> 1 with N=1 IS the identity.
        let id = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into()],
            "C1" => vec![1.into()],
            "N" => 1,
        });
        assert!(
            read_transfer_lut(&doc, &Object::Reference(id)).is_none(),
            "a 0->1 linear ramp is the identity to within one 8-bit step"
        );
        // Something that is not a function at all also yields None rather than garbage.
        assert!(read_transfer_lut(&doc, &Object::Integer(3)).is_none());
    }

    // 7.10.1 Table 38 gives no ordering guarantee on /Domain or /Range beyond their
    // meaning, and `f64::clamp` PANICS when its low bound exceeds its high bound. A
    // reversed pair therefore aborted the page rather than clipping.
    #[test]
    fn reversed_domain_and_range_clip_instead_of_panicking() {
        let mut doc = Document::with_version("1.7");
        let t2 = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![1.into(), 0.into()],
            "Range" => vec![1.into(), 0.into()],
            "C0" => vec![0.into()],
            "C1" => vec![1.into()],
            "N" => 1,
        });
        let f = PdfFunction::parse(&doc, &Object::Reference(t2)).expect("parses");
        assert!(f.eval(&[0.5])[0].is_finite());

        let t4 = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 4,
                "Domain" => vec![1.into(), 0.into()],
                "Range" => vec![1.into(), 0.into()],
            },
            b"{ }".to_vec(),
        ));
        let f4 = PdfFunction::parse(&doc, &Object::Reference(t4)).expect("parses");
        assert!(f4.eval(&[0.5])[0].is_finite());

        let t0 = doc.add_object(Stream::new(
            dictionary! {
                "FunctionType" => 0,
                "Domain" => vec![1.into(), 0.into()],
                "Range" => vec![1.into(), 0.into()],
                "Size" => vec![2.into()],
                "BitsPerSample" => 8,
            },
            vec![0u8, 255u8],
        ));
        let f0 = PdfFunction::parse(&doc, &Object::Reference(t0)).expect("parses");
        assert!(f0.eval(&[0.5])[0].is_finite());

        let t3 = doc.add_object(dictionary! {
            "FunctionType" => 3,
            "Domain" => vec![1.into(), 0.into()],
            "Functions" => vec![Object::Reference(t2)],
            "Bounds" => Vec::<Object>::new(),
            "Encode" => vec![0.into(), 1.into()],
        });
        let f3 = PdfFunction::parse(&doc, &Object::Reference(t3)).expect("parses");
        assert!(f3.eval(&[0.5])[0].is_finite());
    }

}