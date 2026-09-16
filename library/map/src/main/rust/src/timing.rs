//! Per-step frame timing: `Instant` deltas on the render thread, nothing else.
//!
//! The frame log used to say only how many draws and triangles the last frame submitted, so a
//! janky pan or tilt could not be attributed to a stage — upload drain, tile select, fence wait,
//! each record sub-pass, submit, present. This module times each of those steps separately, so
//! the once-a-second `%60` rollup can say *which* one owns the tail.
//!
//! # Cost model
//!
//! Every frame pays one [`Instant::now`](std::time::Instant::now) pair per step and three integer
//! array stores in [`StepTimes::record`] — no allocation, no formatting. The rollup string is
//! built only on report frames (every 60th), in [`StepTimes::report`], which also resets the
//! window. A slow UI poll reads the last frame's array through [`StepTimes::last_nanos`] for the
//! debug overlay; the render thread never blocks on it.
//!
//! # Clocks
//!
//! [`Instant`](std::time::Instant), never the camera's `time_seconds`: that clock wraps hourly
//! (see [`CLOCK_WRAP_NANOS`](crate::camera::CLOCK_WRAP_NANOS)) and a delta across a wrap would
//! read as an hour. `Instant` is monotonic and the whole chain runs on one thread, so a pair of
//! reads around a step is exact.
//!
//! Host-testable: no Vulkan, no JNI, pure integer math over injected samples.

/// One timed step of a frame, in the order the frame runs them.
///
/// Short names are the `%60` logcat labels; [`Step::ALL`] fixes the order the JNI getter and the
/// Kotlin overlay mirror, so adding a step means appending here *and* there (the overlay
/// size-checks the array and shows `n/a` on drift rather than mislabelling rows).
#[derive(Clone, Copy)]
pub(crate) enum Step {
    /// The whole JNI `render` call, entry to return.
    JniEntry,
    /// Draining finished worker tiles into uploads (bounded by `UPLOADS_PER_FRAME`).
    UploadDrain,
    /// Visible-tile select plus the fetch loop and resident-set retain.
    Select,
    /// Waiting on the frame slot's in-flight fence.
    FenceWait,
    /// Freeing retired buffers plus the scratch-ring reset.
    CollectRetired,
    /// Acquiring the next swapchain image.
    Acquire,
    /// The whole `record` (command-buffer build), entry to end.
    RecordTotal,
    /// The global symbol collision pre-pass plus the pick snapshot refresh.
    PlaceSymbols,
    /// The DEM ground-grid pass.
    RecordTerrain,
    /// The flat fill/line layer loop.
    RecordFlat,
    /// The road-carriageway ribbon pass.
    RecordCarriageways,
    /// The extruded-buildings pass.
    RecordBuildings,
    /// The deferred per-tile symbol draws.
    RecordSymbols,
    /// The live-traffic overlay pass.
    RecordTraffic,
    /// The lane-arrow batch builds and draws.
    RecordArrows,
    /// The region-mask stencil plus scrim.
    RecordRegion,
    /// The rail-lines network under the route.
    RecordRail,
    /// Markers, vehicles and the puck.
    RecordOverlays,
    /// The `queue_submit`.
    Submit,
    /// The `queue_present`.
    Present,
}

impl Step {
    /// Every step, in frame order. The JNI getter returns [`StepTimes::last_nanos`] in this
    /// order; the Kotlin overlay labels rows from its mirror of it.
    pub(crate) const ALL: [Step; 20] = [
        Step::JniEntry,
        Step::UploadDrain,
        Step::Select,
        Step::FenceWait,
        Step::CollectRetired,
        Step::Acquire,
        Step::RecordTotal,
        Step::PlaceSymbols,
        Step::RecordTerrain,
        Step::RecordFlat,
        Step::RecordCarriageways,
        Step::RecordBuildings,
        Step::RecordSymbols,
        Step::RecordTraffic,
        Step::RecordArrows,
        Step::RecordRegion,
        Step::RecordRail,
        Step::RecordOverlays,
        Step::Submit,
        Step::Present,
    ];

    /// Steps timed per frame.
    pub(crate) const COUNT: usize = Self::ALL.len();

    /// The `%60` logcat label: short, fixed-width-ish, unambiguous at a glance.
    pub(crate) fn short_name(self) -> &'static str {
        match self {
            Step::JniEntry => "jni",
            Step::UploadDrain => "upl",
            Step::Select => "sel",
            Step::FenceWait => "fen",
            Step::CollectRetired => "ret",
            Step::Acquire => "acq",
            Step::RecordTotal => "rec",
            Step::PlaceSymbols => "plc",
            Step::RecordTerrain => "ter",
            Step::RecordFlat => "flt",
            Step::RecordCarriageways => "car",
            Step::RecordBuildings => "bld",
            Step::RecordSymbols => "sym",
            Step::RecordTraffic => "trf",
            Step::RecordArrows => "arw",
            Step::RecordRegion => "msk",
            Step::RecordRail => "ral",
            Step::RecordOverlays => "ovl",
            Step::Submit => "sub",
            Step::Present => "prs",
        }
    }
}

