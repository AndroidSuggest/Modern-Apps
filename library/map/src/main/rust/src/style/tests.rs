use super::*;
use tilecodec::mamaps::dict;

/// The chips narrow POI and nothing else. A filter that hid roads and country labels
/// because the user tapped "Coffee" would be a blank map, not a filtered one.
#[test]
fn a_kind_filter_narrows_poi_layers_only() {
    let poi = layers()
        .iter()
        .find(|l| l.id == "poi-food")
        .expect("poi-food");
    let road = layers()
        .iter()
        .find(|l| l.toggle.is_none())
        .expect("a basemap layer");
    let cafe = kind_id("cafe").expect("cafe");
    let bar = kind_id("bar").expect("bar");

    let all = KindFilter::all();
    assert!(all.is_empty());
    assert!(all.admits(poi, cafe), "an empty filter admits everything");
    assert!(all.admits(poi, bar));

    let coffee = KindFilter::new(vec![cafe]);
    assert!(coffee.admits(poi, cafe));
    assert!(
        !coffee.admits(poi, bar),
        "a POI kind outside the filter is dropped"
    );
    assert!(
        coffee.admits(road, bar),
        "a basemap layer is never narrowed"
    );
}

/// Equality is by value. The host rebuilds the chip list on every recomposition, so a
/// pointer comparison would report a change every frame and re-tessellate the resident set
/// forever — which is exactly what the no-op rule exists to prevent.
#[test]
fn setting_the_same_filter_twice_does_not_bump_the_generation() {
    let cafe = kind_id("cafe").expect("cafe");
    let bar = kind_id("bar").expect("bar");
    let shared = SharedToggles::new(LayerToggles::default());
    let (_, _, first) = shared.get();

    let on = LayerToggles {
        poi: true,
        transit: false,
        traffic: false,
    };
    assert!(shared.set(on, KindFilter::new(vec![cafe, bar])));
    let (toggles, kinds, second) = shared.get();
    assert!(toggles.poi);
    assert_ne!(second, first, "a real change bumps");

    // A freshly built, separately allocated filter holding the same kinds in the other
    // order — `new` sorts, so it must compare equal and bump nothing.
    assert!(!shared.set(on, KindFilter::new(vec![bar, cafe])));
    let (_, again, third) = shared.get();
    assert_eq!(third, second, "an identical set must not bump");
    assert_eq!(again, kinds);

    assert!(
        shared.set(on, KindFilter::all()),
        "clearing the chips is a change"
    );
    assert_ne!(shared.get().2, third);
}

/// Perceived luminance, for asserting a palette is actually light or dark.
fn luminance(argb: u32) -> f32 {
    let r = ((argb >> 16) & 0xFF) as f32 / 255.0;
    let g = ((argb >> 8) & 0xFF) as f32 / 255.0;
    let b = (argb & 0xFF) as f32 / 255.0;
    0.2126 * r + 0.7152 * g + 0.0722 * b
}

fn find(id: &str) -> &'static Layer {
    layers()
        .iter()
        .find(|l| l.id == id)
        .unwrap_or_else(|| panic!("{id}"))
}

#[test]
fn landcover_is_a_low_zoom_tint_and_stops_before_street_level() {
    // The opacity ramp is the only thing that gates landcover. Drawing it at every zoom —
    // which a transcribed table with no ramp to read did — lays a blanket over the map that
    // follows vegetation polygons rather than coastlines or borders, so it lines up with
    // nothing.
    let landcovers: Vec<&Layer> = layers()
        .iter()
        .filter(|l| l.source_layer == "landcover")
        .collect();
    assert!(
        !landcovers.is_empty(),
        "landcover must still be drawn at low zoom"
    );
    for l in &landcovers {
        assert!(l.opacity_at(4.0) > 0.0, "{} should tint low zooms", l.id);
        assert_eq!(l.opacity_at(7.0), 0.0, "{} is at zero opacity by z7", l.id);
        assert_eq!(
            l.opacity_at(14.0),
            0.0,
            "{} must not reach street level",
            l.id
        );
    }
}

