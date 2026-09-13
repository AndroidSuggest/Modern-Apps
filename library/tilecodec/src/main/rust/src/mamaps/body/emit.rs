use super::consts::{BODY_FLAG_BUILDING_TABLE, BODY_FLAG_EXTENDED_COUNTS, BODY_FLAG_HEIGHTMAP, BODY_FLAG_ID_TABLE, BODY_FLAG_LANE_TABLE, BODY_FLAG_NAME_TABLE, BODY_FLAG_ROAD_LANES, BODY_HEADER_LEN, FEATURE_RECORD_LEN, LAYER_INDEX_LEN, PART_ENTRY_LEN};
use super::model::{
    Body, Heightmap, Layer, MarkingConvention, NAME_NONE, Part, ROOF_ORIENT_MAX, ROOF_SHAPE_MAX,
};
use super::slim::{assemble_slim_payload, push_uvarint};
use super::tables::{serialize_buildings, serialize_carriageways, serialize_heightmap, serialize_ids, serialize_lanes, serialize_names, align4};
use crate::proto::{Result, err};

/// Serialise a body.
///
/// Deliberately available without the `write` feature: a synthetic body is how the reader's own
/// tests get something to read, and `mamaps_dump` round-trips one to check a body is sane.
/// Reusable buffers for [`serialize_into`].
///
/// One per worker, not one per tile. Serialising allocated a payload `Vec` per layer plus the
/// output `Vec` per tile, and on a us-west z14 build across 64 threads that allocation traffic --
/// not the encoding -- was the cost: measured with the encode pass split three ways, serialisation
/// took 20.9 s of CPU at 16 threads and 1120 s at 64, while DEFLATE over the same work merely
/// doubled. Encode wall time was *lower* at 16 threads than at 64. Buffers that outlive a tile take
/// the allocator out of the loop.
#[derive(Default)]
pub struct Scratch {
    payloads: Vec<Vec<u8>>,
    meta: Vec<(u8, u32)>,
    out: Vec<u8>,
}

