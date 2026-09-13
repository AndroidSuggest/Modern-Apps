use super::decode::decode_layer;
use super::encode::encode_layer;
use crate::proto::{self, Reader, Result, Writer, WIRE_BYTES, WIRE_VARINT};

/// The spec's default tile extent, and what tippecanoe emits.
pub const DEFAULT_EXTENT: u32 = 4096;
/// Vector tile spec major version we write. 2 is what every current producer uses.
pub const DEFAULT_VERSION: u32 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GeomType {
    Unknown,
    Point,
    LineString,
    Polygon,
}

impl GeomType {
    pub(crate) fn from_wire(v: u64) -> GeomType {
        match v {
            1 => GeomType::Point,
            2 => GeomType::LineString,
            3 => GeomType::Polygon,
            _ => GeomType::Unknown,
        }
    }
    pub(crate) fn to_wire(self) -> u64 {
        match self {
            GeomType::Unknown => 0,
            GeomType::Point => 1,
            GeomType::LineString => 2,
            GeomType::Polygon => 3,
        }
    }
}

/// A feature property value. The variants mirror the `Value` message's oneof-ish
/// set of optional fields.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    String(String),
    Float(f32),
    Double(f64),
    Int(i64),
    Uint(u64),
    SInt(i64),
    Bool(bool),
}

impl Value {
    /// Key for interning, so two equal values share a dictionary slot. Floats are
    /// keyed on their bit pattern: NaN never dedups, which is correct and avoids
    /// needing `Eq` on a float.
    ///
    /// The string is BORROWED. Cloning it here allocated once per string property per
    /// feature per encode, and the drop policy's binary search encodes the same tile
    /// about eleven times -- so on California z11 this was tens of millions of
    /// throwaway `String`s and the single largest cost in the tiler.
    pub(crate) fn dedup_key(&self) -> (u8, u64, &str) {
        match self {
            Value::String(s) => (0, 0, s.as_str()),
            Value::Float(f) => (1, f.to_bits() as u64, ""),
            Value::Double(d) => (2, d.to_bits(), ""),
            Value::Int(i) => (3, *i as u64, ""),
            Value::Uint(u) => (4, *u, ""),
            Value::SInt(i) => (5, *i as u64, ""),
            Value::Bool(b) => (6, *b as u64, ""),
        }
    }

    pub(crate) fn decode(payload: &[u8]) -> Result<Value> {
        let mut r = Reader::new(payload);
        let mut out: Option<Value> = None;
        while let Some((field, wire)) = r.next_field()? {
            match (field, wire) {
                (1, WIRE_BYTES) => out = Some(Value::String(r.string()?)),
                (2, proto::WIRE_I32) => out = Some(Value::Float(f32::from_bits(r.fixed32()?))),
                (3, proto::WIRE_I64) => out = Some(Value::Double(f64::from_bits(r.fixed64()?))),
                (4, WIRE_VARINT) => out = Some(Value::Int(r.ivarint()?)),
                (5, WIRE_VARINT) => out = Some(Value::Uint(r.uvarint()?)),
                (6, WIRE_VARINT) => out = Some(Value::SInt(r.svarint()?)),
                (7, WIRE_VARINT) => out = Some(Value::Bool(r.uvarint()? != 0)),
                (_, w) => r.skip(w)?,
            }
        }
        // An empty Value is legal protobuf but meaningless as a property; treat it
        // as the empty string rather than dropping the tag pair and desyncing the
        // feature's key/value pairing.
        Ok(out.unwrap_or_else(|| Value::String(String::new())))
    }

    pub(crate) fn encode(&self, out: &mut Writer) {
        match self {
            Value::String(s) => out.string_field(1, s),
            Value::Float(f) => out.fixed32_field(2, f.to_bits()),
            Value::Double(d) => out.fixed64_field(3, d.to_bits()),
            Value::Int(i) => out.ivarint_field(4, *i),
            Value::Uint(u) => out.varint_field(5, *u),
            Value::SInt(i) => out.svarint_field(6, *i),
            Value::Bool(b) => out.varint_field(7, *b as u64),
        };
    }
}

#[derive(Debug, Clone)]
pub struct Feature {
    pub id: Option<u64>,
    pub geom_type: GeomType,
    /// Raw MVT command integers, re-emitted verbatim. See the module docs.
    pub geometry: Vec<u32>,
    pub props: Vec<(String, Value)>,
}

impl Feature {
    pub fn get(&self, key: &str) -> Option<&Value> {
        self.props.iter().find(|(k, _)| k == key).map(|(_, v)| v)
    }
}

/// One feature as the encoder actually needs it: nothing owned.
///
/// The producers already own their geometry and properties somewhere — the pyramid in a
/// candidate list, the point tiler in its input slice — and copying both into a
/// [`Feature`] just to encode it made the per-candidate `props` clone the largest
/// allocation in the tiler: measured at ~35% of single-threaded tile encode time, since
/// every property key and string value is a separate heap copy per tile a feature
/// touches.
///
/// [`encode_layer_from`] is the single implementation; [`Tile::encode`] feeds it views of
/// its owned features, so the two paths cannot drift apart and produce different bytes.
pub struct FeatureRef<'a> {
    pub id: Option<u64>,
    pub geom_type: GeomType,
    /// Raw MVT command integers, re-emitted verbatim. See the module docs.
    pub geometry: &'a [u32],
    pub props: &'a [(String, Value)],
}

#[derive(Debug, Clone)]
pub struct Layer {
    pub name: String,
    pub version: u32,
    pub extent: u32,
    pub features: Vec<Feature>,
}

impl Layer {
    pub fn new(name: impl Into<String>) -> Layer {
        Layer {
            name: name.into(),
            version: DEFAULT_VERSION,
            extent: DEFAULT_EXTENT,
            features: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct Tile {
    pub layers: Vec<Layer>,
}

impl Tile {
    pub fn new() -> Tile {
        Tile { layers: Vec::new() }
    }

    pub fn layer(&self, name: &str) -> Option<&Layer> {
        self.layers.iter().find(|l| l.name == name)
    }

    pub fn layer_names(&self) -> Vec<&str> {
        self.layers.iter().map(|l| l.name.as_str()).collect()
    }

    /// Decode a tile body (already un-gzipped).
    pub fn decode(buf: &[u8]) -> Result<Tile> {
        let mut tile = Tile::new();
        let mut r = Reader::new(buf);
        while let Some((field, wire)) = r.next_field()? {
            match (field, wire) {
                (3, WIRE_BYTES) => tile.layers.push(decode_layer(r.bytes()?)?),
                (_, w) => r.skip(w)?,
            }
        }
        Ok(tile)
    }

    /// Encode to a tile body (caller gzips).
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Writer::new();
        let mut layer_buf = Writer::new();
        for layer in &self.layers {
            layer_buf.clear();
            encode_layer(layer, &mut layer_buf);
            out.message(3, &layer_buf);
        }
        out.into_vec()
    }
}
