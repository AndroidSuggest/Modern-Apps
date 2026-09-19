//! The tag → kind mapping: OSM tags in, `.mamaps` layer and interned ids out.
//!
//! **The main body of work and the main risk of this whole project.** Planetiler's Protomaps
//! profile is thousands of lines of accumulated judgement, and reproducing it is the thing that
//! takes weeks rather than days. So it lives here, alone, behind one function, with no I/O and no
//! geometry: [`classify`] takes tags and returns a [`Class`] or nothing, which makes every rule in
//! it a unit test rather than a screenshot.
//!
//! # The rules
//!
//! Ordered and **first match wins**, like the `osmium tags-filter` expressions this replaces and
//! like Planetiler's own dispatch. Order is therefore load-bearing: a `natural=water` way that also
//! carries `building=yes` is water, because water is asked first.
//!
//! Every layer is pre-screened by [`osm_ingest::select::Select`] before any rule runs. Tag lookup
//! is a linear scan over a block's string table, so at planet scale it matters a great deal whether
//! the common case reads three tags or thirty.
//!
//! # What a `Class` is not
//!
//! It carries no geometry and no name. Names are the largest thing this format drops — an upstream
//! `water` feature carries forty `name:*` localisations — and nothing in the style reads one.

use tilecodec::mamaps::dict;

pub mod boundaries;
pub mod buildings;
pub mod buildings_extra;
pub mod junction;
pub mod landtype;
pub mod places;
pub mod poi;
pub mod roads;
pub mod roads_extra;
pub mod traffic;
pub mod traffic_extra;
pub mod transit;

/// Tag lookup, so a rule is a pure function of its tags.
///
/// [`osm_ingest::osm::Tags`] borrows from the PBF block it was decoded out of, which would make
/// every test here a synthetic protobuf. Behind this trait a test is a list of pairs, which is what
/// makes the mapping — the risky part — cheap enough to cover properly.
pub trait TagSource {
    fn get(&self, key: &str) -> Option<&str>;

    fn has(&self, key: &str) -> bool {
        self.get(key).is_some()
    }

    /// Is this tag one of OSM's affirmatives?
    ///
    /// `yes`, `true` and `1` all mean yes, and a bridge tagged `viaduct` is still a bridge. Only
    /// `no` and absence mean no.
    fn truthy(&self, key: &str) -> bool {
        !matches!(self.get(key), None | Some("no") | Some("false") | Some("0"))
    }
}

impl TagSource for osm_ingest::osm::Tags<'_, '_> {
    fn get(&self, key: &str) -> Option<&str> {
        self.get_str(key)
    }
}

/// Tags as a plain list, for tests.
impl TagSource for [(&str, &str)] {
    fn get(&self, key: &str) -> Option<&str> {
        self.iter().find(|(k, _)| *k == key).map(|(_, v)| *v)
    }
}

/// Where a feature goes and what it is, once classified.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Class {
    /// An index into [`dict::LAYERS`].
    pub layer: u8,
    /// An index into [`dict::KINDS`], or [`dict::NONE`].
    pub kind: u16,
    /// An index into [`dict::DETAILS`], a number under [`FLAG_DETAIL_NUMERIC`], or
    /// [`dict::NONE`].
    ///
    /// [`FLAG_DETAIL_NUMERIC`]: tilecodec::mamaps::body::FLAG_DETAIL_NUMERIC
    pub kind_detail: u16,
    pub flags: u8,
    /// Is this an area? Decides whether a closed way becomes a polygon or a line.
    ///
    /// Not derivable from the geometry: a closed way is a ring for a lake and a loop road for a
    /// cul-de-sac, and only the tags say which.
    pub area: bool,
    /// The shallowest zoom this feature is worth carrying at.
    ///
    /// Where most of the upstream profile's judgement lives, and the reason a world tile is not
    /// every pond in California. Compared against the tile's zoom, so a feature simply is not
    /// written above it.
    pub min_zoom: u8,
    /// The smallest drawn area worth carrying, in square pixels of a 256-unit tile.
    ///
    /// Zero for a line and for anything a zoom gate alone separates. What it is for is the case a
    /// zoom cannot fix: a national park and a back garden are both `leisure=park`, and only a size
    /// Converted to the tile's own units by [`landtype::min_area_units`].
    pub min_area_px: f64,
}

