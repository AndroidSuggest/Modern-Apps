// --- the road flag/detail filters (issue #3) -------------------------------

fn feature(kind: &str, flags: u8, detail: &str) -> tilecodec::mamaps::body::Feature {
    use tilecodec::mamaps::body::{GEOM_LINE, NAME_NONE};
    tilecodec::mamaps::body::Feature {
        kind: kind_id(kind).expect(kind),
        kind_detail: detail_id(detail).expect(detail),
        geom_type: GEOM_LINE,
        flags,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 0,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    }
}

/// Surface class layers draw plain surface roads only: no ramps, no bridges, no
/// tunnels, and (for minor) no service streets. Before these filters a motorway_link
/// drew as a full highway with casing at super-zoom — the thick cream band.
#[test]
fn surface_road_layers_draw_only_plain_surface_roads() {
    use tilecodec::mamaps::body::{FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_TUNNEL};
    let highway = find("roads-highway");
    assert!(highway.matches_feature(&feature("highway", 0, "motorway")));
    assert!(!highway.matches_feature(&feature("highway", FLAG_IS_LINK, "motorway_link")));
    assert!(!highway.matches_feature(&feature("highway", FLAG_IS_BRIDGE, "motorway")));
    assert!(!highway.matches_feature(&feature("highway", FLAG_IS_TUNNEL, "motorway")));
    assert!(!highway.matches_feature(&feature("major_road", 0, "primary")));

    let major = find("roads-major");
    assert!(major.matches_feature(&feature("major_road", 0, "primary")));
    assert!(!major.matches_feature(&feature("major_road", FLAG_IS_LINK, "primary_link")));
    assert!(!major.matches_feature(&feature("major_road", FLAG_IS_BRIDGE, "primary")));

    let minor = find("roads-minor");
    assert!(minor.matches_feature(&feature("minor_road", 0, "residential")));
    assert!(!minor.matches_feature(&feature("minor_road", 0, "service")));
    assert!(!minor.matches_feature(&feature("minor_road", FLAG_IS_TUNNEL, "residential")));

    // Casings agree with their fills: the same feature is outlined and filled, or
    // neither, so a fill can never sit uncased nor a casing unfilled.
    for (casing, fill) in
        [("roads-highway-casing", "roads-highway"), ("roads-major-casing", "roads-major")]
    {
        let (c, f) = (find(casing), find(fill));
        assert_eq!(c.forbid_flags, f.forbid_flags, "{casing} filters what {fill} fills");
        assert_eq!(c.require_flags, f.require_flags);
    }
    assert_eq!(find("roads-minor-casing").forbid_details, find("roads-minor").forbid_details);
}

/// Task-8 regression: bridge spans must draw. Every surface class layer
/// forbids `bridge`, so without the roads-bridges-* pass a motorway
/// bridge (Bay Bridge, Golden Gate) matched NO layer and vanished. Each
/// bridge layer requires `bridge` and mirrors its surface class's paint.
#[test]
fn bridge_spans_draw_in_their_own_pass() {
    use tilecodec::mamaps::body::{FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_TUNNEL};
    let highway = find("roads-bridges-highway");
    assert!(highway.matches_feature(&feature("highway", FLAG_IS_BRIDGE, "motorway")));
    assert!(!highway.matches_feature(&feature("highway", 0, "motorway")));
    assert!(!highway.matches_feature(&feature(
        "highway",
        FLAG_IS_BRIDGE | FLAG_IS_LINK,
        "motorway_link"
    )));
    assert!(!highway.matches_feature(&feature("highway", FLAG_IS_TUNNEL, "motorway")));
    let major = find("roads-bridges-major");
    assert!(major.matches_feature(&feature("major_road", FLAG_IS_BRIDGE, "primary")));
    assert!(!major.matches_feature(&feature("major_road", 0, "primary")));
    let minor = find("roads-bridges-minor");
    assert!(minor.matches_feature(&feature("minor_road", FLAG_IS_BRIDGE, "residential")));
    assert!(!minor.matches_feature(&feature("minor_road", 0, "residential")));
    let link = find("roads-bridges-link");
    assert!(link.matches_feature(&feature(
        "highway",
        FLAG_IS_BRIDGE | FLAG_IS_LINK,
        "motorway_link"
    )));
    assert!(!link.matches_feature(&feature("highway", FLAG_IS_BRIDGE, "motorway")));
    let other = find("roads-bridges-other");
    assert!(other.matches_feature(&feature("other", FLAG_IS_BRIDGE, "unclassified")));
    assert!(!other.matches_feature(&feature("other", 0, "unclassified")));
    // Casings agree with their fills, like the surface pairs.
    for (casing, fill) in [
        ("roads-bridges-highway-casing", "roads-bridges-highway"),
        ("roads-bridges-major-casing", "roads-bridges-major"),
        ("roads-bridges-minor-casing", "roads-bridges-minor"),
        ("roads-bridges-link-casing", "roads-bridges-link"),
    ] {
        let (c, f) = (find(casing), find(fill));
        assert_eq!(c.require_flags, f.require_flags, "{casing} filters what {fill} fills");
        assert_eq!(c.forbid_flags, f.forbid_flags);
    }
}