/// The other half of the same story: `landuse_park`'s ramp is 0 at z6 rising to 1 at z11, so
/// it must not be drawn at world zoom. Ignoring it put continent-sized `national_park`,
/// `nature_reserve` and `military` polygons on the map, whose tile-clipped edges read as
/// straight cuts slashed across the shape.
#[test]
fn the_landuse_park_family_is_gated_off_at_world_zoom() {
    let parks: Vec<&Layer> = layers()
        .iter()
        .filter(|l| l.id.starts_with("landuse_park"))
        .collect();
    assert_eq!(parks.len(), 4, "one layer per authored colour");
    for l in &parks {
        assert_eq!(l.opacity_at(6.0), 0.0, "{} is at zero opacity at z6", l.id);
        assert_eq!(l.opacity_at(11.0), 1.0, "{} is fully on by z11", l.id);
    }
}

/// The gate that decides whether geometry is *built* must not come from the opacity ramp:
/// `min_zoom` is compared against the **tile's** pyramid level while the ramp follows the
/// **camera**. See [`Layer::draws_at`] for what deriving one from the other did. Only
/// `buildings` carries a floor, and that one is a cost decision — it is the densest layer in
/// the schema, so a tessellation pass plus a draw call per tile is worth avoiding even where
/// the archive would return nothing.
#[test]
fn only_a_declared_cost_floor_gates_a_fill_by_zoom() {
    for l in layers().iter().filter(|l| l.kind == LayerKind::Fill) {
        let expected = if l.id == "buildings" { 14 } else { 0 };
        assert_eq!(
            (l.min_zoom, l.max_zoom),
            (expected, paint::MAX_ZOOM),
            "`{}` carries a zoom gate that is not a declared cost floor",
            l.id,
        );
    }
}

/// Spot-checked against the authored values rather than asserting a count, so a restyle
/// changes one colour rather than breaking the test. Every colour is cross-checked against
/// `basemap.json` wholesale by `paint::the_flat_style_agrees_with_basemap_json`.
#[test]
fn fill_colour_is_the_authored_colour() {
    assert_eq!(find("earth").light, 0xFFE2DFDA, "the authored `#e2dfda`");
    assert_eq!(find("water").light, 0xFF80DEEA, "the authored `#80deea`");
    // A `match` arm the authored file writes as `rgba(210, 239, 207, 1)`.
    assert_eq!(find("landcover:grassland").light, 0xFFD2EFCF);
    // A `case` arm reached through `landuse_park`'s `in` conditions.
    assert_eq!(find("landuse_park:military").light, 0xFFC6DCDC);
    // Opacity is a per-frame ramp, not a baked alpha.
    assert_eq!(find("buildings").light, 0xFFCCCCCC);
    assert_eq!(find("landuse_urban_green").light, 0xFF9CD3B4);
}

/// Turn arrows are gated by the *road* lane layer, not by whichever layer happens to be the
/// first with a spread.
///
/// `transit-rail` carries a spread for its corridor colours and is declared before the road
/// layer, so a `find(|l| l.lane_fan())` answers with the rail layer and its `minzoom: 8`. That
/// shipped: turn arrows drew from z8, four zoom levels of dense per-frame CPU triangle building
/// for geometry that belongs at z16. Pinned against the real style because the bug was entirely
/// in the interaction between the predicate and the declaration order — a hand-built two-layer
/// fixture would have passed.
#[test]
fn turn_arrows_are_gated_by_the_road_lane_layer() {
    // With lane rendering forced on: the gate is what the switch removes, so asserting it
    // against the shipped set would only re-state that the switch is off.
    let all = layers_with_lane_rendering();
    let gate = road_carriageway_layer(all).expect("the road carriageway layer");
    assert_eq!(
        gate.id, "roads-carriageway",
        "not `transit-rail`, which has a spread"
    );

    let rail = all
        .iter()
        .find(|l| l.id == "transit-rail")
        .expect("transit-rail");
    assert!(
        rail.lane_fan(),
        "the rail corridor fan is what made the naive predicate wrong"
    );
    assert!(!rail.carriageway, "and a corridor fan is not a carriageway");
    assert!(
        rail.min_zoom < gate.min_zoom,
        "and it is the earlier of the two"
    );

    assert!(!gate.draws_at(12), "no turn arrows at z12");
    assert!(
        !gate.draws_at(gate.min_zoom - 1),
        "nor one level below the lane floor"
    );
    assert!(gate.draws_at(16), "turn arrows from z16");
}

