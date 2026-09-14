#[cfg(test)]
mod tests {
    use super::*;

    /// A table where only the characters named are mapped; everything else is `-1`.
    fn table_of(mapped: &[(char, i16)]) -> Vec<u8> {
        let mut table = vec![0u8; INDEXER_ENTRIES * 2];
        for entry in 0..INDEXER_ENTRIES {
            table[entry * 2..entry * 2 + 2].copy_from_slice(&(-1i16).to_le_bytes());
        }
        for &(codepoint, token) in mapped {
            let at = codepoint as usize * 2;
            table[at..at + 2].copy_from_slice(&token.to_le_bytes());
        }
        table
    }

    #[test]
    fn the_indexer_drops_what_it_cannot_map() {
        // Only 'a' and the combining acute are mapped. 'z' and an emoji outside the BMP both
        // disappear rather than becoming some other character. The tag's characters are unmapped
        // here too, so this says nothing about the tag - `the_indexer_wraps_the_text_in_its_
        // language_tag` does that.
        let table = table_of(&[('a', 60), ('\u{301}', 146)]);
        let got = to_ids(&table, "a\u{301}z\u{1F600}a", "en").expect("indexes");
        assert_eq!(got, vec![60, 146, 60]);
    }

    #[test]
    fn the_indexer_wraps_the_text_in_its_language_tag() {
        // The tag is ordinary characters through the ordinary table, so it has to come out as
        // ids on both sides of the text. Omitting it costs nothing detectable downstream, which
        // is why it is asserted here.
        let table = table_of(&[('<', 1), ('>', 2), ('/', 3), ('e', 4), ('n', 5), ('a', 6)]);
        assert_eq!(
            to_ids(&table, "a", "en").expect("indexes"),
            vec![1, 4, 5, 2, 6, 1, 3, 4, 5, 2],
            "<en>a</en>"
        );
    }

    #[test]
    fn the_indexer_refuses_a_language_that_is_not_a_two_letter_code() {
        let table = table_of(&[('a', 6)]);
        for bad in ["", "e", "eng", "EN", "e1"] {
            let error = to_ids(&table, "a", bad).expect_err("a bad code");
            assert!(error.contains("language code"), "{bad:?}: {error}");
        }
    }

    #[test]
    fn normalising_matches_the_sdk_on_a_sentence_that_uses_all_of_it() {
        // Curly quotes, an em dash, an abbreviation, a bracket, an emoji and an `@`, in one
        // string. The expected value is `UnicodeProcessor._preprocess_text`'s own output, less
        // the trailing period and language tag that `to_ids` is responsible for.
        let raw = "\u{201C}Ready?\u{201D} she asked \u{2014} e.g., [softly] \u{1F600} me@here";
        assert_eq!(normalise(raw), "\"Ready?\" she asked - for example, softly me at here");

        // Both abbreviations in one sentence, each followed by ordinary words.
        let two = "\u{201C}Ready?\u{201D} she asked \u{2014} e.g., the meeting\u{2019}s at three, \
                   i.e., after lunch";
        assert_eq!(
            normalise(two),
            "\"Ready?\" she asked - for example, the meeting's at three, that is, after lunch"
        );
    }

    #[test]
    fn the_indexer_refuses_a_table_of_the_wrong_size() {
        let error = to_ids(&[0u8; 16], "a", "en").expect_err("a short table");
        assert!(error.contains("codepoint table"), "{error}");
    }

    #[test]
    fn normalising_folds_the_symbols_the_model_has_no_token_for() {
        // Verified against `UnicodeProcessor._preprocess_text`, less the trailing period it adds
        // and this does not. Note the quotes close up: the space before `'b'` is eaten by the
        // spacing pass, which runs after the curly quotes have become straight ones.
        assert_eq!(normalise("\u{201C}a\u{201D} \u{2018}b\u{2019}"), "\"a\"'b'");
        assert_eq!(normalise("a\u{2013}b a\u{2014}b"), "a-b a-b");
        assert_eq!(normalise("a[b]c|d/e#f"), "a b c d e f");
        assert_eq!(normalise("i \u{2665}\u{2606} it \u{1F600}"), "i it");
        assert_eq!(normalise("a`b"), "a'b");
    }

