use super::*;
use tilecodec::mamaps::body::{ROOF_FLAT, ROOF_GABLED, ROOF_ORIENT_ALONG, ROOF_PYRAMIDAL, ROOF_SKILLION};

/// A closed unit square footprint over an extent of 100, so tile-local coordinates are the
/// integer coordinate / 100.
fn unit_square() -> Vec<Vec<(i32, i32)>> {
    vec![vec![(0, 0), (100, 0), (100, 100), (0, 100), (0, 0)]]
}

/// Read back one vertex as `(position, normal, packed-rgba-bits)`.
fn vertex(v: &[f32], i: usize) -> ([f32; 3], [f32; 3], u32) {
    let at = i * FLOATS_PER_VERTEX;
    ([v[at], v[at + 1], v[at + 2]], [v[at + 3], v[at + 4], v[at + 5]], v[at + 6].to_bits())
}

fn extrude_square(shape: u8, base: f32, wall_top: f32, apex: f32) -> (Vec<f32>, Vec<u32>) {
    let mut v = Vec::new();
    let mut i = Vec::new();
    extrude(
        &unit_square(),
        100,
        false,
        base,
        wall_top,
        apex,
        shape,
        0.0,
        ROOF_ORIENT_ALONG,
        0xFF_00_80_C0,
        0xFF_C0_40_20,
        &flat_ground,
        &mut v,
        &mut i,
    );
    (v, i)
}

/// Level ground: every existing test extrudes over a flat datum, so the offsets are 0.0 and
/// the meshes are the un-lifted ones.
fn flat_ground(_u: f32, _v: f32) -> f32 {
    0.0
}

/// The raw f32 vertex buffer read as bits — so two meshes can be compared for equality without
/// a NaN colour pattern making a vertex differ from itself.
fn bits(v: &[f32]) -> Vec<u32> {
    v.iter().map(|f| f.to_bits()).collect()
}

#[test]
fn colour_packs_to_rgba_byte_order() {
    // 0xAARRGGBB in, little-endian [r, g, b, a] out, so R8G8B8A8_UNORM reads it upright.
    let packed = pack_argb(0xFF_11_22_33);
    assert_eq!(packed & 0xFF, 0x11, "byte 0 is red");
    assert_eq!((packed >> 8) & 0xFF, 0x22, "byte 1 is green");
    assert_eq!((packed >> 16) & 0xFF, 0x33, "byte 2 is blue");
    assert_eq!((packed >> 24) & 0xFF, 0xFF, "byte 3 is alpha");
}

#[test]
fn a_box_with_height_emits_walls_and_a_roof() {
    // A gabled box: four walls plus a roof cap, so there is geometry both at the base and at
    // the apex, and the index buffer is whole triangles.
    let (v, i) = extrude_square(ROOF_GABLED, 0.0, 0.2, 0.35);
    assert!(!i.is_empty(), "a building with height must produce triangles");
    assert_eq!(i.len() % 3, 0, "indices come in threes");
    assert_eq!(v.len() % FLOATS_PER_VERTEX, 0, "vertices are whole");

    let count = v.len() / FLOATS_PER_VERTEX;
    let mut saw_base = false;
    let mut saw_apex = false;
    for k in 0..count {
        let (p, _, _) = vertex(&v, k);
        saw_base |= (p[2] - 0.0).abs() < 1e-6;
        saw_apex |= (p[2] - 0.35).abs() < 1e-6;
    }
    assert!(saw_base, "walls must start at the base height");
    assert!(saw_apex, "the gabled ridge must reach the apex height");
}

#[test]
fn every_vertex_carries_the_right_colour() {
    // Walls take the building colour, the roof takes the roof colour; every vertex is one or
    // the other and nothing is left uncoloured.
    let (v, _) = extrude_square(ROOF_GABLED, 0.0, 0.2, 0.35);
    let wall = pack_argb(0xFF_00_80_C0);
    let roof = pack_argb(0xFF_C0_40_20);
    let count = v.len() / FLOATS_PER_VERTEX;
    assert!(count > 0);
    for k in 0..count {
        let (_, _, argb) = vertex(&v, k);
        assert!(argb == wall || argb == roof, "vertex {k} colour {argb:#010x} is neither wall nor roof");
    }
}

