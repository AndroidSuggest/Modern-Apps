use super::consts::{FLAG_DETAIL_NUMERIC, FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_ONEWAY, FLAG_IS_TUNNEL, WINDING_HOLE};
use crate::proto::{Result, err};

/// One feature.
///
/// Adding fields here is safe for `library/map`: it never constructs a `Feature` literally, it
/// only reads `kind` and `geom_type`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Feature {
    /// An id into [`dict::KINDS`](super::dict::KINDS), or
    /// [`dict::NONE`](super::dict::NONE).
    pub kind: u16,
    /// An id into [`dict::DETAILS`](super::dict::DETAILS), or a plain number when
    /// [`FLAG_DETAIL_NUMERIC`] is set.
    pub kind_detail: u16,
    pub geom_type: u8,
    pub flags: u8,
    /// Index into the body's per-tile name table, or [`NAME_NONE`] for "no name".
    pub name_idx: u16,
    /// Where this feature's parts start in the layer's part table.
    pub parts_offset: u32,
    pub part_count: u32,
    /// A transit line's colour as `0xRRGGBB`.
    ///
    /// Carried per feature rather than interned because colours are data, not vocabulary.
    pub transit_color: u32,
    /// This colour's index among the distinct colours of the corridor it shares, from zero.
    ///
    /// The feature carries the *inputs* to the lane choice rather than a baked offset, because
    /// how many lanes a corridor draws depends on the camera zoom and so cannot be decided at
    /// export time. Zero for a line in no corridor, and for every other layer.
    pub transit_ordinal: u8,
    /// How many distinct colours the corridor carries, unclamped — the style bounds the lane
    /// count, not the archive. One for a line in no corridor; zero for every other layer.
    pub transit_lanes: u8,
    /// How far into its lane this piece sits, as a fraction of the full offset over 255.
    ///
    /// A route eases into its lane over a short taper at a corridor's ends instead of stepping
    /// sideways onto it. With the offset now computed downstream, the fraction has to travel
    /// separately from the lane index. 255 is fully in lane; zero for every other layer.
    pub transit_taper: u8,
    /// A `roads` feature's carriageway lane count, or zero when it has none.
    ///
    /// The OSM `lanes` total, capped to a byte. The renderer draws this many parallel
    /// sub-lanes with dividers at high zoom (the transit lateral-fan, driven by a lane count
    /// instead of a colour ordinal) and collapses to a single stroke below the gate. Rides in
    /// the byte the v2..v4 record kept reserved, so the record width is unchanged; zero for
    /// every layer but `roads`, and for a road with no `lanes` tag.
    pub lane_count: u8,
}

impl Feature {
    pub fn is_tunnel(&self) -> bool {
        self.flags & FLAG_IS_TUNNEL != 0
    }

    pub fn is_bridge(&self) -> bool {
        self.flags & FLAG_IS_BRIDGE != 0
    }

    pub fn is_link(&self) -> bool {
        self.flags & FLAG_IS_LINK != 0
    }

    /// Traffic runs one way only, toward the feature's last point. See [`FLAG_IS_ONEWAY`].
    pub fn is_oneway(&self) -> bool {
        self.flags & FLAG_IS_ONEWAY != 0
    }

    /// The feature's display name from the body's name table, or `None` when it has none.
    pub fn name<'b>(&self, body: &'b Body) -> Option<&'b str> {
        body.name(self.name_idx)
    }

    /// The numeric `kind_detail`, e.g. a boundary's admin level, or `None` when the field is an
    /// interned id.
    pub fn detail_number(&self) -> Option<u16> {
        (self.flags & FLAG_DETAIL_NUMERIC != 0).then_some(self.kind_detail)
    }
}