    #[test]
    fn normalising_expands_abbreviations_and_tidies_the_spacing_it_leaves() {
        assert_eq!(normalise("me@here"), "me at here");
        assert_eq!(normalise("fruit, e.g., apples"), "fruit, for example, apples");
        assert_eq!(normalise("that is i.e., this"), "that is that is, this");
        // The expansions and the bracket-to-space rule both strand spaces before punctuation.
        assert_eq!(normalise("hello , world ."), "hello, world.");
        assert_eq!(normalise("a  \t\n b"), "a b");
    }

    #[test]
    fn normalising_collapses_a_run_of_one_quote_but_not_two_different_ones() {
        assert_eq!(normalise("he said \"\"hi\"\""), "he said \"hi\"");
        assert_eq!(normalise("it''s"), "it's");
        assert_eq!(normalise("\"'a'\""), "\"'a'\"");
    }

    #[test]
    fn the_indexer_ends_an_unpunctuated_sentence_and_leaves_a_punctuated_one() {
        // The model is trained on sentences, so a missing final stop is out of distribution.
        let table = table_of(&[('<', 1), ('>', 2), ('/', 3), ('e', 4), ('n', 5), ('a', 6), ('.', 7), ('\u{3002}', 8)]);
        let tag_open = vec![1, 4, 5, 2];
        let tag_close = vec![1, 3, 4, 5, 2];

        let mut wanted = tag_open.clone();
        wanted.extend([6, 7]);
        wanted.extend(tag_close.clone());
        assert_eq!(to_ids(&table, "a", "en").expect("indexes"), wanted, "<en>a.</en>");

        // Already ended, so nothing is appended and the ids are the same length.
        assert_eq!(to_ids(&table, "a.", "en").expect("indexes"), wanted, "<en>a.</en>");

        // A Japanese sentence ends in the ideographic full stop, and gets no second ending. The
        // tag stays `en` so this tests the terminal set rather than the tag.
        let mut ideographic = tag_open;
        ideographic.extend([6, 8]);
        ideographic.extend(tag_close);
        assert_eq!(to_ids(&table, "a\u{3002}", "en").expect("indexes"), ideographic);
    }

    #[test]
    fn the_indexer_refuses_text_that_is_only_an_ending() {
        // Without the emptiness check running ahead of the period, this would synthesise a dot.
        let table = table_of(&[('<', 1), ('>', 2), ('/', 3), ('e', 4), ('n', 5), ('.', 7)]);
        let error = to_ids(&table, "\u{1F600}", "en").expect_err("nothing readable");
        assert!(error.contains("vocabulary"), "{error}");
    }

    #[test]
    fn a_voice_file_splits_into_the_two_style_tensors() {
        // The order is style_ttl then style_dp, and the two are 12,800 and 128 values, so a file
        // read in the wrong order is exactly the right length. Only the values catch it — hence a
        // fixture where each tensor holds a constant of its own.
        let text_values = (net::STYLE * net::STYLE_TOKENS) as usize;
        let duration_values = duration_net::STYLE as usize;
        let mut bytes = Vec::new();
        for _ in 0..text_values {
            bytes.extend_from_slice(&crate::preprocess::f32_to_f16(0.25).to_le_bytes());
        }
        for _ in 0..duration_values {
            bytes.extend_from_slice(&crate::preprocess::f32_to_f16(-0.5).to_le_bytes());
        }

        let voice = Voice::read(&bytes).expect("the declared length");
        assert_eq!(voice.text.len(), text_values);
        assert_eq!(voice.duration.len(), duration_values);
        assert!(voice.text.iter().all(|&v| v == 0.25), "style_ttl comes first");
        assert!(voice.duration.iter().all(|&v| v == -0.5), "style_dp comes second");

        // A truncated or padded file is refused rather than read short: the shapes are fixed by
        // the architecture, so a length that disagrees means the wrong file, not a shorter voice.
        assert!(Voice::read(&bytes[..bytes.len() - 2]).is_err());
        assert!(Voice::read(&[]).is_err());
    }

