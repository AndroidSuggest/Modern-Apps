//! Flat-style tests, part 3: the cross-check against `basemap.json` and the pins.
//!
//! Split from [`paint`]'s test module so each file stays small.

use super::paint::{Ramp, MAX_ZOOM};
use super::paint_extra2::{
    assert_ramps_agree, authored_filter_kinds, authored_layer, authored_layout_ramp,
    authored_property, basemap, colors_in, extended_to_fixed_ground_width, find, find_lane,
    FIXED_GROUND_WIDTH,
};
use crate::style::{layers, LayerKind, Toggle};
use serde_json::Value as Json;

/// **The mitigation
/// **The mitigation for the one risk this module has a history of.** Hand-transcribing
/// `basemap.json` failed twice before, so every value that exists in both files is compared
/// here and divergence fails a build rather than being noticed on a screenshot.
///
/// What is *not* compared, and why: a fill's dark colour (the authored file is light-only)
/// and a line's colour and dash (deliberately the app's own, warmer than the authored
/// white-on-grey; see this module's docs).
#[test]
fn the_flat_style_agrees_with_basemap_json() {
    let root = basemap();
    for layer in layers() {
        // A carriageway layer is app-only: it draws a road's surface with its lane markings
        // painted on, which the authored `basemap.json` has no concept of. Its width, colour
        // and lane semantics are all deliberately its own, so — like transit's width — it is
        // pinned by its own test (`the_carriageway_is_gated_and_sized_by_the_lane`) rather
        // than cross-checked here. `junction-connector` is the same surface continued through
        // an intersection and has no authored counterpart either.
        if layer.carriageway {
            continue;
        }
        let authored = authored_layer(&root, &layer.authored);
        // The source layer has to match, with two structural exceptions that are not
        // transcription slips:
        //
        //  * `poi` vs `pois`. The archive's own layer table names it in the singular
        //    (`dict::LAYERS`); the reference tile set uses the plural. Same data.
        //  * `transit`. The reference has no transit layer at all — `v4.pmtiles` does
        //    not carry one — so `transit-rail` names `roads_rail` for the *colour* it
        //    copies while reading a source only our archives have. Its width is not
        //    cross-checked either; see the width arm below.
        if layer.toggle != Some(Toggle::Transit) {
            let expected = if layer.source_layer == "poi" {
                "pois"
            } else {
                layer.source_layer.as_str()
            };
            assert_eq!(
                authored.get("source-layer").and_then(Json::as_str),
                Some(expected),
                "`{}` reads a different source layer than `{}` does",
                layer.id,
                layer.authored,
            );
        }
        match layer.kind {
            LayerKind::Fill => {
                // The light colour has to be one the authored `fill-color` can produce.
                let mut colors = Vec::new();
                colors_in(
                    authored
                        .get("paint")
                        .and_then(|p| p.get("fill-color"))
                        .expect("a colour"),
                    &mut colors,
                );
                assert!(
                    colors.contains(&layer.light),
                    "`{}`'s {:#010X} is not a colour `{}`'s fill-color paints: {:?}",
                    layer.id,
                    layer.light,
                    layer.authored,
                    colors
                        .iter()
                        .map(|c| format!("{c:#010X}"))
                        .collect::<Vec<_>>(),
                );
                let authored_opacity = authored_property(&authored, "fill-opacity")
                    .unwrap_or_else(|| Ramp::constant(1.0));
                assert_ramps_agree(&layer.id, "opacity", &layer.opacity, &authored_opacity);
            }
            LayerKind::Line => {
                let width = authored_property(&authored, "line-width")
                    .unwrap_or_else(|| Ramp::constant(1.0));
                // Gap-only casings (bridges-other: authored gap, no width —
                // the casing IS the gap band, no centre stroke) carry no
                // width ramp of their own; the stroke tessellator keys off
                // the gap peak, so width agreement is vacuous there. The
                // default-1.0 only fires when authored has NO line-width.
                //
                // Transit is exempt for a different reason: a route line is
                // one object the eye follows across the network, so it is a
                // constant width at every zoom rather than `roads_rail`'s
                // ramp. `a_transit_line_is_one_constant_width` pins that
                // deliberate divergence in place of this comparison.
                let authored_has_width = authored
                    .get("paint")
                    .and_then(|p| p.get("line-width"))
                    .is_some();
                if authored_has_width && layer.toggle != Some(Toggle::Transit) {
                    let width = extended_to_fixed_ground_width(&layer.id, "width", &width);
                    assert_ramps_agree(&layer.id, "width", &layer.width, &width);
                }
                let gap = authored_property(&authored, "line-gap-width")
                    .unwrap_or_else(|| Ramp::constant(0.0));
                let gap = extended_to_fixed_ground_width(&layer.id, "gap_width", &gap);
                assert_ramps_agree(&layer.id, "gap_width", &layer.gap_width, &gap);
            }
            LayerKind::Symbol => {
                // A POI layer's colour is one arm of the authored `text-color` `case`,
                // exactly as a data-driven fill's colour is one arm of its
                // `fill-color`. Checked the same way, because the six-way colour split
                // is most of what "matching the reference" means for this layer — and
                // a mistyped hex would otherwise only show as a slightly-wrong shade.
                if let Some(text_color) = authored.get("paint").and_then(|p| p.get("text-color")) {
                    let mut colors = Vec::new();
                    colors_in(text_color, &mut colors);
                    if colors.len() > 1 {
                        assert!(
                            colors.contains(&layer.light),
                            "`{}`'s {:#010X} is not a colour `{}`'s text-color paints: {:?}",
                            layer.id,
                            layer.light,
                            layer.authored,
                            colors
                                .iter()
                                .map(|c| format!("{c:#010X}"))
                                .collect::<Vec<_>>(),
                        );
                    }
                }
                // Text sizes are layout properties in the authored style, not
                // paint — compared stop-for-stop against `text-size`. Data-driven
                // sizes (`case` over population_rank) are transcribed as the
                // constant the arms evaluate to at the reference zooms (see the
                // flat file), so the cross-check only covers plain ramps.
                if let Some(authored_size) = authored_layout_ramp(&authored, "text-size") {
                    assert_ramps_agree(&layer.id, "text_size", &layer.text_size, &authored_size);
                }
                // Halo WIDTH stays authored (1px everywhere): a transcription
                // slip is a halo that is missing (0) or doubled, so it fails
                // the build like any other value. Halo COLOR is deliberately
                // Halo WIDTH stays authored (1px everywhere) except the
                // deliberate locality legibility bump (pinned in
                // `a_line_layer_has_a_width...`); halo COLOR is ours (see
                // above), not the authored #e0e0e0.
                if let Some(width) = authored
                    .get("paint")
                    .and_then(|p| p.get("text-halo-width"))
                    .and_then(Json::as_f64)
                {
                    let expected = if layer.id == "places-locality" {
                        1.5
                    } else {
                        width as f32
                    };
                    assert!(
                        (layer.halo_width - expected).abs() < 1e-6,
                        "`{}`'s halo_width is {} where basemap.json says {width}",
                        layer.id,
                        layer.halo_width,
                    );
                }
            }
        }
    }
}

