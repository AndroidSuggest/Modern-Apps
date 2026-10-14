use super::consts::{
    FEATURE_RECORD_LEN, FEATURE_RECORD_MIN_LEN, FEATURE_RECORD_ROAD_LEN, PART_ENTRY_LEN,
    PART_ENTRY_V10_LEN,
};
use crate::mamaps::dict;
use super::model::Layer;

pub(crate) fn push_uvarint(out: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

/// v10 record class: which fields a layer's features carry.
///
/// Measured on planet z14 bodies: transit bytes are zero on every non-transit
/// layer, `lane_count` only on roads, names only on roads/places/poi,
/// `kind_detail` only on roads/transit (+4.5% of poi). Classes drop the rest:
/// - `Min` (12 B): kind, geom, flags, parts offset/count — landtype,
///   buildings, junction.
/// - `Road` (17 B): Min + detail, name, lane — roads.
/// - `Full` (24 B): everything — boundaries, places, poi, transit, traffic.
///
/// The class is a pure function of the layer id, so no wire bit announces it
/// and the reader cannot disagree with the writer about which layout a layer
/// uses.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RecordClass {
    Min,
    Road,
    Full,
}

/// Record width for a layer's features under the given body version.
pub(crate) fn record_len(layer_id: u8, v10: bool) -> usize {
    if !v10 {
        return FEATURE_RECORD_LEN;
    }
    match record_class(layer_id) {
        RecordClass::Min => FEATURE_RECORD_MIN_LEN,
        RecordClass::Road => FEATURE_RECORD_ROAD_LEN,
        RecordClass::Full => FEATURE_RECORD_LEN,
    }
}

/// Which record class a layer's features use under v10.
pub(crate) fn record_class(layer_id: u8) -> RecordClass {
    match layer_id {
        v if v == dict::LAYER_LANDTYPE
            || v == dict::LAYER_BUILDINGS
            || v == dict::LAYER_JUNCTION =>
        {
            RecordClass::Min
        }
        v if v == dict::LAYER_ROADS => RecordClass::Road,
        _ => RecordClass::Full,
    }
}

/// Whether a layer mixes Min and Road records per feature under v10.
///
/// Landtype is the only mixed layer: polygons (the overwhelming majority)
/// carry no names or details and use Min, while named lines (rivers, canals
/// — curved-label carriers) use Road. Each record is prefixed with a 1-byte
/// tag (0 = Min, 1 = Road). Every other layer is uniform per `record_class`
/// and carries no tag.
pub(crate) fn is_mixed_layer(layer_id: u8) -> bool {
    layer_id == dict::LAYER_LANDTYPE
}

