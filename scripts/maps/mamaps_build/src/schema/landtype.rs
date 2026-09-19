//! `landtype`: the whole ground wash in one layer.
//!
//! v8 merges the four v7 wash layers — `earth`, `water`, `landcover`, `landuse` — into a
//! single layer capped at z14 at 1.0x sharpness, so parks, shorelines and desert stay crisp
//! under z14 roads and buildings instead of overzoom-stretching a z12 cap. Everything here
//! emits [`LAYER_LANDTYPE`](tilecodec::mamaps::dict::LAYER_LANDTYPE); the style tells kinds
//! apart by `kind`, not by layer.
//!
//! # Why two call sites
//!
//! First-match-wins order is load-bearing, and the old dispatch asked earth+water before
//! roads/buildings/boundaries and land after them. A single merged classifier called at one
//! site would move the land rules ahead of roads — a `building=yes` on a `leisure=park`
//! would become a park instead of a building. So this module exposes two halves and
//! [`super::classify`] calls them at the two old positions: [`classify_early`] (islands,
//! cliffs, water) first, [`classify_late`] (surfaces and human use) after boundaries.
//!
//! # The pieces, moved whole
//!
//! * `classify_early` is `earth::classify` then `water::classify`: islands (`place=island`/
//!   `islet`, z6 areas), cliffs (`natural=cliff`, z12 lines), then the water lookup
//!   (`natural` seas/bays/water, `waterway` rivers/canals/streams/docks, `landuse=reservoir`/
//!   `basin`). Island stays before lake exactly as before — earth was asked first.
//! * `classify_late` is `land::classify`: the `natural` surface table (`landcover`) then the
//!   ordered `landuse`/`leisure`/rest table (`landuse`), areas only, each with a minimum zoom
//!   and a minimum drawn area in square pixels of a 256-unit tile.
//! * [`stream_prepared`] is `earth::stream_prepared` unchanged: the mainland is still a
//!   pre-validated land polygon streamed in as `Class::area(LAYER_LANDTYPE, NONE, 0)`, because
//!   land is defined by the absence of coastline, not by a tag.
//! * [`min_area_units`] is `land::min_area_units` unchanged.
//!
//! # What v8 adds
//!
//! Three kinds the wash used to drop: `landuse=orchard`/`vineyard` (z9, 16px) and
//! `landuse=quarry` (z10, 4px), plus `leisure=swimming_pool` (z14, 1px) — whose id already
//! existed in the dictionary. `residential`/`commercial` already existed too: paint only.
//! There is no landcover-fallback `NONE` arm and never was: the only `NONE` emission is the
//! mainland synthesis above, so unclassified tags still classify to nothing.
//!
//! # `ocean` comes from the tiler, not from a tag
//!
//! There is no `natural=ocean` way in OpenStreetMap — water is defined by the absence of
//! land — so the sea cannot be classified here. The renderer paints the sea as its
//! background colour; the `ocean` kind stays in the dictionary so archives that name it
//! keep parsing, and the tiler synthesises it.

use std::path::Path;

use osm_ingest::proto::{err, Result};
use tile_build::geom::Geometry;
use tilecodec::mamaps::dict::LAYER_LANDTYPE;

use super::{kind, Class, TagSource};

/// The pre-screen: the union of what the early and late rules read. A superset of what the
/// rules accept, so a screen that lets something through is harmless and one that rejects
/// something is a bug.
pub const FILTERS: &[&str] = &[
    "place", "natural", "waterway", "landuse", "water", "leisure", "amenity", "tourism",
    "aeroway", "man_made", "boundary", "public_transport",
];

/// Every `kind` this module can emit, for the dictionary-closure test.
///
/// `ocean` is here although [`classify_early`] never returns it: the tiler synthesises it
/// into this layer, and the closure test is what keeps the name in step with the dictionary.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] = &[
    // earth
    "island", "earth", "cliff",
    // water
    "bay", "fjord", "lake", "ocean", "river", "sea", "strait", "stream", "water", "canal",
    "dock", "reef",
    // landcover
    "grassland", "barren", "urban_area", "farmland", "glacier", "scrub", "forest", "wetland",
    "sand", "bare_rock",
    // landuse
    "national_park", "park", "cemetery", "protected_area", "nature_reserve", "golf_course",
    "wood", "grass", "meadow", "military", "naval_base", "airfield", "allotments",
    "village_green", "playground", "garden", "dog_park", "pitch", "recreation_ground",
    "hospital", "industrial", "commercial", "residential", "railway", "school", "university",
    "college", "kindergarten", "beach", "zoo", "aerodrome", "runway", "taxiway", "pedestrian",
    "dam", "pier", "platform",
    // v8: what the wash used to drop (`swimming_pool` already had an id — paint only).
    "orchard", "vineyard", "quarry", "swimming_pool",
];

