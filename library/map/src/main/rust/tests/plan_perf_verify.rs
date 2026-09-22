//! Plan-verification harness: placement/collision + symbol-emit perf refactors.
//!
//! The lib-test target cannot compile in this tree (pre-existing breakage:
//! missing `overlay_tests.rs`, stale `LAYER_EARTH`/`region_links` fixtures —
//! all committed at HEAD, out of scope). This integration test links the lib
//! itself and pins the plan's bit-identical refactorings against from-scratch
//! reimplementations of the replaced algorithms:
//!  1. `place_segmented` (grid broadphase + area hoist) vs brute force.
//!  2. `layout_along_line` (monotonic walk + virtual reversed copy) vs the old
//!     rescan/reversed-collect algorithm.
//!  3. `obb_overlap` AABB path vs the half-open rect rule.
//!  4. `candidate_id` determinism / distinctness.
//!  5. `Ramp::at` single-stop fast path vs a constant.

use map_renderer::tess::text::{layout_along_line, CurvedGlyph, ShapedGlyph, ShapedLine};
use map_renderer::tile::placement::{
    candidate_id, obb_overlap, place_segmented, Obb, SegmentedCandidate,
};

// ---------------------------------------------------------------------------
// 1. Brute-force placer (the pre-grid algorithm, restated from the old code).
// ---------------------------------------------------------------------------

fn brute_overlap(a: &Obb, b: &Obb) -> bool {
    // Full SAT, no AABB fast path — independent of the optimized narrowphase.
    let axes = [
        (a.cos, a.sin),
        (-a.sin, a.cos),
        (b.cos, b.sin),
        (-b.sin, b.cos),
    ];
    let dx = b.cx - a.cx;
    let dy = b.cy - a.cy;
    let radius = |o: &Obb, ax: f32, ay: f32| {
        let u = (o.cos, o.sin);
        let v = (-o.sin, o.cos);
        o.hx * (u.0 * ax + u.1 * ay).abs() + o.hy * (v.0 * ax + v.1 * ay).abs()
    };
    axes.iter().all(|(ax, ay)| {
        let gap = (dx * ax + dy * ay).abs();
        // NOTE: strict `>=` separation (edge-touching = separated) matches the
        // documented half-open rule.
        gap < radius(a, *ax, *ay) + radius(b, *ax, *ay)
    })
}

fn brute_area(c: &SegmentedCandidate) -> f32 {
    c.boxes.iter().map(|b| b.hx * b.hy).sum::<f32>() * 4.0
}

