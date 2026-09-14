    /// The folded plan carries the residual and the shift into the store.
    ///
    /// `nets::reference` serves fused pushes from the same arms as unfolded ones. This
    /// builds the residual-plus-shift graph over a real weights file — one tensor per
    /// slot, no overlaps — runs it through the host interpreter, and checks the fused
    /// store added both addends: outputs differ per position by exactly the input
    /// differences, and per channel pair by exactly the shift difference. An addend the
    /// fold dropped would show up as a missing difference.
    #[test]
    fn a_folded_plan_matches_its_unfolded_numbers() {
        use crate::nets::reference;
        // Four tensors, one per slot: two kernels and two biases. The shift input is
        // a plan input rather than a weight. Built directly — `Offsets` has no public
        // constructor — by parsing a hand-made `.maml` header, which also exercises
        // the real `WeightSource` path instead of the `Shapes` stub.
        let header = maml_header(&[
            (&[4, 2, 1, 1], crate::weights::DTYPE_F16),
            (&[4], crate::weights::DTYPE_F16),
            (&[2, 4, 1, 1], crate::weights::DTYPE_F16),
            (&[2], crate::weights::DTYPE_F16),
        ]);
        let parsed = crate::weights::Weights::parse(&header, crate::weights::graph::SELFIE)
            .expect("the hand-made header parses");
        let table = parsed.offsets();
        let mut builder = Builder::new(&table);
        let x = builder.input(Shape::new(2, 1, 2));
        let shift_in = builder.input(Shape::new(2, 1, 1));
        let widened = builder.conv_same(x, 0, 4, 1, 1, Act::None);
        let narrowed = builder.conv_same(widened, 2, 2, 1, 1, Act::None);
        let residual = builder.add(x, narrowed);
        let out = builder.add_channel(residual, shift_in);
        let plan = builder.finish(&[out]).expect("builds");
        assert!(
            plan.ops.iter().all(|op| !matches!(
                op,
                Op::Dispatch { kind: Kind::Add | Kind::AddBroadcast, .. }
            )),
            "test setup: both binaries should have folded: {:?}",
            plan.ops
        );
        // Kernels all ones, biases all 0.5 — every value exactly representable in fp16.
        // Laid out per the parsed table's own offsets: kernel0 at bytes 0..16, bias0
        // at 16..24, kernel1 at 32..48, bias1 at 48..52. (Each tensor starts
        // 16-aligned, so kernel1 pads to 32.)
        let half = |v: f32| crate::preprocess::f32_to_f16(v).to_le_bytes();
        let mut blob = vec![0u8; 52];
        for (offset, count, value) in [(0, 8, 1.0f32), (16, 4, 0.5), (32, 8, 1.0), (48, 2, 0.5)] {
            for e in 0..count {
                blob[offset + e * 2..offset + e * 2 + 2].copy_from_slice(&half(value));
            }
        }
        let x_values = [1.0f32, 2.0, 3.0, 4.0];
        let shift_values = [10.0f32, 20.0];
        let x_slice: &[f32] = &x_values;
        let shift_slice: &[f32] = &shift_values;
        let outputs =
            reference::run_multi(&plan, &blob, &[x_slice, shift_slice]).expect("runs");
        assert_eq!(outputs.len(), 1);
        // widened[c][p] = sum(x) + 0.5 = 10.5; narrowed[o][p] = 2 * 10.5 + 0.5 = 21.5;
        // out = narrowed + x + shift = 21.5 + x + shift, per channel: the contraction
        // is over the input's 2 channels, not the output's 4.
        //
        // The residual (x) and the shift are separate addends in the fused store, so
        // the differences pin each: positions in a channel differ by exactly the input
        // difference, and channel pairs differ by exactly the shift difference plus
        // the input difference. A dropped addend would zero one of those deltas.
        // `outputs[0]` is `[c, 1, w]` flattened channel-major: index `c * w + p`.
        // Hand-evaluated: x is channel 0 = [1, 2], channel 1 = [3, 4].
        // widened[c][p] = x[0][p] + x[1][p] + 0.5 = 4.5 / 6.5 per position;
        // narrowed[o][p] = 4 * widened + 0.5 = 18.5 / 26.5; out adds the residual
        // x and the shift [10, 20]: [29.5, 38.5, 41.5, 50.5].
        //
        // Same channel, adjacent positions differ by the narrowed delta (8.0) plus
        // the input delta (1.0); same position, adjacent channels differ by the
        // shift (10.0) plus the input (2.0). A dropped addend would zero one of
        // those deltas.
        let deltas = [
            (outputs[0][1] - outputs[0][0], 9.0),
            (outputs[0][3] - outputs[0][2], 9.0),
            (outputs[0][2] - outputs[0][0], 12.0),
            (outputs[0][3] - outputs[0][1], 12.0),
        ];
        for (found, wanted) in deltas {
            assert!((found - wanted).abs() < 0.06, "delta got {found}, want {wanted}");
        }
        // And the absolute level pins the convolution itself.
        assert!((outputs[0][0] - 29.5).abs() < 0.06, "level {:?}", outputs[0]);
    }

    /// A `.maml` header + table for `shapes`, with an empty data section.
    ///
    /// Test-only builder for [`a_folded_plan_matches_its_unfolded_numbers`]: `Offsets`
    /// has no public constructor, so the table is parsed the way a shipped asset is.
    /// `graph::SELFIE` is arbitrary — the id only has to match the parse call.
    ///
    /// `Weights::parse` requires the file to end exactly where the data section does,
    /// so the returned vector is padded with zeros to the declared length.
    fn maml_header(shapes: &[(&[u32], u32)]) -> Vec<u8> {
        use crate::weights::graph;
        let count = shapes.len() as u32;
        let table_bytes = shapes.len() * 32;
        let mut data_len = 0usize;
        for (dims, _) in shapes {
            let len: usize = dims.iter().map(|&d| d as usize).product();
            data_len = (data_len + len * 2).next_multiple_of(16);
        }
        let mut bytes = vec![0u8; 64 + table_bytes + data_len];
        bytes[0..4].copy_from_slice(b"MAML");
        bytes[4..8].copy_from_slice(&1u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&graph::SELFIE.to_le_bytes());
        bytes[12..16].copy_from_slice(&count.to_le_bytes());
        bytes[48..52].copy_from_slice(&(64 + table_bytes as u32).to_le_bytes());
        for (i, (dims, dtype)) in shapes.iter().enumerate() {
            let at = 64 + i * 32;
            bytes[at..at + 4].copy_from_slice(&(dims.len() as u32).to_le_bytes());
            for (d, dim) in dims.iter().enumerate() {
                bytes[at + 4 + d * 4..at + 8 + d * 4].copy_from_slice(&dim.to_le_bytes());
            }
            bytes[at + 20..at + 24].copy_from_slice(&dtype.to_le_bytes());
            // 16-aligned running offset: tensor 0 at 0, each later tensor after the
            // previous one's padded end. Payloads are fp16 here, two bytes an element.
            let mut offset = 0usize;
            for (prev, _) in &shapes[..i] {
                let prev_len: usize = prev.iter().map(|&d| d as usize).product();
                offset = (offset + prev_len * 2).next_multiple_of(16);
            }
            let len: usize = dims.iter().map(|&d| d as usize).product();
            bytes[at + 24..at + 28].copy_from_slice(&(offset as u32).to_le_bytes());
            bytes[at + 28..at + 32].copy_from_slice(&(len as u32).to_le_bytes());
        }
        bytes[52..56].copy_from_slice(&(data_len as u32).to_le_bytes());
        bytes
    }