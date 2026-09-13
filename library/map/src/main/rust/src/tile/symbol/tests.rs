use super::*;
use crate::style::Layer;
use crate::tile::geometry::ShapedLabel;
use crate::tile::glyph::{fonts_staged, Weight};
use crate::tile::sprite::Sprite;
use tilecodec::mamaps::body::{Body, Feature};

/// The size arms are the style's job now, so there is no multiplier here to pin.
/// `paint::city_labels_track_the_big_city_arm_at_compared_zooms` covers the arms.
#[test]
fn rank_for_layer_orders_country_before_subplace() {
    assert!(rank_for_layer("places-country") < rank_for_layer("places-region"));
    assert!(rank_for_layer("places-region") < rank_for_layer("places-locality"));
    assert!(rank_for_layer("places-locality") < rank_for_layer("places-subplace"));
    assert_eq!(rank_for_layer("something-else"), u8::MAX, "unknown ids sink");
}

/// Line labels rank below every point label but above the unknown sink, and major roads
/// outrank minor roads outrank rivers so the more important line wins a crossing collision.
#[test]
fn line_labels_rank_below_points_roads_above_rivers() {
    assert!(rank_for_layer("roads-label-major") > rank_for_layer("places-subplace"));
    assert!(rank_for_layer("roads-label-major") > rank_for_layer("poi-food"));
    assert!(rank_for_layer("roads-label-major") < rank_for_layer("roads-label-minor"));
    assert!(rank_for_layer("roads-label-minor") < rank_for_layer("waterway-label"));
    assert!(rank_for_layer("waterway-label") < u8::MAX, "line labels must not sink");
}

/// Every POI layer sits at one rank, below every place label.
///
/// One rank for all six because the split exists to give each colour group its own
/// `text-color` and nothing else — if they ranked in file order, a cafe would beat a
/// park at a collision for no reason anyone authored. And they must be *known* ranks:
/// falling through to `u8::MAX` would put them below an unrecognised layer.
#[test]
fn every_poi_layer_shares_one_rank_below_the_places() {
    let poi = [
        "poi-outdoor", "poi-transport", "poi-civic", "poi-shop", "poi-food", "poi-culture",
    ];
    for id in poi {
        assert_eq!(rank_for_layer(id), 4, "{id}");
        assert!(rank_for_layer(id) > rank_for_layer("places-subplace"), "{id}");
        assert!(rank_for_layer(id) < u8::MAX, "{id} fell through to the sink rank");
    }
    // And the style really does call them that — a renamed layer would silently sink.
    for layer in crate::style::layers() {
        if layer.toggle == Some(crate::style::Toggle::Poi) {
            assert!(poi.contains(&layer.id.as_str()), "`{}` is not ranked", layer.id);
        }
    }
}

/// The reference's whole `icon-image` expression: `station` renames, everything else
/// is the kind itself, and a kind with no picture draws label-only.
#[test]
fn a_kind_resolves_to_its_own_sprite_except_the_one_the_reference_renames() {
    let id = crate::style::kind_id_for_test;
    let park = sprite_for(id("park")).expect("park has a sprite");
    assert_eq!(
        park.uv.u0.to_bits(),
        crate::tile::sprite::atlas().get("park").expect("park").uv.u0.to_bits(),
    );
    // `station` is the rename; it must NOT resolve to a sprite called `station`.
    let station = sprite_for(id("station")).expect("station resolves to train_station");
    assert_eq!(
        station.uv.u0.to_bits(),
        crate::tile::sprite::atlas().get("train_station").expect("train_station").uv.u0.to_bits(),
    );
    assert!(crate::tile::sprite::atlas().get("station").is_none(), "or this proves nothing");
    // The one POI kind with no picture: label-only, as MapLibre draws it.
    assert!(sprite_for(id("townhall")).is_none());
    // A kind that is not a POI at all, and the `dict::NONE` id.
    assert!(sprite_for(id("highway")).is_none());
    assert!(sprite_for(0).is_none(), "the no-kind id must not index the table");
}