fn brute_place(candidates: &[SegmentedCandidate]) -> Vec<(u64, bool)> {
    let mut ordered: Vec<&SegmentedCandidate> = candidates.iter().collect();
    ordered.sort_by(|a, b| {
        a.rank
            .cmp(&b.rank)
            .then_with(|| b.pop.cmp(&a.pop))
            .then_with(|| {
                brute_area(b)
                    .partial_cmp(&brute_area(a))
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .then_with(|| a.id.cmp(&b.id))
    });
    let mut accepted: Vec<Obb> = Vec::new();
    let mut out = Vec::new();
    for c in ordered {
        let free = |boxes: &[Obb], accepted: &[Obb]| {
            c.rank == 0
                || !boxes
                    .iter()
                    .any(|b| accepted.iter().any(|a| brute_overlap(a, b)))
        };
        let taken = if free(&c.boxes, &accepted) {
            (&c.boxes, false)
        } else if let Some(alternate) = c.alternate.as_ref().filter(|a| free(a, &accepted)) {
            (alternate, true)
        } else {
            continue;
        };
        accepted.extend_from_slice(taken.0);
        out.push((c.id, taken.1));
    }
    out
}

/// Deterministic xorshift64 PRNG (fixed seed — the fuzz corpus is stable).
struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }
    fn f32(&mut self, lo: f32, hi: f32) -> f32 {
        lo + (self.next() as f32 / u64::MAX as f32) * (hi - lo)
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
}

fn random_obb(rng: &mut Rng, axis_aligned_bias: bool) -> Obb {
    let (cos, sin) = if axis_aligned_bias && rng.below(4) != 0 {
        (1.0, 0.0) // the point-label majority case
    } else {
        let a = rng.f32(0.0, std::f32::consts::TAU);
        (a.cos(), a.sin())
    };
    Obb {
        cx: rng.f32(0.0, 1024.0),
        cy: rng.f32(0.0, 1024.0),
        hx: rng.f32(1.0, 60.0),
        hy: rng.f32(1.0, 20.0),
        cos,
        sin,
    }
}

#[test]
fn grid_broadphase_matches_brute_force_on_fuzz() {
    let mut rng = Rng(0x1234_5678_9abc_def0);
    for round in 0..300 {
        let n = 1 + rng.below(40);
        let mut candidates: Vec<SegmentedCandidate> = Vec::with_capacity(n);
        for i in 0..n {
            let nb = 1 + rng.below(4);
            let boxes: Vec<Obb> =
                (0..nb).map(|_| random_obb(&mut rng, true)).collect();
            let alternate = if rng.below(3) == 0 {
                let na = 1 + rng.below(3);
                Some((0..na).map(|_| random_obb(&mut rng, true)).collect())
            } else {
                None
            };
            candidates.push(SegmentedCandidate {
                // Forced id/rank/pop collisions to stress tie-breaks.
                id: (i % 7) as u64,
                rank: [0, 2, 2, 3, 4, 4, 5][rng.below(7)],
                pop: (rng.below(4)) as u16,
                boxes,
                alternate,
                feature_id: i as u64 + 1,
                layer_index: rng.below(3),
            });
        }
        let want = brute_place(&candidates);
        let got = place_segmented(&candidates);
        assert_eq!(got, want, "round {round}: grid diverged from brute force");
    }
}

#[test]
fn grid_matches_brute_force_on_degenerate_inputs() {
    // Empty, single, all-rank-0, identical boxes, edge-touching grid.
    let empty: Vec<SegmentedCandidate> = vec![];
    assert_eq!(place_segmented(&empty), brute_place(&empty));
    let mk = |id: u64, rank: u8, rect: (f32, f32, f32, f32)| SegmentedCandidate {
        id,
        rank,
        pop: 0,
        boxes: vec![Obb::from_rect(rect)],
        alternate: None,
        feature_id: id + 1,
        layer_index: 0,
    };
    // Ten identical boxes: first wins, rest collide.
    let same: Vec<SegmentedCandidate> = (0..10)
        .map(|i| mk(i, 4, (0.0, 0.0, 50.0, 20.0)))
        .collect();
    assert_eq!(place_segmented(&same), brute_place(&same));
    assert_eq!(place_segmented(&same).len(), 1);
    // Edge-touching chain: all place (half-open rule through the grid).
    let chain: Vec<SegmentedCandidate> = (0..8)
        .map(|i| mk(i, 4, (i as f32 * 50.0, 0.0, i as f32 * 50.0 + 50.0, 20.0)))
        .collect();
    assert_eq!(place_segmented(&chain).len(), 8);
    assert_eq!(place_segmented(&chain), brute_place(&chain));
    // Negative coordinates (grid floor division with negatives).
    let neg: Vec<SegmentedCandidate> = (0..6)
        .map(|i| mk(i, 4, (-400.0 + i as f32 * 30.0, -300.0, -360.0 + i as f32 * 30.0, -280.0)))
        .collect();
    assert_eq!(place_segmented(&neg), brute_place(&neg));
    // Zero-area boxes.
    let zero = vec![mk(0, 4, (10.0, 10.0, 10.0, 10.0)), mk(1, 4, (0.0, 0.0, 50.0, 20.0))];
    assert_eq!(place_segmented(&zero), brute_place(&zero));
}

// ---------------------------------------------------------------------------
// 2. Old curved-layout algorithm, restated verbatim (rescan + collect).
// ---------------------------------------------------------------------------

fn old_polyline_length(pts: &[(f32, f32)]) -> f32 {
    let mut sum = 0.0;
    for w in pts.windows(2) {
        let dx = w[1].0 - w[0].0;
        let dy = w[1].1 - w[0].1;
        sum += (dx * dx + dy * dy).sqrt();
    }
    sum
}

const MAX_TURN_RAD: f32 = std::f32::consts::FRAC_PI_4;

fn old_longest_smooth_run(pts: &[(f32, f32)]) -> &[(f32, f32)] {
    if pts.len() < 3 {
        return pts;
    }
    let cos_max = MAX_TURN_RAD.cos();
    let direction = |a: (f32, f32), b: (f32, f32)| {
        let (dx, dy) = (b.0 - a.0, b.1 - a.1);
        let len = (dx * dx + dy * dy).sqrt();
        (len > 0.0).then_some((dx / len, dy / len))
    };
    let (mut best_start, mut best_end) = (0usize, pts.len());
    let mut best_len = -1.0f32;
    let mut start = 0usize;
    let mut previous: Option<(f32, f32)> = None;
    for i in 0..pts.len() - 1 {
        let Some(d) = direction(pts[i], pts[i + 1]) else { continue };
        if let Some(p) = previous {
            if p.0 * d.0 + p.1 * d.1 < cos_max {
                let len = old_polyline_length(&pts[start..=i]);
                if len > best_len {
                    best_len = len;
                    (best_start, best_end) = (start, i + 1);
                }
                start = i;
            }
        }
        previous = Some(d);
    }
    if old_polyline_length(&pts[start..]) > best_len {
        (best_start, best_end) = (start, pts.len());
    }
    &pts[best_start..best_end]
}

fn old_sample_polyline(pts: &[(f32, f32)], d: f32) -> ((f32, f32), (f32, f32)) {
    let d = d.max(0.0);
    let mut acc = 0.0f32;
    for w in pts.windows(2) {
        let dx = w[1].0 - w[0].0;
        let dy = w[1].1 - w[0].1;
        let seg = (dx * dx + dy * dy).sqrt();
        if seg <= 0.0 {
            continue;
        }
        if acc + seg >= d {
            let t = ((d - acc) / seg).clamp(0.0, 1.0);
            return ((w[0].0 + dx * t, w[0].1 + dy * t), (dx / seg, dy / seg));
        }
        acc += seg;
    }
    let last = *pts.last().unwrap_or(&(0.0, 0.0));
    for w in pts.windows(2).rev() {
        let dx = w[1].0 - w[0].0;
        let dy = w[1].1 - w[0].1;
        let seg = (dx * dx + dy * dy).sqrt();
        if seg > 0.0 {
            return (last, (dx / seg, dy / seg));
        }
    }
    (last, (1.0, 0.0))
}

fn old_layout_along_line(
    line: &ShapedLine,
    centreline: &[(f32, f32)],
    px_per_font_unit: f32,
    up_em: f32,
) -> Vec<CurvedGlyph> {
    if centreline.len() < 2 || line.glyphs.is_empty() || px_per_font_unit <= 0.0 {
        return Vec::new();
    }
    let smooth = old_longest_smooth_run(centreline);
    if smooth.len() < 2 {
        return Vec::new();
    }
    let total_len = old_polyline_length(smooth);
    let run_len = line.advance * px_per_font_unit;
    if run_len <= 0.0 || run_len > total_len {
        return Vec::new();
    }
    let net_dx = smooth[smooth.len() - 1].0 - smooth[0].0;
    let reversed: Vec<(f32, f32)>;
    let path: &[(f32, f32)] = if net_dx < 0.0 {
        reversed = smooth.iter().rev().copied().collect();
        &reversed
    } else {
        smooth
    };
    let start = (total_len - run_len) * 0.5;
    let half_window = up_em * px_per_font_unit;
    let mut out = Vec::with_capacity(line.glyphs.len());
    for g in &line.glyphs {
        let d = start + g.pen_x * px_per_font_unit;
        let (pen, segment_tangent) = old_sample_polyline(path, d);
        let (behind, _) = old_sample_polyline(path, d - half_window);
        let (ahead, _) = old_sample_polyline(path, d + half_window);
        let (dx, dy) = (ahead.0 - behind.0, ahead.1 - behind.1);
        let chord = (dx * dx + dy * dy).sqrt();
        let tangent = if chord > 1e-7 { (dx / chord, dy / chord) } else { segment_tangent };
        out.push(CurvedGlyph { glyph: *g, pen, tangent });
    }
    out
}

fn block_run(n: usize, up_em: f32) -> ShapedLine {
    let glyphs: Vec<ShapedGlyph> = (0..n)
        .map(|i| ShapedGlyph {
            pen_x: i as f32 * up_em,
            ch: 'x',
            advance: up_em,
            bearing_x: 0.0,
            top: up_em,
            w: up_em,
            h: up_em,
        })
        .collect();
    ShapedLine { glyphs, advance: n as f32 * up_em }
}

fn assert_same_layout(got: &[CurvedGlyph], want: &[CurvedGlyph], ctx: &str) {
    assert_eq!(got.len(), want.len(), "{ctx}: glyph count");
    // Forward walks are bit-identical (same operands, same order). Reversed walks
    // mirror arcs about the total length instead of accumulating a reversed copy,
    // so they may differ in the last ulp; anything bigger is a real divergence.
    for (i, (g, w)) in got.iter().zip(want.iter()).enumerate() {
        for (field, gv, wv) in [
            ("pen.x", g.pen.0, w.pen.0),
            ("pen.y", g.pen.1, w.pen.1),
            ("tan.x", g.tangent.0, w.tangent.0),
            ("tan.y", g.tangent.1, w.tangent.1),
        ] {
            if gv.to_bits() != wv.to_bits() {
                let bad = (gv - wv).abs();
                assert!(
                    bad <= 2.0 * f32::EPSILON * wv.abs().max(1.0),
                    "{ctx}[{i}] {field}: {gv} vs {wv} (diff {bad} exceeds 2ulp)"
                );
            }
        }
    }
}

#[test]
fn linearized_layout_matches_old_algorithm_bit_for_bit() {
    let up_em = 1000.0f32;
    let ppfu = 1.0 / up_em;
    // Straight, reversed (net_dx < 0), elbow, gentle bend, degenerate points,
    // multi-part join, arc, tiny stub.
    let mut arc = Vec::new();
    for i in 0..=48 {
        let t = std::f32::consts::FRAC_PI_2 * i as f32 / 48.0;
        arc.push((0.5 + 0.35 * t.cos(), 0.5 + 0.35 * t.sin()));
    }
    let cases: Vec<(&str, Vec<(f32, f32)>)> = vec![
        ("straight", vec![(0.0, 0.0), (12.0, 0.0)]),
        ("reversed", vec![(12.0, 0.0), (0.0, 0.0)]),
        ("rev-bend", vec![(9.0, 4.0), (2.0, 0.5), (0.0, 0.0)]),
        ("elbow", vec![(0.0, 0.0), (4.0, 0.0), (4.0, 9.0)]),
        ("bend30", {
            let c = 30.0f32.to_radians();
            vec![(0.0, 0.0), (6.0, 0.0), (6.0 + 6.0 * c.cos(), 6.0 * c.sin())]
        }),
        ("degenerate", vec![(0.0, 0.0), (0.0, 0.0), (6.0, 0.0), (6.0, 0.0), (12.0, 0.0)]),
        (
            "join",
            vec![(0.0, 0.0), (6.0, 0.0), (0.0, 4.0), (9.0, 4.0)],
        ),
        ("arc", arc),
        ("zigzag", vec![(0.0, 0.0), (2.0, 1.0), (4.0, 0.0), (6.0, 1.0), (8.0, 0.0), (10.0, 1.0)]),
        // Net-dx-zero paths: neither forward nor reversed by the sign test — the
        // walk takes the forward branch (strict `<`), matching the old code's
        // `net_dx < 0.0` gate exactly.
        ("vertical", vec![(3.0, 0.0), (3.0, 12.0)]),
        ("rev-vertical", vec![(3.0, 12.0), (3.0, 0.0)]),
        ("there-and-back", vec![(0.0, 0.0), (6.0, 0.0), (0.0, 0.0)]),
    ];
    for (name, path) in &cases {
        for n in [1usize, 3, 8, 10] {
            let run = block_run(n, up_em);
            let want = old_layout_along_line(&run, path, ppfu, up_em);
            let got = layout_along_line(&run, path, ppfu);
            assert_same_layout(&got, &want, &format!("{name}/n={n}"));
        }
    }
}

#[test]
fn linearized_layout_matches_on_fuzz_polylines() {
    let up_em = 1000.0f32;
    let mut rng = Rng(0xfeed_face_cafe_1234);
    for round in 0..200 {
        let npts = 2 + rng.below(14);
        let mut path = Vec::with_capacity(npts);
        let (mut x, mut y) = (rng.f32(0.0, 1.0), rng.f32(0.0, 1.0));
        path.push((x, y));
        for _ in 1..npts {
            // Random walk, sometimes repeating a vertex (degenerate segment).
            if rng.below(8) == 0 {
                path.push((x, y));
            } else {
                x += rng.f32(-0.2, 0.2);
                y += rng.f32(-0.2, 0.2);
                path.push((x, y));
            }
        }
        let n = 1 + rng.below(12);
        let run = block_run(n, up_em);
        // Vary text size over two orders of magnitude.
        let ppfu = [0.5, 1.0, 3.0][rng.below(3)] / up_em;
        let want = old_layout_along_line(&run, &path, ppfu, up_em);
        let got = layout_along_line(&run, &path, ppfu);
        assert_same_layout(&got, &want, &format!("fuzz round {round}"));
    }
}

// ---------------------------------------------------------------------------
// 3-5. AABB rule, candidate_id, Ramp.
// ---------------------------------------------------------------------------

#[test]
fn aabb_fast_path_matches_half_open_rect_rule() {
    let rect_overlaps =
        |a: (f32, f32, f32, f32), b: (f32, f32, f32, f32)| a.0 < b.2 && b.0 < a.2 && a.1 < b.3 && b.1 < a.3;
    let mut rng = Rng(0x0bad_f00d_dead_beef);
    for _ in 0..500 {
        let r = |mut v: (f32, f32, f32, f32)| {
            if v.0 > v.2 {
                std::mem::swap(&mut v.0, &mut v.2);
            }
            if v.1 > v.3 {
                std::mem::swap(&mut v.1, &mut v.3);
            }
            v
        };
        let a = r((rng.f32(-50.0, 50.0), rng.f32(-50.0, 50.0), rng.f32(-50.0, 50.0), rng.f32(-50.0, 50.0)));
        let b = r((rng.f32(-50.0, 50.0), rng.f32(-50.0, 50.0), rng.f32(-50.0, 50.0), rng.f32(-50.0, 50.0)));
        let oa = Obb::from_rect(a);
        let ob = Obb::from_rect(b);
        assert_eq!(obb_overlap(&oa, &ob), rect_overlaps(a, b), "a={a:?} b={b:?}");
    }
    // Exact edge touch: separated.
    let a = Obb::from_rect((0.0, 0.0, 10.0, 10.0));
    let flush = Obb::from_rect((10.0, 0.0, 20.0, 10.0));
    assert!(!obb_overlap(&a, &flush));
}

#[test]
fn candidate_ids_stable_and_distinct() {
    let a = candidate_id(6, 10, 24, 33, 4);
    assert_eq!(a, candidate_id(6, 10, 24, 33, 4));
    assert_ne!(a, candidate_id(6, 10, 24, 33, 5));
    assert_ne!(a, candidate_id(6, 10, 25, 33, 4));
    assert_ne!(a, candidate_id(6, 10, 24, 34, 4));
    assert_ne!(a, candidate_id(7, 10, 24, 33, 4));
}

#[test]
fn ramp_single_stop_answers_constant() {
    use map_renderer::style::paint::Ramp;
    let c = Ramp::constant(3.5);
    for z in [-10.0, 0.0, 5.5, 14.0, 22.0, 100.0] {
        assert_eq!(c.at(z).to_bits(), 3.5f32.to_bits(), "z={z}");
    }
}
