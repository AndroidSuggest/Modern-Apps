/// Bytes of push constant: `mat4` + four `vec4`. Exactly 128, the guaranteed minimum.
pub const PUSH_CONSTANT_BYTES: u32 = 64 + 16 + 16 + 16 + 16;

/// The default `Push::morph`: fully present, no LOD cross-fade. Every draw but WS-D's uses it.
pub const MORPH_NONE: [f32; 4] = [1.0, 0.0, 0.0, 0.0];

/// `Push::line[2]` for a ribbon that carries no markings at all — see the [`Push`] doc comment.
///
/// Below the `[-1, +1]` a real centre-line `t` is bounded to, so it cannot collide with one.
/// `road_surface.frag` tests against its own `NO_MARKINGS_BELOW` of -1.5, halfway between this and
/// the nearest legal split, so neither side is sensitive to the exact value.
pub const NO_MARKINGS: f32 = -2.0;

/// The push constant block, matching the `Push` block the shaders declare.
///
/// `repr(C)` so the field order is the declaration order, which is what the SPIR-V
/// offsets assume.
///
/// # Field map (the shared contract A/B/C/D/E/G build on)
///
/// | field          | bytes   | meaning                                                        |
/// |----------------|---------|---------------------------------------------------------------|
/// | `tile_to_clip` | 0..64   | column-major tile-local `(u, v, height, 1)` → clip            |
/// | `color`        | 64..80  | linear RGBA, 0..1                                              |
/// | `line`         | 80..96  | `half_width_px, gap_half_px, dash_on, dash_off`               |
/// | `misc`         | 96..112 | `tile_px, edge_aa, lateral_px, clock_seconds`                 |
/// | `morph`        | 112..128| `opacity(WS-D), reserved, reserved, reserved`                 |
///
/// `misc.w` is the per-frame clock (seconds); `morph.x` is the per-tile opacity/morph factor
/// (1.0 = fully present) reserved for WS-D. Both default to a value that leaves the flat 2D
/// output byte-identical (`clock` is ignored by the current shaders, `morph.x` is 1.0).
///
/// # The ribbon draw reads three of these slots differently
///
/// [`ribbon`](Pipelines::ribbon) is a road carriageway rather than a stroke, so `gap_half_px`,
/// `dash_on`, `dash_off` and `lateral_px` are all meaningless to it: it has no casing bands, its
/// dash pattern is derived from the lane width, and a carriageway is never fanned sideways. Those
/// dead slots carry the markings contract instead, which is why the block does not have to grow
/// past the guaranteed 128 bytes to gain a whole new layer.
///
/// | slot     | line pipeline | ribbon pipeline                                                |
/// |----------|---------------|-----------------------------------------------------------------|
/// | `line.x` | half width px | carriageway half-width px — unchanged meaning                    |
/// | `line.y` | gap half px   | lane count, both directions together                             |
/// | `line.z` | dash on       | `t` of the forward/backward split, the centre line's position     |
/// | `line.w` | dash off      | 1.0 on a one-way, which suppresses the centre line entirely      |
/// | `misc.z` | lateral px    | 1.0 for a yellow centre line, 0.0 for a white one                |
///
/// `line.z` is an across-road coordinate in `[-1, +1]`, not a lane index, so an odd split needs no
/// special case: the shader takes the lane boundary nearest to it. `t` runs -1 at the left kerb to
/// +1 at the right, so with `n` of the `line.y` lanes lying on the -1 side the split is
///
/// ```text
/// line.z = 2.0 * n / lane_count - 1.0
/// ```
///
/// It is ignored when `line.w` is set, so a one-way may push anything there.
///
/// # `line.z` also carries "no markings at all", as [`NO_MARKINGS`]
///
/// Because a real split is an across-road coordinate it cannot leave `[-1, +1]`, so a value below
/// that range is free to mean something else. [`NO_MARKINGS`] (-2.0) tells `road_surface.frag` to
/// paint the asphalt and nothing on it — no edge lines, no dividers, no centre line.
///
/// The junction layer's lane connectors use it. A connector is a notional path across an
/// intersection, and road paint does not mark those out on the ground; it is also drawn in the
/// carriageway's own colour, so where it overlies the road its asphalt is invisible and its
/// markings are the whole of what shows. Twelve connectors at a crossroads then read as a tangle
/// of hairlines rather than as a widening of the junction.
///
/// Chosen over a new slot or a wider block deliberately: a connector is always a one-way, so
/// `line.z` is *already* dead on exactly the draws this applies to — the shader has never read it
/// there. That makes the reuse something the code enforces rather than a convention a reader has
/// to hold, and it costs no vertex attribute, no `Push` growth past the guaranteed 128 bytes, and
/// no archive format change.
///
/// `misc.z` is the driving convention, resolved per tile at archive build time — the Americas paint
/// the line between opposing traffic yellow and most of the rest of the world paints it white. It
/// is a flag rather than a colour because the marking palette belongs to the shader alongside the
/// white it has to match, not to a per-draw push.
///
/// The remaining slots keep their usual meaning and must still be filled: `color` is the asphalt
/// (the markings are the shader's own), `misc.x` the tile's pixel span, `misc.y` the edge-AA flag,
/// and `morph` [`MORPH_NONE`]. `misc.w` is unread by the ribbon — its markings are static, so there
/// is deliberately no dash phase the way the traffic draw has one.
///
/// Both flags are compared against 0.5, so any nonzero-ish float reads as set; push exactly 0.0 or
/// 1.0 rather than relying on that.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct Push {
    /// Column-major tile-local `(u, v, height, 1)` to clip space. `height` (the vertex z) is 0
    /// for every flat 2D layer and a real world-px height for buildings/terrain.
    pub tile_to_clip: [f32; 16],
    /// Linear RGBA, 0..1.
    pub color: [f32; 4],
    /// `half_width_px, gap_half_px, dash_on, dash_off`. The ribbon pipeline reads
    /// `half_width_px, lane_count, centre_t, oneway` instead; see the type doc.
    pub line: [f32; 4],
    /// `tile_px, edge_aa, lateral_px, clock_seconds`. `misc.w` is the per-frame clock forwarded
    /// from the host's `frameTimeNanos` (see `camera::Camera::time_seconds`). The ribbon pipeline
    /// reads `misc.z` as the centre-line colour flag rather than a lateral shift.
    pub misc: [f32; 4],
    /// Per-draw animation slot. `morph.x` is the per-tile opacity/morph factor (1.0 = fully
    /// present) reserved for WS-D's LOD cross-fade; `y`/`z`/`w` are reserved. Appended past
    /// `misc`, so a shader that never declares it keeps its existing offsets and behaviour.
    pub morph: [f32; 4],
}

impl Push {
    /// The block as bytes, for `vkCmdPushConstants`.
    pub fn as_bytes(&self) -> &[u8] {
        // Safe: `repr(C)` POD with no padding, read as its own length.
        unsafe {
            std::slice::from_raw_parts(
                self as *const Push as *const u8,
                std::mem::size_of::<Push>(),
            )
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_push_block_is_inside_the_guaranteed_limit() {
        // 128 bytes is the minimum `maxPushConstantsSize` the spec requires, so staying
        // at or under it means no device can reject this.
        assert_eq!(std::mem::size_of::<Push>() as u32, PUSH_CONSTANT_BYTES);
        assert!(PUSH_CONSTANT_BYTES <= 128, "{PUSH_CONSTANT_BYTES} exceeds the guaranteed 128");
    }

    #[test]
    fn the_push_block_has_no_padding() {
        // The shader reads it at fixed offsets, so a gap Rust inserted would silently
        // shift the colour and the widths.
        assert_eq!(std::mem::size_of::<Push>(), 64 + 16 + 16 + 16 + 16);
        assert_eq!(std::mem::align_of::<Push>(), 4);
    }
}
