//! Dictionary wire tests: round-trips, id-pinning, and refusals.
//!
//! Pure moves out of the former single-file dict module; nothing here changed.

use super::*;

#[test]
fn the_dictionary_round_trips_and_stays_small_enough_for_the_prefix() {
    let schema = Dictionary::schema();
    let bytes = schema.serialize();
    // 1092 bytes as measured. The bound is what the opening prefix can spare beside the header
    // and a useful root, not a round number: at 2 KiB the root still addresses 1.9 M tiles.
    assert!(bytes.len() < 2048, "the dictionary is {} bytes", bytes.len());
    assert_eq!(bytes.len() % 4, 0, "the root index after it must start aligned");
    assert_eq!(Dictionary::parse(&bytes).expect("should parse"), schema);
}

/// **The invariant the whole table exists for.** An id is a position, so a value's id must
/// not depend on anything a build observed. Pinned as literals: a diff that moves an entry
/// shows up here as a changed number rather than as a recoloured map.
#[test]
fn kind_ids_are_positions_in_a_constant_table() {
    let d = Dictionary::schema();
    assert_eq!(d.kind_name(1), Some("island"), "the first kind");
    assert_eq!(d.kind_name(10), Some("water"));
    assert_eq!(d.kind_name(17), Some("national_park"));
    assert_eq!(d.kind_name(45), Some("highway"));
    assert_eq!(d.kind_name(52), Some("building_part"), "the last of the first draft");
    assert_eq!(d.kind_name(53), Some("earth"), "the first value appended after measuring");
    assert_eq!(d.kind_name(NONE), None, "id 0 is `this feature has no kind`");
    assert_eq!(d.kind_name(u16::MAX), None, "past the table");
    assert_eq!(d.detail_name(4), Some("service"));
    assert_eq!(d.detail_name(5), Some("motorway"), "the road classes follow");
    assert_eq!(d.layer_name(LAYER_ROADS), Some("roads"));
    assert_eq!(d.layer_name(LAYER_PLACES), Some("places"), "v2's label layer");
    assert_eq!(d.layer_name(LAYER_POI), Some("poi"), "v2's icon layer");
    assert_eq!(d.layer_name(LAYER_TRANSIT), Some("transit"), "v2 reserves transit");
    assert_eq!(d.layer_name(LAYER_TRAFFIC), Some("traffic"), "v4's live traffic layer");
    assert_eq!(d.layer_name(LAYER_JUNCTION), Some("junction"), "v7's lane connectors");
    assert_eq!(d.layer_name(12), None, "past the table");
}

/// A duplicate would give one value two ids, so half the features carrying it would filter
/// against a style whitelist and half would not.
#[test]
fn no_value_appears_twice_in_a_table() {
    for (what, table) in [("layer", LAYERS), ("kind", KINDS), ("detail", DETAILS)] {
        let mut seen: Vec<&str> = table.to_vec();
        seen.sort_unstable();
        let before = seen.len();
        seen.dedup();
        assert_eq!(before, seen.len(), "the {what} table repeats a value");
    }
}

/// Ids are `u8` for a layer and `u16` for the rest, and a name's length is one byte.
#[test]
fn every_table_fits_the_field_that_carries_its_ids() {
    assert!(LAYERS.len() <= u8::MAX as usize);
    assert!(KINDS.len() < u16::MAX as usize);
    assert!(DETAILS.len() < u16::MAX as usize);
    for name in LAYERS.iter().chain(KINDS).chain(DETAILS) {
        assert!(!name.is_empty(), "an empty name would be indistinguishable from padding");
        assert!(name.len() <= u8::MAX as usize, "`{name}` needs a longer length prefix");
    }
}

#[test]
fn an_archive_whose_tables_differ_is_refused() {
    assert!(Dictionary::schema().check_matches_schema().is_ok());
    let mut renamed = Dictionary::schema();
    renamed.kinds[16] = "national_parks".to_string();
    let message = renamed.check_matches_schema().expect_err("should be refused").0;
    assert!(message.contains("from id 16"), "{message}");
    // Renaming a layer is equally refused: id 0 must mean `earth` everywhere.
    let mut renamed_layer = Dictionary::schema();
    renamed_layer.layers[0] = "ground".to_string();
    assert!(renamed_layer.check_matches_schema().is_err());
    // A shorter table is refused too, even when it is a prefix: the dict is frozen and an
    // archive built from anything but this tree is a different contract, not an older one.
    // (No backward-compat requirement: rebuild the archive instead.)
    let mut short = Dictionary::schema();
    short.layers.truncate(7);
    assert!(short.check_matches_schema().is_err(), "a 7-layer table opened");
    let mut short_details = Dictionary::schema();
    short_details.details.truncate(40);
    assert!(short_details.check_matches_schema().is_err(), "a 40-detail table opened");
    // And a table *longer* than the schema is refused: those ids mean nothing here.
    let mut long = Dictionary::schema();
    long.kinds.push("future_kind".to_string());
    assert!(long.check_matches_schema().is_err());
}

#[test]
fn a_truncated_or_implausible_dictionary_is_refused() {
    let bytes = Dictionary::schema().serialize();
    for cut in [0, 1, 2, 5, 40, bytes.len() - 4] {
        assert!(Dictionary::parse(&bytes[..cut]).is_err(), "truncated at {cut}");
    }
    // A count no length field could carry, which is how a corrupt section would otherwise
    // ask for a gigabyte of `String`s.
    assert!(Dictionary::parse(&[0xFF, 0xFF]).is_err());
    // A name whose length runs off the end.
    assert!(Dictionary::parse(&[1, 0, 200, b'a']).is_err());
    // A name that is not text.
    assert!(Dictionary::parse(&[1, 0, 1, 0xFF]).is_err());
}
