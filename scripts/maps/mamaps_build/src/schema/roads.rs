//! `roads`: six kinds, thirty details, and three booleans.
//!
//! **This is where roads finally become correct.** The MVT overlay this replaces carried a road's
//! class and nothing else, so a bridge drew as road-coloured tarmac laid over a river and a tunnel
//! drew as though it were on the surface. The three flags are the whole fix, and they cost three
//! bits.
//!
//! # kind versus kind_detail
//!
//! `kind` is the six-way class the style paints — one colour and one width ramp per class. It is
//! deliberately coarse: a map that gave `tertiary` its own colour would be unreadable.
//!
//! `kind_detail` is the OSM `highway` value itself, unreduced. Nothing in the style filters on more
//! than four of them today, and carrying the rest is what lets a future style draw a track
//! differently from a motorway link without a rebuild — and what lets the differential harness
//! compare this generator against upstream value for value rather than class for class.
//!
//! # Minimum zoom is the whole game
//!
//! A motorway belongs on a continent and a service road does not. Six thousand miles of residential
//! street at z8 is not detail, it is a grey wash — and it is also most of the bytes. The `min_zoom`
//! column below is the single most consequential table in this crate.

use tilecodec::mamaps::body::{
    Carriageway, FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_ONEWAY, FLAG_IS_TUNNEL,
};
use tilecodec::mamaps::dict::LAYER_ROADS;

use super::{detail, kind, Class, TagSource};

pub const FILTERS: &[&str] = &["highway", "railway", "aeroway", "route", "man_made"];

/// Every `kind` this module can emit.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] =
    &["highway", "major_road", "minor_road", "path", "other", "rail", "ferry", "aerialway"];

/// Every `kind_detail` this module can emit.
#[cfg_attr(not(test), allow(dead_code))]
pub const DETAILS: &[&str] = &[
    "motorway",
    "motorway_link",
    "trunk",
    "trunk_link",
    "primary",
    "primary_link",
    "secondary",
    "secondary_link",
    "tertiary",
    "tertiary_link",
    "residential",
    "unclassified",
    "living_street",
    "alley",
    "service",
    "track",
    "path",
    "footway",
    "sidewalk",
    "crossing",
    "steps",
    "corridor",
    "cycleway",
    "pedestrian",
    "rail",
    "subway",
    "tram",
    "light_rail",
    "turntable",
    "runway",
    "taxiway",
    "pier",
];

/// `(OSM highway value, kind, kind_detail, min_zoom)`, ordered by importance.
///
/// The `min_zoom` column is the accumulated judgement: a motorway carries a continent, a trunk road
/// a country, a residential street a neighbourhood. Wrong by two levels either way and a mid-zoom
/// tile is either empty or a grey wash.
const HIGHWAYS: &[(&str, &str, &str, u8)] = &[
    ("motorway", "highway", "motorway", 3),
    ("motorway_link", "highway", "motorway_link", 11),
    ("trunk", "major_road", "trunk", 5),
    ("trunk_link", "major_road", "trunk_link", 11),
    ("primary", "major_road", "primary", 7),
    ("primary_link", "major_road", "primary_link", 12),
    ("secondary", "major_road", "secondary", 9),
    ("secondary_link", "major_road", "secondary_link", 12),
    ("tertiary", "major_road", "tertiary", 10),
    ("tertiary_link", "major_road", "tertiary_link", 13),
    ("residential", "minor_road", "residential", 12),
    ("unclassified", "minor_road", "unclassified", 12),
    ("living_street", "minor_road", "living_street", 13),
    // A service road is every driveway and car-park aisle in the world. There are more of them
    // than of every other class combined, and they are street-level detail at best.
    ("service", "minor_road", "service", 14),
    ("road", "other", "unclassified", 13),
    ("track", "path", "track", 14),
    ("path", "path", "path", 14),
    ("footway", "path", "footway", 14),
    ("cycleway", "path", "cycleway", 14),
    ("bridleway", "path", "path", 14),
    ("steps", "path", "steps", 14),
    ("corridor", "path", "corridor", 15),
    ("pedestrian", "path", "pedestrian", 13),
];

/// `(OSM railway value, kind_detail, min_zoom)`.
///
/// One `kind` between them: the style draws every rail the same, and a map that coloured a tram
/// differently from a subway would be a transit diagram rather than a basemap.
const RAILWAYS: &[(&str, &str, u8)] = &[
    ("rail", "rail", 8),
    ("subway", "subway", 12),
    ("light_rail", "light_rail", 12),
    ("tram", "tram", 13),
    ("narrow_gauge", "rail", 11),
    ("monorail", "light_rail", 13),
    ("funicular", "light_rail", 13),
    ("turntable", "turntable", 15),
];

pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    if let Some(highway) = tags.get("highway") {
        let (_, kind_name, detail_name, min_zoom) =
            HIGHWAYS.iter().find(|(value, _, _, _)| *value == highway)?;
        return Some(Class {
            layer: LAYER_ROADS,
            kind: kind(kind_name),
            kind_detail: detail(detail_name),
            flags: flags(tags, highway),
            area: false,
            min_zoom: *min_zoom,
            min_area_px: 0.0,
        });
    }
    if let Some(railway) = tags.get("railway") {
        let (_, detail_name, min_zoom) = RAILWAYS.iter().find(|(value, _, _)| *value == railway)?;
        return Some(Class {
            layer: LAYER_ROADS,
            kind: kind("rail"),
            kind_detail: detail(detail_name),
            flags: flags(tags, railway),
            area: false,
            min_zoom: *min_zoom,
            min_area_px: 0.0,
        });
    }
    // A runway is a line in this layer and a polygon in `landuse`. Both are drawn, because an
    // airport reads as a shape with a stripe down it.
    if let Some(aeroway) = tags.get("aeroway") {
        let detail_name = match aeroway {
            "runway" => "runway",
            "taxiway" => "taxiway",
            _ => return None,
        };
        return Some(Class {
            layer: LAYER_ROADS,
            kind: kind("other"),
            kind_detail: detail(detail_name),
            flags: flags(tags, aeroway),
            area: false,
            min_zoom: if aeroway == "runway" { 9 } else { 13 },
            min_area_px: 0.0,
        });
    }
    // A pier is walkable, so it belongs with the paths rather than with the buildings.
    if tags.get("man_made") == Some("pier") {
        return Some(Class {
            layer: LAYER_ROADS,
            kind: kind("path"),
            kind_detail: detail("pier"),
            flags: flags(tags, "pier"),
            area: false,
            min_zoom: 13,
            min_area_px: 0.0,
        });
    }
    // A ferry is a route, and drawing it is what stops a coastal map looking disconnected.
    if tags.get("route") == Some("ferry") {
        return Some(Class::line(LAYER_ROADS, kind("ferry"), 9));
    }
    None
}

/// The four booleans, and the reason this layer was worth redoing.
///
/// `is_link` is derived from the class name rather than read from a tag, because OSM spells a slip
/// road as `highway=motorway_link` and there is no `link=yes`. That matches what upstream emits and
/// what the style filters with `!has is_link`.
///
/// `is_oneway` is a flag rather than side-table data because it is what decides whether a
/// carriageway has a centre line at all, and because a flag is part of `coalesce`'s merge key —
/// so a one-way and a two-way of the same class cannot collapse into one feature wearing
/// whichever direction came first.
fn flags(tags: &(impl TagSource + ?Sized), value: &str) -> u8 {
    let mut flags = 0u8;
    // `tunnel=building_passage` is a tunnel; only `no` is not. Same for a bridge tagged `viaduct`
    // or `boardwalk`.
    if tags.truthy("tunnel") || tags.get("covered") == Some("yes") {
        flags |= FLAG_IS_TUNNEL;
    }
    if tags.truthy("bridge") {
        flags |= FLAG_IS_BRIDGE;
    }
    if value.ends_with("_link") {
        flags |= FLAG_IS_LINK;
    }
    // `oneway=yes` and only that, which is the test [`osm_ingest::roads::is_oneway`] applies, so a
    // road cannot be one-way to the router and two-way to the carriageway drawn under it.
    // `oneway=-1` is a direction rather than a flag and neither models it.
    if tags.get("oneway") == Some("yes") {
        flags |= FLAG_IS_ONEWAY;
    }
    flags
}

/// The carriageway lane count baked into a `roads` feature, or zero when the way carries no
/// `lanes` tag.
///
/// The OSM `lanes` total (both directions), parsed by the same [`osm_ingest::tags::parse_int_tag`]
/// the routing graph and the `roads.pmtiles` layer use, so a road cannot describe its lane count
/// one way to the router and another to the basemap. Capped at [`osm_ingest::tags::MAX_LANES`]
/// (64), which fits a byte — the renderer stores it in the feature record's last byte and expands
/// it into that many parallel lanes with dividers at high zoom. A mistagged `lanes=999999999`
/// therefore becomes the cap rather than an absurd fan.
///
/// Only the total is baked, not the per-lane `turn:lanes` masks: those are variable length and
/// belong in a side table (the turn-arrow pass), while the count is one byte the parallel-lane
/// geometry needs first.
pub fn lane_count(tags: &(impl TagSource + ?Sized)) -> u8 {
    osm_ingest::tags::parse_int_tag(tags.get("lanes")).min(osm_ingest::tags::MAX_LANES) as u8
}

