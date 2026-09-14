use super::model::{BUILDING_ATTRS_LEN, BuildingAttrs, Layer, ROOF_ORIENT_MAX, ROOF_SHAPE_MAX};
use super::tables::align4;
use crate::proto::{Result, err};

/// Parse the S3DB building attribute table: the same framing as [`super::tables::parse_ids`] — a `u32` entry
/// count, then per entry a `u8` layer id, three reserved bytes, a `u32` record count and that many
/// fixed [`BUILDING_ATTRS_LEN`]-byte [`BuildingAttrs`] records. Returns the table and the bytes
/// consumed (including 4-byte alignment padding).
///
/// Validated against `layers` exactly as the id and turn-lane tables are: an entry must name a
/// layer the body carries, entries ascend and are distinct by layer id, and a layer's attr vector
/// is exactly as long as its feature vector — a mismatch would misattribute every building's
/// height after the first. `roof_shape`, `roof_orientation` and the reserved bytes are checked so a
/// corrupt record fails here rather than extruding a wrong shape on device.
pub(crate) fn parse_buildings(buf: &[u8], layers: &[Layer]) -> Result<(Vec<(u8, Vec<BuildingAttrs>)>, usize)> {
    if buf.len() < 4 {
        return err("a .mamaps building table ends before its count");
    }
    let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    if count > layers.len() {
        return err(format!(
            "a .mamaps building table has {count} entries for a body with {} layer(s)",
            layers.len(),
        ));
    }
    let mut at = 4usize;
    let mut table = Vec::with_capacity(count);
    let mut previous: Option<u8> = None;
    for _ in 0..count {
        if at + 8 > buf.len() {
            return err("a .mamaps building table ends inside an entry header");
        }
        let layer_id = buf[at];
        if buf[at + 1] != 0 || u16::from_le_bytes([buf[at + 2], buf[at + 3]]) != 0 {
            return err("a .mamaps building table entry has non-zero reserved bytes");
        }
        let attrs_len =
            u32::from_le_bytes([buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7]]) as usize;
        at += 8;
        if previous.is_some_and(|p| layer_id <= p) {
            return err("a .mamaps building table's entries are not ordered by layer id");
        }
        previous = Some(layer_id);
        let Some(layer) = layers.iter().find(|l| l.layer_id == layer_id) else {
            return err(format!(
                "a .mamaps building table names layer {layer_id}, which the body does not carry"
            ));
        };
        if attrs_len != layer.features.len() {
            return err(format!(
                "a .mamaps building table gives layer {layer_id} {attrs_len} record(s) for {} \
                 feature(s)",
                layer.features.len(),
            ));
        }
        // Bounded against the slice before allocating, the same discipline `parse_ids` applies.
        let bytes = attrs_len.checked_mul(BUILDING_ATTRS_LEN).ok_or_else(|| {
            crate::proto::Error("a .mamaps building table's entry overflows".to_string())
        })?;
        if at + bytes > buf.len() {
            return err("a .mamaps building table's records run past the table");
        }
        let mut attrs = Vec::with_capacity(attrs_len);
        for i in 0..attrs_len {
            let o = at + i * BUILDING_ATTRS_LEN;
            let roof_shape = buf[o + 6];
            if roof_shape > ROOF_SHAPE_MAX {
                return err(format!("a .mamaps building record has roof shape {roof_shape}"));
            }
            let roof_orientation = buf[o + 8];
            if roof_orientation > ROOF_ORIENT_MAX {
                return err(format!(
                    "a .mamaps building record has roof orientation {roof_orientation}"
                ));
            }
            if buf[o + 9] != 0 || buf[o + 10] != 0 || buf[o + 11] != 0 {
                return err("a .mamaps building record has non-zero reserved bytes");
            }
            attrs.push(BuildingAttrs {
                height: u16::from_le_bytes([buf[o], buf[o + 1]]),
                min_height: u16::from_le_bytes([buf[o + 2], buf[o + 3]]),
                roof_height: u16::from_le_bytes([buf[o + 4], buf[o + 5]]),
                roof_shape,
                roof_direction: buf[o + 7],
                roof_orientation,
                building_colour: u32::from_le_bytes([
                    buf[o + 12],
                    buf[o + 13],
                    buf[o + 14],
                    buf[o + 15],
                ]),
                roof_colour: u32::from_le_bytes([
                    buf[o + 16],
                    buf[o + 17],
                    buf[o + 18],
                    buf[o + 19],
                ]),
            });
        }
        at += bytes;
        table.push((layer_id, attrs));
    }
    let aligned = align4(at);
    if aligned > buf.len() {
        return err("a .mamaps building table's padding runs past the body");
    }
    Ok((table, aligned))
}

/// Serialise an S3DB building attribute table, 4-byte aligned. The inverse of [`parse_buildings`].
pub(crate) fn serialize_buildings(buildings: &[(u8, Vec<BuildingAttrs>)], out: &mut Vec<u8>) {
    out.extend_from_slice(&(buildings.len() as u32).to_le_bytes());
    for (layer_id, entries) in buildings {
        out.push(*layer_id);
        out.extend_from_slice(&[0u8; 3]);
        out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        for a in entries {
            out.extend_from_slice(&a.height.to_le_bytes());
            out.extend_from_slice(&a.min_height.to_le_bytes());
            out.extend_from_slice(&a.roof_height.to_le_bytes());
            out.push(a.roof_shape);
            out.push(a.roof_direction);
            out.push(a.roof_orientation);
            out.extend_from_slice(&[0u8; 3]);
            out.extend_from_slice(&a.building_colour.to_le_bytes());
            out.extend_from_slice(&a.roof_colour.to_le_bytes());
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
}
