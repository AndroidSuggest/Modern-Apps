impl Latch {
    pub fn new(parameters: Hysteresis) -> Latch {
        let window = vec![0i16; parameters.window_frames.max(1)];
        let mut latch = Latch {
            parameters,
            window,
            filled: 0,
            next: 0,
            frames: 0,
            positive_run: 0,
            negative_run: 0,
            since_positive: u32::MAX,
            since_negative: u32::MAX,
            active: false,
        };
        latch.reset();
        latch
    }

    pub fn reset(&mut self) {
        self.window.fill(0);
        self.filled = 0;
        self.next = 0;
        self.frames = 0;
        self.positive_run = 0;
        self.negative_run = 0;
        self.since_positive = u32::MAX;
        self.since_negative = u32::MAX;
        self.active = false;
    }

    /// Feed one int16 score and advance the state machine.
    pub fn push(&mut self, score: i16) -> Verdict {
        self.window[self.next] = score;
        self.next = (self.next + 1) % self.window.len();
        self.filled = (self.filled + 1).min(self.window.len());

        // Mean of the window, in the score domain, then through the sigmoid. **Inferred**:
        // the operator's name says "smoothed" and its options give a window length, but
        // whether it means mean, median or max is not in the file. Mean is the usual
        // choice and the only one the option set gives no evidence against.
        let total: i32 = self.window[..self.filled].iter().map(|&v| v as i32).sum();
        let mean = (total as f32 / self.filled as f32).round();
        let smoothed = probability(mean.clamp(-32768.0, 32767.0) as i16);

        self.frames = self.frames.saturating_add(1);
        self.since_positive = self.since_positive.saturating_add(1);
        self.since_negative = self.since_negative.saturating_add(1);

        if smoothed >= self.parameters.positive_threshold {
            self.positive_run += 1;
        } else {
            self.positive_run = 0;
        }
        if smoothed < self.parameters.negative_threshold {
            self.negative_run += 1;
        } else {
            self.negative_run = 0;
        }

        let squelched = self.frames <= self.parameters.initial_squelch_frames;
        let mut positive_event = false;
        let mut negative_event = false;

        if !self.active && self.positive_run >= self.parameters.positive_frames_before_event {
            self.active = true;
            positive_event = self.parameters.generate_positive_events
                && !squelched
                && self.since_positive > self.parameters.positive_event_timeout_frames;
            if positive_event {
                self.since_positive = 0;
            }
        } else if self.active
            && !self.parameters.positive_state_is_persistent
            && self.negative_run >= self.parameters.negative_frames_before_event
        {
            self.active = false;
            negative_event = self.parameters.generate_negative_events
                && !squelched
                && self.since_negative > self.parameters.negative_event_timeout_frames;
            if negative_event {
                self.since_negative = 0;
            }
        }

        Verdict {
            smoothed,
            active: self.active,
            positive_event,
            negative_event,
        }
    }
}

/// The whole gate: 16 kHz PCM in, one music probability per 10 ms hop out.
///
/// This is what the JNI layer holds and what `:nowplaying`'s `MusicDetector` is backed by.
/// It owns the log-mel front end, so a caller hands it raw microphone samples and nothing
/// else. Latching is deliberately *not* applied here — the app smooths and latches in
/// Kotlin, and doing it twice would just make the two disagree.
pub struct MusicGate {
    frontend: Frontend,
    gate: Gate,
    frames: Vec<f32>,
}

impl MusicGate {
    pub fn new() -> Result<MusicGate, String> {
        MusicGate::with_config(NOW_PLAYING)
    }

    pub fn with_config(config: Config) -> Result<MusicGate, String> {
        if config.channels != CHANNELS {
            return Err(format!("{} mel channels, not {CHANNELS}", config.channels));
        }
        Ok(MusicGate {
            frontend: Frontend::new(config)?,
            gate: Gate::new(),
            frames: Vec::with_capacity(CHANNELS * 4),
        })
    }

    /// Discard the front end's partial frame and the trunk's context.
    pub fn reset(&mut self) {
        self.frontend.reset();
        self.gate.reset();
    }

