//! Flat-style tests, part 6: sub-pixel widths and dash animation.
//!
//! Split from [`paint_extra3`]'s test module so each file stays small.

use super::paint::{Stroke, MIN_HALF_WIDTH_PX};
use super::paint_extra2::dash_drawn;

#[test]
fn a_sub_pixel_stroke_keeps_its_true_width() {
    let hair = Stroke {
        width_dp: 0.18,
        gap_width_dp: 0.0,
    };
    let (half_width, _) = hair.half_px(3.0);
    assert!(
        (half_width - 0.27).abs() < 1e-6,
        "0.18 Dp at density 3 is 0.27 px of half-width, got {half_width}",
    );
    assert!(
        half_width < MIN_HALF_WIDTH_PX,
        "and it is under the shader's floor"
    );
    // A stroke already wider than a pixel is unaffected either way.
    let solid = Stroke {
        width_dp: 4.0,
        gap_width_dp: 3.0,
    };
    assert_eq!(solid.half_px(3.0), (6.0, 4.5));
    // Density still scales it.
    assert_eq!(
        Stroke {
            width_dp: 0.4,
            gap_width_dp: 0.0
        }
        .half_px(1.0)
        .0,
        0.2
    );
    assert_eq!(
        Stroke {
            width_dp: 0.4,
            gap_width_dp: 0.0
        }
        .half_px(3.0)
        .0,
        0.6
    );
}

/// Both line shaders hardcode the floor because GLSL cannot include a Rust
/// constant. A silent divergence would put the geometry and the coverage term on
/// different widths, which shows up as roads that are too faint or too hard-edged
/// — subtle enough to survive review.
#[test]
fn the_shader_width_floor_matches_this_constant() {
    let declared = format!("const float MIN_HALF_WIDTH_PX = {MIN_HALF_WIDTH_PX:.1};");
    for (name, source) in [
        ("line.vert", include_str!("../../shaders/line.vert")),
        ("line.frag", include_str!("../../shaders/line.frag")),
    ] {
        assert!(
            source.contains(&declared),
            "{name} does not declare `{declared}`"
        );
    }
}

/// The gap is deliberately not floored: two bands a sub-pixel apart read as one band, which
/// is correct, whereas forcing them apart would widen a road the style wanted narrow.
#[test]
fn a_sub_pixel_gap_is_left_alone() {
    assert_eq!(
        Stroke {
            width_dp: 2.0,
            gap_width_dp: 0.1
        }
        .half_px(3.0)
        .1,
        0.15
    );
}
/// The no-regression guarantee: a static road (`morph.y = 0`) or a stopped clock makes the
/// phase 0, and the dash then has to be exactly what the pre-WS-B shader drew.
#[test]
fn a_static_dash_is_byte_identical_with_a_zero_phase() {
    // The un-phased test the shader ran before WS-B.
    let unphased = |d: f32, on: f32, off: f32| d.rem_euclid(on + off) <= on;
    let (on, off) = (6.0, 6.0);
    for i in 0..480 {
        let d = i as f32 * 0.25;
        assert_eq!(
            dash_drawn(d, on, off, 0.0),
            unphased(d, on, off),
            "a zero phase must reproduce the old dash at distance {d}",
        );
    }
    // A solid line (non-positive gap) is untouched too.
    for i in 0..480 {
        let d = i as f32 * 0.25;
        assert!(
            dash_drawn(d, 2.0, 0.0, 12.0),
            "a [2, 0] line stays solid under any phase"
        );
    }
}

/// And the animation actually moves: a whole-period phase is a no-op, a half-period phase
/// inverts the pattern. If this ever stops differing the dash has frozen.
#[test]
fn a_travelling_dash_shifts_with_the_phase() {
    let (on, off) = (6.0, 6.0);
    let period = on + off;
    let mut differed = false;
    for i in 0..480 {
        let d = i as f32 * 0.25;
        assert_eq!(
            dash_drawn(d, on, off, period),
            dash_drawn(d, on, off, 0.0),
            "a whole-period phase lands back on the same pattern",
        );
        if dash_drawn(d, on, off, period / 2.0) != dash_drawn(d, on, off, 0.0) {
            differed = true;
        }
    }
    assert!(differed, "a half-period phase must move the dash");
}

/// The phase is gated on both the clock and the per-draw speed slot, so a future edit
/// cannot animate static lines by accident. `line.frag` hardcodes the expression because
/// GLSL cannot include the Rust push layout.
#[test]
fn the_dash_phase_is_gated_on_the_clock_and_the_speed_slot() {
    let frag = include_str!("../../shaders/line.frag");
    assert!(
        frag.contains("push.misc.w * push.morph.y"),
        "line.frag must derive the phase from the clock times the per-draw speed slot",
    );
    assert!(
        frag.contains("mod(inDistancePx - phase, period)"),
        "line.frag must subtract the phase inside the dash modulo",
    );
}