#[test]
fn an_unknown_roof_shape_falls_back_to_flat() {
    // The format never stores an out-of-range shape, but the renderer must still not guess:
    // shape 200 has to extrude exactly as a flat roof does. Compared by bits because the
    // packed colour is a float NaN pattern that would never equal itself.
    let (unknown, ui) = extrude_square(200, 0.0, 0.3, 0.3);
    let (flat, fi) = extrude_square(ROOF_FLAT, 0.0, 0.3, 0.3);
    assert_eq!(bits(&unknown), bits(&flat), "an unknown shape must render as flat");
    assert_eq!(ui, fi);
}

#[test]
fn a_flat_roof_caps_level_at_the_apex() {
    // With wall_top == apex the roof is a flat lid; every roof vertex sits at the apex and its
    // normal points straight up.
    let (v, _) = extrude_square(ROOF_FLAT, 0.0, 0.25, 0.25);
    let count = v.len() / FLOATS_PER_VERTEX;
    let mut roof_vertices = 0;
    for k in 0..count {
        let (p, n, _) = vertex(&v, k);
        if n[2] > 0.9 {
            roof_vertices += 1;
            assert!((p[2] - 0.25).abs() < 1e-6, "a flat roof vertex must sit at the apex");
            assert!(n[0].abs() < 1e-6 && n[1].abs() < 1e-6, "a flat roof normal is straight up");
        }
    }
    assert!(roof_vertices >= 3, "the flat cap must tessellate to at least one triangle");
}

#[test]
fn the_footprint_survives_overhead_projection() {
    // From directly overhead the height column drops out, so the mesh must read as its
    // footprint: every vertex's (x, y) stays within the unit square.
    let (v, _) = extrude_square(ROOF_PYRAMIDAL, 0.0, 0.2, 0.6);
    let count = v.len() / FLOATS_PER_VERTEX;
    assert!(count > 0);
    let mut peak = 0.0f32;
    let mut peak_xy = (0.0, 0.0);
    for k in 0..count {
        let (p, _, _) = vertex(&v, k);
        assert!((-1e-4..=1.0 + 1e-4).contains(&p[0]), "x {} leaves the footprint", p[0]);
        assert!((-1e-4..=1.0 + 1e-4).contains(&p[1]), "y {} leaves the footprint", p[1]);
        if p[2] > peak {
            peak = p[2];
            peak_xy = (p[0], p[1]);
        }
    }
    assert!((peak - 0.6).abs() < 1e-4, "the apex reaches the full height");
    assert!((peak_xy.0 - 0.5).abs() < 0.05 && (peak_xy.1 - 0.5).abs() < 0.05, "the apex is over the centre");
}

#[test]
fn a_floating_part_flush_with_its_base_draws_no_walls() {
    // base == wall_top: a roof-only part contributes only its cap, never a zero-height wall.
    let (v, _) = extrude_square(ROOF_FLAT, 0.3, 0.3, 0.3);
    let count = v.len() / FLOATS_PER_VERTEX;
    for k in 0..count {
        let (_, n, _) = vertex(&v, k);
        assert!(n[2] > 0.9, "with no wall every triangle is roof-facing");
    }
}

#[test]
fn a_skillion_roof_slopes_from_one_edge_to_the_other() {
    // A mono-pitch roof: one edge stays at the eaves, the opposite edge rises to the apex.
    let (v, _) = extrude_square(ROOF_SKILLION, 0.0, 0.2, 0.5);
    let count = v.len() / FLOATS_PER_VERTEX;
    let mut low = f32::MAX;
    let mut high = f32::MIN;
    for k in 0..count {
        let (p, n, _) = vertex(&v, k);
        if n[2] > 0.1 {
            low = low.min(p[2]);
            high = high.max(p[2]);
        }
    }
    assert!((low - 0.2).abs() < 1e-4, "the low edge stays at the eaves, got {low}");
    assert!((high - 0.5).abs() < 1e-4, "the high edge reaches the apex, got {high}");
}

