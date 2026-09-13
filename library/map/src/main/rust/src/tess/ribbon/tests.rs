use super::*;
use crate::tess::stroke::MITER_LIMIT;

fn at(v: &[f32], vertex: usize, field: usize) -> f32 {
    v[vertex * FLOATS_PER_VERTEX + field]
}
fn position_x(v: &[f32], i: usize) -> f32 {
    at(v, i, 0)
}
fn normal_x(v: &[f32], i: usize) -> f32 {
    at(v, i, 2)
}
fn normal_y(v: &[f32], i: usize) -> f32 {
    at(v, i, 3)
}
fn across(v: &[f32], i: usize) -> f32 {
    at(v, i, 4)
}
fn distance(v: &[f32], i: usize) -> f32 {
    at(v, i, 5)
}
fn normal_len(v: &[f32], i: usize) -> f32 {
    (normal_x(v, i).powi(2) + normal_y(v, i).powi(2)).sqrt()
}

/// What `line.vert` does with a vertex, so the invariants can be checked on the
/// host: the whole point of keeping the width out of the buffer is that the shader
/// is the only place the two are ever combined.
fn shade(v: &[f32], i: usize, t: f32, half_width: f32) -> (f32, f32) {
    (
        at(v, i, 0) + normal_x(v, i) * t * half_width,
        at(v, i, 1) + normal_y(v, i) * t * half_width,
    )
}

#[test]
fn a_straight_segment_becomes_one_quad() {
    let mut v = Vec::new();
    let mut idx = Vec::new();
    // Extent 100, so tile coordinates and 0..1 positions differ by a round 100.
    ribbon(&[0, 0, 100, 0], 100, &mut v, &mut idx);

    assert_eq!(v.len() / FLOATS_PER_VERTEX, 4, "two points, two vertices each");
    assert_eq!(idx.len(), 6, "two triangles");
    for i in 0..4 {
        assert!((normal_x(&v, i) - 0.0).abs() < 1e-6);
        assert!((normal_y(&v, i) - 1.0).abs() < 1e-6);
    }
    assert!((position_x(&v, 0) - 0.0).abs() < 1e-6);
    assert!((position_x(&v, 2) - 1.0).abs() < 1e-6);
}

#[test]
fn t_reaches_both_kerbs_and_never_leaves_the_carriageway() {
    // A marking is placed by comparing against `t`, so anything outside [-1, +1]
    // would paint kerb markings onto the verge.
    let mut v = Vec::new();
    ribbon(&[0, 0, 100, 0, 100, 100, 250, 40], 4096, &mut v, &mut Vec::new());
    let n = v.len() / FLOATS_PER_VERTEX;
    assert_eq!(n, 8);
    for i in 0..n {
        assert!((-1.0..=1.0).contains(&across(&v, i)), "t {} at {i}", across(&v, i));
    }
    for i in 0..(n / 2) {
        assert_eq!(across(&v, i * 2), -1.0, "left kerb is exactly -1");
        assert_eq!(across(&v, i * 2 + 1), 1.0, "right kerb is exactly +1");
    }
}

#[test]
fn a_markings_place_on_the_road_survives_a_width_change() {
    // The regression this guards is the expensive one: if the half-width were baked
    // in, a zoom step would need a re-tessellation. The same buffer has to serve
    // every width, with the marking staying the same fraction across the road.
    let mut v = Vec::new();
    ribbon(&[0, 0, 100, 0], 100, &mut v, &mut Vec::new());
    let divider = -1.0 / 3.0;

    let fraction = |half_width: f32| {
        let (lx, ly) = shade(&v, 0, -1.0, half_width);
        let (rx, ry) = shade(&v, 1, 1.0, half_width);
        let (mx, my) = shade(&v, 0, divider, half_width);
        let span = ((rx - lx).powi(2) + (ry - ly).powi(2)).sqrt();
        ((mx - lx).powi(2) + (my - ly).powi(2)).sqrt() / span
    };

    let narrow = fraction(2.5);
    let wide = fraction(37.0);
    assert!((narrow - wide).abs() < 1e-6, "{narrow} vs {wide}");
    // And it is where `t` says it is: a third of the way in from the left kerb.
    assert!((narrow - (divider + 1.0) / 2.0).abs() < 1e-6);
}

