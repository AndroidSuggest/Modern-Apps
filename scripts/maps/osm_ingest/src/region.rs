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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Region {
    California,
    World,
}

impl Region {
    pub fn parse(s: &str) -> Result<Region> {
        match s.to_ascii_lowercase().as_str() {
            "california" | "ca" | "cali" => Ok(Region::California),
            "world" | "planet" | "global" => Ok(Region::World),
            other => Err(Error(format!(
                "--region wants `california` or `world`, got `{other}`"
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
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Region::California => "california",
            Region::World => "world",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_both_regions() {
        assert_eq!(Region::parse("california").unwrap(), Region::California);
        assert_eq!(Region::parse("CA").unwrap(), Region::California);
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
}
