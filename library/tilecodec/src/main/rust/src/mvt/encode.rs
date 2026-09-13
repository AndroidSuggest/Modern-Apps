use std::collections::HashMap;
use super::model::{DEFAULT_VERSION, FeatureRef, GeomType, Layer, Value};
use crate::proto::Writer;

pub(crate) fn encode_layer(layer: &Layer, out: &mut Writer) {
    encode_layer_from(
        &layer.name,
        layer.extent,
        layer.version,
        layer.features.iter().map(|f| FeatureRef {
            id: f.id,
            geom_type: f.geom_type,
            geometry: &f.geometry,
            props: &f.props,
        }),
        out,
    );
}

/// Encode a single layer body from borrowed features.
///
/// The one implementation of the layer encoder: [`encode_layer`] is a thin view over
/// owned [`Feature`]s, and the tilers pass their own data straight in. See
/// [`FeatureRef`].
pub fn encode_layer_from<'a>(
    name: &str,
    extent: u32,
    version: u32,
    features: impl IntoIterator<Item = FeatureRef<'a>>,
    out: &mut Writer,
) {
    out.string_field(1, name);

    // Dictionaries are rebuilt from the decoded properties, interning on first use.
    let mut keys: Vec<&str> = Vec::new();
    let mut key_idx: HashMap<&str, u32> = HashMap::new();
    let mut values: Vec<&Value> = Vec::new();
    let mut value_idx: HashMap<(u8, u64, &str), u32> = HashMap::new();

    let mut feat_buf = Writer::new();
    let mut tag_buf = Writer::new();
    let mut geom_buf = Writer::new();
    for f in features {
        feat_buf.clear();
        if let Some(id) = f.id {
            feat_buf.varint_field(1, id);
        }

        tag_buf.clear();
        for (k, v) in f.props {
            let ki = *key_idx.entry(k.as_str()).or_insert_with(|| {
                keys.push(k.as_str());
                (keys.len() - 1) as u32
            });
            let vi = *value_idx.entry(v.dedup_key()).or_insert_with(|| {
                values.push(v);
                (values.len() - 1) as u32
            });
            tag_buf.uvarint(ki as u64).uvarint(vi as u64);
        }
        if !tag_buf.is_empty() {
            feat_buf.bytes_field(2, tag_buf.as_slice());
        }

        // Field order follows the .proto's own numbering; readers must not care,
        // but matching it keeps a diff against tippecanoe output legible.
        if f.geom_type != GeomType::Unknown {
            feat_buf.varint_field(3, f.geom_type.to_wire());
        }
        if !f.geometry.is_empty() {
            geom_buf.clear();
            for &g in f.geometry {
                geom_buf.uvarint(g as u64);
            }
            feat_buf.bytes_field(4, geom_buf.as_slice());
        }
        out.bytes_field(2, feat_buf.as_slice());
    }

    for k in &keys {
        out.string_field(3, k);
    }
    let mut val_buf = Writer::new();
    for v in &values {
        val_buf.clear();
        v.encode(&mut val_buf);
        out.message(4, &val_buf);
    }

    out.varint_field(5, extent as u64);
    out.varint_field(15, version as u64);
}

/// Encode a whole one-layer tile body from borrowed features.
///
/// Byte-for-byte what `Tile { layers: vec![one] }.encode()` produces — the layer
/// wrapper is field 3 either way — which is what the tilers rely on.
pub fn encode_tile_from<'a>(
    name: &str,
    extent: u32,
    features: impl IntoIterator<Item = FeatureRef<'a>>,
) -> Vec<u8> {
    let mut layer_buf = Writer::new();
    encode_layer_from(name, extent, DEFAULT_VERSION, features, &mut layer_buf);
    let mut out = Writer::new();
    out.message(3, &layer_buf);
    out.into_vec()
}