/// The link layers catch exactly the `is_link` features the surface layers refuse.
#[test]
fn link_layers_draw_only_slip_roads() {
    use tilecodec::mamaps::body::{FLAG_IS_BRIDGE, FLAG_IS_LINK};
    let link = find("roads-link");
    assert!(link.matches_feature(&feature("highway", FLAG_IS_LINK, "motorway_link")));
    assert!(link.matches_feature(&feature("major_road", FLAG_IS_LINK, "primary_link")));
    assert!(!link.matches_feature(&feature("highway", 0, "motorway")));
    assert!(!link.matches_feature(&feature("minor_road", 0, "residential")));
    // A bridge that is also a link still draws as a link: the authored link layers
    // filter on `is_link` alone, and bridges keep their own roads-bridges-*
    // layers (task 8) alongside the surface + link passes.
    assert!(link.matches_feature(&feature(
        "highway",
        FLAG_IS_LINK | FLAG_IS_BRIDGE,
        "motorway_link"
    )));
    let casing = find("roads-link-casing");
    assert!(casing.matches_feature(&feature("highway", FLAG_IS_LINK, "motorway_link")));
    assert!(!casing.matches_feature(&feature("highway", 0, "motorway")));
}

/// Service streets have their own layer at their own width.
#[test]
fn service_streets_draw_only_in_the_service_layer() {
    let service = find("roads-minor-service");
    assert!(service.matches_feature(&feature("minor_road", 0, "service")));
    assert!(!service.matches_feature(&feature("minor_road", 0, "residential")));
    assert!(!find("roads-minor").matches_feature(&feature("minor_road", 0, "service")));
}

// --- light and dark ----------------------------------------------------

#[test]
fn every_layer_defines_both_variants_and_they_differ() {
    // A layer that forgot its dark colour would render its light one on a dark
    // background, which is the single most visible way to get this wrong.
    // Symbol layers are exempt (see the dark-palette test): one mid-grey column
    // until the dark label palette lands in M5.
    for l in layers() {
        if l.kind == LayerKind::Symbol {
            continue;
        }
        assert_ne!(l.light, l.dark, "{} has the same colour in both variants", l.id);
        // Translucency is legitimate — buildings draw at 0.5 — but that lives in the
        // opacity ramp, so a fully transparent colour is a layer that silently does
        // nothing.
        assert!(l.light >> 24 > 0, "{} light colour is fully transparent", l.id);
        assert!(l.dark >> 24 > 0, "{} dark colour is fully transparent", l.id);
    }
}

#[test]
fn land_is_clearly_distinguishable_from_ocean() {
    // The most basic thing a map must do, and it was broken: dark `earth` was given
    // BasemapPalette's Background, which is a hair from its Water, so the whole map was a
    // flat navy field with no coastline. Asserted in every variant *and* muted, because
    // muting pulls colours toward the background and could collapse them again.
    let (earth, water) = (find("earth"), find("water"));
    for dark in [false, true] {
        for muted in [false, true] {
            let palette = Palette::new(dark, muted);
            let separation = distance(earth.color(palette), water.color(palette));
            assert!(
                separation > 0.09,
                "land and ocean are indistinguishable (dark={dark}, muted={muted}): \
                 distance {separation:.3}",
            );
        }
    }
}

#[test]
fn the_backdrop_is_the_ocean_rather_than_the_land() {
    // An unloaded map should read as sea with land appearing on top, so a gap in coverage
    // never looks like a continent.
    let (earth, water) = (find("earth"), find("water"));
    for variant in [Variant::Light, Variant::Dark] {
        let backdrop = background(variant);
        let palette = Palette { variant, muted: false };
        assert!(
            distance(backdrop, water.color(palette)) < 0.02,
            "the backdrop should be the water colour in {variant:?}",
        );
        assert!(
            distance(backdrop, earth.color(palette)) > 0.09,
            "the backdrop must not look like land in {variant:?}",
        );
    }
}

#[test]
fn the_dark_palette_is_actually_dark_and_the_light_one_light() {
    assert!(luminance(background(Variant::Dark)) < 0.2, "the dark backdrop must be dark");
    assert!(luminance(background(Variant::Light)) > 0.7, "the light backdrop must be light");
    for l in layers() {
        // Symbol layers are exempt: the authored style is light-only and label
        // colours are mid-grey by design (country #a3a3a3, region #b3b3b3), so
        // they read as labels on a dark basemap too. M1 keeps one column; the
        // dark label palette is M5 with the dark restyle.
        if l.kind == LayerKind::Symbol {
            continue;
        }
        assert!(
            luminance(l.dark) < 0.45,
            "{} is too bright for a dark basemap: {:.2}",
            l.id,
            luminance(l.dark),
        );
    }
}

