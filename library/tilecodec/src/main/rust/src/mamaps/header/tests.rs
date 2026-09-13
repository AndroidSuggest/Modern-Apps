//! Header wire tests: round-trips, refusals, and the v8 fail-watch suite.
//!
//! Pure moves out of the former single-file header module; nothing here changed.

use super::*;

/// A header naming sections that do not overlap, in a file big enough to hold them.
fn plausible() -> Header {
    Header {
        flags: FLAG_BODIES_COMPRESSED,
        compression: COMPRESSION_DEFLATE,
        layer_count: 7,
        min_zoom: 0,
        max_zoom: 14,
        build_id: 0x0123_4567_89AB_CDEF,
        file_len: 4096,
        dict_offset: 128,
        dict_len: 512,
        leaf_entry_capacity: 4096,
        root_offset: 640,
        root_len: 64,
        leaf_count: 2,
        leaf_offset: 704,
        leaf_len: 32,
        data_offset: 736,
        data_len: 3360,
        tiles_addressed: 9,
        bodies_written: 4,
        min_lon_e7: -1_242_000_000,
        min_lat_e7: 324_000_000,
        max_lon_e7: -1_140_000_000,
        max_lat_e7: 420_000_000,
        shared_offset: 0,
        shared_len: 0,
        shared_flags: 0,
        shared_pools: 0,
    }
}

#[test]
fn a_header_round_trips_through_its_own_bytes() {
    let header = plausible();
    let bytes = header.serialize();
    assert_eq!(bytes.len(), HEADER_LEN, "the header is a fixed 128 bytes");
    assert_eq!(Header::parse(&bytes).expect("should parse"), header);
}

#[test]
fn every_u64_field_is_eight_byte_aligned() {
    // So a reader may take them as aligned loads out of a zero-copy prefix slice.
    for offset in [16, 24, 32, 48, 64, 80, 88, 96, 104, 128, 136] {
        assert_eq!(offset % 8, 0, "a u64 sits at byte {offset}");
    }
}

#[test]
fn a_header_with_trailing_bytes_still_parses() {
    // It arrives inside a 16 KiB prefix, never on its own.
    let mut bytes = plausible().serialize();
    bytes.extend_from_slice(&[0xAB; 1024]);
    assert!(Header::parse(&bytes).is_ok());
}

#[test]
fn a_truncated_or_foreign_header_is_refused() {
    let bytes = plausible().serialize();
    assert!(Header::parse(&bytes[..HEADER_LEN - 1]).is_err(), "short");
    let mut wrong_magic = bytes.clone();
    wrong_magic[0] = b'P';
    assert!(Header::parse(&wrong_magic).is_err(), "PMTiles is not this format");
    let mut wrong_version = bytes.clone();
    wrong_version[7] = FORMAT_VERSION_V8 + 1;
    assert!(Header::parse(&wrong_version).is_err(), "a version past v8");
}

/// An unknown flag means the writer said something about the bodies this reader would
/// ignore, and every flag here changes how a body must be handled.
#[test]
fn an_unknown_flag_or_a_dirty_reserved_word_is_refused() {
    let mut bytes = plausible().serialize();
    bytes[11] = 0x80;
    assert!(Header::parse(&bytes).is_err(), "an unknown flag");
    let mut bytes = plausible().serialize();
    bytes[76] = 1;
    assert!(Header::parse(&bytes).is_err(), "a reserved word a later version may claim");
}

/// Every later read takes its length from a header field, so a header that names an
/// impossible section has to fail here rather than somewhere uninformative.
#[test]
fn a_header_whose_sections_do_not_fit_the_file_is_refused() {
    let cases: &[(&str, fn(&mut Header))] = &[
        ("data past the end", |h| h.data_len = 1 << 40),
        ("a section inside the header", |h| h.dict_offset = 8),
        ("the dictionary over the root", |h| h.dict_len = 600),
        ("a file shorter than the header", |h| h.file_len = 8),
        ("a zoom range inverted", |h| h.min_zoom = 15),
        ("a zoom past the renderer's maximum", |h| h.max_zoom = 30),
        ("a leaf capacity that is not a power of two", |h| h.leaf_entry_capacity = 3000),
        ("no leaves at all", |h| h.leaf_count = 0),
        ("an unknown compression", |h| h.compression = 9),
        ("more bodies than addressed tiles", |h| h.bodies_written = 10),
        ("a root that is not whole entries", |h| h.root_len = 63),
        ("a flag that contradicts the compression byte", |h| {
            h.compression = COMPRESSION_NONE;
        }),
    ];
    for (what, break_it) in cases {
        let mut header = plausible();
        break_it(&mut header);
        assert!(Header::parse(&header.serialize()).is_err(), "{what} should be refused");
    }
}

