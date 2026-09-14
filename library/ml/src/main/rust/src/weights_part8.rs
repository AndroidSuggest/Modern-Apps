
/// `floor((in + pad - dilation * (k - 1) - 1) / stride) + 1`, ONNX's convolution output
/// size. Deliberately the same formula as `nets::conv_out` rather than a shared helper:
/// the validator and the builder must agree, and sharing the function would make a change
/// to one silently change the other's checks. Duplicated on purpose, tested by agreement
/// (see the round-trip tests).
fn conv_out_shape(input: u32, kernel: u32, stride: u32, dilation: u32, pad_total: u32) -> u32 {
    let effective = dilation * (kernel - 1) + 1;
    let padded = input + pad_total;
    if padded < effective || stride == 0 {
        return 0;
    }
    (padded - effective) / stride + 1
}

fn u32_of(bytes: &[u8]) -> u32 {
    u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])
}

/// A cursor over the section bytes, refusing overruns with the field at fault.
struct Cursor<'a> {
    bytes: &'a [u8],
}

impl Cursor<'_> {
    fn take(&mut self, n: usize, what: &str) -> Result<Vec<u8>, String> {
        if self.bytes.len() < n {
            return Err(format!("graph section ends in {what}"));
        }
        let (head, tail) = self.bytes.split_at(n);
        self.bytes = tail;
        Ok(head.to_vec())
    }

    fn u8(&mut self, what: &str) -> Result<u8, String> {
        Ok(self.take(1, what)?[0])
    }

    fn u32(&mut self, what: &str) -> Result<u32, String> {
        let f = self.take(4, what)?;
        Ok(u32_of(&f))
    }

    fn indices(&mut self, what: &str) -> Result<Vec<u32>, String> {
        let count = self.u32(&format!("{what} count"))? as usize;
        if count > MAX_GRAPH_TENSORS {
            return Err(format!("graph section claims {count} {what}"));
        }
        let mut out = Vec::with_capacity(count.min(64));
        for i in 0..count {
            out.push(self.u32(&format!("{what} {i}"))?);
        }
        Ok(out)
    }

    fn rest(&self) -> &[u8] {
        self.bytes
    }
}

#[cfg(test)]
mod graph_tests {
    use super::*;

