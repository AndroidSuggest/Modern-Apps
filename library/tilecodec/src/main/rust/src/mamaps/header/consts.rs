//! Wire constants for the `.mamaps` header: magic, versions, lengths and flags.
//!
//! Pure moves out of the former single-file header module; nothing here changed.

pub const MAGIC: &[u8; 7] = b"MAMAPS\0";

/// Bumped only for a change a reader cannot ignore. The archive carries a
/// [`build_id`](crate::mamaps::header::Header::build_id) for "same format, different data".
///
/// v7 adds what the carriageway renderer needs to paint lane markings onto a road surface rather
/// than stroke parallel lines over it: a `FLAG_IS_ONEWAY` feature flag (bit 4), and one more
/// optional trailing body section behind `BODY_FLAG_ROAD_LANES` — a per-road **carriageway table**
/// (dense-parallel to the `roads` layer: the `lanes:forward`/`lanes:backward` split that says where
/// the centre line goes, and which dividers are solid) plus a per-tile **marking convention** byte
/// (left- or right-hand traffic, yellow or white centre line). The 24-byte feature record is
/// unchanged. The bump is forced twice over: an older reader rejects both an unknown body flag and
/// an unknown feature flag bit, so a v7 archive is a clean rejection rather than a wrong map.
///
/// v7 also adds the `junction` layer (id 11) to [`dict::LAYERS`](crate::mamaps::dict::LAYERS): one
/// `GEOM_LINE` feature per lane connector through an intersection, built from the same v6 routing
/// graph `traffic` reads. **That was only free because it landed inside v7's window.** Appending a
/// layer normally forces a bump of its own — v4 did exactly this for `traffic` — because `read`'s
/// `check_matches_schema` compares the whole dictionary on open, so every existing archive stops
/// opening. It cost nothing here only because v7 was committed but never built or shipped, leaving
/// no deployed reader to reject. The window shuts the moment a v7 archive exists: any layer added
/// after that costs v8.
///
/// v6 adds two optional trailing body sections behind new body flags: a per-building **S3DB
/// attribute table** (`BODY_FLAG_BUILDING_TABLE`, dense-parallel to the `buildings` layer —
/// heights, roof shape/height/direction/orientation and wall/roof colours for 3D extrusion) and a
/// per-tile **DEM heightmap** grid (`BODY_FLAG_HEIGHTMAP`, `u16` metres for 3D terrain relief).
/// The 24-byte feature record is unchanged, so the bump is not forced by the record width — it is
/// forced because an older reader rejects an unknown body flag, and would otherwise silently draw a
/// v6 tile flat. `Body::parse` refuses a version it does not speak, so an old v5 archive is a clean
/// rejection rather than a wrong map.
///
/// v5 bakes lane data into the `roads` layer: a per-feature **lane count** in the byte the
/// feature record kept reserved (byte 23), which the renderer expands into that many parallel
/// carriageway lanes with dividers at high zoom. The record width is unchanged, so the bump is
/// not forced by the dictionary (no new `kind`/`kind_detail`) — it is forced because an older
/// reader would read a v5 body's lane byte as the reserved zero it always was and draw every
/// multi-lane road as a single line. `Body::parse` refuses a version it does not speak, so the
/// mismatch is a clean rejection rather than a silently wrong map.
///
/// v4 adds the `traffic` layer (id 10) to [`dict::LAYERS`](crate::mamaps::dict::LAYERS): one
/// `GEOM_LINE` feature per drivable component segment of the v6 routing graph, each carrying
/// its packed `component_id` in the body id table. The layer addition alone forces the bump —
/// `read`'s `check_matches_schema` validates the whole dictionary on open, so an older reader
/// rejects a v4 archive anyway.
///
/// v3 adds `fuel`, `hotel` and `atm` to [`dict::KINDS`](crate::mamaps::dict::KINDS) and an optional
/// per-body feature id table. The kind additions alone force the bump: `read`'s
/// `check_matches_schema` validates the whole dictionary on open, so an older reader would
/// reject a v3 archive anyway. v1 is no longer read — the last v1 archive predates `places`,
/// `poi` and `transit` entirely.
pub const FORMAT_VERSION: u8 = 7;