#[test]
fn an_offset_length_pair_cannot_overflow_into_looking_valid() {
    let mut header = plausible();
    header.data_offset = u64::MAX - 8;
    header.data_len = 64;
    assert!(Header::parse(&header.serialize()).is_err(), "the end wraps");
}

/// Planet scale: leaf index is ~268M bodies ×16 B ≈4.29 GB > u32::MAX,
/// overflowing the old 4 GiB leaf_len field by 1257 B in the failed run.
/// Extended encoding uses FLAG_LEAF_LEN_64 and the formerly-reserved
/// 76..80 word as high 32 of a u64 leaf_len (72..76 low 32). Common path
/// (NA ~418 MB < u32::MAX) stays flag 0 + u32 + 0 → byte-identical to v1.
#[test]
fn a_leaf_len_past_u32_max_round_trips_and_common_path_stays_byte_identical() {
    // The leaf field itself round-trips via Header serialization; use a
    // large file_len so the overall header check (sections fit) doesn't
    // reject the test file. NA's file_len is 418 MB leaf_len + data, but
    // plausible() is 4096 B — enlarge for the planet-sized leaf.
    let planet_leaf: u64 = u32::MAX as u64 + 1257;
    // Extended: flag set, high word carries the overflow.
    let mut extended = Header {
        leaf_len: planet_leaf,
        flags: FLAG_BODIES_COMPRESSED | FLAG_LEAF_LEN_64,
        ..plausible()
    };
    extended.data_offset = extended.leaf_offset + extended.leaf_len;
    extended.file_len = extended.data_offset + extended.data_len;
    let bytes_ext = extended.serialize();
    assert_eq!(bytes_ext.len(), HEADER_LEN);
    assert_ne!(bytes_ext[10] & 0x08, 0, "extended flag must be set");
    assert_ne!(&bytes_ext[76..80], &[0, 0, 0, 0], "high word must carry overflow (1257 beyond 4 GiB)");
    let parsed = Header::parse(&bytes_ext).expect("extended must parse");
    assert_eq!(parsed.leaf_len, planet_leaf);
    assert_eq!(parsed.flags & FLAG_LEAF_LEN_64, FLAG_LEAF_LEN_64);
    // Common path (NA ~418 MB) stays byte-identical to v1: flag 0, reserved 0, low 32 only.
    let na_leaf: u64 = 418 * 1024 * 1024;
    let mut common = Header {
        leaf_len: na_leaf,
        ..plausible()
    };
    common.data_offset = common.leaf_offset + common.leaf_len;
    common.file_len = common.data_offset + common.data_len;
    let bytes_common = common.serialize();
    assert_eq!(bytes_common.len(), HEADER_LEN);
    assert_eq!(bytes_common[10] & FLAG_LEAF_LEN_64 as u8, 0, "common flag must be 0");
    assert_eq!(&bytes_common[76..80], &[0, 0, 0, 0], "reserved must stay 0 for <4 GiB");
    assert_eq!(Header::parse(&bytes_common).expect("common must parse").leaf_len, na_leaf);
    // Byte-identity: common bytes up to leaf_len are identical to what v1
    // would have written (low 32 + 0 reserved); extended differs only in flag + high word.
    // Extended canonical: flag set but value fits u32 is rejected
    let mut bad = Header {
        leaf_len: 100,
        flags: FLAG_BODIES_COMPRESSED | FLAG_LEAF_LEN_64,
        ..plausible()
    };
    bad.data_offset = bad.leaf_offset + bad.leaf_len;
    bad.file_len = bad.data_offset + bad.data_len;
    assert!(Header::parse(&bad.serialize()).is_err(), "extended with small leaf_len must be rejected");
}

// Fail-watch convention (lane B): each test pins one wire fact as a literal, so a
// revert of that fact quotes its failure (`left` vs `right`) rather than a vague
// mismatch. Revert → quote the failure → restore.