/// One path: a line, or one ring of a polygon.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Part {
    /// Where this part's points start in the layer's decoded coordinate arena, in points.
    ///
    /// Derivable from the preceding parts' counts, and carried anyway: it is what `points` indexes
    /// with, and validating it on parse catches a body whose parts and arena disagree.
    pub coord_start: u32,
    pub point_count: u32,
    /// [`WINDING_OUTER`] or [`WINDING_HOLE`], stated rather than derived.
    ///
    /// Explicit because the generator already computed the signed area in `f64` with no frame
    /// budget, and a reader recovering it from `i16` coordinates that have been clipped and
    /// quantised can get a near-degenerate ring wrong.
    pub winding: u16,
}

impl Part {
    pub fn is_hole(&self) -> bool {
        self.winding == WINDING_HOLE
    }
}

/// One layer's features, parts and coordinates, decoded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Layer {
    pub layer_id: u8,
    pub features: Vec<Feature>,
    pub parts: Vec<Part>,
    /// `x, y` pairs in extent units.
    pub coords: Vec<(i16, i16)>,
}

impl Layer {
    pub fn new(layer_id: u8) -> Layer {
        Layer { layer_id, features: Vec::new(), parts: Vec::new(), coords: Vec::new() }
    }

    /// The points of one part, as a slice of the arena.
    pub fn points(&self, part: &Part) -> &[(i16, i16)] {
        let start = part.coord_start as usize;
        &self.coords[start..start + part.point_count as usize]
    }

    /// The parts of one feature.
    pub fn parts_of(&self, feature: &Feature) -> &[Part] {
        let start = feature.parts_offset as usize;
        &self.parts[start..start + feature.part_count as usize]
    }
}

/// `name_idx` for "this feature has no name". Index 0 is reserved for the same reason
/// [`dict::NONE`] is: most features are unnamed, and the common value should be zero bytes.
pub const NAME_NONE: u16 = 0;

/// `id` for "this feature has no stable identity", for the same reason [`NAME_NONE`] is zero:
/// a layer's id vector is dense, and features the generator could not attribute an OSM element
/// to should cost zero bytes of entropy rather than a sentinel the reader has to know about.
pub const ID_NONE: u64 = 0;

/// `region_link` for "this label names no admin region". Zero for the same reason [`ID_NONE`] is:
/// the table is dense-parallel to the layer's features, and a label with no linked boundary should
/// cost no entropy rather than a sentinel the reader must special-case.
pub const REGION_NONE: u64 = 0;

/// One road feature's per-lane turn indications, from OSM `turn:lanes[:forward|:backward]`.
///
/// Each `u16` is a lane's [`LANE_*`](super::super) bit set — the same scheme the routing graph
/// writes (`maps/src/main/rust/src/graph.rs`) — ordered left to right. `forward` lanes are
/// traversed toward the way's last point (its junction end) and `backward` toward its first, so a
/// renderer draws forward arrows at the end and backward arrows at the start. Both empty for a
/// feature with no `turn:lanes`, which is almost every road; such a feature costs two bytes in the
/// table (a zero count each way) and nothing at all when its whole layer has no lane data.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct LaneTurns {
    pub forward: Vec<u16>,
    pub backward: Vec<u16>,
}

impl LaneTurns {
    /// Does this feature carry any turn indication at all?
    pub fn is_empty(&self) -> bool {
        self.forward.is_empty() && self.backward.is_empty()
    }
}

/// Which way traffic drives and what colour separates opposing directions.
///
/// A property of the country, and therefore of the tile: the renderer needs it to decide the
/// centre line's colour and which side of the carriageway the forward lanes sit on. Baked once per
/// tile at build time from the admin boundaries, because the renderer has no country data and
/// resolving one on device would mean shipping a polygon set to answer a question that never
/// changes for a given tile.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct MarkingConvention {
    /// Traffic keeps left (UK, Japan, Australia, India, ...). Default is right-hand traffic,
    /// which is most of the world by land area and the safer thing to be wrong about.
    pub left_hand: bool,
    /// A yellow line separates opposing directions (the Americas). Elsewhere it is white, and only
    /// the line's *style* distinguishes it from a lane divider.
    pub yellow_centre: bool,
}

