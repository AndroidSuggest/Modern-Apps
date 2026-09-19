//! Build region: `california` or `world`.
//!
//! One parameter, one meaning: which bbox filters the **built** layers
//! (`roads`, `poi`, `buildings`). Everything else — water, earth, boundaries,
//! landcover, landuse, places, transit, traffic, junction, DEM — is always
//! included regardless of this setting.
//!
//! `world` disables the filter entirely (no bbox). `california` keeps only the
//! features touching the California box below. Callers use touches-box, never
//! containment, for the same reason [`crate::bbox`] documents for
//! `osmium extract complete_ways`: truncating a way at the box boundary would
//! invent a vertex that is not in OSM.

use crate::bbox::BBox;
use crate::proto::{Error, Result};

/// California, with margin for border-straddling ways:
///
/// lon -124.5 .. -114.0, lat 32.0 .. 42.1. Covers the state plus a slim
/// offshore/inland margin so a road straddling the border is kept whole.
pub const CALIFORNIA_BBOX: (f64, f64, f64, f64) = (-124.5, 32.0, -114.0, 42.1);

/// North America, with margin for border-straddling ways:
///
/// lon -170.0 .. -55.0, lat 7.0 .. 72.0. Alaska through Panama plus the
/// Caribbean; Greenland excluded (huge empty tile area for no road/POI gain).
/// Same touches-box semantics as California below.
pub const NA_BBOX: (f64, f64, f64, f64) = (-170.0, 7.0, -55.0, 72.0);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Region {
    California,
    Na,
    World,
}

impl Region {
    pub fn parse(s: &str) -> Result<Region> {
        match s.to_ascii_lowercase().as_str() {
            "california" | "ca" | "cali" => Ok(Region::California),
            "na" | "northamerica" | "north_america" | "north-america" => Ok(Region::Na),
            "world" | "planet" | "global" => Ok(Region::World),
            other => Err(Error(format!(
                "--region wants `california`, `na` or `world`, got `{other}`"
            ))),
        }
    }

    /// The bbox to filter by, or `None` for world (no filtering).
    pub fn bbox(self) -> Option<BBox> {
        match self {
            Region::World => None,
            Region::California => Some(BBox {
                min_lon: CALIFORNIA_BBOX.0,
                min_lat: CALIFORNIA_BBOX.1,
                max_lon: CALIFORNIA_BBOX.2,
                max_lat: CALIFORNIA_BBOX.3,
            }),
            Region::Na => Some(BBox {
                min_lon: NA_BBOX.0,
                min_lat: NA_BBOX.1,
                max_lon: NA_BBOX.2,
                max_lat: NA_BBOX.3,
            }),
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Region::California => "california",
            Region::Na => "na",
            Region::World => "world",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_all_regions() {
        assert_eq!(Region::parse("california").unwrap(), Region::California);
        assert_eq!(Region::parse("CA").unwrap(), Region::California);
        assert_eq!(Region::parse("na").unwrap(), Region::Na);
        assert_eq!(Region::parse("NorthAmerica").unwrap(), Region::Na);
        assert_eq!(Region::parse("world").unwrap(), Region::World);
        assert!(Region::parse("europe").is_err());
    }

    #[test]
    fn world_has_no_bbox_and_california_covers_the_state() {
        assert!(Region::World.bbox().is_none());
        let b = Region::California.bbox().unwrap();
        // San Francisco, Los Angeles, San Diego inside; New York, London outside.
        assert!(b.contains(-122.4194, 37.7749));
        assert!(b.contains(-118.2437, 34.0522));
        assert!(b.contains(-117.1611, 32.7157));
        assert!(!b.contains(-74.0, 40.7));
        assert!(!b.contains(-0.12, 51.5));
    }

    #[test]
    fn na_covers_the_continent_but_not_greenland_or_europe() {
        let b = Region::Na.bbox().unwrap();
        // Anchorage, Mexico City, Panama City, Havana inside.
        assert!(b.contains(-149.9, 61.2));
        assert!(b.contains(-99.1, 19.4));
        assert!(b.contains(-79.5, 9.0));
        assert!(b.contains(-82.4, 23.1));
        // Nuuk (Greenland), London, Bogota outside.
        assert!(!b.contains(-51.7, 64.2));
        assert!(!b.contains(-0.12, 51.5));
        assert!(!b.contains(-74.1, 4.7));
    }
}