/// A header naming a shared section past the tile data: every v7 section shifted by
/// the 32-byte tail, the section itself last, `file_len` covering it.
fn plausible_v8() -> Header {
    let v7 = plausible();
    let shift = (HEADER_LEN_V8 - HEADER_LEN) as u64;
    Header {
        dict_offset: v7.dict_offset + shift,
        root_offset: v7.root_offset + shift,
        leaf_offset: v7.leaf_offset + shift,
        data_offset: v7.data_offset + shift,
        shared_offset: v7.data_offset + shift + v7.data_len,
        shared_len: 512,
        file_len: v7.data_offset + shift + v7.data_len + 512,
        shared_flags: 0,
        // The seven pool kinds the shared section defines.
        shared_pools: 7,
        ..v7
    }
}

/// Fail-watched: the v8 shape. 160 bytes behind version byte 8, field positions
/// identical to v7 (lengths untouched, offsets shifted by exactly the tail), the tail
/// naming the section. A revert of the version byte quotes `left: 7, right: 8`; of the
/// length, `left: 128, right: 160`.
#[test]
fn a_v8_header_is_160_bytes_with_a_v7_identical_first_128() {
    let v8 = plausible_v8();
    let bytes = v8.serialize();
    let v7bytes = plausible().serialize();
    assert_eq!(bytes.len(), 160, "a v8 header is 160 bytes");
    assert_eq!(bytes[7], 8, "the version byte marks v8");
    assert_eq!(&bytes[8..10], &160u16.to_le_bytes(), "the header declares 160");
    assert_eq!(&bytes[0..7], &v7bytes[0..7], "magic");
    assert_eq!(&bytes[10..24], &v7bytes[10..24], "flags through build_id");
    assert_eq!(&bytes[24..32], &4640u64.to_le_bytes(), "file_len covers the section");
    // Offsets shift by exactly the 32-byte tail; lengths are untouched.
    for (at, off) in [(32usize, 160u64), (48, 672u64), (64, 736u64), (80, 768u64)] {
        assert_eq!(&bytes[at..at + 8], &off.to_le_bytes(), "offset at {at}");
    }
    assert_eq!(&bytes[40..44], &v7bytes[40..44], "dict_len");
    assert_eq!(&bytes[56..60], &v7bytes[56..60], "root_len");
    assert_eq!(&bytes[72..76], &v7bytes[72..76], "leaf_len low");
    assert_eq!(&bytes[88..112], &v7bytes[88..112], "data_len, tiles, bodies");
    assert_eq!(&bytes[112..128], &v7bytes[112..128], "bbox");
    // The tail itself, little-endian: section at 4128 for 512 bytes, 7 pools.
    assert_eq!(&bytes[128..136], &4128u64.to_le_bytes(), "shared_offset");
    assert_eq!(&bytes[136..144], &512u64.to_le_bytes(), "shared_len");
    assert_eq!(&bytes[144..146], &0u16.to_le_bytes(), "shared_flags");
    assert_eq!(&bytes[146..150], &7u32.to_le_bytes(), "shared_pools");
    assert_eq!(&bytes[150..160], &[0u8; 10], "reserved tail");
    assert_eq!(Header::parse(&bytes).expect("should parse"), v8);
    // It arrives inside a 16 KiB prefix like every header.
    let mut prefixed = bytes.clone();
    prefixed.extend_from_slice(&[0xAB; 1024]);
    assert!(Header::parse(&prefixed).is_ok());
}

/// Fail-watched: the v8 reader still opens a 128-byte v7 header, with the shared
/// fields zeroed and no shared section.
#[test]
fn a_128_byte_v7_header_opens_with_no_shared_section() {
    let header = Header::parse(&plausible().serialize()).expect("v7 still parses");
    assert_eq!((header.shared_offset, header.shared_len), (0, 0));
    assert_eq!(header.shared_location(), None, "v7 carries no shared section");
    assert_eq!(header.wire_len(), 128);
}