impl MarkingConvention {
    /// The wire byte: bit 0 left-hand traffic, bit 1 yellow centre line. The remaining six bits are
    /// reserved and must be zero, so a later convention (dashed-vs-solid edge lines, say) appends
    /// rather than renumbering.
    pub const LEFT_HAND: u8 = 1 << 0;
    pub const YELLOW_CENTRE: u8 = 1 << 1;
    const KNOWN: u8 = Self::LEFT_HAND | Self::YELLOW_CENTRE;

    pub fn to_byte(self) -> u8 {
        let mut b = 0;
        if self.left_hand {
            b |= Self::LEFT_HAND;
        }
        if self.yellow_centre {
            b |= Self::YELLOW_CENTRE;
        }
        b
    }

    pub fn from_byte(b: u8) -> Result<MarkingConvention> {
        if b & !Self::KNOWN != 0 {
            return err(format!("a .mamaps marking convention sets reserved bits ({b:#04x})"));
        }
        Ok(MarkingConvention {
            left_hand: b & Self::LEFT_HAND != 0,
            yellow_centre: b & Self::YELLOW_CENTRE != 0,
        })
    }
}

/// One road feature's carriageway shape, for the surface renderer.
///
/// [`Feature::lane_count`] gives the total; this says how it divides. `forward` lanes run toward
/// the feature's last point and `backward` toward its first, matching [`LaneTurns`]'s convention
/// exactly. The centre line goes at the boundary between them, which on a road whose split is
/// unknown (both zero) the renderer places down the middle for a two-way and omits for a one-way.
///
/// `solid_dividers` is a bit per interior divider, ordered left to right from the leftmost, set
/// when a lane change across it is prohibited (OSM `change:lanes` `not_left`/`not_right`/`no`). A
/// road with more than 32 interior dividers has none recorded past the 32nd, which no real road
/// reaches.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Carriageway {
    pub forward: u8,
    pub backward: u8,
    pub solid_dividers: u32,
}

impl Carriageway {
    /// Nothing known about this road's carriageway beyond its total lane count.
    pub fn is_empty(&self) -> bool {
        *self == Carriageway::default()
    }
}

/// The OSM `turn:lanes` indication bits, one set per lane in a [`LaneTurns`] mask.
///
/// **An on-disk contract** with the generator (`scripts/maps/osm_ingest` and the routing graph's
/// `maps/src/main/rust/src/graph.rs`): the archive stores exactly these bits, so the renderer's
/// arrow glyphs and the router's lane guidance read one scheme. Keep the three in sync — never
/// renumber a bit, only append. A lane with no marking is [`LANE_NONE`].
pub const LANE_NONE: u16 = 1 << 0;
pub const LANE_THROUGH: u16 = 1 << 1;
pub const LANE_LEFT: u16 = 1 << 2;
pub const LANE_SLIGHT_LEFT: u16 = 1 << 3;
pub const LANE_SHARP_LEFT: u16 = 1 << 4;
pub const LANE_RIGHT: u16 = 1 << 5;
pub const LANE_SLIGHT_RIGHT: u16 = 1 << 6;
pub const LANE_SHARP_RIGHT: u16 = 1 << 7;
pub const LANE_REVERSE: u16 = 1 << 8;
pub const LANE_MERGE_TO_LEFT: u16 = 1 << 9;
pub const LANE_MERGE_TO_RIGHT: u16 = 1 << 10;

