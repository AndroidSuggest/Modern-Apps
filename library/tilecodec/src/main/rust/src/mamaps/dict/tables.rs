//! The archive-wide tables: layer names, `kind`s and `kind_detail`s, interned to integers.
//!
//! Pure moves out of the former single-file dict module; nothing here changed
//! except re-rooting `super::body` to `crate::mamaps::body`,
//! which the extra module level requires.

/// `kind` and `kind_detail` id 0: the feature carries none.
///
/// Not a sentinel a reader has to guess at — the unfiltered layers (`earth`'s land, the
/// `landcover` fallback) genuinely have no `kind`, and the style draws them by matching
/// everything.
pub const NONE: u16 = 0;

/// The layers this format carries, in draw order.
///
/// A layer's id **is** its index here. Twelve: the seven the style draws, plus `places`
/// (labels), `poi` (icons), `transit` (reserved by v2; populated when transit lands),
/// `traffic` (v4: one line per drivable component segment, recoloured live from a pushed
/// id→speed table — geometry from the v6 routing graph, not the basemap) and `junction`
/// (v7: one line per lane connector through an intersection, also from the routing graph).
/// `u8` ids fit with room to spare.
pub const LAYERS: &[&str] = &[
    "earth",
    "water",
    "landcover",
    "landuse",
    "roads",
    "boundaries",
    "buildings",
    "places",
    "poi",
    "transit",
    "traffic",
    "junction",
];

pub const LAYER_EARTH: u8 = 0;
pub const LAYER_WATER: u8 = 1;
pub const LAYER_LANDCOVER: u8 = 2;
pub const LAYER_LANDUSE: u8 = 3;
pub const LAYER_ROADS: u8 = 4;
pub const LAYER_BOUNDARIES: u8 = 5;
pub const LAYER_BUILDINGS: u8 = 6;
pub const LAYER_PLACES: u8 = 7;
pub const LAYER_POI: u8 = 8;
pub const LAYER_TRANSIT: u8 = 9;
/// v4. One `GEOM_LINE` feature per drivable **component** segment of the v6 routing graph,
/// each carrying its packed `component_id` in the body's id side table so the renderer can
/// recolour it from a live id→speed push. Excluded from the basemap style: it draws only
/// when the traffic overlay is enabled.
pub const LAYER_TRAFFIC: u8 = 10;
/// v7. One `GEOM_LINE` feature per **lane connector** through an intersection: the sampled
/// centreline a single lane follows from an approach to an exit, built from the same v6
/// routing graph `traffic` reads. Excluded from the basemap style — it draws only where the
/// carriageway surface does, which is why its `min_zoom` is deep.
///
/// Added inside v7 rather than under a version bump of its own. Appending to [`LAYERS`]
/// changes the dictionary every archive carries and
/// [`Dictionary::check_matches_schema`](crate::mamaps::dict::Dictionary::check_matches_schema)
/// compares the whole table on open, so this is only safe because v7 was committed but never
/// built or shipped — there is no deployed reader to refuse. Any layer appended after a v7
/// archive exists must bump the format, the way v4 did for `traffic`.
pub const LAYER_JUNCTION: u8 = 11;