#[test]
fn an_unnamed_feature_shapes_nothing() {
    // A feature the name table cannot resolve shapes to None: the renderer
    // skips it silently — no panic, no partial geometry.
    let body = Body::new(4096);
    let layer = Layer {
        id: "places-country".to_string(),
        source_layer: "places".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_PLACES,
        kind: crate::style::LayerKind::Symbol,
        kinds: vec!["country".to_string()],
        kind_ids: vec![crate::style::kind_id_for_test("country")],
        require_flags: 0,
        forbid_flags: 0,
        detail_ids: Vec::new(),
        forbid_details: Vec::new(),
        light: 0xFFA3A3A3,
        dark: 0xFFA3A3A3,
        opacity: crate::style::paint::Ramp::constant(1.0),
        width: crate::style::paint::Ramp::constant(0.0),
        gap_width: crate::style::paint::Ramp::constant(0.0),
        spread: crate::style::paint::Ramp::constant(0.0),
        lanes: crate::style::paint::Ramp::constant(1.0),
        carriageway: false,
        dash: (0.0, 0.0),
        text_size: crate::style::paint::Ramp::constant(12.0),
        text_size_large: None,
        rank_threshold: None,
        uppercase: true,
        medium: true,
        toggle: None,
        icon: false,
        text_offset: (0.0, 0.0),
        text_max_width: 0.0,
        variable_anchor: Vec::new(),
        halo_light: 0xFFE2DFDA,
        halo_dark: 0xFFE2DFDA,
        halo_width: 1.0,
        min_zoom: 0,
        browse_min_zoom: 0,
        max_zoom: 22,
        authored: "places_country".to_string(),
    };
    let feature = Feature {
        kind: 1,
        kind_detail: 0,
        geom_type: tilecodec::mamaps::body::GEOM_POINT,
        flags: 0,
        name_idx: tilecodec::mamaps::body::NAME_NONE,
        parts_offset: 0,
        part_count: 0,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    };
    let label = shape_label(&layer, &body, &feature, "Test", 4096, 7, 0);
    assert!(label.is_none(), "no point, no anchor, no label");
}