/// Serialise a body into `scratch`, returning the bytes it holds.
///
/// Byte for byte what [`serialize`] returns; that function is this one with a fresh [`Scratch`].
pub fn serialize_into<'s>(body: &Body, scratch: &'s mut Scratch) -> Result<&'s [u8]> {
    // Borrowed and sorted by reference, never cloned. Cloning to sort copied every layer's whole
    // coordinate arena per tile, which is 2.5 GB of memcpy on a us-west z14 build. Nothing here
    // mutates a layer, so the sort only ever needed the order.
    let mut layers: Vec<&Layer> = body.layers.iter().collect();
    // Ascending by id, which the parser requires and `Body::layer` assumes. Sorted rather than
    // rejected, because a caller assembling a tile per style layer has no reason to care.
    layers.sort_by_key(|l| l.layer_id);
    if layers.windows(2).any(|pair| pair[0].layer_id == pair[1].layer_id) {
        return err("a .mamaps body cannot carry two layers with the same id");
    }
    let needs_extended = layers.iter().any(|l| l.features.len() > u16::MAX as usize);
    for layer in &layers {
        if !needs_extended && layer.features.len() > u16::MAX as usize {
            return err(format!(
                "layer {} has {} features, past the {} a .mamaps body can index",
                layer.layer_id,
                layer.features.len(),
                u16::MAX,
            ));
        }
        if layer.features.len() > u32::MAX as usize {
            return err(format!(
                "layer {} has {} features, past u32",
                layer.layer_id,
                layer.features.len()
            ));
        }
        // The arena is written in parts-table order with no offsets of its own, so a part has to
        // start exactly where the one before it ended. Checked here rather than trusted, because a
        // caller that got it wrong would produce a body that round-trips to different geometry.
        let mut at = 0u32;
        for part in &layer.parts {
            if part.coord_start != at {
                return err(format!(
                    "layer {}'s parts are not contiguous: one starts at point {} where {at} was \
                     expected",
                    layer.layer_id, part.coord_start,
                ));
            }
            at = at
                .checked_add(part.point_count)
                .ok_or_else(|| crate::proto::Error("a .mamaps layer's parts overflow".into()))?;
        }
        if at as usize != layer.coords.len() {
            return err(format!(
                "layer {}'s parts cover {at} points but its arena holds {}",
                layer.layer_id,
                layer.coords.len(),
            ));
        }
        for feature in &layer.features {
            let end = feature.parts_offset as usize + feature.part_count as usize;
            if feature.part_count == 0 || end > layer.parts.len() {
                return err(format!(
                    "layer {}'s feature indexes parts {}..{end} of {}",
                    layer.layer_id,
                    feature.parts_offset,
                    layer.parts.len(),
                ));
            }
            // A name index must name something: index 0 is NAME_NONE and anything past the
            // table would decode to a different string (or nothing) on read.
            if feature.name_idx != NAME_NONE
                && (feature.name_idx as usize) > body.names.len()
            {
                return err(format!(
                    "layer {}'s feature names index {} of {} name(s)",
                    layer.layer_id,
                    feature.name_idx,
                    body.names.len(),
                ));
            }
        }
    }

    // The id table is keyed by layer id and parallel to that layer's features, so a caller that
    // built it against a different layer set would produce a body that reads back with every id
    // attributed to the wrong feature. Checked here rather than trusted, for the same reason the
    // parts contiguity above is.
    let mut previous: Option<u8> = None;
    for (layer_id, entries) in &body.ids {
        if previous.is_some_and(|p| *layer_id <= p) {
            return err("a .mamaps body's id table is not ordered by layer id");
        }
        previous = Some(*layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == *layer_id) else {
            return err(format!(
                "a .mamaps id table names layer {layer_id}, which the body does not carry"
            ));
        };
        if entries.len() != layer.features.len() {
            return err(format!(
                "a .mamaps id table gives layer {layer_id} {} id(s) for {} feature(s)",
                entries.len(),
                layer.features.len(),
            ));
        }
    }

    // The turn-lane table is validated exactly as the id table is: keyed and ascending by layer id
    // and dense-parallel to that layer's features, so a caller that built it against a different
    // layer set is refused here rather than producing a body that misattributes every lane.
    let mut previous: Option<u8> = None;
    for (layer_id, entries) in &body.turn_lanes {
        if previous.is_some_and(|p| *layer_id <= p) {
            return err("a .mamaps body's turn-lane table is not ordered by layer id");
        }
        previous = Some(*layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == *layer_id) else {
            return err(format!(
                "a .mamaps turn-lane table names layer {layer_id}, which the body does not carry"
            ));
        };
        if entries.len() != layer.features.len() {
            return err(format!(
                "a .mamaps turn-lane table gives layer {layer_id} {} record(s) for {} feature(s)",
                entries.len(),
                layer.features.len(),
            ));
        }
        for turns in entries {
            if turns.forward.len() > u8::MAX as usize || turns.backward.len() > u8::MAX as usize {
                return err(format!(
                    "a .mamaps turn-lane record on layer {layer_id} has more than {} lanes",
                    u8::MAX,
                ));
            }
        }
    }

    // The carriageway table, validated exactly as the turn-lane table above. The convention is
    // only written alongside a table, so a body that sets one with no rows would drop it silently
    // on the wire and come back `None` — refused here rather than round-tripping to something the
    // caller did not ask for.
    if body.convention.is_some() && body.carriageways.is_empty() {
        return err("a .mamaps body sets a marking convention but carries no carriageway table");
    }
    let mut previous: Option<u8> = None;
    for (layer_id, entries) in &body.carriageways {
        if previous.is_some_and(|p| *layer_id <= p) {
            return err("a .mamaps body's carriageway table is not ordered by layer id");
        }
        previous = Some(*layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == *layer_id) else {
            return err(format!(
                "a .mamaps carriageway table names layer {layer_id}, which the body does not carry"
            ));
        };
        if entries.len() != layer.features.len() {
            return err(format!(
                "a .mamaps carriageway table gives layer {layer_id} {} record(s) for {} feature(s)",
                entries.len(),
                layer.features.len(),
            ));
        }
    }

    // The building table is validated exactly as the id and turn-lane tables are: keyed and
    // ascending by layer id and dense-parallel to that layer's features, so a caller that built it
    // against a different layer set is refused rather than misattributing every building's height.
    let mut previous: Option<u8> = None;
    for (layer_id, entries) in &body.buildings {
        if previous.is_some_and(|p| *layer_id <= p) {
            return err("a .mamaps body's building table is not ordered by layer id");
        }
        previous = Some(*layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == *layer_id) else {
            return err(format!(
                "a .mamaps building table names layer {layer_id}, which the body does not carry"
            ));
        };
        if entries.len() != layer.features.len() {
            return err(format!(
                "a .mamaps building table gives layer {layer_id} {} record(s) for {} feature(s)",
                entries.len(),
                layer.features.len(),
            ));
        }
        for a in entries {
            if a.roof_shape > ROOF_SHAPE_MAX {
                return err(format!(
                    "a .mamaps building record on layer {layer_id} has roof shape {}",
                    a.roof_shape,
                ));
            }
            if a.roof_orientation > ROOF_ORIENT_MAX {
                return err(format!(
                    "a .mamaps building record on layer {layer_id} has roof orientation {}",
                    a.roof_orientation,
                ));
            }
        }
    }

    let mut index_end = BODY_HEADER_LEN + layers.len() * LAYER_INDEX_LEN;
    if needs_extended {
        index_end += layers.len() * 4;
    }
    // Both fields at once, which needs the struct broken apart: the payloads are built first and
    // then copied into the output, and each wants its own mutable borrow.
    let Scratch { payloads, meta, out: assembled } = scratch;
    meta.clear();
    for (i, layer) in layers.iter().enumerate() {
        if i >= payloads.len() {
            payloads.push(Vec::new());
        }
        let out = &mut payloads[i];
        out.clear();
        write_full_layer_payload(layer, out);
        meta.push((layer.layer_id, layer.features.len() as u32));
    }

    let mut offset = align4(index_end);
    assembled.clear();
    assembled.reserve(index_end + payloads[..layers.len()].iter().map(Vec::len).sum::<usize>() + 4);
    assembled.extend_from_slice(b"MBD");
    assembled.push(crate::mamaps::header::FORMAT_VERSION);
    // Patched below, once the length is known.
    assembled.extend_from_slice(&0u32.to_le_bytes());
    assembled.extend_from_slice(&body.extent.to_le_bytes());
    assembled.push(layers.len() as u8);
    let mut body_flags = if needs_extended { BODY_FLAG_EXTENDED_COUNTS } else { 0 };
    if !body.names.is_empty() {
        body_flags |= BODY_FLAG_NAME_TABLE;
    }
    if !body.ids.is_empty() {
        body_flags |= BODY_FLAG_ID_TABLE;
    }
    if !body.turn_lanes.is_empty() {
        body_flags |= BODY_FLAG_LANE_TABLE;
    }
    if !body.buildings.is_empty() {
        body_flags |= BODY_FLAG_BUILDING_TABLE;
    }
    if body.heightmap.is_some() {
        body_flags |= BODY_FLAG_HEIGHTMAP;
    }
    if !body.carriageways.is_empty() {
        body_flags |= BODY_FLAG_ROAD_LANES;
    }
    assembled.push(body_flags);
    assembled.extend_from_slice(&0u32.to_le_bytes());
    for (i, (layer_id, feature_count)) in meta.iter().enumerate() {
        assembled.push(*layer_id);
        assembled.push(0);
        // For extended, write sentinel-limited low 16 (old readers see at most 65534
        // and reject on payload bound); real count is the u32 after the index.
        let fc: u16 = if needs_extended {
            (*feature_count as usize).min(0xFFFE) as u16
        } else {
            // Non-extended path is byte-identical to the old format: u16 carry
            // validated above to be ≤65535.
            u16::try_from(*feature_count).expect("feature count fits u16 in non-extended body")
        };
        assembled.extend_from_slice(&fc.to_le_bytes());
        assembled.extend_from_slice(&(offset as u32).to_le_bytes());
        assembled.extend_from_slice(&(payloads[i].len() as u32).to_le_bytes());
        offset += payloads[i].len();
    }
    if needs_extended {
        for layer in &layers {
            assembled.extend_from_slice(&(layer.features.len() as u32).to_le_bytes());
        }
    }
    while assembled.len() % 4 != 0 {
        assembled.push(0);
    }
    for payload in &payloads[..layers.len()] {
        assembled.extend_from_slice(payload);
    }
    // The trailing sections, each omitted when empty so a tile with none is payloads-then-end
    // exactly as the parser expects. Each is announced in `body_flags` above.
    if !body.names.is_empty()
        || !body.ids.is_empty()
        || !body.turn_lanes.is_empty()
        || !body.buildings.is_empty()
        || body.heightmap.is_some()
        || !body.carriageways.is_empty()
    {
        while assembled.len() % 4 != 0 {
            assembled.push(0);
        }
    }
    if !body.names.is_empty() {
        assembled.extend_from_slice(&serialize_names(&body.names));
    }
    if !body.ids.is_empty() {
        serialize_ids(&body.ids, assembled);
    }
    if !body.turn_lanes.is_empty() {
        serialize_lanes(&body.turn_lanes, assembled);
    }
    if !body.buildings.is_empty() {
        serialize_buildings(&body.buildings, assembled);
    }
    if let Some(grid) = &body.heightmap {
        serialize_heightmap(grid, assembled);
    }
    if !body.carriageways.is_empty() {
        // The convention only reaches the wire alongside a table, so a body that sets one without
        // any carriageway rows would silently drop it. Validation above rejects that.
        serialize_carriageways(
            &body.carriageways,
            body.convention.unwrap_or_default(),
            assembled,
        );
    }
    let raw_len = assembled.len() as u32;
    assembled[4..8].copy_from_slice(&raw_len.to_le_bytes());
    Ok(assembled)
}

