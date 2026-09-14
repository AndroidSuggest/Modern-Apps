use super::consts::{FEATURE_RECORD_LEN, PART_ENTRY_LEN};
use super::model::Layer;
use super::slim::push_uvarint;

/// One layer's full v7 payload bytes: features, part table, align4, arena.
///
/// Factored out of [`super::emit::serialize_into`] so v8.1 mixed bodies reuse it for their
/// full layers — one implementation, so a full layer inside a v8 body is byte
/// for byte the v7 layer it would have been.
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