/// The per-step timing state for one renderer.
///
/// Three fixed arrays plus the window length: the last frame's steps (for the JNI getter), and
/// the rolling sum/max the `%60` rollup reports. Fixed-size and allocation-free by construction —
/// [`record`](Self::record) is three integer stores, and only [`report`](Self::report) formats.
pub(crate) struct StepTimes {
    last: [u64; Step::COUNT],
    sum: [u64; Step::COUNT],
    max: [u64; Step::COUNT],
}

impl StepTimes {
    /// Empty: every step reads 0 until its first frame.
    pub(crate) fn new() -> Self {
        StepTimes {
            last: [0; Step::COUNT],
            sum: [0; Step::COUNT],
            max: [0; Step::COUNT],
        }
    }

    /// Time one step's sample: the index is the step's position in [`Step::ALL`].
    ///
    /// Integer adds/stores only — this runs twenty times a frame, so it must not allocate, format
    /// or branch on anything but the max.
    pub(crate) fn record(&mut self, step: Step, nanos: u64) {
        let at = step as usize;
        self.last[at] = nanos;
        self.sum[at] = self.sum[at].saturating_add(nanos);
        if nanos > self.max[at] {
            self.max[at] = nanos;
        }
    }

    /// The last frame's step times in [`Step::ALL`] order, for the JNI getter's copy.
    pub(crate) fn last_nanos(&self) -> &[u64; Step::COUNT] {
        &self.last
    }

    /// The `avg/max` rollup over the last `frames` frames, in ms with one decimal, then reset the
    /// window. Called only on report frames, so this is the one place that formats.
    ///
    /// `frames` is the window length (60 on the `%60` cadence), not read from here, so a frame
    /// that never ran a step still divides correctly.
    pub(crate) fn report(&mut self, frames: u32) -> String {
        let frames = frames.max(1) as u64;
        let mut out = String::from("steps");
        for step in Step::ALL {
            let at = step as usize;
            let avg_ms = self.sum[at] as f64 / frames as f64 / 1_000_000.0;
            let max_ms = self.max[at] as f64 / 1_000_000.0;
            out.push_str(&format!(" {} {avg_ms:.1}/{max_ms:.1}", step.short_name()));
            self.sum[at] = 0;
            self.max[at] = 0;
        }
        out
    }
}

/// Nanos between two `Instant` reads, saturating rather than wrapping on absurd values.
pub(crate) fn nanos_since(start: std::time::Instant) -> u64 {
    u64::try_from(start.elapsed().as_nanos()).unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_fresh_timer_reads_zero() {
        let t = StepTimes::new();
        assert!(t.last_nanos().iter().all(|&n| n == 0));
    }

    #[test]
    fn record_keeps_last_sum_and_max() {
        let mut t = StepTimes::new();
        t.record(Step::Select, 100);
        t.record(Step::Select, 300);
        assert_eq!(t.last_nanos()[Step::Select as usize], 300);
        // Avg over a 2-frame window is 200 ns; max is the worst sample.
        let line = t.report(2);
        assert!(
            line.contains("sel 0.0/0.0"),
            "200ns rounds to 0.0ms, got {line}"
        );
        // A millisecond-scale sample to check the decimals are real.
        t.record(Step::Present, 2_500_000);
        let line = t.report(1);
        assert!(line.contains("prs 2.5/2.5"), "got {line}");
    }

    #[test]
    fn report_resets_the_window() {
        let mut t = StepTimes::new();
        t.record(Step::Submit, 1_000_000);
        let _ = t.report(1);
        let line = t.report(1);
        assert!(
            line.contains("sub 0.0/0.0"),
            "the window must reset after a report, got {line}"
        );
        // But the last-frame array survives for the JNI getter.
        assert_eq!(t.last_nanos()[Step::Submit as usize], 1_000_000);
    }

    #[test]
    fn every_step_has_a_distinct_short_name() {
        let mut names: Vec<&str> = Step::ALL.iter().map(|s| s.short_name()).collect();
        names.sort_unstable();
        names.dedup();
        assert_eq!(
            names.len(),
            Step::COUNT,
            "short names must be unambiguous in logcat"
        );
    }

    #[test]
    fn step_discriminants_match_all_order() {
        // `record` indexes by `as usize`: the enum order and ALL order must agree.
        for (at, step) in Step::ALL.iter().enumerate() {
            assert_eq!(
                *step as usize,
                at,
                "Step::{:?} sits at the wrong index",
                step.short_name()
            );
        }
    }
}