/// One layer's full v7 payload bytes: features, part table, align4, arena.
///
/// Factored out of [`super::emit::serialize_into`] so the emit path has one
/// implementation for every full layer it writes.
pub(crate) fn write_full_layer_payload(layer: &Layer, out: &mut Vec<u8>) {
    // Sized up front rather than doubled into. Feature and part records are fixed width, and
    // two zigzag varints average under three bytes a point on clipped tile geometry -- an
    // estimate that is allowed to be wrong, because the only cost of being wrong is the growth
    // this avoids in the common case. On a reused buffer it is usually already large enough.
    out.reserve(
        layer.features.len() * FEATURE_RECORD_LEN
            + layer.parts.len() * PART_ENTRY_LEN
            + layer.coords.len() * 3
            + 4,
    );
    for feature in &layer.features {
        out.extend_from_slice(&feature.kind.to_le_bytes());
        out.extend_from_slice(&feature.kind_detail.to_le_bytes());
        out.push(feature.geom_type);
        out.push(feature.flags);
        // Bytes 6..8 are the v2 name index (zero/NAME_NONE on v1's wire, which never wrote
        // them — the old encoder wrote literal zero here).
        out.extend_from_slice(&feature.name_idx.to_le_bytes());
        out.extend_from_slice(&feature.parts_offset.to_le_bytes());
        out.extend_from_slice(&feature.part_count.to_le_bytes());
        // Bytes 16..20 are the v2 transit colour (zero until transit lands).
        out.extend_from_slice(&feature.transit_color.to_le_bytes());
        // Bytes 20..23 are the transit lane inputs, which the v2 record reserved and its
        // decoder never validated — so they drop in with no version bump and an older reader
        // ignores them. Byte 23 was the last reserved byte; v5 gives it to the roads lane
        // count, and the record stays 4-byte aligned like every other fixed record.
        out.push(feature.transit_ordinal);
        out.push(feature.transit_lanes);
        out.push(feature.transit_taper);
        out.push(feature.lane_count);
    }
    for part in &layer.parts {
        out.extend_from_slice(&part.coord_start.to_le_bytes());
        out.extend_from_slice(&part.point_count.to_le_bytes());
        out.extend_from_slice(&part.winding.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
    // The arena, in parts-table order. Deltas restart at each part so a part decodes
    // independently and a long line accumulates nothing.
    //
    // Written straight into `out` rather than through a `proto::Writer` and copied: the writer
    // is a `Vec<u8>` with a varint method, so routing through one bought a second allocation
    // and a second pass over every coordinate in the archive.
    for part in &layer.parts {
        let (mut px, mut py) = (0i32, 0i32);
        for &(x, y) in layer.points(part) {
            push_uvarint(out, crate::proto::zigzag_encode(x as i64 - px as i64));
            push_uvarint(out, crate::proto::zigzag_encode(y as i64 - py as i64));
            (px, py) = (x as i32, y as i32);
        }
    }
}

/// One layer's v10 payload: narrowed records, 4-byte parts, shared arena.
///
/// Same geometry as [`write_full_layer_payload`] — only the fixed records
/// shrink. Parts stay contiguous (validated by the caller exactly as the v8
/// path validates), so `coord_start` is reconstructed on read.
pub(crate) fn write_v10_layer_payload(
    layer: &Layer,
    out: &mut Vec<u8>,
) -> crate::proto::Result<()> {
    use crate::proto::err;
    let class = record_class(layer.layer_id);
    let reclen = record_len(layer.layer_id, true);
    out.reserve(
        layer.features.len() * reclen
            + layer.parts.len() * PART_ENTRY_V10_LEN
            + layer.coords.len() * 3
            + 4,
    );
    for feature in &layer.features {
        // v10 drops fields per class — refuse nonzero dropped values rather
        // than silently discarding them. The tiler never sets transit bytes
        // on non-transit layers (or names/details/lanes where the class has
        // none), so this fires only on a caller bug, never on real tiles.
        //
        // Mixed layers (landtype) prefix each record with a 1-byte tag so
        // the parser can stride: 0 = Min, 1 = Road. Uniform layers carry no
        // tag; their class comes from `record_class`.
        let feature_class = if is_mixed_layer(layer.layer_id) {
            let wide = feature.name_idx != super::model::NAME_NONE;
            out.push(u8::from(wide));
            if wide {
                RecordClass::Road
            } else {
                RecordClass::Min
            }
        } else {
            class
        };
        out.extend_from_slice(&feature.kind.to_le_bytes());
        match feature_class {
            RecordClass::Min => {
                if feature.kind_detail != 0
                    || feature.name_idx != super::model::NAME_NONE
                    || feature.transit_color != 0
                    || feature.transit_ordinal != 0
                    || feature.transit_lanes != 0
                    || feature.transit_taper != 0
                    || feature.lane_count != 0
                {
                    return err(format!(
                        "layer {}'s feature carries bytes the v10 minimal record drops",
                        layer.layer_id
                    ));
                }
                out.push(feature.geom_type);
                out.push(feature.flags);
            }
            RecordClass::Road => {
                if feature.transit_color != 0
                    || feature.transit_ordinal != 0
                    || feature.transit_lanes != 0
                    || feature.transit_taper != 0
                {
                    return err(format!(
                        "layer {}'s feature carries transit bytes the v10 roads record drops",
                        layer.layer_id
                    ));
                }
                out.extend_from_slice(&feature.kind_detail.to_le_bytes());
                out.push(feature.geom_type);
                out.push(feature.flags);
                out.extend_from_slice(&feature.name_idx.to_le_bytes());
            }
            RecordClass::Full => {
                out.extend_from_slice(&feature.kind_detail.to_le_bytes());
                out.push(feature.geom_type);
                out.push(feature.flags);
                out.extend_from_slice(&feature.name_idx.to_le_bytes());
            }
        }
        out.extend_from_slice(&feature.parts_offset.to_le_bytes());
        out.extend_from_slice(&feature.part_count.to_le_bytes());
        match class {
            RecordClass::Min => {}
            RecordClass::Road => {
                out.push(feature.lane_count);
            }
            RecordClass::Full => {
                out.extend_from_slice(&feature.transit_color.to_le_bytes());
                out.push(feature.transit_ordinal);
                out.push(feature.transit_lanes);
                out.push(feature.transit_taper);
                out.push(feature.lane_count);
            }
        }
    }
    for part in &layer.parts {
        if part.point_count > u16::MAX as u32 {
            return err(format!(
                "a v10 part holds {} points, past the u16 count",
                part.point_count
            ));
        }
        out.extend_from_slice(&(part.point_count as u16).to_le_bytes());
        // Bit 0 is the winding (outer/hole); the rest is reserved zero.
        let flags = if part.winding == super::consts::WINDING_HOLE {
            1u8
        } else {
            0u8
        };
        out.push(flags);
        out.push(0);
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
    // The arena is byte-identical to the v8 layout: same parts order, same
    // varint deltas. Only the tables around it narrow.
    for part in &layer.parts {
        let (mut px, mut py) = (0i32, 0i32);
        for &(x, y) in layer.points(part) {
            push_uvarint(out, crate::proto::zigzag_encode(x as i64 - px as i64));
            push_uvarint(out, crate::proto::zigzag_encode(y as i64 - py as i64));
            (px, py) = (x as i32, y as i32);
        }
    }
    Ok(())
}