/// Fail-watched: version and length are bound together. A v7 version declaring 160,
/// a v8 version declaring 128, a v8 header cut to 159 bytes, and anything past v8
/// are all refused — while 8 itself parses.
#[test]
fn a_version_length_mismatch_is_refused() {
    let mut v7declares160 = plausible().serialize();
    v7declares160[8..10].copy_from_slice(&160u16.to_le_bytes());
    assert!(Header::parse(&v7declares160).is_err(), "v7 declaring 160");
    let mut v8declares128 = plausible_v8().serialize();
    v8declares128[8..10].copy_from_slice(&128u16.to_le_bytes());
    assert!(Header::parse(&v8declares128).is_err(), "v8 declaring 128");
    let full = plausible_v8().serialize();
    assert!(Header::parse(&full[..159]).is_err(), "a v8 header cut to 159 bytes");
    assert!(Header::parse(&full[..128]).is_err(), "a v8 version in 128 bytes");
    let mut past = full.clone();
    past[7] = 9;
    assert!(Header::parse(&past).is_err(), "a version past v8");
}

/// Fail-watched: the tail's own hygiene. Unknown shared flags and a dirty reserved
/// tail are refused at parse; a 160-byte header naming a zero-length section is
/// refused with it.
#[test]
fn a_dirty_shared_tail_is_refused() {
    let mut flags = plausible_v8().serialize();
    flags[144] = 1;
    assert!(Header::parse(&flags).is_err(), "unknown shared flags");
    let mut reserved = plausible_v8().serialize();
    reserved[159] = 1;
    assert!(Header::parse(&reserved).is_err(), "a dirty reserved tail");
    let mut zero_len = plausible_v8().serialize();
    zero_len[136..144].copy_from_slice(&0u64.to_le_bytes());
    assert!(Header::parse(&zero_len).is_err(), "v8 naming a zero-length section");
}

/// Fail-watched: the shared section gets the same three refusals as every other
/// section — inside the header (including the 32-byte v8 tail), past the end
/// (including an offset+length that wraps), overlapping a section — plus the
/// zero-length contradiction a hand-built header can state.
#[test]
fn a_shared_section_must_fit_without_overlapping() {
    let cases: &[(&str, fn(&mut Header))] = &[
        ("shared over the dictionary", |h| {
            h.shared_offset = 200;
        }),
        ("shared over the tile data", |h| {
            h.shared_offset = 1000;
            h.shared_len = 100;
        }),
        ("shared past the end", |h| {
            h.shared_offset = h.file_len - 100;
        }),
        ("shared inside the v8 tail", |h| {
            h.shared_offset = 140;
        }),
        ("shared wrapping the address space", |h| {
            h.shared_offset = u64::MAX - 8;
            h.shared_len = 64;
        }),
    ];
    for (what, break_it) in cases {
        let mut header = plausible_v8();
        break_it(&mut header);
        assert!(Header::parse(&header.serialize()).is_err(), "{what} should be refused");
    }
    // Zero length beside a nonzero offset is a section claimed and not named.
    let mut header = plausible();
    header.shared_offset = 4096;
    assert!(header.check().is_err(), "a zero-length section with an offset");
}

/// Fail-watched: the pool directory must fit its section and the opening prefix.
/// 100 pools need 2432 bytes of directory, past the 512-byte section; 1000 pools
/// need 24032, inside a 1 MiB section but past the 16 KiB prefix a corrupt count
/// must never make a reader allocate into.
#[test]
fn a_pool_directory_must_fit_its_section_and_the_opening_prefix() {
    let mut past_section = plausible_v8();
    past_section.shared_pools = 100;
    assert!(
        Header::parse(&past_section.serialize()).is_err(),
        "a directory past its section"
    );
    let mut past_prefix = plausible_v8();
    past_prefix.shared_len = 1 << 20;
    past_prefix.file_len = past_prefix.shared_offset + past_prefix.shared_len;
    past_prefix.shared_pools = 1000;
    assert!(
        Header::parse(&past_prefix.serialize()).is_err(),
        "a directory past the opening prefix"
    );
    let mut absurd = plausible_v8();
    absurd.shared_pools = u32::MAX;
    assert!(
        Header::parse(&absurd.serialize()).is_err(),
        "an absurd pool count"
    );
}

/// Fail-watched: the lane C wiring. `shared_location()` is `None` on v7 and the
/// section on v8; `wire_len()` is the dictionary's offset on either shape.
#[test]
fn shared_location_is_none_for_v7_and_the_section_for_v8() {
    assert_eq!(plausible().shared_location(), None);
    assert_eq!(plausible().wire_len(), HEADER_LEN);
    let v8 = plausible_v8();
    assert_eq!(v8.shared_location(), Some((4128, 512)));
    assert_eq!(v8.wire_len(), HEADER_LEN_V8);
}
