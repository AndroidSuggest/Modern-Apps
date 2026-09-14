                // A `1 x 1` convolution kernel.
                4 => assert_eq!(dims[2..], [1, 1], "tensor {index}: {dims:?}"),
                // An int4 scale, `(out, blocks)`.
                2 => assert!(dims[1] > 0, "tensor {index}: {dims:?}"),
                // A norm, a bias, an int8 scale, or the layer scalar.
                1 => {}
                _ => panic!("tensor {index} has an unexpected rank: {dims:?}"),
            }
        }
    }

    #[test]
    fn the_parameter_total_is_within_reach_of_the_published_size() {
        // Not an equality: the embedding and the per-layer table live in `embed_tokens`, a
        // separate export, so this counts the decoder only. What it catches is a layout that is
        // wrong by a factor - a transposed projection or a doubled width.
        let mut total: u64 = 0;
        for index in 0..LAYERS {
            let dim = u64::from(head_dim(index));
            let inner = u64::from(ffn(index));
            let d = u64::from(D_MODEL);
            total += d; // input norm
            total += dim; // q_norm
            total += d * u64::from(HEADS) * dim; // q_proj
            if owns_cache(index) {
                total += dim + 2 * d * u64::from(KV_HEADS) * dim; // k_norm, k_proj, v_proj
            }
            total += u64::from(HEADS) * dim * d; // o_proj
            total += 3 * d; // the three remaining d_model norms
            total += d * inner * 2 + inner * d; // gate_up and down
            total += d * u64::from(PER_LAYER) + u64::from(PER_LAYER) * d; // per-layer pair
            total += d; // post_per_layer_input_norm
            total += 1; // layer_scalar
        }
        // The decoder alone, without the 262,144-row embedding or the logits head.
        assert!(
            (1_500_000_000..2_600_000_000).contains(&total),
            "decoder parameters came to {total}, which is not the right order of magnitude"
        );
    }
}