impl Class {
    /// A polygon feature with no detail and no flags.
    pub const fn area(layer: u8, kind: u16, min_zoom: u8) -> Class {
        Class {
            layer,
            kind,
            kind_detail: dict::NONE,
            flags: 0,
            area: true,
            min_zoom,
            min_area_px: 0.0,
        }
    }

    /// A line feature with no detail and no flags.
    pub const fn line(layer: u8, kind: u16, min_zoom: u8) -> Class {
        Class {
            layer,
            kind,
            kind_detail: dict::NONE,
            flags: 0,
            area: false,
            min_zoom,
            min_area_px: 0.0,
        }
    }
}

/// Look up a `kind` name's id at compile-time-ish cost.
///
/// A linear scan over 79 short strings, called once per classified feature. Interning at
/// classification time rather than at encode time is what keeps the encoder free of names entirely.
///
/// Panics on a name the table does not carry, because every name here is a literal in this crate
/// and `every_kind_this_schema_names_is_in_the_dictionary` proves them all.
pub fn kind(name: &str) -> u16 {
    match dict::KINDS.iter().position(|k| *k == name) {
        Some(index) => index as u16 + 1,
        None => panic!("the schema names kind `{name}`, which the dictionary has no id for"),
    }
}

/// Look up a `kind_detail` name's id.
pub fn detail(name: &str) -> u16 {
    match dict::DETAILS.iter().position(|d| *d == name) {
        Some(index) => index as u16 + 1,
        None => panic!("the schema names detail `{name}`, which the dictionary has no id for"),
    }
}

/// The deepest zoom a layer is worth carrying at, by layer id.
///
/// The `landtype` wash is no longer a low-zoom backdrop: it tiles to z14 at 1.0x sharpness, so
/// parks, shorelines and desert stay crisp under z14 roads and buildings. Boundaries keep
/// z13: lines carry sub-10m wiggles a 2x grid stretch could straighten, and 3GB buys keeping
/// them crisp. Every other layer tiles to the archive max (14). Enforced in the spill lane
/// filter beside `min_zoom`, so no spill format change: a per-layer table, not a per-feature
/// field.
pub const MAX_ZOOM_PER_LAYER: [u8; 9] = [
    14, // landtype
    14, // roads
    13, // boundaries
    14, // buildings
    14, // places
    14, // poi
    14, // transit
    14, // traffic
    14, // junction
];

/// The cap for `layer`, or 14 (no cap) for an id outside the table.
pub fn max_zoom_for_layer(layer: u8) -> u8 {
    MAX_ZOOM_PER_LAYER.get(layer as usize).copied().unwrap_or(14)
}

/// Which layers a build is producing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Layers {
    pub landtype: bool,
    pub buildings: bool,
    pub roads: bool,
    pub boundaries: bool,
    pub places: bool,
    pub poi: bool,
    pub transit: bool,
    pub traffic: bool,
    pub junction: bool,
}

impl Layers {
    pub fn all() -> Layers {
        Layers {
            landtype: true,
            buildings: true,
            roads: true,
            boundaries: true,
            places: true,
            poi: true,
            transit: true,
            traffic: true,
            junction: true,
        }
    }
}