    /// The GPU stages, as a trait, so the sequencing below is host-testable against stubs.
    ///
    /// `sampler_both` counts dual submits; the single-branch `sampler` counts single
    /// submits. The `Recording` stub below tracks both through one counter.
    struct Recording {
        seconds: f32,
        chars: u32,
        frames: u32,
        sampler_calls: usize,
        sampler_duals: usize,
        latents: Vec<Vec<f32>>,
        vocoded_frames: Vec<u32>,
    }

    impl Stages for Recording {
        fn duration(&mut self, lanes: &[f32], style: &[f32]) -> Result<f32, String> {
            // Two lanes over `chars + 1` positions, the sentence token first.
            assert_eq!(lanes.len() % 2, 0);
            let positions = lanes.len() / 2;
            assert_eq!(positions as u32, self.chars + 1);
            assert_eq!(
                lanes[0],
                (duration_net::SENTENCE_TOKEN % super::super::super::nets::EMBED_LANE) as f32
            );
            assert_eq!(style.len(), 128);
            Ok(self.seconds.ln())
        }

        fn text(&mut self, lanes: &[f32], style: &[f32]) -> Result<Vec<f32>, String> {
            // No sentence token on this side.
            assert_eq!(lanes.len() / 2, self.chars as usize);
            assert_eq!(style.len(), 256 * 50);
            Ok(vec![0.25; 256 * self.chars as usize])
        }

        fn sampler(
            &mut self,
            latent: &[f32],
            text: &[f32],
            keys: &[f32],
            style: &[f32],
            shifts: &[f32],
            query_angles: &[f32],
            key_angles: &[f32],
        ) -> Result<Vec<f32>, String> {
            assert_eq!(latent.len(), net::LATENT as usize * self.frames as usize);
            assert_eq!(text.len(), net::TEXT as usize * self.chars as usize);
            assert_eq!(keys.len(), net::STYLE as usize * net::MAIN_BLOCKS * 50);
            assert_eq!(style.len(), net::STYLE as usize * 50);
            assert_eq!(shifts.len(), net::CHANNELS as usize * net::MAIN_BLOCKS);
            assert_eq!(query_angles.len(), 64 * self.frames as usize);
            assert_eq!(key_angles.len(), 64 * self.chars as usize);
            self.sampler_calls += 1;
            self.latents.push(latent.to_vec());
            // A velocity of zero, so the latent must come back unchanged and any accidental
            // scaling of it in `step` shows up.
            Ok(vec![0.0; latent.len()])
        }

        /// `synthesise` calls `sampler_both`, so the pipeline tests pin the dual
        /// path: 16 `sampler_both` calls, each served here as two single submits.
        /// `bridge.rs` serves the same calls as one dual submit per step.
        #[allow(clippy::too_many_arguments)]
        fn sampler_both(
            &mut self,
            latent: &[f32],
            conditional_text: &[f32],
            conditional_keys: &[f32],
            conditional_style: &[f32],
            unconditional_text: &[f32],
            unconditional_keys: &[f32],
            unconditional_style: &[f32],
            shifts: &[f32],
            query_angles: &[f32],
            key_angles: &[f32],
        ) -> Result<[Vec<f32>; 2], String> {
            self.sampler_duals += 1;
            let conditional = self.sampler(
                latent,
                conditional_text,
                conditional_keys,
                conditional_style,
                shifts,
                query_angles,
                key_angles,
            )?;
            let unconditional = self.sampler(
                latent,
                unconditional_text,
                unconditional_keys,
                unconditional_style,
                shifts,
                query_angles,
                key_angles,
            )?;
            Ok([conditional, unconditional])
        }