/// Above its clamp a road's stroke doubles with every zoom, which is what holds the road a
/// fixed width **on the ground** while the ground halves under it.
///
/// A ratio against a tolerance rather than an equality on the values: the stops are `f32`
/// and are read back through the ramp's exponential interpolation, so asserting an exact
/// number would be a test that passes by luck and fails on a rounding change. The ratio is
/// also the property that actually matters — it is what "fixed ground width" *means*, and
/// it stays true no matter what the anchor value is or which layer carries it.
#[test]
fn a_road_stroke_doubles_with_every_zoom_above_its_clamp() {
    for (id, property, anchor) in FIXED_GROUND_WIDTH {
        let layer = find(id);
        let ramp = match *property {
            "width" => &layer.width,
            "gap_width" => &layer.gap_width,
            other => panic!("`{id}` is tabulated with an unknown property `{other}`"),
        };
        let mut zoom = *anchor;
        while zoom + 1.0 <= MAX_ZOOM as f64 {
            let (lower, upper) = (ramp.at(zoom), ramp.at(zoom + 1.0));
            assert!(
                lower > 0.0,
                "`{id}`'s {property} is 0 at z{zoom}, so it has no width to double",
            );
            let ratio = upper / lower;
            assert!(
                (ratio - 2.0).abs() < 1e-3,
                "`{id}`'s {property} goes {lower} -> {upper} across z{zoom}..z{}, a ratio of \
                     {ratio} where holding a fixed ground width needs 2",
                zoom + 1.0,
            );
            zoom += 1.0;
        }
    }
}
/// The one place the flat file cannot say what the authored file says: two casings are an
/// `_early`/`_late` pair split at z12 there and one ramp here. The flat layer carries the
/// `_late` half, so this bounds what carrying it costs at the shallow zooms the `_early`
/// half used to own — 0.4 Dp, worst immediately below the split.
#[test]
fn a_collapsed_casing_pair_matches_its_authored_late_half() {
    let root = basemap();
    for (id, pair) in [
        ("roads-major-casing", "roads_major_casing"),
        ("roads-highway-casing", "roads_highway_casing"),
    ] {
        let layer = find(id);
        assert_eq!(layer.authored, format!("{pair}_late"));
        let early = authored_layer(&root, &format!("{pair}_early"));
        for property in ["line-width", "line-gap-width"] {
            let Some(ramp) = authored_property(&early, property) else {
                continue;
            };
            for tenth in 0..120 {
                let zoom = tenth as f64 / 10.0;
                // Only where the casing is drawn at all. Below its own width ramp the two
                // halves may say anything, because neither puts a pixel on screen.
                if !layer.stroke(zoom).visible() {
                    continue;
                }
                let ours = match property {
                    "line-width" => layer.width.at(zoom),
                    _ => layer.gap_width.at(zoom),
                };
                assert!(
                    (ours - ramp.at(zoom)).abs() <= 0.4,
                    "`{id}`'s {property} is {ours} at z{zoom} where the `_early` half says {}",
                    ramp.at(zoom),
                );
            }
        }
    }
}