#[test]
fn a_right_angle_join_gets_a_miter_normal() {
    // (0,0) -> (100,0) -> (100,100). The incoming normal is (0,1) and the outgoing
    // one is (-1,0); their bisector is (-1,1)/sqrt(2) and the miter length is
    // 1/cos(45°) = sqrt(2), so the normal comes out exactly (-1, 1) and the kerb
    // stays on `t = ±1` through the turn.
    let mut v = Vec::new();
    ribbon(&[0, 0, 100, 0, 100, 100], 100, &mut v, &mut Vec::new());
    assert_eq!(v.len() / FLOATS_PER_VERTEX, 6);
    assert!((normal_x(&v, 2) - -1.0).abs() < 1e-5, "join nx {}", normal_x(&v, 2));
    assert!((normal_y(&v, 2) - 1.0).abs() < 1e-5, "join ny {}", normal_y(&v, 2));
    assert!((normal_len(&v, 2) - 2f32.sqrt()).abs() < 1e-5);
    assert!((normal_len(&v, 0) - 1.0).abs() < 1e-5);
    assert!((normal_len(&v, 4) - 1.0).abs() < 1e-5);
}

#[test]
fn a_hairpin_miter_is_clamped_rather_than_throwing_a_spike() {
    let mut v = Vec::new();
    ribbon(&[0, 0, 1000, 0, 0, 10], 4096, &mut v, &mut Vec::new());
    assert!((normal_len(&v, 2) - MITER_LIMIT).abs() < 1e-4, "got {}", normal_len(&v, 2));
}

#[test]
fn a_perfect_reversal_does_not_produce_nan() {
    let mut v = Vec::new();
    ribbon(&[0, 0, 100, 0, 0, 0], 100, &mut v, &mut Vec::new());
    for (i, f) in v.iter().enumerate() {
        assert!(f.is_finite(), "float {i} is {f}");
    }
}

#[test]
fn points_one_tile_unit_apart_still_give_a_unit_normal() {
    // `dedupe` only drops *exactly* coincident points, so the shortest surviving
    // segment is one integer tile unit — never short enough for `direction` to
    // divide by something near zero. Junction connectors converging on a shared
    // arm endpoint lean on this. It stops holding the moment a caller feeds in
    // coordinates that are not integer tile units, or `dedupe` grows a tolerance.
    let mut v = Vec::new();
    ribbon(&[0, 0, 1, 0, 1, 1, 2, 1], 4096, &mut v, &mut Vec::new());
    for (i, f) in v.iter().enumerate() {
        assert!(f.is_finite(), "float {i} is {f}");
    }
    assert!((normal_len(&v, 0) - 1.0).abs() < 1e-5, "start {}", normal_len(&v, 0));
    let last = v.len() / FLOATS_PER_VERTEX - 1;
    assert!((normal_len(&v, last) - 1.0).abs() < 1e-5, "end {}", normal_len(&v, last));
}

#[test]
fn repeated_vertices_are_dropped() {
    let mut v = Vec::new();
    ribbon(&[0, 0, 0, 0, 0, 0, 50, 0], 100, &mut v, &mut Vec::new());
    assert_eq!(v.len() / FLOATS_PER_VERTEX, 4, "three coincident points became one");
}

#[test]
fn a_part_with_fewer_than_two_distinct_points_emits_nothing() {
    let mut v = Vec::new();
    let mut idx = Vec::new();
    ribbon(&[], 100, &mut v, &mut idx);
    ribbon(&[5, 5], 100, &mut v, &mut idx);
    ribbon(&[5, 5, 5, 5], 100, &mut v, &mut idx);
    assert!(v.is_empty());
    assert!(idx.is_empty());
}

#[test]
fn distance_accumulates_along_the_line_in_tile_local_units() {
    // Mirrors the stroke path's assertion: `line.vert` scales this by the tile's
    // pixel span, so extent units here would stretch every marking pattern by the
    // extent.
    let mut v = Vec::new();
    ribbon(&[0, 0, 300, 0, 300, 400], 100, &mut v, &mut Vec::new());
    assert!((distance(&v, 0) - 0.0).abs() < 1e-6);
    assert!((distance(&v, 2) - 3.0).abs() < 1e-6, "300 tile units at extent 100");
    assert!((distance(&v, 4) - 7.0).abs() < 1e-6, "plus 400 more");
}

/// The kerb offset the vertex shader lands on, in tile-local units, for the push
/// constant `half_width`. Everything below asserts on this rather than on the raw
/// normal, because it is what actually reaches a pixel.
fn kerb_offset(v: &[f32], i: usize, half_width: f32) -> f32 {
    let (x, y) = shade(v, i, 1.0, half_width);
    ((x - at(v, i, 0)).powi(2) + (y - at(v, i, 1)).powi(2)).sqrt()
}