        fn vocoder(&mut self, latent: &[f32], frames: u32) -> Result<Vec<f32>, String> {
            assert_eq!(latent.len(), net::LATENT as usize * frames as usize);
            self.vocoded_frames.push(frames);
            Ok(vec![0.5; frames as usize * 3072])
        }
    }

    /// A codepoint table mapping the ASCII letters to themselves, plus the three punctuation
    /// marks the language tag is spelled with, and nothing else.
    fn letters() -> Vec<u8> {
        let mut table = vec![0u8; INDEXER_ENTRIES * 2];
        for entry in 0..INDEXER_ENTRIES {
            let token = if (b'a' as usize..=b'z' as usize).contains(&entry) {
                (entry - b'a' as usize + 1) as i16
            } else if entry < 128 && b"<>/".contains(&(entry as u8)) {
                27
            } else {
                -1
            };
            table[entry * 2..entry * 2 + 2].copy_from_slice(&token.to_le_bytes());
        }
        table
    }

    fn conditioning() -> Conditioning {
        Conditioning {
            theta: vec![1.0; net::FREQUENCIES as usize],
            shifts: vec![vec![0.0; net::CHANNELS as usize * net::MAIN_BLOCKS]; STEPS as usize],
            conditional_keys: vec![0.0; net::STYLE as usize * net::MAIN_BLOCKS * 50],
            unconditional_keys: vec![0.0; net::STYLE as usize * net::MAIN_BLOCKS * 50],
            text_token: vec![0.0; net::TEXT as usize],
            unconditional_style: vec![0.0; net::STYLE as usize * 50],
        }
    }

    #[test]
    fn the_pipeline_runs_the_four_nets_in_order_at_the_predicted_length() {
        // "hello" is five mapped letters, and `<en>` and `</en>` are nine more. At one second the
        // duration predictor's answer becomes ceil(44100 / 1.05 / 3072) = 14 frames, and the
        // vocoder emits 3072 samples a frame.
        let mut stages = Recording {
            seconds: 1.0,
            chars: 14,
            frames: 14,
            sampler_calls: 0,
            sampler_duals: 0,
            latents: Vec::new(),
            vocoded_frames: Vec::new(),
        };
        let voice = Voice { duration: vec![0.1; 128], text: vec![0.2; 256 * 50] };
        let samples = synthesise(
            &mut stages,
            &conditioning(),
            &letters(),
            &voice,
            "hello",
            "en",
            &|count| vec![0.0; count],
        )
        .expect("synthesises");
        assert_eq!(samples.len(), 14 * 3072);
        assert_eq!(stages.vocoded_frames, vec![14]);
        // `synthesise` takes the dual path: 16 `sampler_both` calls, each served by
        // the default as two single submits.
        assert_eq!(stages.sampler_duals, STEPS as usize);
        assert_eq!(stages.sampler_calls, STEPS as usize * 2);
    }

    #[test]
    fn a_zero_velocity_leaves_the_latent_where_the_noise_put_it() {
        // The stub returns a velocity of zero, so every step is the identity and all 16 must see
        // the same latent. A `step` that scaled or reordered would show here rather than as
        // quiet noise on a device.
        let mut stages = Recording {
            seconds: 1.0,
            chars: 14,
            frames: 14,
            sampler_calls: 0,
            sampler_duals: 0,
            latents: Vec::new(),
            vocoded_frames: Vec::new(),
        };
        let voice = Voice { duration: vec![0.1; 128], text: vec![0.2; 256 * 50] };
        synthesise(
            &mut stages,
            &conditioning(),
            &letters(),
            &voice,
            "hello",
            "en",
            &|count| (0..count).map(|i| i as f32 * 0.001).collect(),
        )
        .expect("synthesises");
        let first = stages.latents.first().expect("a latent").clone();
        for (i, latent) in stages.latents.iter().enumerate() {
            assert_eq!(latent, &first, "step {i} moved the latent");
        }
    }