/// Euclidean RGB distance, 0..~1.7.
///
/// Luminance alone is the wrong measure for legibility: the light highway colour is a
/// pale yellow whose luminance nearly matches the beige land behind it, and it is
/// perfectly legible — which is why every real basemap draws highways that way. Hue
/// separation has to count.
fn distance(a: u32, b: u32) -> f32 {
    let channel = |v: u32, shift: u32| ((v >> shift) & 0xFF) as f32 / 255.0;
    let dr = channel(a, 16) - channel(b, 16);
    let dg = channel(a, 8) - channel(b, 8);
    let db = channel(a, 0) - channel(b, 0);
    (dr * dr + dg * dg + db * db).sqrt()
}

#[test]
fn roads_stay_legible_against_the_land_behind_them() {
    // The reason BasemapPalette exists, and the reason the line colours are the app's own
    // rather than the authored white-on-grey: a road has to read against the terrain, and
    // in dark mode both are dark. Assert real separation rather than merely different hex.
    for dark in [false, true] {
        let palette = Palette::new(dark, false);
        let land = background(palette.variant);
        for id in ["roads-major", "roads-minor", "roads-highway"] {
            let road = find(id).color(palette);
            let separation = distance(road, land);
            assert!(
                separation > 0.05,
                "{id} is invisible against the land, dark={dark}: distance {separation:.3}",
            );
        }
    }
}

#[test]
fn muting_moves_every_colour_toward_the_background() {
    // What weather needs, and what Positron gave it: the basemap recedes so a colour
    // ramp on top of it stays readable.
    for dark in [false, true] {
        let plain = Palette::new(dark, false);
        let muted = Palette::new(dark, true);
        let land = background(plain.variant);
        for l in layers() {
            let before = distance(l.color(plain), land);
            let after = distance(l.color(muted), land);
            assert!(
                after <= before + 1e-6,
                "{} got further from the land when muted, dark={dark}",
                l.id,
            );
        }
        // And it actually does something measurable to a high-contrast layer.
        let road = find("roads-major");
        assert!(
            distance(road.color(muted), land) < distance(road.color(plain), land) * 0.75,
            "muting barely changed roads-major, dark={dark}",
        );
    }
}

#[test]
fn muting_preserves_opacity() {
    // Muting shifts hue toward the background; it must not change alpha. Forcing a
    // translucent layer opaque would make `weather`'s muted basemap hide what it is
    // drawn over, and forcing an opaque one translucent would let the app's own surface
    // bleed through the map.
    for dark in [false, true] {
        for l in layers() {
            let plain = l.color(Palette::new(dark, false)) >> 24;
            let muted = l.color(Palette::new(dark, true)) >> 24;
            assert_eq!(plain, muted, "{} changed alpha when muted", l.id);
        }
    }
}

#[test]
fn buildings_are_translucent_as_the_authored_style_draws_them() {
    // `fill-opacity: 0.5` in the authored style. Pinned because it is the one layer whose
    // translucency is load-bearing, and because it means tile overspill drawn twice would
    // darken visibly — unlike an opaque layer, where double-drawing is harmless.
    assert_eq!(find("buildings").opacity_at(16.0), 0.5);
}

#[test]
fn a_dark_casing_is_darker_than_the_road_it_outlines() {
    // In dark mode the casing is what separates two adjacent roads, since the land
    // behind them is dark too. A casing lighter than its road would read as a second
    // road.
    let dark = Palette::new(true, false);
    for kind in ["minor", "major", "highway"] {
        let casing = luminance(find(&format!("roads-{kind}-casing")).color(dark));
        let road = luminance(find(&format!("roads-{kind}")).color(dark));
        assert!(casing < road, "the dark roads-{kind} casing must be darker than its road");
    }
}

#[test]
fn a_variant_switch_changes_hue_and_nothing_else() {
    // Colour is a push constant and the layer set is one table read by both variants, so a
    // switch re-tessellates and re-uploads nothing. What could still go wrong is a variant
    // changing *alpha*, which changes blending rather than geometry — and would mean the
    // dark column had smuggled an opacity decision out of the ramp.
    for l in layers() {
        for muted in [false, true] {
            assert_eq!(
                l.color(Palette::new(false, muted)) >> 24,
                l.color(Palette::new(true, muted)) >> 24,
                "{} changes alpha with the variant",
                l.id,
            );
        }
    }
}