/// Classify one element's tags, or `None` for the overwhelming majority that are not drawn.
///
/// Ordered and first-match-wins. `is_way` distinguishes a way from a relation, which matters
/// because a closed way's area-ness is a tag question while a multipolygon relation is always an
/// area.
///
/// Every build carries all 9 layers, so there is no layer selection to gate on.
pub fn classify(
    tags: &(impl TagSource + ?Sized),
    is_way: bool,
    layers: Layers,
) -> Option<Class> {
    debug_assert_eq!(layers, Layers::all(), "every build carries all 9 layers");
    // The early half of `landtype` (islands, cliffs, water) at the old earth+water position:
    // a `natural=water` way that also carries `building=yes` is water, because water is
    // asked first.
    if let Some(class) = landtype::classify_early(tags, is_way) {
        return Some(class);
    }
    // Before buildings, because a road bridge over a building passage is a road.
    if let Some(class) = roads::classify(tags) {
        return Some(class);
    }
    if let Some(class) = buildings::classify(tags) {
        return Some(class);
    }
    if let Some(class) = boundaries::classify(tags) {
        return Some(class);
    }
    // The late half of `landtype` (surfaces, human use) at the old land position: last among
    // geometry, because it is the layer everything else is drawn on top of.
    if let Some(class) = landtype::classify_late(tags) {
        return Some(class);
    }
    // Labels last of all: an area-mapped feature keeps its fill (a park stays a `landuse`
    // polygon, a museum its `buildings` footprint) and only what no geometry layer claimed —
    // overwhelmingly nodes — becomes a point label. Ways that reach here are centroided by
    // extract; see `places` for why points.
    if let Some(class) = places::classify(tags) {
        return Some(class);
    }
    if let Some(class) = poi::classify(tags) {
        return Some(class);
    }
    None
}

/// A label's display name for a classified feature, or `None` when the layer carries none.
///
/// `places` and `poi` name points; `roads` and `water` name their lines (a street or a river) so
/// the renderer can curve a label along the centreline. All four coalesce `name:en` then `name`,
/// the same order the reference style does. Called by extract at each classify site; the name
/// travels beside the class into the spill and is interned per tile with no format cost — the name
/// table already exists.
pub fn display_name(tags: &(impl TagSource + ?Sized), layer: u8) -> Option<String> {
    match layer {
        dict::LAYER_PLACES => places::display_name(tags),
        dict::LAYER_POI => poi::display_name(tags),
        dict::LAYER_ROADS | dict::LAYER_LANDTYPE => line_name(tags),
        _ => None,
    }
}

/// The coalesced `name:en`/`name` of a line feature, trimmed, or `None` when it has neither.
///
/// The same rule [`places::display_name`] applies, spelled out here because a road or a river is
/// not a `places` label and reusing that function would read as one.
fn line_name(tags: &(impl TagSource + ?Sized)) -> Option<String> {
    tags.get("name:en")
        .or_else(|| tags.get("name"))
        .map(str::trim)
        .filter(|name| !name.is_empty())
        .map(str::to_string)
}