/// And the answer does not depend on the order the style lists its layers in.
///
/// `junction-connector` carries `carriageway` too, so a `find` on the flag alone is once again
/// a predicate several layers share, answering with whichever is declared first — the same
/// shape as the `lane_fan` bug above, one layer along. `paint`'s
/// `the_carriageway_is_gated_and_sized_by_the_lane` pins that order, which keeps today's style
/// honest; this pins that the order is not load-bearing in the first place. Asserted as a
/// filtered list rather than a `find`, because "one layer matches" is the property that makes
/// order irrelevant, and a `find` cannot tell one match from the first of several.
#[test]
fn the_arrow_gate_does_not_depend_on_the_declaration_order() {
    let all = layers_with_lane_rendering();
    let matches: Vec<&str> = all
        .iter()
        .filter(|l| l.carriageway && l.source_layer_id == dict::LAYER_ROADS)
        .map(|l| l.id.as_str())
        .collect();
    assert_eq!(
        matches,
        vec!["roads-carriageway"],
        "the gate predicate must name one layer"
    );

    // The layer that would answer instead, and the two halves of why it does not.
    let connector = all
        .iter()
        .find(|l| l.id == "junction-connector")
        .expect("junction-connector");
    assert!(
        connector.carriageway,
        "a connector draws as a road surface as well"
    );
    assert_ne!(
        connector.source_layer_id,
        dict::LAYER_ROADS,
        "but it reads the junction source, which is what keeps it out of the gate",
    );
}

/// [`LANE_RENDERING`] decides whether the shipped layer set carries the carriageways at all,
/// and the roads it uncovers are still there.
///
/// The second half is the point. The carriageway pass draws *over* the flat layer loop, so
/// switching it off has to leave the pre-carriageway road lines drawing rather than a hole —
/// and `roads-carriageway` had to be dropped rather than have its flag cleared, because a
/// `carriageway: false` layer of that width would fall through to the stroke path and paint a
/// band over every road it names.
#[test]
fn the_lane_rendering_switch_removes_the_carriageways_and_nothing_else() {
    let shipped: Vec<&str> = layers()
        .iter()
        .filter(|l| l.carriageway)
        .map(|l| l.id.as_str())
        .collect();
    if LANE_RENDERING {
        assert_eq!(shipped, vec!["roads-carriageway", "junction-connector"]);
        assert!(
            road_carriageway_layer(layers()).is_some(),
            "and the turn arrows are gated on"
        );
    } else {
        assert!(
            shipped.is_empty(),
            "no surface layer, so no asphalt, markings or taper"
        );
        assert!(
            road_carriageway_layer(layers()).is_none(),
            "which is the turn-arrow gate"
        );
    }

    // Either way the plain road lines are untouched, and they draw to the top of the range.
    for id in ["roads-major", "roads-highway", "roads-minor", "roads-link"] {
        let road = find(id);
        assert!(!road.carriageway, "{id} is a stroke and stays one");
        assert!(
            road.draws_at(16) && road.draws_at(22),
            "{id} covers the carriageway's window"
        );
    }

    // And the switch removes exactly the two, leaving every other layer in its place.
    let dropped: Vec<&str> = layers_with_lane_rendering()
        .iter()
        .filter(|l| !layers().iter().any(|kept| kept.id == l.id))
        .map(|l| l.id.as_str())
        .collect();
    let expected: Vec<&str> = if LANE_RENDERING {
        Vec::new()
    } else {
        vec!["roads-carriageway", "junction-connector"]
    };
    assert_eq!(dropped, expected);
}