/// Every `kind` value the schema can emit, id 1 upward. Index 0 is [`NONE`].
///
/// Grouped by the layer that introduced each value and **append-only**: inserting in the middle
/// would renumber everything after it, which is the one thing this table exists to prevent. A
/// value shared by two layers appears once, because ids are archive-wide.
///
/// The vocabulary is **measured, not guessed**. Everything here appears in the published
/// `v4.pmtiles`, sampled by `tile_build`'s `mamaps_vocabulary` example, which flags any value the
/// table has no id for. Re-run it after an upstream republish.
pub const KINDS: &[&str] = &[
    // earth
    "island",
    // water
    "bay",
    "fjord",
    "lake",
    "ocean",
    "river",
    "sea",
    "strait",
    "stream",
    "water",
    // landcover
    "grassland",
    "barren",
    "urban_area",
    "farmland",
    "glacier",
    "scrub",
    // landuse. `grassland` and `scrub` are already above and are not repeated.
    "national_park",
    "park",
    "cemetery",
    "protected_area",
    "nature_reserve",
    "forest",
    "golf_course",
    "wood",
    "grass",
    "military",
    "naval_base",
    "airfield",
    "allotments",
    "village_green",
    "playground",
    "hospital",
    "industrial",
    "school",
    "university",
    "college",
    "beach",
    "zoo",
    "aerodrome",
    "runway",
    "taxiway",
    "pedestrian",
    "dam",
    "pier",
    // roads
    "highway",
    "major_road",
    "minor_road",
    "path",
    "other",
    "rail",
    // buildings
    "building",
    "building_part",
    // Appended after the first measurement against the published archive, which turned up 42 of
    // 54 features on the z0 tile carrying a value the table above had no id for. Append-only, so
    // these take fresh ids rather than disturbing any above.
    // earth
    "earth",
    "cliff",
    // water
    "canal",
    "dock",
    "fountain",
    "reef",
    "swimming_pool",
    // landuse
    "bare_rock",
    "commercial",
    "dog_park",
    "garden",
    "kindergarten",
    "meadow",
    "pitch",
    "platform",
    "railway",
    "recreation_ground",
    "residential",
    "sand",
    "wetland",
    // roads
    "aerialway",
    "ferry",
    // boundaries. The style filters these numerically through `kind_detail`, so no flat style layer
    // names them -- but they are what the data says, and the differential harness compares names.
    "country",
    "region",
    "county",
    "locality",
    "overlay_limit",
    "unrecognized_country",
    // places + poi (v2, append-only). Most of the POI set the reference style's `pois`
    // layer draws already exists above as landuse kinds (`beach`, `forest`, `park`, `zoo`,
    // `garden`, `aerodrome`, `university`, `school`, `building`) and is NOT repeated: ids are
    // archive-wide, so `park` means the same id on `landuse` and on `poi`. Only names the
    // original table lacks are appended here. Every name must equal the v4/light sprite name
    // where they overlap, because the renderer resolves the icon image from the kind. Values
    // the style does not name (e.g. a bare `amenity=yes`) never reach here: the tiler only
    // emits kinds the style draws.
    "neighbourhood",
    "macrohood",
    "marina",
    "peak",
    "bench",
    "station",
    "bus_stop",
    "ferry_terminal",
    "stadium",
    "library",
    "animal",
    "toilets",
    "drinking_water",
    "post_office",
    "townhall",
    "restaurant",
    "fast_food",
    "cafe",
    "bar",
    "supermarket",
    "convenience",
    "books",
    "beauty",
    "electronics",
    "clothes",
    "attraction",
    "museum",
    "theatre",
    "artwork",
    // v3, append-only. The `maps` app's category chips have always offered Gas, Hotels and ATMs;
    // the archive had no kind for any of them, so those three chips could only ever have filtered
    // a POI set that did not contain their subject. `bank` comes along because `atm` alone misses
    // the machines that are attached to a branch and tagged only as one.
    "fuel",
    "hotel",
    "atm",
    "bank",
    // v4. The *shape* of an administrative region, as opposed to `country`/`region`/`county`/
    // `locality`, which are its border drawn as a line.
    //
    // A separate kind rather than reusing those, because the two cannot share one: the style's
    // `boundaries` layer is `type: line`, and a line layer strokes a polygon's outline. Clipping a
    // polygon to a tile adds segments along the tile edge to close the ring, so carrying regions
    // under the existing kinds drew a grid of tile borders across the whole map. That was tried
    // and reverted. This kind is excluded from the `boundaries` layer's `kinds` list, so nothing
    // in the basemap draws it — it exists only for the region mask to read.
    "region_area",
];

/// Every `kind_detail` value, id 1 upward. Index 0 is [`NONE`].
///
/// A separate table from [`KINDS`], so `runway` as a `landuse` kind and `runway` as a road's
/// detail are different ids. They describe different things and collapsing them would make a
/// road's detail collide with a polygon's kind.
///
/// On `roads` this is the OSM highway class, which is what the upstream schema puts here — 30-odd
/// values, not the four the style happens to filter on. Carrying the rest is what lets the
/// generator be checked against upstream feature for feature, and what a future style would need
/// to draw a track differently from a motorway link.
///
/// `boundaries` does not appear here: its detail is a numeric admin level carried in the same
/// field under [`crate::mamaps::body::FLAG_DETAIL_NUMERIC`], because an admin level is an integer the
/// style compares with `<=` rather than a name it matches. The sample confirms it — upstream
/// writes it as an `SInt`, not a string.
pub const DETAILS: &[&str] = &[
    // The four the style filters on, first because they were here first.
    "runway",
    "taxiway",
    "pier",
    "service",
    // The rest of the road classes, measured from the published archive.
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
    "cable_car",
    "turntable",
    // water
    "basin",
    "canal",
    "lake",
    "river",
    "stream",
    "rock",
    // transit stations (v2): the mode a `poi` station names in its detail, so the renderer can
    // show stations with Transit on and POIs off. Appended, like everything since the first
    // measurement — ids before this line do not move.
    "station",
    "halt",
    // transit lines (task 52): the route modes `roads` never needed details for. Same rule.
    "train",
    "monorail",
    // buildings
    "yes",
];