/// The `osmium tags-filter` expressions that pre-screen each layer, as one list.
///
/// Reading three tags to reject a feature instead of the thirty the rules would read between them.
/// A superset of what the rules accept, so a screen that lets something through is harmless and one
/// that rejects something is a bug.
///
/// Every build carries all 9 layers, so the screen is unconditional.
pub fn filters() -> Vec<&'static str> {
    let mut out = Vec::new();
    out.extend_from_slice(landtype::FILTERS);
    out.extend_from_slice(roads::FILTERS);
    out.extend_from_slice(buildings::FILTERS);
    out.extend_from_slice(boundaries::FILTERS);
    out.extend_from_slice(places::FILTERS);
    out.extend_from_slice(poi::FILTERS);
    // No `transit` entry: the layer's geometry comes from a GTFS export rather than the `.osm.pbf`,
    // and the one tag it still reads — `station`, for a station POI's `kind_detail` — is screened by
    // `poi::FILTERS`.
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every name this crate writes has to be in the dictionary, or [`kind`] and [`detail`] panic
    /// at runtime on whichever feature happens to hit that rule first.
    #[test]
    fn every_name_this_schema_uses_is_in_the_dictionary() {
        for name in landtype::KINDS
            .iter()
            .chain(buildings::KINDS)
            .chain(roads::KINDS)
            .chain(boundaries::KINDS)
            .chain(places::KINDS)
            .chain(poi::KINDS)
        {
            assert!(
                dict::KINDS.contains(name),
                "the schema names kind `{name}`, which the dictionary has no id for",
            );
            assert_eq!(dict::KINDS[kind(name) as usize - 1], *name);
        }
        for name in roads::DETAILS {
            assert!(
                dict::DETAILS.contains(name),
                "the schema names detail `{name}`, which the dictionary has no id for",
            );
            assert_eq!(dict::DETAILS[detail(name) as usize - 1], *name);
        }
    }

    #[test]
    fn every_build_carries_all_nine_layers() {
        let all = Layers::all();
        for layer in [
            all.landtype,
            all.buildings,
            all.roads,
            all.boundaries,
            all.places,
            all.poi,
            all.transit,
            all.traffic,
            all.junction,
        ] {
            assert!(layer, "every build carries all 9 layers");
        }
    }

    /// Order is load-bearing and first match wins. A way tagged as both a road and a building is a
    /// road, because a building passage is something you drive through.
    #[test]
    fn the_rules_are_ordered_and_the_first_match_wins() {
        let both: &[(&str, &str)] = &[("highway", "residential"), ("building", "yes")];
        let class = classify(both, true, Layers::all()).expect("classified");
        assert_eq!(class.layer, dict::LAYER_ROADS);
        // And water is asked before either.
        let water: &[(&str, &str)] =
            &[("natural", "water"), ("highway", "residential"), ("building", "yes")];
        assert_eq!(classify(water, true, Layers::all()).expect("water").layer, dict::LAYER_LANDTYPE);
    }

    #[test]
    fn the_pre_screen_is_a_superset_of_what_the_rules_accept() {
        // Not provable in general, so this pins the shape: the screen is a list of tag *keys* the
        // rules actually read, in osmium's `tags-filter` spelling, and no layer has an empty one —
        // an empty screen would let every element in the file through to the rules.
        let all = filters();
        assert!(!all.is_empty());
        for filter in &all {
            assert!(!filter.is_empty(), "an empty screen matches everything");
            assert!(!filter.contains(' '), "`{filter}` is not a bare tag key");
        }
        // Every key a rule reads for a *decision* has to be in the screen, or the rule never runs.
        // `water` is what `landtype`'s reservoir rule reads alongside `landuse`; `man_made` is
        // the pier rule; `public_transport` the platform rule.
        for key in [
            "natural", "waterway", "landuse", "water", "building", "highway", "railway",
            "boundary", "place", "leisure", "amenity", "shop", "tourism", "aeroway", "man_made",
            "public_transport", "name", "route", "station",
        ] {
            assert!(all.contains(&key), "the screen omits `{key}`");
        }
        // `station` is what `transit::station_detail` reads, and it is screened by
        // `poi::FILTERS` — otherwise every station silently loses its mode.
        assert!(all.contains(&"station"), "the screen omits `station`");
    }

    #[test]
    fn the_landtype_wash_tiles_to_z14_and_boundaries_keep_z13() {
        use tilecodec::mamaps::dict;
        // The merged wash tiles to the archive max (crisp parks and shorelines under z14
        // streets); boundaries keep z13 (lines carry sub-10m wiggles a 2x stretch could
        // straighten).
        assert_eq!(max_zoom_for_layer(dict::LAYER_LANDTYPE), 14, "the wash is street detail");
        assert_eq!(max_zoom_for_layer(dict::LAYER_BOUNDARIES), 13, "lines keep z13");
        for layer in [
            dict::LAYER_ROADS,
            dict::LAYER_BUILDINGS,
            dict::LAYER_PLACES,
            dict::LAYER_POI,
            dict::LAYER_TRANSIT,
            dict::LAYER_TRAFFIC,
            dict::LAYER_JUNCTION,
        ] {
            assert_eq!(max_zoom_for_layer(layer), 14, "layer {layer} is street detail");
        }
        assert_eq!(max_zoom_for_layer(99), 14, "unknown layer is uncapped");
    }
}