/// The per-lane turn-indication masks for a road, `(forward, backward)`, each a left-to-right
/// list of `LANE_*` bit sets from OSM `turn:lanes[:forward|:backward]`.
///
/// A verbatim reuse of the routing graph's own derivation
/// ([`osm_ingest::roads::lane_masks`]) via a [`RoadTags`](osm_ingest::roads::RoadTags) built from
/// this tag source, so a road cannot describe its lanes one way to the router and another to the
/// arrows drawn over it. Empty vectors when the way has no `turn:lanes`, which is almost every
/// road. Forward lanes are traversed toward the way's end (its junction), backward toward its
/// start — which is where the renderer places each direction's arrows.
pub fn turn_masks(tags: &(impl TagSource + ?Sized)) -> (Vec<u16>, Vec<u16>) {
    let rt = osm_ingest::roads::RoadTags {
        highway: tags.get("highway"),
        lanes: tags.get("lanes"),
        lanes_forward: tags.get("lanes:forward"),
        lanes_backward: tags.get("lanes:backward"),
        turn_lanes: tags.get("turn:lanes"),
        turn_lanes_forward: tags.get("turn:lanes:forward"),
        turn_lanes_backward: tags.get("turn:lanes:backward"),
        oneway: tags.get("oneway"),
        ..Default::default()
    };
    osm_ingest::roads::lane_masks(&rt)
}

/// How a road's lanes divide between the two directions, and which of the dividers between them
/// may not be crossed.
///
/// [`lane_count`] is the total; this is what the surface renderer needs on top of it to put the
/// centre line anywhere but the middle. `forward` runs toward the way's last point and `backward`
/// toward its first, the same convention [`turn_masks`] uses, and `oneway` is decided by
/// [`osm_ingest::roads::is_oneway`] so the split agrees with how the router traverses the road.
///
/// **An absent split stays absent.** A two-way road tagged only `lanes=4` comes back all-zero
/// rather than as 2/2, because 2/2 is a guess dressed as a survey — the renderer already draws an
/// unknown split down the middle, and on an odd total the guess would be wrong rather than
/// unhelpful. All-zero is also what lets a tile of untagged residential streets carry no
/// carriageway table at all.
pub fn carriageway(tags: &(impl TagSource + ?Sized)) -> Carriageway {
    let total = lane_count(tags) as u32;
    let forward = osm_ingest::tags::parse_int_tag(tags.get("lanes:forward"));
    let backward = osm_ingest::tags::parse_int_tag(tags.get("lanes:backward"));
    let (forward, backward) = if tags.get("oneway") == Some("yes") {
        // Every lane runs one way, so the total is the forward count even when the way also
        // carries a `lanes:forward` that agrees with it.
        (if forward > 0 { forward } else { total }, 0)
    } else {
        // One side tagged implies the other, and the pair is the more common tagging than either
        // alone. Saturating rather than wrapping: `lanes=2` with `lanes:backward=3` is a mistagged
        // road, and the answer to it is "nothing known about the other side".
        (
            if forward > 0 { forward } else { total.saturating_sub(backward) },
            if backward > 0 { backward } else { total.saturating_sub(forward) },
        )
    };
    // A split that does not add up to the total is not a split: the renderer reads the two
    // together, so 3 forward and 4 backward of five lanes would be a shape it cannot draw. The
    // dividers survive it — they are a property of the total, not of the division.
    let (forward, backward) = if forward + backward == total { (forward, backward) } else { (0, 0) };
    Carriageway {
        forward: forward.min(osm_ingest::tags::MAX_LANES) as u8,
        backward: backward.min(osm_ingest::tags::MAX_LANES) as u8,
        solid_dividers: solid_dividers(tags.get("change:lanes"), total),
    }
}

/// The interior dividers a lane change is prohibited across, a bit each from the leftmost.
///
/// Only the unsuffixed `change:lanes` is read. The `:forward`/`:backward` pair describes each
/// direction's lanes in *that direction's* left-to-right order, so stitching the two into one
/// carriageway-wide ordering needs the driving side — which is a property of the tile rather than
/// of the way and is not known here. The plain tag is already ordered the way this field is.
///
/// A divider is solid when either lane it separates forbids crossing it: lane `k` tagged
/// `not_right` or `no`, or lane `k + 1` tagged `not_left` or `no`.
fn solid_dividers(spec: Option<&str>, lanes: u32) -> u32 {
    let Some(spec) = spec.filter(|s| !s.is_empty()) else {
        return 0;
    };
    let values: Vec<&str> = spec.split('|').map(str::trim).collect();
    // A list that does not describe this road's lanes describes some other road's, and half of it
    // applied to this one would draw solid lines down the wrong gaps.
    if values.len() as u32 != lanes {
        return 0;
    }
    let mut bits = 0u32;
    // A road with more than 32 interior dividers has none recorded past the 32nd, which no real
    // road reaches.
    for k in 0..values.len().saturating_sub(1).min(32) {
        let blocked = matches!(values[k], "no" | "not_right")
            || matches!(values[k + 1], "no" | "not_left");
        if blocked {
            bits |= 1 << k;
        }
    }
    bits
}