    /// A minimal section: one fp16 conv over a 2x2 input, then an output binding.
    fn one_conv_section() -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&1u32.to_le_bytes());
        bytes.push(TAG_CONV);
        // input 0, out 1, weight 0, bias 1, 1x1, stride 1, dilation 1, no pads,
        // group 1, act none, no slope, pad_edge 0: 18 u32s = 72 bytes, matching
        // the parser's `take(72)` and the emitter's `18 * 4` stride.
        for v in [0u32, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 0] {
            bytes.extend_from_slice(&v.to_le_bytes());
        }
        bytes.extend_from_slice(&NO_TENSOR.to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        // computed: [2,1,2] in, [2,1,2] out.
        bytes.extend_from_slice(&2u32.to_le_bytes());
        for shape in [[2u32, 1, 2], [2, 1, 2]] {
            for v in shape {
                bytes.extend_from_slice(&v.to_le_bytes());
            }
        }
        // inputs [0], outputs [1], no hosts.
        bytes.extend_from_slice(&1u32.to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        bytes.extend_from_slice(&1u32.to_le_bytes());
        bytes.extend_from_slice(&1u32.to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        bytes
    }

    fn one_conv_table() -> Vec<Tensor> {
        vec![
            Tensor { rank: 4, dims: [2, 2, 1, 1], offset: 0, len: 4, dtype: Dtype::F16 },
            Tensor { rank: 1, dims: [2, 0, 0, 0], offset: 16, len: 2, dtype: Dtype::F16 },
        ]
    }

    #[test]
    fn a_minimal_section_parses_and_validates() {
        let graph = Graph::parse(&one_conv_section(), &one_conv_table()).expect("parses");
        assert_eq!(graph.nodes.len(), 1);
        assert_eq!(graph.inputs, vec![0]);
        assert_eq!(graph.outputs, vec![1]);
    }

    #[test]
    fn an_unknown_kind_tag_is_refused() {
        let mut bytes = one_conv_section();
        bytes[4] = 9;
        let error = Graph::parse(&bytes, &one_conv_table()).expect_err("bad tag");
        assert!(error.contains("kind tag 9"), "{error}");
    }

    #[test]
    fn a_weight_ref_past_the_table_is_refused() {
        let mut bytes = one_conv_section();
        // weight file index lives at payload offset 8.
        bytes[5 + 8..5 + 12].copy_from_slice(&7u32.to_le_bytes());
        let error = Graph::parse(&bytes, &one_conv_table()).expect_err("bad weight");
        assert!(error.contains("file tensor 7"), "{error}");
    }

    #[test]
    fn a_shape_mismatch_is_refused() {
        // Bias table entry claims [3] but the node needs [2].
        let mut table = one_conv_table();
        table[1].dims = [3, 0, 0, 0];
        table[1].len = 3;
        let error = Graph::parse(&one_conv_section(), &table).expect_err("bad bias");
        assert!(error.contains("as [2]"), "{error}");
    }

    #[test]
    fn trailing_section_bytes_are_refused() {
        let mut bytes = one_conv_section();
        bytes.push(0);
        let error = Graph::parse(&bytes, &one_conv_table()).expect_err("trailing");
        assert!(error.contains("trailing"), "{error}");
    }

    #[test]
    fn an_empty_section_is_refused() {
        let error = Graph::parse(&[], &one_conv_table()).expect_err("empty");
        assert!(error.contains("node count"), "{error}");
    }

    #[test]
    fn a_version_two_file_without_a_section_parses_as_version_one() {
        // Bytes 56..64 are the graph offset/len pair; zero length is absent even at
        // version 2, so a v2 file with no emitted graph behaves exactly like v1.
        // One fp16 tensor [4]: rank 1, dims [4,0,0,0], offset 0, len 4.
        let mut bytes = vec![0u8; 64 + 32];
        bytes[0..4].copy_from_slice(&MAGIC);
        bytes[4..8].copy_from_slice(&2u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&graph::U2NETP.to_le_bytes());
        bytes[12..16].copy_from_slice(&1u32.to_le_bytes());
        bytes[48..52].copy_from_slice(&96u32.to_le_bytes());
        bytes[52..56].copy_from_slice(&8u32.to_le_bytes());
        bytes[64..68].copy_from_slice(&1u32.to_le_bytes());
        bytes[68..72].copy_from_slice(&4u32.to_le_bytes());
        bytes[84..88].copy_from_slice(&DTYPE_F16.to_le_bytes());
        bytes[88..92].copy_from_slice(&0u32.to_le_bytes());
        bytes[92..96].copy_from_slice(&4u32.to_le_bytes());
        for v in [1.0f32, 2.0, 3.0, 4.0] {
            bytes.extend_from_slice(&crate::preprocess::f32_to_f16(v).to_le_bytes());
        }
        let weights = Weights::parse(&bytes, graph::U2NETP).expect("a section-less v2 parses");
        assert!(weights.graph_section().is_none());
    }

    #[test]
    fn a_version_one_file_naming_a_section_is_refused() {
        let mut bytes = vec![0u8; 64 + 32 + 8];
        bytes[0..4].copy_from_slice(&MAGIC);
        bytes[4..8].copy_from_slice(&1u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&graph::U2NETP.to_le_bytes());
        bytes[12..16].copy_from_slice(&1u32.to_le_bytes());
        bytes[48..52].copy_from_slice(&96u32.to_le_bytes());
        bytes[52..56].copy_from_slice(&8u32.to_le_bytes());
        bytes[56..60].copy_from_slice(&104u32.to_le_bytes());
        bytes[60..64].copy_from_slice(&16u32.to_le_bytes());
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("v1 with section");
        assert!(error.contains("version-1"), "{error}");
    }

    #[test]
    fn a_future_version_is_refused_loudly() {
        let mut bytes = vec![0u8; 64 + 32 + 8];
        bytes[0..4].copy_from_slice(&MAGIC);
        bytes[4..8].copy_from_slice(&9u32.to_le_bytes());
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("version 9");
        assert!(error.contains("format version 9"), "{error}");
    }

    #[test]
    fn a_section_past_the_blob_parses_with_the_file() {
        // A full v2 file: header + table + blob + section, with the header's
        // reserved pair naming the section. Tensor 0 at blob offset 0 ([2,2,1,1]),
        // tensor 1 at 16 ([2]) — matching `one_conv_table`.
        let section = one_conv_section();
        let blob_len = 16 + 4;
        let mut bytes = vec![0u8; 64 + 64 + blob_len];
        bytes[0..4].copy_from_slice(&MAGIC);
        bytes[4..8].copy_from_slice(&2u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&graph::U2NETP.to_le_bytes());
        bytes[12..16].copy_from_slice(&2u32.to_le_bytes());
        bytes[48..52].copy_from_slice(&128u32.to_le_bytes());
        bytes[52..56].copy_from_slice(&(blob_len as u32).to_le_bytes());
        bytes[56..60].copy_from_slice(&(128 + blob_len as u32).to_le_bytes());
        bytes[60..64].copy_from_slice(&(section.len() as u32).to_le_bytes());
        // table entries
        bytes[64..68].copy_from_slice(&4u32.to_le_bytes());
        for (o, d) in [2u32, 2, 1, 1].iter().enumerate() {
            bytes[68 + o * 4..72 + o * 4].copy_from_slice(&d.to_le_bytes());
        }
        bytes[84..88].copy_from_slice(&DTYPE_F16.to_le_bytes());
        bytes[88..92].copy_from_slice(&0u32.to_le_bytes());
        bytes[92..96].copy_from_slice(&4u32.to_le_bytes());
        bytes[96..100].copy_from_slice(&1u32.to_le_bytes());
        bytes[100..104].copy_from_slice(&2u32.to_le_bytes());
        bytes[116..120].copy_from_slice(&DTYPE_F16.to_le_bytes());
        bytes[120..124].copy_from_slice(&16u32.to_le_bytes());
        bytes[124..128].copy_from_slice(&2u32.to_le_bytes());
        // blob payload: 4 fp16 then pad to 16 then 2 fp16.
        let mut blob = vec![0u8; blob_len];
        for (i, v) in [1.0f32, 1.0, 1.0, 1.0, 0.5, 0.5].iter().enumerate() {
            let at = if i < 4 { i * 2 } else { 16 + (i - 4) * 2 };
            blob[at..at + 2].copy_from_slice(&crate::preprocess::f32_to_f16(*v).to_le_bytes());
        }
        bytes[128..128 + blob_len].copy_from_slice(&blob);
        bytes.extend_from_slice(&section);
        let weights = Weights::parse(&bytes, graph::U2NETP).expect("a v2 file parses");
        let graph = weights.graph_section().expect("a section");
        assert_eq!(graph.nodes.len(), 1);
    }

    #[test]
    fn a_misplaced_section_is_refused() {
        // One fp16 tensor [4]: rank 1, dims [4,0,0,0], blob 8 bytes at 96..104,
        // so naming the section at 100 points inside it — weights aliased as
        // topology.
        let mut bytes = vec![0u8; 64 + 32 + 8];
        bytes[0..4].copy_from_slice(&MAGIC);
        bytes[4..8].copy_from_slice(&2u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&graph::U2NETP.to_le_bytes());
        bytes[12..16].copy_from_slice(&1u32.to_le_bytes());
        bytes[48..52].copy_from_slice(&96u32.to_le_bytes());
        bytes[52..56].copy_from_slice(&8u32.to_le_bytes());
        bytes[56..60].copy_from_slice(&100u32.to_le_bytes());
        bytes[60..64].copy_from_slice(&16u32.to_le_bytes());
        bytes[64..68].copy_from_slice(&1u32.to_le_bytes());
        bytes[68..72].copy_from_slice(&4u32.to_le_bytes());
        bytes[84..88].copy_from_slice(&DTYPE_F16.to_le_bytes());
        bytes[88..92].copy_from_slice(&0u32.to_le_bytes());
        bytes[92..96].copy_from_slice(&4u32.to_le_bytes());
        let error = Weights::parse(&bytes, graph::U2NETP).expect_err("misplaced");
        assert!(error.contains("graph section at 100"), "{error}");
    }
}