/// **The deliberate divergence from `roads_rail`**, in place of the width comparison
/// `the_flat_style_agrees_with_basemap_json` skips for this layer.
///
/// A basemap's rail casing is scenery and thickens with the zoom like every other road. A
/// transit line is not scenery — it is one object the eye follows from end to end, and a route
/// that is a hairline at z10 and a band at z18 reads as two different things. So it is a
/// constant width, and the floor is where the network first appears rather than where the
/// stroke first has a pixel in it.
///
/// The cap is not arbitrary: `no_line_layer_is_more_than_a_couple_of_dp_wide_below_street_zoom`
/// walks z0..z8 over the ramp alone and does not consult `min_zoom`, so a constant here is
/// spent against that budget at every zoom whether or not it is drawn.
#[test]
fn a_transit_line_is_one_constant_width_from_its_own_floor() {
    let layer = find("transit-rail");
    assert_eq!(
        layer.min_zoom, 8,
        "the network's floor, matching `schema::transit::MIN_ZOOM`"
    );
    assert_eq!(layer.max_zoom, MAX_ZOOM);
    for tenth in 0..=(MAX_ZOOM as u32 * 10) {
        let zoom = tenth as f64 / 10.0;
        assert!(
            (layer.width.at(zoom) - 3.0).abs() < 1e-6,
            "transit-rail is {} Dp at z{zoom}",
            layer.width.at(zoom),
        );
    }
    // Whatever the constant becomes, it has to stay inside the shallow-zoom budget.
    assert!(
        layer.width.at(0.0) * 2.0 <= 10.0,
        "over the shallow-zoom width budget"
    );
}

/// The corridor fan-out: routes sharing one track are one line at regional zoom and separate
/// parallel lines once the camera is close enough to tell them apart.
///
/// The *count* steps rather than the spacing ramping. Widening a spacing ramp makes four
/// lanes narrower at low zoom, which is not the same as a corridor carrying fewer of them:
/// they all thin together and converge into one unreadable stripe. Stepping the count
/// instead keeps a constant, legible 6 Dp between adjacent lanes at every zoom and makes a
/// colour visibly re-assign to a different lane at each boundary.
#[test]
fn transit_lanes_step_with_zoom_over_a_constant_spacing() {
    let layer = find("transit-rail");
    // Two adjacent lanes are always 6 Dp apart, whatever the camera is doing.
    for zoom in [0.0, 8.0, 9.5, 11.0, 13.0, 20.0] {
        assert_eq!(
            layer.spread.at(zoom),
            6.0,
            "the spacing is constant at z{zoom}"
        );
    }
    assert_eq!(layer.lanes.at(8.0).floor(), 1.0, "one corridor below z9");
    assert_eq!(layer.lanes.at(9.0).floor(), 2.0, "two lanes from z9");
    assert_eq!(layer.lanes.at(11.0).floor(), 3.0, "three from z11");
    assert_eq!(layer.lanes.at(13.0).floor(), 4.0, "four from z13");
    assert_eq!(layer.lanes.at(20.0).floor(), 4.0, "and it stays there");
    // Nothing else moves sideways, or every road in the style would. The road lane fan used to
    // be the one exception; the carriageway that replaced it needs no spread at all, because a
    // marking's place across the road is a coordinate in the mesh rather than an offset of it.
    for other in layers().iter().filter(|l| l.id != "transit-rail") {
        assert_eq!(other.spread.peak(), 0.0, "{} must not spread", other.id);
        assert_eq!(other.lanes.peak(), 1.0, "{} must not fan out", other.id);
    }
}