/// One `buildings` feature's OSM Simple 3D Buildings (S3DB) attributes.
///
/// A side table entry rather than a field on [`Feature`] for exactly the reason the id and
/// turn-lane tables are: these nine values are present on a small minority of buildings, and
/// widening the fixed 24-byte record for them would cost every road and building in the archive to
/// serve a few. The table is dense-parallel to the `buildings` layer's features — a building that
/// carried no S3DB tags reads back as [`BuildingAttrs::default`], which extrudes as a flat box at
/// the default height — so `building_attrs(i)` lines up with `layer.features[i]`.
///
/// # Wire layout (a fixed [`BUILDING_ATTRS_LEN`]-byte record, all little-endian)
///
/// | offset | field | encoding |
/// |---|---|---|
/// | 0..2 | `height` | `u16` decimetres (0.1 m); 0 means "absent, use the renderer default" |
/// | 2..4 | `min_height` | `u16` decimetres; the height the walls start at (`building:min_level`) |
/// | 4..6 | `roof_height` | `u16` decimetres of the roof alone, within `height` |
/// | 6 | `roof_shape` | one of [`ROOF_FLAT`]..=[`ROOF_DOME`]; unknown shapes are stored as flat |
/// | 7 | `roof_direction` | quantised degrees: `deg * 256 / 360`, so decode is `v * 360 / 256` |
/// | 8 | `roof_orientation` | [`ROOF_ORIENT_ALONG`] or [`ROOF_ORIENT_ACROSS`] |
/// | 9..12 | reserved | zero |
/// | 12..16 | `building_colour` | `0xAARRGGBB`; 0 means "no colour, use the style default" |
/// | 16..20 | `roof_colour` | `0xAARRGGBB`; 0 means "no colour" |
///
/// Heights are decimetres because a `u16` of metres would quantise a house to the nearest storey
/// and a `u16` of centimetres would top out at 655 m — decimetres reach 6553.5 m (every real
/// building, the tallest under 830 m) at 0.1 m resolution, which is finer than the source tags.
/// Colours carry an alpha byte so "no colour" (all zero, fully transparent) is distinct from
/// opaque black `0xFF000000`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct BuildingAttrs {
    /// Total height in decimetres (0.1 m), or 0 for "absent — the renderer picks a default".
    pub height: u16,
    /// The height the walls begin at, in decimetres — `building:min_level` for a floating part.
    pub min_height: u16,
    /// The roof's own height in decimetres, part of `height`, or 0 for a flat roof.
    pub roof_height: u16,
    /// One of [`ROOF_FLAT`]..=[`ROOF_DOME`]. An OSM `roof:shape` this build does not model is
    /// stored as [`ROOF_FLAT`], never as an out-of-range value.
    pub roof_shape: u8,
    /// The roof ridge/slope direction as quantised degrees: `deg * 256 / 360` on the way in, so a
    /// reader recovers `deg = v * 360 / 256`. Zero for a roof with no direction.
    pub roof_direction: u8,
    /// [`ROOF_ORIENT_ALONG`] (the default) or [`ROOF_ORIENT_ACROSS`], for `roof:orientation`.
    pub roof_orientation: u8,
    /// The wall colour as `0xAARRGGBB`, or 0 for "no colour — use the style default".
    pub building_colour: u32,
    /// The roof colour as `0xAARRGGBB`, or 0 for "no colour".
    pub roof_colour: u32,
}

/// The fixed width of one [`BuildingAttrs`] record on the wire. 4-byte aligned so the two `u32`
/// colours sit on a 4-byte boundary within the record.
pub const BUILDING_ATTRS_LEN: usize = 20;

/// [`BuildingAttrs::roof_shape`] values. An OSM `roof:shape` this build does not model falls back
/// to [`ROOF_FLAT`] rather than being stored as an unknown number, so a reader never has to guess.
pub const ROOF_FLAT: u8 = 0;
pub const ROOF_GABLED: u8 = 1;
pub const ROOF_HIPPED: u8 = 2;
pub const ROOF_PYRAMIDAL: u8 = 3;
pub const ROOF_SKILLION: u8 = 4;
pub const ROOF_DOME: u8 = 5;
/// The largest roof-shape id this format defines. `parse` refuses anything above it.
pub const ROOF_SHAPE_MAX: u8 = ROOF_DOME;

