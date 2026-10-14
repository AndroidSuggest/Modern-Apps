//! Supplemental street-geometry extractors for the geocoder build.
//!
//! Minimum spacing and sampling helpers shared by the street extract passes.

/// Minimum spacing between samples along a street, in e7 latitude units (~100 m).
pub const STREET_SAMPLE_E7: i64 = 10_000;