/// Serialise a body to a fresh buffer. [`serialize_into`] is the same thing with the buffer reused.
pub fn serialize(body: &Body) -> Result<Vec<u8>> {
    let mut scratch = Scratch::default();
    serialize_into(body, &mut scratch).map(<[u8]>::to_vec)
}

/// One layer's full v7 payload bytes: features, part table, align4, arena.
///
/// Factored out of [`serialize_into`] so v8.1 mixed bodies reuse it for their
/// full layers — one implementation, so a full layer inside a v8 body is byte
/// for byte the v7 layer it would have been.
fn write_full_layer_payload(layer: &Layer, out: &mut Vec<u8>) {
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

/// One layer of a v8.1 mixed body: full v7 bytes or a slim payload.
pub enum MixedLayer<'a> {
    /// v7 bytes verbatim (K≈1 layers, styled layers, anything without refs).
    Full(&'a Layer),
    /// 16-byte instances plus the layer's own part table and arena.
    Slim {
        refs: Vec<(u32, u32, u32, u32)>,
        parts: Vec<Part>,
        coords: Vec<(i16, i16)>,
    },
}

/// Serialise a v8.1 mixed body: v8 version byte, per-layer slim/full choice in
/// the index encoding byte, slim payloads via [`assemble_slim_payload`], full
/// payloads via [`write_full_layer_payload`], then the convention byte and
/// heightmap when present. Never any per-tile side tables — a v8 body carries
/// none, and resolve refuses a full layer that names one.
///
/// Extended counts mirror the v7 rule: any layer (slim or full) past 65535
/// records buys the u32 block for every layer.
pub fn serialize_mixed_body<'s>(
    extent: u16,
    layers: &[(u8, MixedLayer<'_>)],
    convention: Option<MarkingConvention>,
    heightmap: Option<&Heightmap>,
    scratch: &'s mut Scratch,
) -> Result<&'s [u8]> {
    let mut ordered: Vec<(u8, &MixedLayer<'_>)> = layers.iter().map(|(id, l)| (*id, l)).collect();
    ordered.sort_by_key(|(id, _)| *id);
    if ordered.windows(2).any(|pair| pair[0].0 == pair[1].0) {
        return err("a .mamaps mixed body cannot carry two layers with the same id");
    }
    let counts: Vec<usize> = ordered
        .iter()
        .map(|(_, l)| match l {
            MixedLayer::Full(layer) => layer.features.len(),
            MixedLayer::Slim { refs, .. } => refs.len(),
        })
        .collect();
    let needs_extended = counts.iter().any(|&n| n > u16::MAX as usize);
    let Scratch { payloads, meta, out: assembled } = scratch;
    meta.clear();
    // Payload i belongs to ordered[i]: rebuild the scratch payloads in order.
    while payloads.len() < ordered.len() {
        payloads.push(Vec::new());
    }
    for (i, (_, layer)) in ordered.iter().enumerate() {
        let out = &mut payloads[i];
        out.clear();
        let count = match layer {
            MixedLayer::Full(full) => {
                write_full_layer_payload(full, out);
                full.features.len()
            }
            MixedLayer::Slim { refs, parts, coords } => {
                let payload = assemble_slim_payload(refs, parts, coords)?;
                out.extend_from_slice(&payload);
                refs.len()
            }
        };
        meta.push((ordered[i].0, count as u32));
    }
    let mut index_end = BODY_HEADER_LEN + ordered.len() * LAYER_INDEX_LEN;
    if needs_extended {
        index_end += ordered.len() * 4;
    }
    let mut offset = align4(index_end);
    assembled.clear();
    assembled.extend_from_slice(b"MBD");
    assembled.push(crate::mamaps::header::FORMAT_VERSION_V8);
    assembled.extend_from_slice(&0u32.to_le_bytes());
    assembled.extend_from_slice(&extent.to_le_bytes());
    assembled.push(ordered.len() as u8);
    let mut body_flags = if needs_extended { BODY_FLAG_EXTENDED_COUNTS } else { 0 };
    if heightmap.is_some() {
        body_flags |= BODY_FLAG_HEIGHTMAP;
    }
    if convention.is_some() {
        body_flags |= BODY_FLAG_ROAD_LANES;
    }
    assembled.push(body_flags);
    assembled.extend_from_slice(&0u32.to_le_bytes());
    for (i, ((layer_id, _), count)) in ordered.iter().zip(counts.iter()).enumerate() {
        assembled.push(*layer_id);
        assembled.push(match ordered[i].1 {
            MixedLayer::Full(_) => crate::mamaps::read::SLIM_INDEX_FULL,
            MixedLayer::Slim { .. } => crate::mamaps::read::SLIM_INDEX_SLIM,
        });
        let fc: u16 = if needs_extended {
            (*count as usize).min(0xFFFE) as u16
        } else {
            u16::try_from(*count).expect("record count fits u16 in non-extended body")
        };
        assembled.extend_from_slice(&fc.to_le_bytes());
        assembled.extend_from_slice(&(offset as u32).to_le_bytes());
        assembled.extend_from_slice(&(payloads[i].len() as u32).to_le_bytes());
        offset += payloads[i].len();
    }
    if needs_extended {
        for count in &counts {
            assembled.extend_from_slice(&(*count as u32).to_le_bytes());
        }
    }
    while assembled.len() % 4 != 0 {
        assembled.push(0);
    }
    for payload in &payloads[..ordered.len()] {
        assembled.extend_from_slice(payload);
    }
    if convention.is_some() || heightmap.is_some() {
        while assembled.len() % 4 != 0 {
            assembled.push(0);
        }
    }
    if let Some(c) = convention {
        assembled.push(c.to_byte());
        while assembled.len() % 4 != 0 {
            assembled.push(0);
        }
    }
    if let Some(grid) = heightmap {
        serialize_heightmap(grid, assembled);
    }
    let raw_len = assembled.len() as u32;
    assembled[4..8].copy_from_slice(&raw_len.to_le_bytes());
    Ok(assembled)
}