/// The road carriageway: `roads-carriageway` is the road layer drawn as a surface, it only
/// appears once the camera is close enough to make lane markings legible, and its width ramp is
/// **one lane** rather than a whole road — the renderer multiplies by the feature's lane count,
/// so a two-lane street and an eight-lane motorway come off the same ramp at their true widths.
#[test]
fn the_carriageway_is_gated_and_sized_by_the_lane() {
    let all = crate::style::layers_with_lane_rendering();
    let layer = find_lane("roads-carriageway");
    assert!(
        layer.carriageway,
        "roads-carriageway must draw as a surface"
    );
    // Two layers draw a surface now. `road_carriageway_layer` no longer picks between them by
    // declaration order — it names the roads source — so this list is pinning *draw order*,
    // not the gate: the connector has to come second, or the road surface paints over the
    // connector at the mouth of the junction and clips its edge lines short of the kerb.
    assert_eq!(
        all.iter()
            .filter(|l| l.carriageway)
            .map(|l| l.id.as_str())
            .collect::<Vec<_>>(),
        vec!["roads-carriageway", "junction-connector"],
        "the layers drawn as road surfaces, in draw order",
    );
    // And the gate really is order-independent: it still answers the road layer when the
    // connector is put first, which is what `turn_arrows_are_gated_by_the_road_lane_layer`
    // relies on and what the flag alone could not promise.
    assert_eq!(
        super::road_carriageway_layer(all).map(|l| l.id.as_str()),
        Some("roads-carriageway"),
    );
    assert_eq!(
        layer.min_zoom, 16,
        "the dense lane detail is gated to high zoom"
    );
    assert!(!layer.draws_at(15), "no carriageway at z15");
    assert!(layer.draws_at(16), "a carriageway from z16");
    // A lane is a ground width, so it grows with zoom — roughly the 3.5 m real lane at each of
    // these, which is what makes the shader's marking widths land where a driver expects them.
    assert!(layer.width.at(16.0) > 0.0, "a lane has width at z16");
    assert!(
        layer.width.at(20.0) > layer.width.at(16.0),
        "and it widens zooming in"
    );
    // And it keeps growing to the top of the range, which is the one part of this ramp that is
    // not a styling choice. `Ramp::at` clamps above the last stop, so a ramp ending at z20
    // freezes the lane at 40 dp while the world keeps doubling — the painted lane's *ground*
    // width then halves per zoom, reaching a quarter of a lane by z22, and `MAX_ZOOM` is 22.
    // That put the baked connector offset, which is a fixed projected size, 4x outside the
    // road surface at full zoom. The z22 stop continues the base-2 progression so a lane holds
    // one true ground width from z20 up; drop it and the cliff comes back.
    for zoom in [20.0_f64, 21.0] {
        let (here, next) = (layer.width.at(zoom), layer.width.at(zoom + 1.0));
        assert!(
            (f64::from(next / here) - 2.0).abs() < 1e-6,
            "a lane must double from z{zoom} to z{}: {here} then {next}",
            zoom + 1.0,
        );
    }
    // The asphalt has to be darker than the off-white the shader paints its markings in, which
    // is why this is not the white `roads-major` fill it draws over.
    let road = find("roads-major");
    assert_ne!(layer.light, road.light);
    assert_ne!(layer.dark, road.dark);
}