/// [`BuildingAttrs::roof_orientation`] values, for OSM `roof:orientation`.
pub const ROOF_ORIENT_ALONG: u8 = 0;
pub const ROOF_ORIENT_ACROSS: u8 = 1;
/// The largest roof-orientation id this format defines. `parse` refuses anything above it.
pub const ROOF_ORIENT_MAX: u8 = ROOF_ORIENT_ACROSS;

/// One tile's DEM heightmap: a fixed square `u16` grid sampled over the tile's own extent.
///
/// A single grid for the whole tile — not per layer, not per feature — so an elevation-free tile
/// (open ocean) omits the section entirely and stays 16-byte. `dim` is the side length, so
/// `samples.len() == dim * dim`, stored row-major from the tile's top-left.
///
/// A sample is metres above sea level **biased by 32768**, i.e. `stored = metres + 32768`, so the
/// whole `i16` range of real terrain (below the Dead Sea to above Everest) fits an unsigned `u16`
/// with sea level at 32768. That is the same bias the AWS Terrain Tiles "terrarium" source uses
/// before its `-32768`, so the ingest keeps the number it already computed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Heightmap {
    /// The grid side length, so the grid is `dim * dim` samples. A `dim` of 0 is not a heightmap;
    /// such a tile omits the section and reads back as `None`.
    pub dim: u16,
    /// `dim * dim` samples, row-major from the tile's top-left, each `metres + 32768`.
    pub samples: Vec<u16>,
}

impl Heightmap {
    /// The sample at grid `(col, row)`, or `None` when either is out of range.
    pub fn sample(&self, col: u16, row: u16) -> Option<u16> {
        if col >= self.dim || row >= self.dim {
            return None;
        }
        self.samples.get(row as usize * self.dim as usize + col as usize).copied()
    }

    /// A sample's elevation in metres, undoing the 32768 bias.
    pub fn metres(stored: u16) -> i32 {
        stored as i32 - 32768
    }
}