/// The early half: islands, cliffs, then water. Called before roads/buildings/boundaries.
pub fn classify_early(tags: &(impl TagSource + ?Sized), is_way: bool) -> Option<Class> {
    // `place=island`/`islet` are real tags on real ways and relations. An island is an area
    // whatever its geometry says, carried from z6: an island big enough to be tagged is big
    // enough to see, and the minimum area filters the rest.
    if matches!(tags.get("place"), Some("island" | "islet")) {
        return Some(Class {
            min_area_px: 1.0,
            ..Class::area(LAYER_LANDTYPE, kind("island"), 6)
        });
    }
    // A cliff is a line in this layer, and it is what makes a coastal relief map read as relief.
    if tags.get("natural") == Some("cliff") {
        return Some(Class::line(LAYER_LANDTYPE, kind("cliff"), 12));
    }
    classify_water(tags, is_way)
}

/// The late half: natural surfaces, then human use. Called after boundaries, last among
/// geometry — it is the layer everything else is drawn on top of.
pub fn classify_late(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    if let Some(natural) = tags.get("natural") {
        if let Some((_, name, min_zoom, area)) =
            LANDCOVER.iter().find(|(value, _, _, _)| *value == natural)
        {
            return Some(with_area(kind(name), *min_zoom, *area));
        }
    }
    for (key, value, name, min_zoom, area) in LANDUSE {
        if tags.get(key) == Some(value) {
            return Some(with_area(kind(name), *min_zoom, *area));
        }
    }
    None
}

/// Classify a water feature.
///
/// First match wins. `is_way` decides whether a closed way is a ring or a line, which for water is
/// almost always a ring — a `waterway=river` is the exception, and it is a line even when it closes
/// around an island.
fn classify_water(tags: &(impl TagSource + ?Sized), is_way: bool) -> Option<Class> {
    // `natural=*`, the bulk of it. Areas, every one.
    if let Some(natural) = tags.get("natural") {
        let class = match natural {
            // A sea and a bay carry a whole world tile; a strait or a fjord is a coastal
            // feature that only reads once the coast is on screen.
            "sea" => Some(Class::area(LAYER_LANDTYPE, kind("sea"), 0)),
            "bay" => Some(Class::area(LAYER_LANDTYPE, kind("bay"), 6)),
            "strait" => Some(Class::area(LAYER_LANDTYPE, kind("strait"), 6)),
            "fjord" => Some(Class::area(LAYER_LANDTYPE, kind("fjord"), 6)),
            "reef" => Some(Class::area(LAYER_LANDTYPE, kind("reef"), 10)),
            // The generic one, and by far the most common. A lake if `water` says so.
            "water" => Some(water_class(tags)),
            _ => None,
        };
        if class.is_some() {
            return class;
        }
    }

    // `waterway=*`. Rivers and canals are lines that widen into areas when tagged
    // `area=yes`; a stream is a line and stays one.
    if let Some(waterway) = tags.get("waterway") {
        let area = tags.get("area") == Some("yes") || !is_way;
        let (name, min_zoom) = match waterway {
            // A river is the only waterway a continent-scale map draws.
            "river" => ("river", 8),
            "canal" => ("canal", 10),
            "stream" => ("stream", 13),
            // A ditch or a drain is street-level detail at best, and there are millions.
            "ditch" | "drain" => ("stream", 14),
            "dock" => ("dock", 12),
            _ => return None,
        };
        let id = kind(name);
        return Some(if area {
            Class::area(LAYER_LANDTYPE, id, min_zoom)
        } else {
            Class::line(LAYER_LANDTYPE, id, min_zoom)
        });
    }

    // A reservoir or a basin is tagged on `landuse`, not `natural`.
    if let Some(landuse) = tags.get("landuse") {
        if matches!(landuse, "reservoir" | "basin") {
            return Some(Class::area(LAYER_LANDTYPE, kind("water"), 8));
        }
    }
    None
}