    /// Feed PCM and collect one probability per completed hop, oldest first.
    ///
    /// Hops still inside the warm-up produce nothing, so an entirely valid call can append
    /// nothing at all.
    pub fn push(&mut self, pcm: &[i16], out: &mut Vec<f32>) {
        self.frames.clear();
        let produced = self.frontend.process(pcm, &mut self.frames);
        for frame in 0..produced {
            let logmel = &self.frames[frame * CHANNELS..(frame + 1) * CHANNELS];
            if let Some(score) = self.gate.push(logmel) {
                out.push(probability(score));
            }
        }
    }

    /// The most recent probability from `pcm`, or `None` if no hop completed or the gate
    /// is still warming up.
    pub fn score(&mut self, pcm: &[i16]) -> Option<f32> {
        let mut out = Vec::new();
        self.push(pcm, &mut out);
        out.last().copied()
    }

    /// Samples fed since the last [`MusicGate::reset`] have produced this many frames.
    pub fn frames(&self) -> usize {
        self.gate.frames()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Generated by scripts/ml/gate_reference.py. Do not edit by hand.

    /// The i16 score `synthetic()`'s first 40 frames produce, from the float64
    /// reference in `scripts/ml/gate_reference.py`. Bit-exact rather than approximate:
    /// the accumulators are integers and both sides round halves away from zero.
    #[rustfmt::skip]
    const PROBE_SCORES: [i16; 40] = [
        17411, 16126, 13942, 12143, 11115, 8416, 6489, 6746, 7774, 8288, 10087, 10729,
        8802, 7131, 7003, 7003, 7774, 8673, 8930, 7260, 5589, 6617, 6746, 7388,
        8288, 8673, 8673, 8031, 7388, 5975, 6103, 7260, 7902, 8159, 8802, 9830,
        10344, 10344, 9958, 8802,
    ];

    /// The same generator as `gate_reference.py`'s `synthetic()`, integer-only so both
    /// sides produce identical frames without carrying 1,280 values across as a fixture.
    fn synthetic(frames: usize) -> Vec<[i16; CHANNELS]> {
        let mut state: u32 = 0x2545_F491;
        (0..frames)
            .map(|t| {
                let mut row = [0i16; CHANNELS];
                let peak = (t * 3 + 7) % CHANNELS;
                for (c, slot) in row.iter_mut().enumerate() {
                    state = state.wrapping_mul(1103515245).wrapping_add(12345) & 0x7FFF_FFFF;
                    let ridge = 12i32 - 3 * (c as i32 - peak as i32).abs();
                    *slot = (ridge.max(0) * 1600 + ((state >> 20) % 1600) as i32) as i16;
                }
                row
            })
            .collect()
    }

    #[test]
    fn the_blob_holds_exactly_the_parameters_the_graph_declares() {
        let weights = 6 * (TAPS * CHANNELS + CHANNELS * CHANNELS) + HIDDEN * HEAD_FRAMES * CHANNELS + HIDDEN;
        let biases = 12 * CHANNELS + HIDDEN + 1;
        assert_eq!(weights, 8_200);
        assert_eq!(biases, 393);
        assert_eq!(BLOB.len(), weights + biases * 4);
    }

    #[test]
    fn every_parameter_is_consumed_and_none_is_invented() {
        let parameters = Parameters::load();
        assert_eq!(parameters.blocks.len(), BLOCKS);
        for block in &parameters.blocks {
            assert_eq!(block.depthwise.len(), TAPS * CHANNELS);
            assert_eq!(block.pointwise.len(), CHANNELS * CHANNELS);
            assert_eq!(block.depthwise_bias.len(), CHANNELS);
            assert_eq!(block.pointwise_bias.len(), CHANNELS);
            // ARCHITECTURE.md section 3.3: every depthwise bias in the graph is zero.
            assert!(block.depthwise_bias.iter().all(|&b| b == 0));
            assert!(block.pointwise_bias.iter().any(|&b| b != 0));
        }
        assert_eq!(parameters.dense.len(), HIDDEN * HEAD_FRAMES * CHANNELS);
        assert_eq!(parameters.logit.len(), HIDDEN);
        // The two dense biases were read out of the flatbuffer independently.
        assert_eq!(parameters.dense_bias, vec![430, -1014, 8, -1317, 1964, 2087, -995, -550]);
        assert_eq!(parameters.logit_bias, vec![1840]);
    }

    #[test]
    fn the_bias_scales_are_the_product_the_quantization_requires() {
        // For a TFLite quantized layer `bias_scale == input_scale * weight_scale`, so the
        // recorded bias scales are a checksum over the transcribed activation and weight
        // scales. Values from ARCHITECTURE.md section 3.3's verified table, which prints
        // them to eight significant figures, so compare relatively rather than absolutely.
        for (product, recorded) in [
            (FEATURE_SCALE * WEIGHT_SCALES[0], 0.000_194_172_72),
            (OUTPUT_QUANT[0].0 * WEIGHT_SCALES[1], 0.002_269_894),
            (OUTPUT_QUANT[11].0 * WEIGHT_SCALES[12], 0.000_385_858_88),
            (OUTPUT_QUANT[12].0 * WEIGHT_SCALES[13], 0.000_251_926_98),
        ] {
            assert!(
                (product - recorded).abs() < 1e-7 * recorded,
                "{product} is not the recorded bias scale {recorded}"
            );
        }

        let mut input = FEATURE_SCALE;
        for index in 0..BLOCKS {
            let (dw_scale, _) = OUTPUT_QUANT[index * 2];
            let (pw_scale, _) = OUTPUT_QUANT[index * 2 + 1];
            for multiplier in [
                input * WEIGHT_SCALES[index * 2] / dw_scale,
                dw_scale * WEIGHT_SCALES[index * 2 + 1] / pw_scale,
            ] {
                assert!(multiplier.is_finite() && multiplier > 0.0, "block {index}");
            }
            input = pw_scale;
        }
    }

    #[test]
    fn the_forward_pass_matches_an_independently_computed_reference() {
        let mut gate = Gate::new();
        for (index, frame) in synthetic(PROBE_SCORES.len()).iter().enumerate() {
            assert_eq!(gate.evaluate(frame), PROBE_SCORES[index], "frame {index}");
        }
    }

    #[test]
    fn a_reset_makes_the_next_frame_the_first_one_again() {
        let frames = synthetic(PROBE_SCORES.len());
        let mut gate = Gate::new();
        for frame in &frames {
            gate.evaluate(frame);
        }
        gate.reset();
        assert_eq!(gate.frames(), 0);
        for (index, frame) in frames.iter().enumerate() {
            assert_eq!(gate.evaluate(frame), PROBE_SCORES[index], "frame {index}");
        }
    }

    #[test]
    fn no_score_is_reported_until_the_context_is_real_audio() {
        assert_eq!(RECEPTIVE_FIELD_FRAMES, 23);
        let mut gate = Gate::new();
        let silence = [0.0f32; CHANNELS];
        for frame in 1..RECEPTIVE_FIELD_FRAMES {
            assert_eq!(gate.push(&silence), None, "frame {frame}");
        }
        assert!(gate.push(&silence).is_some());
        assert!(gate.push(&silence).is_some());
    }

    #[test]
    fn the_score_encoding_spans_ten_logits_either_side_of_the_zero_point() {
        assert!((probability(SCORE_ZERO as i16) - 0.5).abs() < 1e-6);
        assert!(probability(0) < 1e-4);
        assert!(probability(32767) > 0.9999);
        // ARCHITECTURE.md section 3.4's trip points for the shipped thresholds.
        assert!(probability(16912) >= Hysteresis::SHIPPED.positive_threshold);
        assert!(probability(16911) < Hysteresis::SHIPPED.positive_threshold);
        assert!(probability(16845) >= Hysteresis::SHIPPED.negative_threshold);
        assert!(probability(16844) < Hysteresis::SHIPPED.negative_threshold);
    }

    #[test]
    fn the_latch_waits_for_the_configured_run_before_it_fires() {
        let mut latch = Latch::new(Hysteresis {
            window_frames: 1,
            ..Hysteresis::SHIPPED
        });
        let high = 20_000i16;
        for frame in 1..Hysteresis::SHIPPED.positive_frames_before_event {
            let verdict = latch.push(high);
            assert!(!verdict.active, "fired on frame {frame}");
        }
        let verdict = latch.push(high);
        assert!(verdict.active);
        assert!(verdict.positive_event);
        // Already active, so no second event.
        assert!(!latch.push(high).positive_event);
    }

    #[test]
    fn the_latch_drops_out_below_the_negative_threshold_and_not_between_them() {
        let mut latch = Latch::new(Hysteresis {
            window_frames: 1,
            ..Hysteresis::SHIPPED
        });
        for _ in 0..Hysteresis::SHIPPED.positive_frames_before_event {
            latch.push(20_000);
        }
        assert!(latch.push(20_000).active);
        // Inside the hysteresis band: below positive, above negative. Holds.
        assert!(latch.push(16_880).active);
        // Below negative. Drops.
        let verdict = latch.push(16_000);
        assert!(!verdict.active);
        // generate_negative_events is false in the shipped configuration.
        assert!(!verdict.negative_event);
    }

    #[test]
    fn the_latch_forgets_everything_on_reset() {
        let mut latch = Latch::new(Hysteresis::SHIPPED);
        for _ in 0..20 {
            latch.push(20_000);
        }
        assert!(latch.push(20_000).active);
        latch.reset();
        assert!(!latch.push(20_000).active);
    }

    #[test]
    fn the_front_end_and_the_trunk_agree_on_the_hop() {
        assert_eq!(HOP_SAMPLES, 160);
        assert_eq!(NOW_PLAYING.channels, CHANNELS);
        // The front end clamps at ln = 15, which is exactly int8 127 at the trunk's scale.
        let ceiling = (NOW_PLAYING.ceiling as f64 / FEATURE_SCALE).round() as i32 + FEATURE_ZERO;
        assert_eq!(ceiling, 127);
        assert_eq!((0.0f64 / FEATURE_SCALE).round() as i32 + FEATURE_ZERO, -128);
    }

    #[test]
    fn pcm_in_gives_one_probability_per_hop_once_warm() {
        let mut gate = MusicGate::new().expect("the gate builds");
        // 400 samples fill the first analysis window; every 160 after it is one hop.
        let hops = RECEPTIVE_FIELD_FRAMES + 10;
        let samples = 400 + HOP_SAMPLES * (hops - 1);
        let pcm: Vec<i16> = (0..samples)
            .map(|i| ((i as f32 * 0.07).sin() * 8000.0) as i16)
            .collect();
        let mut out = Vec::new();
        gate.push(&pcm, &mut out);
        assert_eq!(gate.frames(), hops);
        assert_eq!(out.len(), hops - RECEPTIVE_FIELD_FRAMES + 1);
        assert!(out.iter().all(|p| (0.0..=1.0).contains(p)));

        gate.reset();
        assert_eq!(gate.frames(), 0);
        let mut again = Vec::new();
        gate.push(&pcm, &mut again);
        assert_eq!(out, again);
    }

    #[test]
    fn feeding_one_hop_at_a_time_is_the_same_as_feeding_the_whole_buffer() {
        let samples = 400 + HOP_SAMPLES * 40;
        let pcm: Vec<i16> = (0..samples)
            .map(|i| ((i as f32 * 0.11).sin() * 6000.0) as i16)
            .collect();

        let mut bulk = MusicGate::new().expect("the gate builds");
        let mut whole = Vec::new();
        bulk.push(&pcm, &mut whole);

        let mut streamed = MusicGate::new().expect("the gate builds");
        let mut piecewise = Vec::new();
        for chunk in pcm.chunks(HOP_SAMPLES) {
            streamed.push(chunk, &mut piecewise);
        }
        assert_eq!(whole, piecewise);
        assert!(!whole.is_empty());
    }
}