#[test]
fn no_taper_is_byte_identical_to_the_untapered_path() {
    // The overwhelming majority of parts, and the whole of the junction connector
    // path. A ratio of one must not perturb a single float.
    let line = [0, 0, 300, 0, 300, 400, 700, 400];
    let (mut plain_v, mut plain_i) = (Vec::new(), Vec::new());
    ribbon(&line, 4096, &mut plain_v, &mut plain_i);
    let (mut none_v, mut none_i) = (Vec::new(), Vec::new());
    ribbon_tapered(&line, 4096, Taper::NONE, &mut none_v, &mut none_i);
    assert_eq!(plain_v, none_v);
    assert_eq!(plain_i, none_i);

    // And a taper whose ratios are both one is the same thing however long its run.
    let (mut flat_v, mut flat_i) = (Vec::new(), Vec::new());
    ribbon_tapered(&line, 4096, Taper::new(1.0, 1.0, 0.05), &mut flat_v, &mut flat_i);
    assert_eq!(plain_v, flat_v);
    assert_eq!(plain_i, flat_i);
}

#[test]
fn a_tapered_end_is_exactly_the_ratio_and_the_far_end_is_untouched() {
    // The property the whole design rests on: the narrow end is *exactly* the
    // neighbour's width, not nearly it, so two sections meeting there cannot leave
    // a step between them.
    let mut v = Vec::new();
    // 1000 units long at extent 1000, so tile-local length is 1.0 and a run of 0.2
    // is 200 units.
    ribbon_tapered(&[0, 0, 1000, 0], 1000, Taper::new(0.5, 1.0, 0.2), &mut v, &mut Vec::new());
    let last = v.len() / FLOATS_PER_VERTEX - 1;
    assert!((kerb_offset(&v, 0, 40.0) - 20.0).abs() < 1e-5, "start is half width");
    assert!((kerb_offset(&v, last, 40.0) - 40.0).abs() < 1e-5, "end is full width");
}

#[test]
fn the_ramp_reaches_full_width_at_the_run_and_stays_there() {
    let mut v = Vec::new();
    ribbon_tapered(&[0, 0, 1000, 0], 1000, Taper::new(0.5, 1.0, 0.2), &mut v, &mut Vec::new());
    let n = v.len() / FLOATS_PER_VERTEX;
    assert_eq!(n, 6, "a vertex was inserted where the ramp ends");
    // The inserted point is at 200 units along, which is 0.2 in tile-local units.
    assert!((position_x(&v, 2) - 0.2).abs() < 1e-5, "at {}", position_x(&v, 2));
    assert!((kerb_offset(&v, 2, 40.0) - 40.0).abs() < 1e-5, "full width from the run on");
}

#[test]
fn a_taper_narrows_monotonically_and_never_inverts_the_road() {
    let mut v = Vec::new();
    ribbon_tapered(
        &[0, 0, 250, 0, 500, 0, 750, 0, 1000, 0],
        1000,
        Taper::new(0.25, 0.5, 0.3),
        &mut v,
        &mut Vec::new(),
    );
    let n = v.len() / FLOATS_PER_VERTEX;
    let widths: Vec<f32> = (0..n).step_by(2).map(|i| kerb_offset(&v, i, 40.0)).collect();
    assert!(widths.iter().all(|w| *w > 0.0), "a zero width draws nothing: {widths:?}");
    assert!(widths.iter().all(|w| *w <= 40.0 + 1e-5), "never wider than the push: {widths:?}");
    assert!((widths[0] - 10.0).abs() < 1e-5, "start quarter width: {}", widths[0]);
    let end = widths.len() - 1;
    assert!((widths[end] - 20.0).abs() < 1e-5, "end half width: {}", widths[end]);
    // Rising away from the start, falling toward the end: no bulge in between.
    let peak = widths.iter().cloned().fold(f32::MIN, f32::max);
    assert!((peak - 40.0).abs() < 1e-5, "the middle reaches full width: {widths:?}");
}

#[test]
fn a_taper_leaves_t_on_the_kerbs_so_the_markings_still_span_the_road() {
    // `road_surface.frag` places every marking against `t`. If a taper moved it the
    // dividers would drift off the asphalt exactly where the road is changing.
    let mut v = Vec::new();
    ribbon_tapered(
        &[0, 0, 400, 0, 400, 400],
        4096,
        Taper::new(0.4, 0.6, 0.02),
        &mut v,
        &mut Vec::new(),
    );
    let n = v.len() / FLOATS_PER_VERTEX;
    for i in 0..(n / 2) {
        assert_eq!(across(&v, i * 2), -1.0, "left kerb is exactly -1");
        assert_eq!(across(&v, i * 2 + 1), 1.0, "right kerb is exactly +1");
    }
}