/// Which kind a `natural=water` polygon is, from its `water` tag.
///
/// Upstream distinguishes a lake from the generic `water`, and the style gives them the same colour
/// today — but the distinction is in the schema, so it is carried rather than flattened. A restyle
/// that wants lakes bluer than reservoirs then needs no rebuild.
fn water_kind(tags: &(impl TagSource + ?Sized)) -> u16 {
    match tags.get("water") {
        Some("lake") => kind("lake"),
        Some("river" | "canal") => kind("river"),
        Some("lagoon" | "oxbow" | "pond" | "reservoir" | "basin") => kind("water"),
        _ => kind("water"),
    }
}

/// How shallow a `natural=water` polygon is worth carrying.
///
/// A named lake is a landmark and an unnamed pond is not, and a name is the only signal in the tags
/// that separates them. This is the one place the classifier reads `name` at all — for a *decision*,
/// not to carry it.
///
/// Both ride at the same zoom as the other landtypes (the park tier, z8): the area floor, not the
/// zoom gate, is what keeps farm ponds out of shallow tiles — a pond smaller than a couple of
/// pixels is a speck at z8, and `with_area` culls it there instead of hiding every unnamed lake
/// until z12.
fn water_class(tags: &(impl TagSource + ?Sized)) -> Class {
    if tags.has("name") {
        Class::area(LAYER_LANDTYPE, water_kind(tags), 6)
    } else {
        with_area(water_kind(tags), 8, 2.0)
    }
}

/// `natural=*`, the surface of the world rather than what is done with it.
///
/// `(OSM value, kind, min_zoom, min_area_px)`. The areas are in square pixels of a 256-unit tile,
/// which is how a threshold stays meaningful across zooms — see [`min_area_units`].
const LANDCOVER: &[(&str, &str, u8, f64)] = &[
    // A glacier or an ice sheet is a continental feature and there are few of them.
    ("glacier", "glacier", 2, 4.0),
    ("wood", "forest", 7, 16.0),
    ("scrub", "scrub", 7, 16.0),
    ("grassland", "grassland", 7, 16.0),
    ("heath", "scrub", 7, 16.0),
    ("wetland", "wetland", 7, 16.0),
    ("sand", "sand", 8, 4.0),
    ("beach", "sand", 10, 2.0),
    ("bare_rock", "bare_rock", 8, 8.0),
    ("scree", "bare_rock", 9, 8.0),
    ("shingle", "bare_rock", 10, 4.0),
    ("desert", "barren", 3, 8.0),
];

/// `landuse=*`, `leisure=*` and the rest: what people do with a patch of ground.
///
/// `(key, OSM value, kind, min_zoom, min_area_px)`. Ordered, and the order is the tie-break for a
/// polygon carrying two of them.
const LANDUSE: &[(&str, &str, &str, u8, f64)] = &[
    // The big protected areas, which carry a continent.
    ("boundary", "national_park", "national_park", 4, 2.0),
    ("boundary", "protected_area", "protected_area", 5, 2.0),
    ("leisure", "nature_reserve", "nature_reserve", 6, 2.0),
    ("landuse", "forest", "forest", 6, 4.0),
    ("landuse", "military", "military", 6, 4.0),
    ("military", "naval_base", "naval_base", 8, 4.0),
    ("aeroway", "aerodrome", "aerodrome", 8, 2.0),
    ("landuse", "farmland", "farmland", 9, 16.0),
    ("landuse", "orchard", "orchard", 9, 16.0),
    ("landuse", "vineyard", "vineyard", 9, 16.0),
    ("landuse", "meadow", "meadow", 8, 16.0),
    ("landuse", "grass", "grass", 10, 4.0),
    ("landuse", "residential", "residential", 10, 8.0),
    ("landuse", "commercial", "commercial", 11, 4.0),
    ("landuse", "industrial", "industrial", 10, 4.0),
    ("landuse", "quarry", "quarry", 10, 4.0),
    ("landuse", "railway", "railway", 12, 4.0),
    ("landuse", "cemetery", "cemetery", 11, 2.0),
    ("landuse", "allotments", "allotments", 12, 2.0),
    ("landuse", "village_green", "village_green", 12, 2.0),
    ("landuse", "recreation_ground", "recreation_ground", 12, 2.0),
    ("leisure", "park", "park", 8, 2.0),
    ("leisure", "golf_course", "golf_course", 10, 2.0),
    ("leisure", "garden", "garden", 13, 1.0),
    ("leisure", "swimming_pool", "swimming_pool", 14, 1.0),
    ("leisure", "dog_park", "dog_park", 14, 1.0),
    ("leisure", "playground", "playground", 14, 1.0),
    ("leisure", "pitch", "pitch", 14, 1.0),
    ("amenity", "hospital", "hospital", 12, 2.0),
    ("amenity", "school", "school", 13, 1.0),
    ("amenity", "university", "university", 11, 2.0),
    ("amenity", "college", "college", 12, 2.0),
    ("amenity", "kindergarten", "kindergarten", 14, 1.0),
    ("tourism", "zoo", "zoo", 12, 2.0),
    ("aeroway", "runway", "runway", 11, 1.0),
    ("aeroway", "taxiway", "taxiway", 13, 1.0),
    ("highway", "pedestrian", "pedestrian", 13, 1.0),
    ("man_made", "pier", "pier", 13, 1.0),
    ("waterway", "dam", "dam", 12, 1.0),
    ("public_transport", "platform", "platform", 14, 1.0),
];