/// A whole tile.
///
/// `names` is the per-tile string table: `names[i - 1]` is the text for `name_idx == i`,
/// deduplicated in first-use order. Empty on any tile with nothing named. A renderer that
/// ignores names ignores this field.
///
/// `ids` is the optional per-layer feature id table, keyed by `layer_id` and ascending by it.
/// Each vector is dense and parallel to that layer's `features`, so `ids[k].1[i]` is the id of
/// `layer(ids[k].0).features[i]`.
///
/// `turn_lanes` is the optional per-layer turn-lane table, the same shape as `ids` — keyed by
/// `layer_id`, ascending, and dense-parallel to that layer's `features`. Only the `roads` layer
/// carries one, and only when some road in the tile has `turn:lanes`; a feature with none holds an
/// empty [`LaneTurns`]. It is a side table rather than a field on [`Feature`] for the same reason
/// `ids` is: the masks are variable length and present on a small minority of features, so
/// widening the fixed 24-byte record for them would cost every road in the archive to serve a few.
///
/// It is a side table rather than a field on [`Feature`] because widening the 24-byte feature
/// record to 32 would cost eight bytes on every road and building in the archive — roughly 17 GB
/// on a 68 GB planet — to carry an id that a line feature cannot even have: `coalesce` merges
/// lines hard and deliberately relies on them having no identity, so a merged road's id would be
/// whichever input happened to win. Only `poi` and `places` are pure, never-coalesced points, and
/// giving ids to those two alone costs roughly 320 MB.
///
/// `buildings` is the optional per-layer S3DB attribute table (v6), the same shape as `ids` and
/// `turn_lanes`: keyed by `layer_id`, ascending, and dense-parallel to that layer's `features`.
/// Only the `buildings` layer ever carries one, and only when some building in the tile has S3DB
/// tags; a building with none holds a default [`BuildingAttrs`]. A side table for the same reason
/// the others are — the attrs are ~20 bytes present on a minority of buildings.
///
/// `heightmap` is the optional per-tile DEM grid (v6). One grid for the whole tile, `None` on any
/// tile with no elevation data (open ocean), so such a tile stays as cheap as it was.
///
/// `carriageways` is the optional per-layer carriageway table (v7), the same shape as `turn_lanes`.
/// Only the `roads` layer carries one. It exists because [`Feature::lane_count`] is a total and the
/// surface renderer needs the directional split to place a centre line. `convention` rides with it
/// A whole tile.
///
/// `names` is the per-tile string table: `names[i - 1]` is the text for `name_idx == i`,
/// deduplicated in first-use order. Empty on any tile with nothing named. A renderer that
/// ignores names ignores this field.
///
/// `ids` is the optional per-layer feature id table, keyed by `layer_id` and ascending by it.
/// Each vector is dense and parallel to that layer's `features`, so `ids[k].1[i]` is the id of
/// `layer(ids[k].0).features[i]`.
///
/// `turn_lanes` is the optional per-layer turn-lane table, the same shape as `ids` — keyed by
/// `layer_id`, ascending, and dense-parallel to that layer's `features`. Only the `roads` layer
/// carries one, and only when some road in the tile has `turn:lanes`; a feature with none holds an
/// empty [`LaneTurns`]. It is a side table rather than a field on [`Feature`] for the same reason
/// `ids` is: the masks are variable length and present on a small minority of features, so
/// widening the fixed 24-byte record for them would cost every road in the archive to serve a few.
///
/// It is a side table rather than a field on [`Feature`] because widening the 24-byte feature
/// record to 32 would cost eight bytes on every road and building in the archive — roughly 17 GB
/// on a 68 GB planet — to carry an id that a line feature cannot even have: `coalesce` merges
/// lines hard and deliberately relies on them having no identity, so a merged road's id would be
/// whichever input happened to win. Only `poi` and `places` are pure, never-coalesced points, and
/// giving ids to those two alone costs roughly 320 MB.
///
/// `buildings` is the optional per-layer S3DB attribute table (v6), the same shape as `ids` and
/// `turn_lanes`: keyed by `layer_id`, ascending, and dense-parallel to that layer's `features`.
/// Only the `buildings` layer ever carries one, and only when some building in the tile has S3DB
/// tags; a building with none holds a default [`BuildingAttrs`]. A side table for the same reason
/// the others are — the attrs are ~20 bytes present on a minority of buildings.
///
/// `heightmap` is the optional per-tile DEM grid (v6). One grid for the whole tile, `None` on any
/// tile with no elevation data (open ocean), so such a tile stays as cheap as it was.
///
/// `carriageways` is the optional per-layer carriageway table (v7), the same shape as `turn_lanes`.
/// Only the `roads` layer carries one. It exists because [`Feature::lane_count`] is a total and the
/// surface renderer needs the directional split to place a centre line. `convention` rides with it
/// as a single per-tile byte; it is `None` exactly when the table is absent.
///
/// `region_links` is the optional per-layer region-link table (v8), the same shape as `ids`: keyed
/// by `layer_id`, ascending, and dense-parallel to that layer's `features`. Only the `places` layer
/// carries one, and only when some label in the tile links to an admin boundary; a label with none
/// holds [`REGION_NONE`]. Each entry is the tagged OSM relation id of the boundary the label names
/// (the same id space as the `boundaries` id table), so a tap on the label can outline that region
/// directly. A side table for the same reason `ids` is.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Body {
    pub extent: u16,
    pub layers: Vec<Layer>,
    pub names: Vec<String>,
    pub ids: Vec<(u8, Vec<u64>)>,
    pub turn_lanes: Vec<(u8, Vec<LaneTurns>)>,
    pub buildings: Vec<(u8, Vec<BuildingAttrs>)>,
    pub heightmap: Option<Heightmap>,
    pub carriageways: Vec<(u8, Vec<Carriageway>)>,
    pub convention: Option<MarkingConvention>,
    pub region_links: Vec<(u8, Vec<u64>)>,
}