/// **The bug this field exists to fix.** A shaped label reports the feature's own kind, not
/// its layer's first whitelist entry — the old pick path read `layer.kinds.first()`, so every
/// hit on `poi-food`'s four kinds came back as `restaurant`.
///
/// Also pins the id: the index passed in is the position in the *body* layer, which is what
/// the archive's id table is parallel to.
#[test]
fn a_label_carries_its_own_kind_and_id_not_its_layers() {
    let cafe = crate::style::kind_id_for_test("cafe");
    let mut poi = tilecodec::mamaps::body::Layer::new(tilecodec::mamaps::dict::LAYER_POI);
    poi.features.push(Feature {
        kind: cafe,
        kind_detail: 0,
        geom_type: tilecodec::mamaps::body::GEOM_POINT,
        flags: 0,
        name_idx: 1,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    poi.parts.push(tilecodec::mamaps::body::Part {
        coord_start: 0,
        point_count: 1,
        winding: tilecodec::mamaps::body::WINDING_OUTER,
    });
    poi.coords = vec![(2048, 2048)];
    let body = Body {
        extent: 4096,
        layers: vec![poi],
        names: vec!["Blue Bottle".to_string()],
        ids: vec![(tilecodec::mamaps::dict::LAYER_POI, vec![987_654_321])],
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None, carriageways: Vec::new(), convention: None,
    };
    // A layer whose whitelist lists `restaurant` first, exactly as `poi-food` does.
    let layer = food_layer();
    // A reference *into* the body, not a copy: `tile_point` recovers the feature's layer by
    // scanning for the pointer inside each layer's feature slice, so a copy anchors nowhere.
    let feature = &body.layers[0].features[0];
    // The staged TTFs are real, so shaping must succeed; a `None` here means the fixture
    // broke, not that the test does not apply. Skipping silently would make the two
    // assertions below vacuous.
    assert!(fonts_staged(), "this test needs the staged Noto Sans");
    let label =
        shape_label(&layer, &body, feature, "Blue Bottle", 4096, 3, 0).expect("a shaped poi");
    assert_eq!(label.kind, cafe, "the feature's kind, not the layer's first");
    assert_ne!(label.kind, layer.kind_ids[0], "or this proves nothing");
    assert_eq!(label.feature_id, 987_654_321);
}

fn food_layer() -> Layer {
    let kinds = ["restaurant", "fast_food", "cafe", "bar"];
    Layer {
        id: "poi-food".to_string(),
        source_layer: "poi".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_POI,
        kind: crate::style::LayerKind::Symbol,
        kinds: kinds.iter().map(|k| (*k).to_string()).collect(),
        kind_ids: kinds.iter().map(|k| crate::style::kind_id_for_test(k)).collect(),
        require_flags: 0,
        forbid_flags: 0,
        detail_ids: Vec::new(),
        forbid_details: Vec::new(),
        light: 0xFFCB6704,
        dark: 0xFFCB6704,
        opacity: crate::style::paint::Ramp::constant(1.0),
        width: crate::style::paint::Ramp::constant(0.0),
        gap_width: crate::style::paint::Ramp::constant(0.0),
        spread: crate::style::paint::Ramp::constant(0.0),
        lanes: crate::style::paint::Ramp::constant(1.0),
        carriageway: false,
        dash: (0.0, 0.0),
        text_size: crate::style::paint::Ramp::constant(12.0),
        text_size_large: None,
        rank_threshold: None,
        uppercase: false,
        medium: false,
        toggle: Some(crate::style::Toggle::Poi),
        icon: true,
        text_offset: (1.1, 0.0),
        text_max_width: 8.0,
        variable_anchor: Vec::new(),
        halo_light: 0xFFE2DFDA,
        halo_dark: 0xFF0D1B2A,
        halo_width: 1.0,
        min_zoom: 0,
        browse_min_zoom: 0,
        max_zoom: 22,
        authored: "pois".to_string(),
    }
}

// --- point-label billboarding under tilt --------------------------------

use crate::camera::Camera;

fn camera(pitch_deg: f64) -> Camera {
    Camera {
        center_lon: -122.4194,
        center_lat: 37.7749,
        zoom: 14.0,
        width_dp: 800.0,
        height_dp: 1000.0,
        density: 1.0,
        bearing_deg: 0.0,
        pitch_deg,
        time_seconds: 0.0,
    }
}

/// The pitch-0 tile matrix's linear 2x2 `[m0, m1, m4, m5]`, as the renderer passes it.
fn ortho2x2(cam: &Camera, z: u8, x: u32, y: u32) -> [f32; 4] {
    let flat = Camera { pitch_deg: 0.0, ..*cam }.tile_to_clip(z, x, y);
    [flat[0], flat[1], flat[4], flat[5]]
}

#[test]
fn billboard_off_is_the_plain_projection_byte_for_byte() {
    // At pitch 0 the renderer passes `billboard = false`, and the glyph must be drawn straight
    // through `tile_to_clip` — bit-identical to the pre-billboard path, whatever the anchor is.
    let cam = camera(0.0);
    let (z, x, y) = (14u8, 2617, 6335);
    let m = cam.tile_to_clip(z, x, y);
    let o = ortho2x2(&cam, z, x, y);
    let pos = (0.62f32, 0.48f32);
    let got = billboard_clip(&m, o, pos, (0.5, 0.5), false);
    let want =
        [m[0] * pos.0 + m[4] * pos.1 + m[12], m[1] * pos.0 + m[5] * pos.1 + m[13], m[14], m[15]];
    assert_eq!(got.map(f32::to_bits), want.map(f32::to_bits), "billboard-off moved a vertex");
}

#[test]
fn a_pitched_anchor_projects_to_the_same_ground_clip_as_unbillboarded() {
    // The anchor vertex (offset zero) must land exactly where the plain projection would put
    // the ground point, so a billboarded label stays glued to its feature under tilt.
    let cam = camera(50.0);
    let (z, x, y) = (14u8, 2617, 6335);
    let m = cam.tile_to_clip(z, x, y);
    let o = ortho2x2(&cam, z, x, y);
    let anchor = (0.4f32, 0.55f32);
    let billed = billboard_clip(&m, o, anchor, anchor, true);
    let plain = billboard_clip(&m, o, anchor, anchor, false);
    for (a, b) in billed.iter().zip(plain.iter()) {
        assert!((a - b).abs() < 1e-6, "anchor drifted off the ground: {a} vs {b}");
    }
}

#[test]
fn a_curved_glyph_vertex_is_never_billboarded() {
    // A curved label writes each vertex as its own anchor (offset zero), so even with the
    // billboard flag on it projects straight onto the ground — map-aligned, as it must be.
    let cam = camera(45.0);
    let (z, x, y) = (14u8, 2617, 6335);
    let m = cam.tile_to_clip(z, x, y);
    let o = ortho2x2(&cam, z, x, y);
    let v = (0.63f32, 0.47f32);
    let curved = billboard_clip(&m, o, v, v, true);
    let ground = billboard_clip(&m, o, v, v, false);
    assert_eq!(curved.map(f32::to_bits), ground.map(f32::to_bits));
}

#[test]
fn a_billboarded_glyph_offset_is_screen_constant_regardless_of_depth() {
    // The whole point of scaling the offset by the anchor's `w`: the same tile-local glyph
    // offset must produce the same *screen* (NDC) offset whether the anchor is near the camera
    // or far up-map toward the horizon — otherwise text would shrink into the distance.
    let cam = camera(55.0);
    let (z, x, y) = (14u8, 2617, 6335);
    let m = cam.tile_to_clip(z, x, y);
    let o = ortho2x2(&cam, z, x, y);
    let off = (0.02f32, -0.015f32);
    let ndc_offset = |anchor: (f32, f32)| {
        let a = billboard_clip(&m, o, anchor, anchor, true);
        let g = billboard_clip(&m, o, (anchor.0 + off.0, anchor.1 + off.1), anchor, true);
        ((g[0] / g[3]) - (a[0] / a[3]), (g[1] / g[3]) - (a[1] / a[3]))
    };
    // Two anchors at very different ground depths under the tilt (near vs far up-map). The NDC
    // offset is mathematically `ortho2x2 * off` — independent of the anchor's depth — so the
    // only difference is floating-point noise from the `* w / w` round trip.
    let near = ndc_offset((0.5, 0.72));
    let far = ndc_offset((0.5, 0.30));
    assert!((near.0 - far.0).abs() < 1e-5 && (near.1 - far.1).abs() < 1e-5,
        "screen offset changed with depth: {near:?} vs {far:?}");
}

// --- POI icon billboarding ----------------------------------------------

#[test]
fn an_icon_quad_carries_its_ground_anchor_on_every_corner() {
    // The billboard shader recovers a corner's screen offset as `position - anchor`, so all
    // four corners must name the same anchor or the quad tears apart under tilt.
    let label = icon_label((0.4, 0.6));
    let (mut vertices, mut indices) = (Vec::new(), Vec::new());
    emit_icon(&label, test_sprite(), false, 1.0, 512.0, (1.0, 0.0), &mut vertices, &mut indices);
    assert_eq!(vertices.len(), 4 * ICON_FLOATS_PER_VERTEX, "expected one quad");
    for corner in vertices.chunks_exact(ICON_FLOATS_PER_VERTEX) {
        assert_eq!((corner[4], corner[5]), label.anchor, "corner lost its anchor");
    }
}

#[test]
fn an_icon_corner_offset_is_a_tile_local_offset_from_the_anchor() {
    // Not a resolved screen position: the offset has to survive into the vertex as something
    // the shader can rotate, or billboarding would have to happen on the CPU per frame.
    let label = icon_label((0.4, 0.6));
    let (mut vertices, mut indices) = (Vec::new(), Vec::new());
    let (density, tile_span_px) = (2.0f32, 512.0f32);
    let sprite = test_sprite();
    emit_icon(&label, sprite, false, density, tile_span_px, (1.0, 0.0), &mut vertices,
        &mut indices);
    let half_w = sprite.width_dp * density * 0.5 / tile_span_px;
    let half_h = sprite.height_dp * density * 0.5 / tile_span_px;
    let first = &vertices[..ICON_FLOATS_PER_VERTEX];
    assert!((first[0] - first[4] + half_w).abs() < 1e-6, "x offset is not the half-width");
    assert!((first[1] - first[5] + half_h).abs() < 1e-6, "y offset is not the half-height");
}

#[test]
fn a_billboarded_icon_stays_the_same_screen_size_at_any_pitch() {
    // The bug being fixed: on the ground plane a tilted camera foreshortens the quad, so its
    // screen height collapses. Billboarded, the icon must measure the same in NDC at pitch 60
    // as it does flat on — that is what "faces the camera" means here.
    let sprite = test_sprite();
    let (z, x, y) = CENTRED_TILE;
    let ndc_height = |pitch: f64, billboard: bool| {
        let cam = camera(pitch);
        let m = cam.tile_to_clip(z, x, y);
        let o = ortho2x2(&cam, z, x, y);
        let label = icon_label(CENTRE_ANCHOR);
        let (mut vertices, mut indices) = (Vec::new(), Vec::new());
        emit_icon(&label, sprite, false, 1.0, cam.tile_span_px(z) as f32, (1.0, 0.0),
            &mut vertices, &mut indices);
        let corner = |i: usize| {
            let v = &vertices[i * ICON_FLOATS_PER_VERTEX..];
            let c = billboard_clip(&m, o, (v[0], v[1]), (v[4], v[5]), billboard);
            c[1] / c[3]
        };
        (corner(2) - corner(0)).abs()
    };
    let flat = ndc_height(0.0, false);
    let tilted = ndc_height(60.0, true);
    assert!((tilted - flat).abs() < 1e-5, "icon changed screen height under tilt: \
        {flat} flat vs {tilted} tilted");
    // And the bug really was a bug: left on the ground plane, pitch 60 squashes it.
    let squashed = ndc_height(60.0, false);
    assert!(squashed < flat * 0.9, "expected the on-ground quad to foreshorten, got {squashed} \
        against {flat} flat");
}

#[test]
fn a_billboarded_icon_stays_pinned_to_its_ground_point() {
    // Facing the camera must not mean floating free of the feature: the quad's centre has to
    // land exactly where the plain projection puts the POI's ground point.
    let cam = camera(55.0);
    let (z, x, y) = CENTRED_TILE;
    let m = cam.tile_to_clip(z, x, y);
    let o = ortho2x2(&cam, z, x, y);
    let label = icon_label(CENTRE_ANCHOR);
    let (mut vertices, mut indices) = (Vec::new(), Vec::new());
    emit_icon(&label, test_sprite(), false, 1.0, cam.tile_span_px(z) as f32, (1.0, 0.0),
        &mut vertices, &mut indices);
    let centre_ndc = |axis: usize| {
        let mut sum = 0.0;
        for v in vertices.chunks_exact(ICON_FLOATS_PER_VERTEX) {
            let c = billboard_clip(&m, o, (v[0], v[1]), (v[4], v[5]), true);
            sum += c[axis] / c[3];
        }
        sum / 4.0
    };
    let ground = billboard_clip(&m, o, CENTRE_ANCHOR, CENTRE_ANCHOR, false);
    assert!((centre_ndc(0) - ground[0] / ground[3]).abs() < 1e-5, "icon drifted in x");
    assert!((centre_ndc(1) - ground[1] / ground[3]).abs() < 1e-5, "icon drifted in y");
}

/// The z14 tile holding [`camera`]'s centre, and the tile-local point inside it that the
/// centre falls on. Projecting an icon here keeps it near the middle of the viewport at any
/// pitch — far from the horizon, where a clip `w` near zero would swamp the measurement.
const CENTRED_TILE: (u8, u32, u32) = (14, 2620, 6332);
const CENTRE_ANCHOR: (f32, f32) = (0.6, 0.4);

/// A minimal POI label anchored at `anchor`. No shaped text: `emit_icon` reads only the
/// anchor and the sprite.
fn icon_label(anchor: (f32, f32)) -> ShapedLabel {
    ShapedLabel {
        layer_index: 0,
        anchor,
        name: "Cafe".to_string(),
        lines: Vec::new(),
        total_advance: 0.0,
        weight: Weight::Regular,
        rank: 4,
        pop: 0,
        sprite: None,
        kind: 0,
        feature_id: tilecodec::mamaps::body::ID_NONE,
        centreline: None,
    }
}

/// A 20x24 Dp icon somewhere in the middle of the sheet. Deliberately non-square, so a test
/// that confused the two half-extents would fail.
fn test_sprite() -> Sprite {
    Sprite {
        uv: crate::tile::glyph::UvRect { u0: 0.1, v0: 0.2, u1: 0.15, v1: 0.28 },
        width_dp: 20.0,
        height_dp: 24.0,
    }
}

// --- the icon push is one derivation, not two checked values -------------

/// Reverting either push value must fail here, not silently flatten icons onto the ground.
///
/// The audit that motivated this guard: writing `line: [0.0, 0.0, 0.0, 0.0]` (flag cleared)
/// or `morph: [0.0; 4]` (matrix zeroed) in the icon push compiles clean and passes all 439
/// host tests — `vulkan/` never compiles on the host, and no host test reads the draw the
/// renderer fills by hand. On device the icons stay on the ground plane under tilt while
/// their labels stand up, and nothing says so.
///
/// So the renderer builds both symbol pushes from [`icon_push_billboard`], and this pins the
/// contract: the derivation's output equals the values the `record_symbol` icon push carries —
/// a revert in either copy breaks the equality rather than the picture.
#[test]
fn an_icon_push_built_from_parts_matches_icon_push() {
    // The derivation under test.
    let cam = camera(50.0);
    let (z, x, y) = CENTRED_TILE;
    let flat = Camera { pitch_deg: 0.0, ..cam }.tile_to_clip(z, x, y);
    let (flag, ortho) = icon_push_billboard(&flat, cam.pitch_deg);
    // The audit's revert #1: the flag cleared. At pitch 50 the icon must stand up.
    assert_eq!(flag.to_bits(), 1.0f32.to_bits(), "a pitched camera billboards its icons");
    // The audit's revert #2: the matrix zeroed. A zero matrix keeps the anchor's clip
    // offset at zero for every corner — the quad collapses onto the ground point.
    assert!(
        ortho.iter().any(|v| *v != 0.0),
        "the billboard matrix is the pitch-0 linear 2x2, never zeroed",
    );
    assert_eq!(
        ortho,
        [flat[0], flat[1], flat[4], flat[5]],
        "and it is exactly the flat matrix's linear part",
    );
    // At pitch 0 the flag clears and the shader draws straight through `tile_to_clip`,
    // byte-identical to the pre-billboard path — the derivation must say so too.
    let level = camera(0.0);
    let flat_level = Camera { pitch_deg: 0.0, ..level }.tile_to_clip(z, x, y);
    let (flag_level, _) = icon_push_billboard(&flat_level, level.pitch_deg);
    assert_eq!(flag_level.to_bits(), 0.0f32.to_bits(), "a level camera draws flat");
}

/// The flag the renderer fills and the flag the derivation computes are the same value.
///
/// This is the half of the guard the shader actually reads (`push.line.w > 0.5`): whatever
/// `record_symbol` puts in `line.w` for the icon draw must be what `billboard_clip` treats
/// as "on", at every pitch the map supports — otherwise the CPU mirror and the GPU disagree
/// about whether the icon is standing up.
#[test]
fn the_derived_flag_drives_billboard_clip_the_way_the_shader_reads_it() {
    let (z, x, y) = CENTRED_TILE;
    for pitch in [0.0f64, 15.0, 45.0, 60.0] {
        let cam = camera(pitch);
        let m = cam.tile_to_clip(z, x, y);
        let flat = Camera { pitch_deg: 0.0, ..cam }.tile_to_clip(z, x, y);
        let (flag, ortho) = icon_push_billboard(&flat, cam.pitch_deg);
        let on = flag > 0.5;
        // A glyph offset from its anchor: billboarded it stays screen-constant, flat it
        // foreshortens with the ground. `billboard_clip` must take the branch the push says.
        let anchor = CENTRE_ANCHOR;
        let off = (anchor.0 + 0.02, anchor.1 - 0.015);
        let billed = billboard_clip(&m, ortho, off, anchor, on);
        let ground = billboard_clip(&m, ortho, off, anchor, false);
        if on {
            assert_ne!(
                billed.map(f32::to_bits),
                ground.map(f32::to_bits),
                "at pitch {pitch} the flag says billboard but the vertex drew flat",
            );
        } else {
            assert_eq!(
                billed.map(f32::to_bits),
                ground.map(f32::to_bits),
                "at pitch 0 the flag says flat but the vertex stood up",
            );
        }
    }
}