/// A polygon with a minimum drawn area.
///
/// The area is smuggled through the `Class` in the only place it fits — see [`Class::min_area_px`].
fn with_area(kind: u16, min_zoom: u8, min_area_px: f64) -> Class {
    Class { min_area_px, ..Class::area(LAYER_LANDTYPE, kind, min_zoom) }
}

/// The minimum area a polygon needs at `extent`, in that tile's own square units.
///
/// Stated in square pixels of a 256-unit tile because that is the only scale that means anything: a
/// shape smaller than a couple of pixels is not detail, it is a speck, and there are millions of
/// them. Converted to the tile's extent here so the threshold is the same *visual* size whatever the
/// extent is.
pub fn min_area_units(min_area_px: f64, extent: u32) -> f64 {
    let scale = extent as f64 / 256.0;
    min_area_px * scale * scale
}

/// Stream a prepared land polygon into the feature sink, clipped to `bbox`.
///
/// Streamed rather than returned, because the planet product is 1.26 GB of geometry and collecting it
/// would undo the change this signature exists to serve.
///
/// `bbox` is `(min_lon, min_lat, max_lon, max_lat)` in degrees, and is **required**: the land polygon
/// product covers the whole planet, so a regional build that did not clip would tile every coastline
/// on Earth. That is not a slow build, it is a wrong one — the archive's bounding box would be the
/// globe and its tile count would be planetary.
///
/// Two formats, told apart by extension: an ESRI `.shp`, which is what the OSMCoastline product ships
/// as, and GeoJSON-seq, which is what a hand-made test fixture is easiest to write.
pub fn stream_prepared(
    path: &Path,
    bbox: (f64, f64, f64, f64),
    sink: &mut crate::store::Sink,
) -> Result<u64> {
    let class = Class::area(LAYER_LANDTYPE, tilecodec::mamaps::dict::NONE, 0);
    let is_shapefile = path
        .extension()
        .and_then(|e| e.to_str())
        .is_some_and(|e| e.eq_ignore_ascii_case("shp"));

    let mut written = 0u64;
    if is_shapefile {
        let mut reader = crate::shapefile::ShapeReader::open(path)?;
        reader.clip_to(bbox);
        while let Some(rings) = reader.next()? {
            // One mamaps feature per polygon, not per record: a record can hold several islands, and
            // the tessellator wants one exterior with its own holes.
            for polygon in crate::shapefile::group(rings) {
                sink.push(&class, &Geometry::Polygons(vec![polygon]))?;
                written += 1;
            }
        }
        println!(
            "  {} land polygon(s) kept, {} outside the extract",
            written, reader.skipped,
        );
    } else {
        let text = std::fs::read_to_string(path).map_err(|e| {
            osm_ingest::proto::Error(format!("cannot read {}: {e}", path.display()))
        })?;
        for (line_number, line) in text.lines().enumerate() {
            if line.trim().is_empty() {
                continue;
            }
            let Some(feature) = tile_build::geojson::parse_feature(line) else {
                return err(format!(
                    "{}:{}: not a GeoJSON feature",
                    path.display(),
                    line_number + 1,
                ));
            };
            // Polygons only. A prepared land product has nothing else in it, and a line in there
            // would be a sign the wrong file was handed over.
            let Geometry::Polygons(polygons) = feature.geometry else { continue };
            for polygon in polygons {
                if polygon.is_empty() || !meets(&polygon, bbox) {
                    continue;
                }
                sink.push(&class, &Geometry::Polygons(vec![polygon]))?;
                written += 1;
            }
        }
    }
    if written == 0 {
        return err(format!(
            "{} holds no land polygon meeting {bbox:?}; is it the right area?",
            path.display(),
        ));
    }
    Ok(written)
}