/// A connector is the carriageway continued through a junction, so it has to be the same
/// asphalt at the same width over the same zoom range.
///
/// Divergence here does not fail anything, it just looks wrong: a connector a shade off the
/// road it joins draws a visible seam across every intersection, and one off the road's lane
/// width steps in or out at the kerb. Cheaper to pin than to notice on a screenshot.
#[test]
fn a_connector_matches_the_carriageway_it_continues() {
    let (road, connector) = (
        find_lane("roads-carriageway"),
        find_lane("junction-connector"),
    );
    assert_eq!(
        connector.light, road.light,
        "a connector is the same asphalt"
    );
    assert_eq!(connector.dark, road.dark);
    for zoom in [16.0, 17.5, 18.0, 20.0, 22.0] {
        let (a, b) = (connector.width.at(zoom), road.width.at(zoom));
        assert!(
            (a - b).abs() < 1e-6,
            "a connector is one lane of the road's own width at z{zoom}: {a} vs {b}",
        );
    }
    // The same floor as the road, which is the point: a connector one zoom later than the
    // carriageway it joins would leave every junction as a hole in the road surface for a
    // whole zoom level, and one zoom earlier would hang it in the air with nothing to join.
    // The tiler's junction layer is emitted from this zoom to match.
    assert_eq!(
        connector.min_zoom, road.min_zoom,
        "a connector appears with its road"
    );
    assert_eq!(connector.max_zoom, road.max_zoom, "and leaves with it");
    // No kind or flag filter: the junction layer carries connectors and nothing else, so
    // there is nothing to narrow it to.
    assert!(connector.kind_ids.is_empty());
    assert_eq!((connector.require_flags, connector.forbid_flags), (0, 0));
}
/// The other half of the cross-check, and the larger hand-transcribed surface: **which
/// features each layer draws**.
///
/// A data-driven authored layer becomes several flat layers, one per colour, so no single
/// flat layer's `kinds` matches the authored filter. What must hold is closure: the union
/// across the family is exactly the set the authored filter admits, so a kind cannot be
/// dropped (it would stop being drawn) or invented (it would be drawn in the wrong colour).
#[test]
fn the_kinds_each_authored_layer_admits_are_all_drawn_and_no_others() {
    let root = basemap();
    let mut families: Vec<&str> = Vec::new();
    for layer in layers().iter().filter(|l| l.kind == LayerKind::Fill) {
        if !families.contains(&layer.authored.as_str()) {
            families.push(&layer.authored);
        }
    }
    for family in families {
        let authored = authored_layer(&root, family);
        let mut admitted = authored_filter_kinds(authored.get("filter"));
        let mut drawn: Vec<String> = layers()
            .iter()
            .filter(|l| l.authored == family)
            .flat_map(|l| l.kinds.iter().cloned())
            .collect();
        if admitted.is_empty() {
            // An unrestricted authored layer needs an unfiltered flat layer, or the kinds
            // its colour expression does not name would stop being drawn at all.
            assert!(
                layers()
                    .iter()
                    .any(|l| l.authored == family && l.kinds.is_empty()),
                "`{family}` admits every kind but no flat layer draws them",
            );
            continue;
        }
        admitted.sort_unstable();
        drawn.sort_unstable();
        // Kinds we deliberately do not draw, and why. Checked explicitly so the assertion
        // below still catches an *accidental* divergence from upstream, which is what it is
        // for — a silent one would mean a kind quietly stopped rendering.
        //
        // `protected_area` and `nature_reserve` are the tags the world's MARINE protected
        // areas carry. The sea has no geometry, so nothing is drawn over them, and on a planet
        // build they painted green across open water — the reported bug. Deriving sea geometry
        // to cover them was attempted twice and failed twice; see `mamaps_build`'s
        // `tiler::add_ocean` for both failure modes. Not drawing them is the fix that works.
        //
        // The cost, stated plainly: a protected area or nature reserve *on land* is no longer
        // green. Restore them here the day the sea can paint over them.
        const NOT_DRAWN: &[&str] = &["protected_area", "nature_reserve"];
        let (skipped, admitted): (Vec<String>, Vec<String>) = admitted
            .into_iter()
            .partition(|k| NOT_DRAWN.contains(&k.as_str()));
        for kind in &skipped {
            assert!(
                !drawn.contains(kind),
                "`{kind}` is in NOT_DRAWN but a flat layer still draws it",
            );
        }
        // Every kind exactly once: two flat layers claiming the same kind would draw it
        // twice, in whichever colour came last.
        assert_eq!(
            drawn, admitted,
            "the flat layers for `{family}` draw a different kind set than its filter admits",
        );
    }
}

/// Every authored layer a flat layer names has to exist, or the cross-check silently stops
/// checking that layer.
#[test]
fn every_authored_layer_a_flat_layer_names_exists() {
    let root = basemap();
    let ids: Vec<&str> = root
        .get("layers")
        .and_then(Json::as_array)
        .expect("layers")
        .iter()
        .filter_map(|layer| layer.get("id").and_then(Json::as_str))
        .collect();
    for layer in layers() {
        assert!(
            ids.contains(&layer.authored.as_str()),
            "`{}` names authored layer `{}`, which is not in basemap.json",
            layer.id,
            layer.authored,
        );
    }
}
