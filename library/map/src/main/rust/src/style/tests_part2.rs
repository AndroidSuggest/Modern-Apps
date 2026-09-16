use super::*;
use tilecodec::mamaps::dict;

fn find(id: &str) -> &'static Layer {
    layers()
        .iter()
        .find(|l| l.id == id)
        .unwrap_or_else(|| panic!("{id}"))
}

/// The lane arithmetic, at density 1 so a Dp is a pixel: `lanes` lanes of the constant
/// 6 Dp spacing, centred on the track, so an even count straddles it and an odd one sits
/// one line on it. The count comes from the style's zoom step, not from the feature.
#[test]
fn a_corridor_fans_out_centred_on_the_track_it_shares() {
    let rail = find("transit-rail");
    let of =
        |zoom: f64, ordinal: u8, count: u8| rail.lane_offset_px(zoom, 1.0, ordinal, count, 255);
    assert_eq!(
        [of(9.0, 0, 2), of(9.0, 1, 2)],
        [-3.0, 3.0],
        "two lanes straddle it"
    );
    assert_eq!(
        [of(11.0, 0, 3), of(11.0, 1, 3), of(11.0, 2, 3)],
        [-6.0, 0.0, 6.0]
    );
    assert_eq!(
        [
            of(13.0, 0, 4),
            of(13.0, 1, 4),
            of(13.0, 2, 4),
            of(13.0, 3, 4)
        ],
        [-9.0, -3.0, 3.0, 9.0],
    );
    // Past the zoom's lane count the ordinals squash, so a busy corridor shares lanes
    // rather than fanning off the street — from the middle, so the outermost line on each
    // side keeps a lane of its own.
    assert_eq!(
        of(9.0, 1, 4),
        of(9.0, 0, 4),
        "ordinals 0 and 1 of 4 share lane 0 of 2"
    );
    assert_eq!(of(9.0, 3, 4), of(9.0, 2, 4), "and 2 and 3 share lane 1");
    assert!(
        of(9.0, 2, 4) > of(9.0, 1, 4),
        "without the two halves swapping"
    );
    // A corridor of one has nothing to fan, and neither does a single-lane zoom.
    assert_eq!(of(13.0, 0, 1), 0.0);
    assert_eq!(of(8.0, 1, 4), 0.0);
}

/// Two groups of three merging, at a zoom that can draw four lanes: the outermost line on
/// each side keeps a lane to itself and the four in the middle pair up, rather than the
/// crowding landing on the two edges where it is most visible.
#[test]
fn a_corridor_past_its_lane_budget_doubles_up_in_the_middle() {
    let rail = find("transit-rail");
    let of = |ordinal: u8| rail.lane_offset_px(13.0, 1.0, ordinal, 6, 255);
    assert_eq!(
        [of(0), of(1), of(2), of(3), of(4), of(5)],
        [-9.0, -3.0, -3.0, 3.0, 3.0, 9.0],
        "one, two, two, one across the four lanes z13 draws",
    );
}

/// The property the whole lane-order pass rests on: within a corridor the offset never
/// decreases as the ordinal rises, at any zoom and any colour count. Two lines can come
/// to share a lane, but they can never cross.
#[test]
fn squashing_a_corridor_onto_fewer_lanes_never_reorders_it() {
    let rail = find("transit-rail");
    for count in 1u8..=12 {
        for step in 0..=40 {
            let zoom = 4.0 + f64::from(step) * 0.5;
            let offsets: Vec<f32> = (0..count)
                .map(|ordinal| rail.lane_offset_px(zoom, 1.0, ordinal, count, 255))
                .collect();
            assert!(
                offsets.windows(2).all(|w| w[0] <= w[1]),
                "count {count} at zoom {zoom}: {offsets:?}",
            );
            // And the fan always reaches its full width: the first and last ordinals take
            // the outermost lanes, which is what makes the squashing land in the middle.
            let (first, last) = (offsets[0], offsets[offsets.len() - 1]);
            assert_eq!(first, -last, "count {count} at zoom {zoom}: {offsets:?}");
        }
    }
}

/// The taper is a fraction of whatever the offset turns out to be, which is why it has to
/// travel separately from the lane index.
#[test]
fn a_taper_scales_the_offset_it_eases_into() {
    let rail = find("transit-rail");
    assert_eq!(rail.lane_offset_px(9.0, 1.0, 1, 2, 255), 3.0);
    assert_eq!(
        rail.lane_offset_px(9.0, 1.0, 1, 2, 128),
        3.0 * (128.0 / 255.0)
    );
    assert_eq!(rail.lane_offset_px(9.0, 1.0, 1, 2, 0), 0.0);
}

#[test]
fn the_kind_filter_is_a_whitelist_and_empty_means_everything() {
    let highway = find("roads-highway");
    assert!(highway.matches(Some("highway")));
    assert!(!highway.matches(Some("major_road")));
    assert!(
        !highway.matches(None),
        "a feature with no kind is not a highway"
    );

    let earth = find("earth");
    assert!(
        earth.matches(None),
        "an unfiltered layer draws a feature with no kind"
    );
    assert!(
        earth.matches(Some("island")),
        "and every kind the schema can emit"
    );
    assert!(earth.matches(Some("ocean")));
    // A name the schema has no id for cannot be on a feature at all, so nothing draws it.
    // Interning the whitelist is what turns that from a silent miss into an impossibility.
    assert!(!earth.matches(Some("not_a_kind")));
}

/// The interned whitelist and the authored names must agree, or the render path filters on
/// something other than what the style says.
#[test]
fn the_interned_whitelist_is_the_authored_one() {
    for l in layers() {
        assert_eq!(
            l.kind_ids.len(),
            l.kinds.len(),
            "`{}` lost a kind when its whitelist was interned",
            l.id,
        );
        for name in &l.kinds {
            assert!(l.matches(Some(name)), "`{}` should draw `{name}`", l.id);
        }
        assert!(
            l.kind_ids.windows(2).all(|p| p[0] < p[1]),
            "`{}` is not sorted",
            l.id
        );
    }
    // And every layer reads a source the archive actually carries.
    let roads = find("roads-highway");
    assert_eq!(roads.source_layer_id, dict::LAYER_ROADS);
    assert_eq!(find("earth").source_layer_id, dict::LAYER_EARTH);
}

#[test]
fn zoom_ranges_gate_the_expensive_layers() {
    // Buildings are the densest layer in the schema, so they stay off until they are
    // worth drawing.
    assert!(!find("buildings").draws_at(13));
    assert!(find("buildings").draws_at(14));
    assert!(find("earth").draws_at(0));
    assert!(find("earth").draws_at(22));
}

#[test]
fn the_degenerate_dash_is_present_so_the_shader_path_is_exercised() {
    // `boundaries_country`'s authored `[2, 0]`: a zero gap has to render solid. See
    // line.frag.
    assert_eq!(find("boundaries").dash, (2.0, 0.0));
}

#[test]
fn every_layer_id_is_unique() {
    let mut ids: Vec<&str> = layers().iter().map(|l| l.id.as_str()).collect();
    ids.sort_unstable();
    let count = ids.len();
    ids.dedup();
    assert_eq!(count, ids.len(), "layer ids are used as identities");
}