#[test]
fn a_tapered_marking_still_survives_a_width_change() {
    // The invariant the module exists to protect, re-asserted *through* a taper: a
    // ratio is not a screen measurement, so a zoom step still needs no
    // re-tessellation.
    let mut v = Vec::new();
    ribbon_tapered(&[0, 0, 1000, 0], 1000, Taper::new(0.5, 1.0, 0.2), &mut v, &mut Vec::new());
    let divider = -1.0 / 3.0;
    let fraction = |vertex: usize, half_width: f32| {
        let (lx, ly) = shade(&v, vertex, -1.0, half_width);
        let (rx, ry) = shade(&v, vertex + 1, 1.0, half_width);
        let (mx, my) = shade(&v, vertex, divider, half_width);
        let span = ((rx - lx).powi(2) + (ry - ly).powi(2)).sqrt();
        ((mx - lx).powi(2) + (my - ly).powi(2)).sqrt() / span
    };
    for vertex in [0, 2] {
        let narrow = fraction(vertex, 2.5);
        let wide = fraction(vertex, 37.0);
        assert!((narrow - wide).abs() < 1e-5, "vertex {vertex}: {narrow} vs {wide}");
        assert!((narrow - (divider + 1.0) / 2.0).abs() < 1e-5);
    }
}

#[test]
fn two_ramps_longer_than_the_part_meet_instead_of_crossing() {
    // A section shorter than two taper runs — a slip lane between two changes. The
    // ends must still be exact and nothing in between may invert.
    let mut v = Vec::new();
    ribbon_tapered(&[0, 0, 100, 0], 1000, Taper::new(0.5, 0.25, 0.4), &mut v, &mut Vec::new());
    let n = v.len() / FLOATS_PER_VERTEX;
    let last = n - 1;
    assert!((kerb_offset(&v, 0, 40.0) - 20.0).abs() < 1e-5);
    assert!((kerb_offset(&v, last, 40.0) - 10.0).abs() < 1e-5);
    for i in 0..n {
        let w = kerb_offset(&v, i, 40.0);
        assert!(w > 0.0 && w <= 40.0 + 1e-5, "vertex {i} width {w}");
    }
}

#[test]
fn a_ramp_that_lands_on_an_existing_vertex_inserts_nothing() {
    // The guard the connector path relies on: `dedupe` drops exact duplicates and
    // `direction` normalises segments of at least one tile unit, so a split point
    // within rounding distance of a vertex must not be inserted at all.
    let mut v = Vec::new();
    // A run of 0.2 at extent 1000 is 200 units, which is exactly the second point.
    ribbon_tapered(&[0, 0, 200, 0, 1000, 0], 1000, Taper::new(0.5, 1.0, 0.2), &mut v, &mut Vec::new());
    assert_eq!(v.len() / FLOATS_PER_VERTEX, 6, "three points, no fourth inserted");
    assert!((kerb_offset(&v, 2, 40.0) - 40.0).abs() < 1e-5, "and it is the full-width point");
}

#[test]
fn every_float_of_a_tapered_ribbon_is_finite() {
    for taper in [
        Taper::new(0.5, 0.5, 0.25),
        Taper::new(1.0 / 255.0, 1.0, 0.5),
        Taper::new(0.5, 0.5, 10.0),
        Taper::new(f32::NAN, -3.0, f32::NEG_INFINITY),
    ] {
        for line in [
            &[0, 0, 100, 0][..],
            &[0, 0, 1, 0, 1, 1, 2, 1][..],
            &[0, 0, 1000, 0, 0, 10][..],
            &[0, 0, 100, 0, 0, 0][..],
        ] {
            let mut v = Vec::new();
            ribbon_tapered(line, 4096, taper, &mut v, &mut Vec::new());
            for (i, f) in v.iter().enumerate() {
                assert!(f.is_finite(), "float {i} is {f} for {taper:?} on {line:?}");
            }
        }
    }
}

#[test]
fn a_ratio_outside_the_unit_range_cannot_be_constructed() {
    // Clamped at the constructor rather than checked at the tessellator: a zero
    // ratio draws nothing and a negative one turns the road inside out, and neither
    // should be a state the caller can hand over to be caught later.
    assert_eq!(Taper::new(2.0, 1.5, 0.1), Taper::new(1.0, 1.0, 0.1));
    assert!(Taper::new(0.0, -1.0, 0.1).start > 0.0);
    assert!(Taper::new(0.0, -1.0, 0.1).end > 0.0);
    assert_eq!(Taper::new(f32::NAN, f32::INFINITY, 0.1), Taper::new(1.0, 1.0, 0.1));
    assert!(Taper::new(0.5, 0.5, -1.0).is_flat(), "a negative run cannot ramp");
    assert!(Taper::NONE.is_flat());
}