#[test]
fn empty_rings_emit_nothing() {
    let mut v = Vec::new();
    let mut i = Vec::new();
    extrude(&[], 100, false, 0.0, 0.2, 0.4, ROOF_FLAT, 0.0, ROOF_ORIENT_ALONG, 0xFFFFFFFF, 0xFFFFFFFF, &flat_ground, &mut v, &mut i);
    assert!(v.is_empty() && i.is_empty());
}

#[test]
fn a_tilted_ground_drapes_walls_and_roof_without_changing_height() {
    // A ground plane rising 0.1 tile-norm per unit u: the wall's uphill corners sit 0.1 higher
    // than its downhill ones, the flat cap rides the same slope (its uphill edge above its
    // downhill edge), and the wall height itself is unchanged — the building translates with
    // the hill, it does not stretch.
    let slope = |u: f32, _v: f32| -> f32 { u * 0.1 };
    let mut v = Vec::new();
    let mut i = Vec::new();
    extrude(
        &unit_square(),
        100,
        false,
        0.0,
        0.2,
        0.2,
        ROOF_FLAT,
        0.0,
        ROOF_ORIENT_ALONG,
        0xFFFFFFFF,
        0xFFFFFFFF,
        &slope,
        &mut v,
        &mut i,
    );
    let count = v.len() / FLOATS_PER_VERTEX;
    assert!(count > 0);
    let (mut wall_lo, mut wall_hi) = (f32::MAX, f32::MIN);
    let (mut cap_lo, mut cap_hi) = (f32::MAX, f32::MIN);
    for k in 0..count {
        let (p, n, _) = vertex(&v, k);
        if n[2] > 0.9 {
            cap_lo = cap_lo.min(p[2]);
            cap_hi = cap_hi.max(p[2]);
        } else {
            wall_lo = wall_lo.min(p[2]);
            wall_hi = wall_hi.max(p[2]);
        }
    }
    assert!((cap_hi - cap_lo - 0.1).abs() < 1e-5, "the cap follows the slope, got {cap_lo}..{cap_hi}");
    assert!(
        (wall_hi - wall_lo - 0.3).abs() < 1e-5,
        "the wall spans its 0.2 height plus the 0.1 slope, got {wall_lo}..{wall_hi}",
    );
}

#[test]
fn a_zero_ground_leaves_every_structural_height_exact() {
    // The pitch-0 guarantee at the unit level: with a 0.0 ground closure the wall base, eaves
    // and ridge must sit at exactly the structural heights passed in — no offset may leak in.
    // Compared by bits so a float rounding slip cannot hide.
    for shape in [ROOF_FLAT, ROOF_GABLED, ROOF_SKILLION, ROOF_PYRAMIDAL] {
        let (v, _) = extrude_square(shape, 0.05, 0.2, 0.35);
        let count = v.len() / FLOATS_PER_VERTEX;
        assert!(count > 0);
        let mut saw_base = false;
        let mut saw_eaves = false;
        let mut saw_ridge = false;
        for k in 0..count {
            let (p, _, _) = vertex(&v, k);
            saw_base |= p[2].to_bits() == 0.05f32.to_bits();
            saw_eaves |= p[2].to_bits() == 0.2f32.to_bits();
            saw_ridge |= p[2].to_bits() == 0.35f32.to_bits();
            assert!(
                p[2] >= 0.05 && p[2] <= 0.35,
                "shape {shape}: vertex z {z} escapes the 0.05..0.35 structural band",
                z = p[2],
            );
        }
        assert!(saw_base, "shape {shape}: no vertex sits at the exact base height");
        assert!(saw_eaves, "shape {shape}: no vertex sits at the exact eaves height");
        assert!(saw_ridge, "shape {shape}: no vertex sits at the exact ridge height");
    }
}