/// Does a polygon's exterior meet `bbox`? Intersection, not containment: a polygon straddling the
/// extract's edge is land inside it.
fn meets(polygon: &[Vec<(f64, f64)>], bbox: (f64, f64, f64, f64)) -> bool {
    let Some(exterior) = polygon.first() else { return false };
    let (mut min_x, mut min_y) = (f64::MAX, f64::MAX);
    let (mut max_x, mut max_y) = (f64::MIN, f64::MIN);
    for &(x, y) in exterior {
        min_x = min_x.min(x);
        min_y = min_y.min(y);
        max_x = max_x.max(x);
        max_y = max_y.max(y);
    }
    !(max_x < bbox.0 || min_x > bbox.2 || max_y < bbox.1 || min_y > bbox.3)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn classify_tags(pairs: &[(&str, &str)], is_way: bool) -> Option<Class> {
        super::classify_early(pairs, is_way).or_else(|| super::classify_late(pairs))
    }

    fn early_tags(pairs: &[(&str, &str)], is_way: bool) -> Option<Class> {
        super::classify_early(pairs, is_way)
    }

    fn late_tags(pairs: &[(&str, &str)]) -> Option<Class> {
        super::classify_late(pairs)
    }

    fn kind_name(class: &Class) -> &'static str {
        dict::KINDS[class.kind as usize - 1]
    }

    #[test]
    fn an_island_is_an_area_carried_from_a_continental_zoom() {
        for value in ["island", "islet"] {
            let class = early_tags(&[("place", value)], true).expect(value);
            assert_eq!(class.layer, dict::LAYER_LANDTYPE);
            assert_eq!(dict::KINDS[class.kind as usize - 1], "island");
            assert!(class.area);
            assert_eq!(class.min_zoom, 6);
            // A minimum area, because `place=islet` is on some very small rocks.
            assert!(class.min_area_px > 0.0);
        }
    }

    #[test]
    fn a_cliff_is_a_line_and_nothing_else_early_is_land() {
        let cliff = early_tags(&[("natural", "cliff")], true).expect("cliff");
        assert_eq!(cliff.layer, dict::LAYER_LANDTYPE);
        assert!(!cliff.area);
        for pairs in [
            vec![("place", "city")],
            vec![("natural", "coastline")],
            vec![("natural", "wood")],
            vec![("landuse", "residential")],
            vec![],
        ] {
            assert!(early_tags(&pairs, true).is_none(), "{pairs:?} should not be early landtype");
        }
    }

    /// **The reason `natural=coastline` is not handled here.** Land is defined by absence: the
    /// coastline ways enclose it, and turning them into polygons is a stitching problem, not a
    /// classification one.
    #[test]
    fn a_coastline_way_is_not_classified_as_land() {
        assert!(classify_tags(&[("natural", "coastline")], true).is_none());
    }

    /// A land file that yields nothing is an error, not an empty layer. Silently shipping an
    /// oceanless world because a path was wrong is the failure this catches.
    #[test]
    fn a_missing_or_empty_prepared_polygon_is_an_error_rather_than_an_empty_layer() {
        let scratch = std::env::temp_dir().join("mamaps_land_sink.features");
        let mut sink = crate::store::Sink::create(&scratch).expect("sink");

        let missing = std::path::Path::new("no_such_land_polygons.geojsonseq");
        assert!(stream_prepared(missing, (-180.0, -90.0, 180.0, 90.0), &mut sink).is_err());

        let empty = std::env::temp_dir().join("mamaps_empty_land.geojsonseq");
        std::fs::write(&empty, "\n\n").expect("write");
        let failure = match stream_prepared(&empty, (-180.0, -90.0, 180.0, 90.0), &mut sink) {
            Ok(_) => panic!("an empty land file should be refused"),
            Err(e) => e,
        };
        assert!(failure.0.contains("no land polygon"), "{}", failure.0);
        let _ = std::fs::remove_file(&empty);
        let _ = std::fs::remove_file(&scratch);
    }

    #[test]
    fn a_prepared_polygon_streams_in_as_an_unfiltered_landtype_feature() {
        let path = std::env::temp_dir().join("mamaps_land.geojsonseq");
        std::fs::write(
            &path,
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\
             \"coordinates\":[[[-120.0,35.0],[-119.0,35.0],[-119.0,36.0],[-120.0,36.0],\
             [-120.0,35.0]]]}}\n",
        )
        .expect("write");
        let scratch = std::env::temp_dir().join("mamaps_land_out.features");
        let mut sink = crate::store::Sink::create(&scratch).expect("sink");
        assert_eq!(stream_prepared(&path, (-180.0, -90.0, 180.0, 90.0), &mut sink).expect("read"), 1);
        let store = sink.finish(&scratch).expect("finish");

        let feature = store
            .reader()
            .expect("reader")
            .next()
            .expect("read")
            .expect("a feature");
        assert_eq!(feature.class.layer, dict::LAYER_LANDTYPE);
        // No kind, so the style's unfiltered `landtype` earth arm draws it.
        assert_eq!(feature.class.kind, dict::NONE);
        assert_eq!(feature.class.min_zoom, 0, "the mainland carries a world tile");
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(&scratch);
    }

    /// **The bug this clip exists for.** The land polygon product covers the whole planet, so a
    /// California build that did not clip would tile every coastline on Earth: it ran for 37 minutes
    /// before being killed, and the archive it was building had a global bounding box.
    #[test]
    fn a_land_polygon_outside_the_build_area_is_not_carried() {
        let path = std::env::temp_dir().join("mamaps_land_clip.geojsonseq");
        // One polygon off California, one off Portugal.
        std::fs::write(
            &path,
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\
             \"coordinates\":[[[-120.0,35.0],[-119.0,35.0],[-119.0,36.0],[-120.0,36.0],\
             [-120.0,35.0]]]}}\n\
             {\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\
             \"coordinates\":[[[-9.0,38.0],[-8.0,38.0],[-8.0,39.0],[-9.0,39.0],[-9.0,38.0]]]}}\n",
        )
        .expect("write");

        let scratch = std::env::temp_dir().join("mamaps_land_clip.features");
        let mut sink = crate::store::Sink::create(&scratch).expect("sink");
        let california = (-125.0, 32.0, -114.0, 42.0);
        assert_eq!(
            stream_prepared(&path, california, &mut sink).expect("read"),
            1,
            "only the polygon meeting the build area is carried",
        );

        // Straddling the edge counts as inside: that is land in the extract, cut off at the border.
        let mut sink = crate::store::Sink::create(&scratch).expect("sink");
        let straddling = (-119.5, 35.5, -100.0, 45.0);
        assert_eq!(stream_prepared(&path, straddling, &mut sink).expect("read"), 1);

        // And an area with no land in it is an error rather than an empty layer, because it almost
        // certainly means the wrong file or the wrong extract.
        let mut sink = crate::store::Sink::create(&scratch).expect("sink");
        let pacific = (-160.0, 0.0, -150.0, 10.0);
        assert!(stream_prepared(&path, pacific, &mut sink).is_err());

        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(&scratch);
    }

    #[test]
    fn the_natural_tags_classify_as_areas() {
        for (value, expected, min_zoom) in [
            ("sea", "sea", 0u8),
            ("bay", "bay", 6),
            ("strait", "strait", 6),
            ("fjord", "fjord", 6),
            ("reef", "reef", 10),
        ] {
            let class = classify_tags(&[("natural", value)], true).expect(value);
            assert_eq!(kind_name(&class), expected);
            assert_eq!(class.layer, dict::LAYER_LANDTYPE);
            assert!(class.area, "{value} is an area");
            assert_eq!(class.min_zoom, min_zoom);
        }
    }

    /// The judgement that matters: a named lake is a landmark from z6, an unnamed pond
    /// rides at the park tier (z8) with a 2px floor — the floor, not the zoom gate, is
    /// what keeps farm ponds out of shallow tiles.
    #[test]
    fn a_named_water_body_is_carried_far_shallower_than_an_unnamed_one() {
        let named = classify_tags(&[("natural", "water"), ("name", "Lake Tahoe")], true).expect("named");
        let pond = classify_tags(&[("natural", "water")], true).expect("unnamed");
        assert_eq!(named.min_zoom, 6);
        assert_eq!(pond.min_zoom, 8);
        assert_eq!(pond.min_area_px, 2.0);
        assert!(named.min_zoom < pond.min_zoom);
    }

    #[test]
    fn the_water_tag_picks_the_kind() {
        let lake = classify_tags(&[("natural", "water"), ("water", "lake")], true).expect("lake");
        assert_eq!(kind_name(&lake), "lake");
        let river = classify_tags(&[("natural", "water"), ("water", "river")], true).expect("river");
        assert_eq!(kind_name(&river), "river");
        // Anything else, including an absent tag, is the generic kind rather than dropped.
        let pond = classify_tags(&[("natural", "water"), ("water", "pond")], true).expect("pond");
        assert_eq!(kind_name(&pond), "water");
        let bare = classify_tags(&[("natural", "water")], true).expect("bare");
        assert_eq!(kind_name(&bare), "water");
    }

    /// A river is a line that becomes an area when tagged one, which is how OSM models a wide
    /// river: a centreline way plus a riverbank polygon.
    #[test]
    fn a_waterway_is_a_line_unless_it_says_it_is_an_area() {
        let river = classify_tags(&[("waterway", "river")], true).expect("river");
        assert!(!river.area, "a river centreline is a line");
        assert_eq!(river.min_zoom, 8);
        let bank = classify_tags(&[("waterway", "river"), ("area", "yes")], true).expect("bank");
        assert!(bank.area);
        // A relation is always an area, whatever the tags say: a multipolygon is a multipolygon.
        let relation = classify_tags(&[("waterway", "river")], false).expect("relation");
        assert!(relation.area);
    }

    /// There are millions of ditches and drains. Carrying them above street level is what turns a
    /// mid-zoom tile into a mesh of blue hair.
    #[test]
    fn a_ditch_is_held_back_to_street_zoom_and_a_river_is_not() {
        let ditch = classify_tags(&[("waterway", "ditch")], true).expect("ditch");
        let stream = classify_tags(&[("waterway", "stream")], true).expect("stream");
        let river = classify_tags(&[("waterway", "river")], true).expect("river");
        assert_eq!(ditch.min_zoom, 14);
        assert_eq!(stream.min_zoom, 13);
        assert_eq!(river.min_zoom, 8);
        // A ditch is drawn as a stream: the style has no ditch colour and inventing one would be
        // paint, not data.
        assert_eq!(kind_name(&ditch), "stream");
    }

    #[test]
    fn a_reservoir_is_water_even_though_it_is_tagged_landuse() {
        let reservoir = classify_tags(&[("landuse", "reservoir")], true).expect("reservoir");
        assert_eq!(reservoir.layer, dict::LAYER_LANDTYPE);
        assert_eq!(kind_name(&reservoir), "water");
        assert!(early_tags(&[("landuse", "residential")], true).is_none(), "not early");
    }

    /// First match wins, and `natural` is asked first. A polygon tagged both ways is water, because
    /// a lake with a boathouse on its edge is still a lake.
    #[test]
    fn natural_is_asked_before_waterway() {
        let both = classify_tags(&[("waterway", "stream"), ("natural", "water")], true).expect("both");
        assert_eq!(kind_name(&both), "water", "the natural rule won");
    }

    /// The early half is islands, cliffs and water — and the island rule wins over water,
    /// because earth was asked before water.
    #[test]
    fn an_island_tagged_as_water_is_still_an_island() {
        let both = classify_tags(&[("place", "island"), ("natural", "water")], true).expect("both");
        assert_eq!(kind_name(&both), "island", "the island rule won");
        assert!(both.area);
    }

    #[test]
    fn natural_surfaces_go_late_and_all_landtype() {
        let wood = late_tags(&[("natural", "wood")]).expect("wood");
        assert_eq!(wood.layer, dict::LAYER_LANDTYPE);
        assert_eq!(kind_name(&wood), "forest");

        let park = late_tags(&[("leisure", "park")]).expect("park");
        assert_eq!(park.layer, dict::LAYER_LANDTYPE);
        assert_eq!(kind_name(&park), "park");
        // Both are areas; neither is ever a line.
        assert!(wood.area && park.area);
    }

    /// The layer's whole difficulty: a national park and a back garden are both green polygons, and
    /// there are four of the first and four million of the second.
    #[test]
    fn the_minimum_zooms_separate_a_national_park_from_a_back_garden() {
        let at = |pairs: &[(&str, &str)]| late_tags(pairs).expect("classified").min_zoom;
        assert_eq!(at(&[("boundary", "national_park")]), 4);
        assert!(at(&[("boundary", "national_park")]) < at(&[("leisure", "park")]));
        assert!(at(&[("leisure", "park")]) < at(&[("leisure", "garden")]));
        assert_eq!(at(&[("leisure", "garden")]), 13);
        assert_eq!(at(&[("leisure", "pitch")]), 14, "a tennis court is street-level");
        // A glacier carries a world tile.
        assert_eq!(at(&[("natural", "glacier")]), 2);
        // Forest and farmland floors rose with the diet (z7, area 16px).
        assert_eq!(at(&[("natural", "wood")]), 7);
        assert_eq!(at(&[("landuse", "farmland")]), 9);
    }

    /// A minimum **zoom** cannot separate two things tagged identically; a minimum **area** can.
    /// Stated in square pixels of a 256-unit tile, so the threshold is the same visual size at any
    /// extent.
    #[test]
    fn a_minimum_area_is_a_visual_size_not_a_coordinate_count() {
        // 2 px of a 256-unit tile, expressed in a 4096-unit one, is 2 * 16 * 16.
        assert_eq!(min_area_units(2.0, 256), 2.0);
        assert_eq!(min_area_units(2.0, 4096), 512.0);
        assert_eq!(min_area_units(1.0, 4096), 256.0);
        // Every classified polygon carries one.
        for pairs in [
            vec![("natural", "wood")],
            vec![("leisure", "park")],
            vec![("landuse", "residential")],
        ] {
            assert!(late_tags(&pairs).expect("classified").min_area_px > 0.0);
        }
    }

    /// Several OSM values collapse to one drawn kind, because the style has one colour for them and
    /// inventing more would be paint rather than data.
    #[test]
    fn several_osm_values_can_share_a_drawn_kind() {
        for value in ["bare_rock", "scree", "shingle"] {
            assert_eq!(kind_name(&late_tags(&[("natural", value)]).expect(value)), "bare_rock");
        }
        for value in ["scrub", "heath"] {
            assert_eq!(kind_name(&late_tags(&[("natural", value)]).expect(value)), "scrub");
        }
        for value in ["sand", "beach"] {
            assert_eq!(kind_name(&late_tags(&[("natural", value)]).expect(value)), "sand");
        }
    }

    /// `natural` is asked before everything, and the `landuse` list is ordered, so a polygon
    /// carrying two tags gets the more significant one.
    #[test]
    fn the_late_rules_are_ordered() {
        // A forest inside a national park is the national park, which is the bigger statement.
        let both = late_tags(&[("landuse", "forest"), ("boundary", "national_park")])
            .expect("both");
        assert_eq!(kind_name(&both), "national_park");
        // And a natural surface beats a use, because the surface rules run first.
        let surface = late_tags(&[("natural", "wood"), ("leisure", "park")]).expect("surface");
        assert_eq!(kind_name(&surface), "forest");
    }

    /// v8: the kinds the wash used to drop are carried, at the floors the plan sets.
    #[test]
    fn the_new_kinds_are_carried_at_their_floors() {
        let at = |pairs: &[(&str, &str)]| late_tags(pairs).expect("classified");
        let orchard = at(&[("landuse", "orchard")]);
        assert_eq!(orchard.layer, dict::LAYER_LANDTYPE);
        assert_eq!(kind_name(&orchard), "orchard");
        assert_eq!(orchard.min_zoom, 9);
        assert_eq!(orchard.min_area_px, 16.0);
        let vineyard = at(&[("landuse", "vineyard")]);
        assert_eq!(kind_name(&vineyard), "vineyard");
        assert_eq!(vineyard.min_zoom, 9);
        assert_eq!(vineyard.min_area_px, 16.0);
        let quarry = at(&[("landuse", "quarry")]);
        assert_eq!(kind_name(&quarry), "quarry");
        assert_eq!(quarry.min_zoom, 10);
        assert_eq!(quarry.min_area_px, 4.0);
        let pool = at(&[("leisure", "swimming_pool")]);
        assert_eq!(kind_name(&pool), "swimming_pool");
        assert_eq!(pool.min_zoom, 14);
        assert_eq!(pool.min_area_px, 1.0);
        assert!(orchard.area && vineyard.area && quarry.area && pool.area);
    }

    #[test]
    fn nothing_untagged_or_unrecognised_is_carried() {
        for pairs in [
            vec![("natural", "tree_row")],
            vec![("waterway", "weir")],
            vec![("amenity", "parking")],
            vec![("building", "yes")],
            vec![],
        ] {
            assert!(classify_tags(&pairs, true).is_none(), "{pairs:?} should not be carried");
        }
    }
}