    #[test]
    fn text_with_nothing_in_the_vocabulary_is_refused() {
        // Rather than synthesising silence, or a plan over zero characters that the nets refuse
        // with a message about frames. The language tag maps in `letters()`, so this also covers
        // the tag alone not being mistaken for content.
        let mut stages = Recording {
            seconds: 1.0,
            chars: 0,
            frames: 1,
            sampler_calls: 0,
            sampler_duals: 0,
            latents: Vec::new(),
            vocoded_frames: Vec::new(),
        };
        let voice = Voice { duration: vec![0.1; 128], text: vec![0.2; 256 * 50] };
        let error = synthesise(
            &mut stages,
            &conditioning(),
            &letters(),
            &voice,
            "12345",
            "en",
            &|count| vec![0.0; count],
        )
        .expect_err("no vocabulary");
        assert!(error.contains("vocabulary"), "{error}");
    }

    #[test]
    fn the_rotary_table_is_normalised_by_its_own_length() {
        // Two positions and one frequency of 1: position 0 is angle 0 and position 1 is angle
        // 0.5, because the divisor is the length rather than a constant. Cosines first.
        let table = rotary_angles(&vec![1.0; net::FREQUENCIES as usize], 2).expect("angles");
        assert_eq!(table.len(), 64 * 2);
        assert!((table[0] - 1.0).abs() < 1e-6);
        assert!((table[1] - 0.5f32.cos()).abs() < 1e-6);
        // Sines start at channel 32.
        assert!((table[32 * 2]).abs() < 1e-6);
        assert!((table[32 * 2 + 1] - 0.5f32.sin()).abs() < 1e-6);
    }

    #[test]
    fn mish_matches_its_definition_and_survives_a_large_input() {
        // `x * tanh(softplus(x))`. At 0 it is 0, at 1 it is 0.86509836, and at 100 it is 100 —
        // the last only because `softplus` is not computed as `ln(1 + e^100)`, which is inf.
        assert!((mish(0.0)).abs() < 1e-6);
        assert!((mish(1.0) - 0.865_098_4).abs() < 1e-5);
        assert!((mish(100.0) - 100.0).abs() < 1e-3);
        assert!(mish(100.0).is_finite());
        // And it is not ReLU: a small negative input is negative, not zero.
        assert!(mish(-1.0) < -0.3 && mish(-1.0) > -0.31);
    }

    #[test]
    fn a_linear_reads_its_weight_row_major() {
        // `[2, 3]` over three inputs. A column-major read would give (14, 32) here, which is
        // the same magnitude and the wrong answer.
        let weight = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0];
        let got = linear(&weight, &[10.0, 20.0], &[1.0, 1.0, 1.0]);
        assert_eq!(got, vec![16.0, 35.0]);
    }

    #[test]
    fn the_euler_step_is_guidance_four_over_the_step_count() {
        // `x + (4c - 3u) / total`. With c = u the guidance cancels to plain c, which is the
        // check that the two coefficients differ by exactly one.
        let got = step(&[0.0, 1.0], &[2.0, 2.0], &[2.0, 2.0], 4).expect("steps");
        assert_eq!(got, vec![0.5, 1.5]);
        // And with them apart, the conditional is extrapolated away from the unconditional.
        let got = step(&[0.0], &[1.0], &[0.0], 1).expect("steps");
        assert_eq!(got, vec![4.0]);
    }

    #[test]
    fn the_euler_step_refuses_mismatched_branches() {
        let error = step(&[0.0, 0.0], &[0.0], &[0.0, 0.0], 1).expect_err("a short branch");
        assert!(error.contains("conditional"), "{error}");
        let error = step(&[0.0], &[0.0], &[0.0], 0).expect_err("no steps");
        assert!(error.contains("no steps"), "{error}");
    }
}
