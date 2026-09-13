pub const BODY_HEADER_LEN: usize = 16;
pub const LAYER_INDEX_LEN: usize = 12;
/// Feature records: kind, kind_detail, geom_type, flags, name_idx (u16), parts_offset,
/// part_count, transit_color (u32), the three transit lane bytes (ordinal, lanes, taper) and
/// the roads lane count (byte 23) — 24, with no byte reserved any longer.
pub const FEATURE_RECORD_LEN: usize = 24;
/// One carriageway record: `u8` forward lanes, `u8` backward lanes, `u32` solid divider bits.
pub const CARRIAGEWAY_RECORD_LEN: usize = 6;
pub const PART_ENTRY_LEN: usize = 12;
pub const BODY_FLAG_EXTENDED_COUNTS: u8 = 0x01;
/// A feature id table follows the name table. See [`Body::ids`].
pub const BODY_FLAG_ID_TABLE: u8 = 0x02;
/// A name table follows the layer payloads.
///
/// Stated rather than inferred from "are there bytes left", which is how v2 found it. That
/// inference only worked while the name table was the *only* optional trailing section; with the
/// id table chaining onto it, an omitted name table and a present id table are indistinguishable
/// without a flag.
pub const BODY_FLAG_NAME_TABLE: u8 = 0x04;
/// A per-road-feature turn-lane table follows the id table. See [`Body::turn_lanes`].
///
/// The last of the optional trailing sections, chained after the id table for the same reason the
/// id table is chained after the name table: with three of them, "there are bytes left" no longer
/// says which one they are, so each is announced by its own flag.
pub const BODY_FLAG_LANE_TABLE: u8 = 0x08;
/// A per-building-feature S3DB attribute table follows the turn-lane table. See
/// [`Body::building_attrs`].
///
/// Chained after the turn-lane table exactly as that was chained after the id table: another
/// optional trailing section announced by its own flag rather than inferred from leftover bytes.
/// v6. Dense-parallel to the `buildings` layer's features — a building with no S3DB tags carries a
/// default [`BuildingAttrs`], and the table costs nothing on any tile whose `buildings` layer is
/// absent or which has no S3DB tags at all.
pub const BODY_FLAG_BUILDING_TABLE: u8 = 0x10;
/// A per-tile DEM heightmap grid follows the building table. See [`Body::heightmap`].
///
/// The last of the optional trailing sections. One fixed `u16` grid for the whole tile (not per
/// layer and not per feature), so an ocean or otherwise elevation-free tile omits it and stays
/// 16-byte. v6.
pub const BODY_FLAG_HEIGHTMAP: u8 = 0x20;
/// A per-road-feature carriageway table follows the heightmap. See [`Body::carriageways`].
///
/// The last of the optional trailing sections. What the carriageway renderer needs that
/// [`Feature::lane_count`] cannot say: the **directional split** (how many of those lanes run each
/// way, which is where the centre line goes) and which dividers are solid rather than dashed. Both
/// come straight from OSM `lanes:forward`/`lanes:backward` and `change:lanes`.
///
/// It also carries the tile's [`MarkingConvention`], which is a property of the *tile* rather than
/// of any feature: a centre line is yellow in the Americas and white almost everywhere else, and
/// traffic keeps left in the UK, Japan and Australia. One byte per tile rather than per road,
/// because a tile never spans two conventions in any way that matters. v7.
pub const BODY_FLAG_ROAD_LANES: u8 = 0x40;

pub(crate) const KNOWN_BODY_FLAGS: u8 = BODY_FLAG_EXTENDED_COUNTS
    | BODY_FLAG_ID_TABLE
    | BODY_FLAG_NAME_TABLE
    | BODY_FLAG_LANE_TABLE
    | BODY_FLAG_BUILDING_TABLE
    | BODY_FLAG_HEIGHTMAP
    | BODY_FLAG_ROAD_LANES;

/// A feature whose geometry is one or more open paths.
pub const GEOM_LINE: u8 = 1;
/// A feature whose geometry is one exterior ring plus its holes.
pub const GEOM_POLYGON: u8 = 2;
/// A feature whose geometry is one or more labelled points (`places` and `poi`).
///
/// Points decode like lines: each part holds that point's tile-local coordinates and the arena
/// walk is identical.
pub const GEOM_POINT: u8 = 3;

pub const FLAG_IS_TUNNEL: u8 = 1 << 0;
pub const FLAG_IS_BRIDGE: u8 = 1 << 1;
pub const FLAG_IS_LINK: u8 = 1 << 2;
/// `kind_detail` is a number, not an id into
/// [`dict::DETAILS`](super::dict::DETAILS).
///
/// What lets `boundaries` carry an admin level in the same field a road carries `service` in: the
/// style compares an admin level with `<=`, so interning it would mean interning every integer.
pub const FLAG_DETAIL_NUMERIC: u8 = 1 << 3;
/// A `roads` feature carries traffic in one direction only, toward its last point.
///
/// OSM `oneway=yes`, and only that — the same test the routing graph applies, so the two cannot
/// disagree about a road. What the carriageway renderer needs it for is the **centre line**: a
/// two-way road separates opposing traffic down the middle and a one-way does not, so without this
/// every one-way street would grow a centre line it has no business having.
///
/// It rides a feature flag rather than the side table because `coalesce` already keys on `flags`,
/// so a one-way and a two-way road of the same class cannot merge into one feature.
pub const FLAG_IS_ONEWAY: u8 = 1 << 4;

pub(crate) const KNOWN_FEATURE_FLAGS: u8 =
    FLAG_IS_TUNNEL | FLAG_IS_BRIDGE | FLAG_IS_LINK | FLAG_DETAIL_NUMERIC | FLAG_IS_ONEWAY;

/// A ring wound counter-clockwise: the outside of a polygon.
pub const WINDING_OUTER: u16 = 0;
/// A ring wound clockwise: a hole.
pub const WINDING_HOLE: u16 = 1;

/// The tile grid a body's coordinates are in. 4096, as MVT uses, so nothing downstream rescales.
pub const DEFAULT_EXTENT: u16 = 4096;