/// A kind the authored `case` gives its own colour has its own layer, and a kind that shares
/// a colour with another sits in the same layer rather than adding a draw.
#[test]
fn a_data_driven_fill_is_one_layer_per_colour() {
    let park: Vec<&Layer> = layers()
        .iter()
        .filter(|l| l.id.starts_with("landuse_park"))
        .collect();
    let of = |kind: &str| park.iter().find(|l| l.matches(Some(kind))).map(|l| l.light);
    assert_eq!(
        of("national_park"),
        of("cemetery"),
        "one arm, one colour, one layer"
    );
    assert_ne!(of("national_park"), of("military"));
    assert_eq!(
        of("pier"),
        None,
        "a kind the authored filter excludes is not drawn here"
    );
}

#[test]
fn every_landcover_kind_has_its_own_colour_in_both_palettes() {
    // The authored `fill-color` gives each kind a different colour, and that difference is
    // most of what makes a low zoom readable: it is why the Sahara does not look like the
    // Congo. Collapsing a palette's whole column to one literal paints every landmass a
    // single flat tint that follows vegetation polygons and lines up with nothing.
    for palette in [Palette::new(false, false), Palette::new(true, false)] {
        let mut seen: Vec<(u32, &str)> = Vec::new();
        for l in layers().iter().filter(|l| l.source_layer == "landcover") {
            let colour = l.color(palette);
            if let Some((_, other)) = seen.iter().find(|(c, _)| *c == colour) {
                panic!("{} and {} share {colour:#010X} in {palette:?}", l.id, other);
            }
            seen.push((colour, &l.id));
        }
        assert_eq!(
            seen.len(),
            7,
            "every authored `match` arm needs a layer here"
        );
    }
}

#[test]
fn landcover_kinds_are_lighter_than_the_earth_they_tint() {
    // A tint sits *on* the land, so in light mode it must not be darker than the land
    // itself or it reads as a separate landmass.
    let earth = find("earth");
    for l in layers().iter().filter(|l| l.source_layer == "landcover") {
        assert!(
            luminance(l.light) > luminance(earth.light) - 0.06,
            "{} is darker than earth, so it reads as land rather than a tint",
            l.id,
        );
    }
}

#[test]
fn the_unfiltered_landcover_layer_is_drawn_first_so_specific_kinds_win() {
    // The authored style's `match` has a fallback arm; here that is a separate unfiltered
    // layer, which only behaves like a fallback if it draws under nothing else — i.e. it
    // must come first, before the kind-specific ones paint over it.
    let indices: Vec<(usize, &Layer)> = layers()
        .iter()
        .enumerate()
        .filter(|(_, l)| l.source_layer == "landcover")
        .collect();
    let fallback = indices
        .iter()
        .find(|(_, l)| l.kinds.is_empty())
        .expect("a fallback arm");
    for (index, l) in &indices {
        if l.kinds.is_empty() {
            continue;
        }
        assert!(
            *index > fallback.0,
            "{} must follow the unfiltered fallback so its colour is not overpainted",
            l.id,
        );
    }
}

#[test]
fn draw_order_is_the_order_in_the_file() {
    // Order is draw order, and the renderer draws layer-major so it holds across tiles. A
    // casing drawn after its fill would outline the road on top of itself.
    let index = |id: &str| layers().iter().position(|l| l.id == id).expect(id);
    for kind in ["minor", "major", "highway"] {
        assert!(
            index(&format!("roads-{kind}-casing")) < index(&format!("roads-{kind}")),
            "roads-{kind}-casing must precede roads-{kind}",
        );
    }
    assert!(index("earth") < index("water"), "water draws over earth");
    assert!(
        index("water") < index("roads-major"),
        "roads draw over water"
    );
    // The authored style puts `landuse_park` through `landuse_runway` *before* `water` and
    // only `landuse_pedestrian` and `landuse_pier` after it. Flattening landuse to one side
    // of water puts parks on top of rivers or rivers on top of parks.
    assert!(index("landuse_park:national_park") < index("water"));
    assert!(index("landuse_runway") < index("water"));
    assert!(index("water") < index("landuse_pedestrian"));
    assert!(index("landuse_pier") < index("buildings"));
}

include!("tests_part1.rs");
