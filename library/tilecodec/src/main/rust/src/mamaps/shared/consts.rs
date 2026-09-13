//! Shared-section wire constants: magic, versions, lengths, pool kinds and flags.
//!
//! Pure moves out of the former single-file shared module; nothing here changed.

/// Magic bytes opening every shared section.
pub const SHARED_MAGIC: &[u8; 4] = b"MBSH";
/// The only shared-section version this reader speaks.
pub const SHARED_VERSION: u8 = 1;
/// `SharedHeader` wire length. Fixed so a reader can slice it out of a prefix.
pub const SHARED_HEADER_LEN: usize = 32;
/// One pool directory entry's wire length.
pub const SHARED_POOL_ENTRY_LEN: usize = 24;
/// One logical row's wire length.
pub const SHARED_ROW_LEN: usize = 32;
/// One slim reference's wire length.
pub const SHARED_SLIM_REF_LEN: usize = 8;
/// One shared building-attribute record's wire length (matches `body`'s 20 B).
pub const SHARED_BUILDING_LEN: usize = 20;
/// One shared carriageway record's wire length (matches `body`'s 6 B).
pub const SHARED_CARRIAGEWAY_LEN: usize = 6;

/// Pool-table selectors carried in [`SharedPoolDirEntry::kind`](super::records::SharedPoolDirEntry).
pub const SHARED_KIND_STRINGS: u8 = 1;
pub const SHARED_KIND_ROWS: u8 = 2;
pub const SHARED_KIND_BUILDINGS: u8 = 3;
pub const SHARED_KIND_CARRIAGEWAYS: u8 = 4;
pub const SHARED_KIND_LANE_TURNS: u8 = 5;
pub const SHARED_KIND_ID_RUNS: u8 = 6;
pub const SHARED_KIND_SLIM_REFS: u8 = 7;
/// Canonical geometry + per-zoom keep-masks (lane A v8.1).
///
/// The only pool a section may gain without touching kinds 1..=7: a builder
/// that interns no geometry emits exactly the old seven pools, and a reader
/// that meets no kind 8 parses exactly as before.
pub const SHARED_KIND_GEOMETRY: u8 = 8;

/// `name_ref` (and any string reference) for "no name".
///
/// Index 0 is reserved for the same reason `body::NAME_NONE` is: most rows are
/// unnamed, and the common value should cost zero entropy. Valid references are
/// 1-based (`names[ref - 1]`), mirroring [`Body::name`](crate::mamaps::body::Body::name).
pub const SHARED_NAME_NONE: u32 = 0;

/// Attribute-pool index of the default/empty value.
///
/// Every attribute pool seeds index 0 with its default (`BuildingAttrs::default`,
/// a zero carriageway, empty lane turns) so "no data" is a zero index rather
/// than an absent entry. The builder returns 0 for default values without
/// storing a duplicate; the parser accepts 0 unconditionally and bounds-checks
/// anything above it.
pub const SHARED_ATTR_DEFAULT: u32 = 0;

/// Row flag: `kind_detail` is a number, not an interned id.
///
/// Mirrors `body::FLAG_DETAIL_NUMERIC` so a shared row and its per-tile feature
/// cannot disagree about what the field means.
pub const SHARED_FLAG_DETAIL_NUMERIC: u32 = 1 << 0;

pub(super) const KNOWN_SHARED_FLAGS: u8 = 0;
pub(super) const KNOWN_ROW_FLAGS: u32 = SHARED_FLAG_DETAIL_NUMERIC;