/// The archive version byte of a v8 archive: one carrying a shared section.
///
/// v8 interns the long-lived per-feature attributes (names, logical rows, S3DB extrusion,
/// carriageway splits, lane turns, stable ids) into one archive-global shared section behind
/// `MBSH`, and appends a 32-byte tail to the header (bytes 128..160) naming it:
/// `shared_offset`/`shared_len`, `shared_flags`, `shared_pools`, reserved. An archive without
/// a shared section is byte-identical v7 — 128 bytes with version byte 7. With one it is 160
/// bytes with version byte 8. The bump is forced twice over, the way v6's and v7's were: an
/// older reader rejects both an unknown version byte and a header length that is not 128, so
/// a v8 archive is a clean rejection rather than a misread map. A v8 reader opens both
/// shapes: a 128-byte header reads as v7 with no shared section, a 160-byte header parses
/// the tail.
///
/// Tile bodies version independently: a v8 slim body carries body version 8 and resolves
/// through the shared section, while a v7 full body still carries 7. `Body::parse` keeps
/// gating on [`FORMAT_VERSION`], which is why this const exists beside it rather than
/// replacing it.
pub const FORMAT_VERSION_V8: u8 = 8;

pub const HEADER_LEN: usize = 128;

/// A v8 header's wire length: the 128 v7 bytes plus the 32-byte shared-section tail.
///
/// The first 128 bytes keep their v7 field positions exactly, so everything up to the tail
/// reads the same out of either shape.
pub const HEADER_LEN_V8: usize = 160;

/// Bodies are compressed frames; clear means the body is stored raw.
pub const FLAG_BODIES_COMPRESSED: u16 = 1 << 0;
/// At least one leaf entry has a `run_length` above 1, so a reader must honour runs.
pub const FLAG_RUN_LENGTH_PRESENT: u16 = 1 << 1;
/// Ring winding and hole containment were normalised at build time.
///
/// What lets `tess::fill`'s repair pass be skipped: with this set, every polygon part group has
/// exactly one CCW outer and its holes are CW and strictly inside it.
pub const FLAG_RINGS_VALIDATED: u16 = 1 << 2;
/// The leaf index exceeds 4 GiB (planet scale: ~268M bodies ×16 B ≈4.29 GB).
/// When set, bytes 72..80 of the header hold leaf_len as a little-endian u64
/// (instead of u32 leaf_len at 72..76 plus 0-reserved at 76..80). Common
/// path (NA ~418 MB) stays flag 0 + u32 + 0 — byte-identical to v1.
pub const FLAG_LEAF_LEN_64: u16 = 1 << 3;

pub(super) const KNOWN_FLAGS: u16 =
    FLAG_BODIES_COMPRESSED | FLAG_RUN_LENGTH_PRESENT | FLAG_RINGS_VALIDATED | FLAG_LEAF_LEN_64;

/// Bodies stored as written.
pub const COMPRESSION_NONE: u8 = 0;
/// Raw DEFLATE, one independent frame per body.
///
/// Not zstd, which is what a container designed on paper would reach for. This crate's whole
/// dependency list is `miniz_oxide` — pure Rust, so it cross-compiles for
/// `aarch64-linux-android` with no C toolchain and nothing to configure — and zstd's encoder
/// would end that. Not gzip either: gzip costs 18 bytes of framing per body and existed only
/// because MapLibre requires it, which nothing reading this format does.
pub const COMPRESSION_DEFLATE: u8 = 1;

/// The deepest zoom a `tile_id_lo` can span inside one leaf. See [`crate::mamaps::index`].
pub const MAX_ZOOM: u8 = 22;
