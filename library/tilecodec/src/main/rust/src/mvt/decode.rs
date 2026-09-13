use super::model::{DEFAULT_EXTENT, Feature, GeomType, Layer, Value};
use crate::proto::{self, err, Reader, Result, WIRE_BYTES, WIRE_VARINT};

pub(crate) fn decode_layer(buf: &[u8]) -> Result<Layer> {
    let mut name = String::new();
    let mut version = 1u32;
    let mut extent = DEFAULT_EXTENT;
    let mut keys: Vec<String> = Vec::new();
    let mut values: Vec<Value> = Vec::new();
    // Features are collected raw first: the spec does not require the key/value
    // dictionaries to precede them, and tippecanoe in fact writes them last.
    let mut raw_features: Vec<&[u8]> = Vec::new();

    let mut r = Reader::new(buf);
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_BYTES) => name = r.string()?,
            (2, WIRE_BYTES) => raw_features.push(r.bytes()?),
            (3, WIRE_BYTES) => keys.push(r.string()?),
            (4, WIRE_BYTES) => values.push(Value::decode(r.bytes()?)?),
            (5, WIRE_VARINT) => extent = r.uvarint()? as u32,
            (15, WIRE_VARINT) => version = r.uvarint()? as u32,
            (_, w) => r.skip(w)?,
        }
    }
    if extent == 0 {
        return err("MVT layer extent 0");
    }

    let mut features = Vec::with_capacity(raw_features.len());
    for body in raw_features {
        features.push(decode_feature(body, &keys, &values)?);
    }
    Ok(Layer { name, version, extent, features })
}

fn decode_feature(buf: &[u8], keys: &[String], values: &[Value]) -> Result<Feature> {
    let mut id = None;
    let mut geom_type = GeomType::Unknown;
    let mut tags: Vec<u32> = Vec::new();
    let mut geometry: Vec<u32> = Vec::new();

    let mut r = Reader::new(buf);
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_VARINT) => id = Some(r.uvarint()?),
            (2, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut tags)?,
            (3, WIRE_VARINT) => geom_type = GeomType::from_wire(r.uvarint()?),
            (4, WIRE_BYTES) => proto::packed_u32(r.bytes()?, &mut geometry)?,
            (_, w) => r.skip(w)?,
        }
    }
    if tags.len() % 2 != 0 {
        return err("MVT feature has an odd number of tag entries");
    }
    let mut props = Vec::with_capacity(tags.len() / 2);
    for pair in tags.chunks_exact(2) {
        let (ki, vi) = (pair[0] as usize, pair[1] as usize);
        // Out-of-range indices mean a corrupt tile; dropping the pair keeps the
        // rest of the feature usable, which matters when compositing a big archive.
        if let (Some(k), Some(v)) = (keys.get(ki), values.get(vi)) {
            props.push((k.clone(), v.clone()));
        }
    }
    Ok(Feature { id, geom_type, geometry, props })
}
