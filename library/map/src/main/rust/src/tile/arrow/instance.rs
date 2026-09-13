use super::kind::TurnArrow;

/// One arrow to draw for one lane, in tile-local coordinates.
///
/// This is where the road *ends*, not where the glyph sits: the setback that puts the arrow back on
/// the approach is a ground distance, and a ground distance cannot be turned into tile-local units
/// without the tile's zoom and latitude, which this pass is not given. [`super::place::placed_anchor`] applies it
/// from `junction_end`, `angle` and `run`. The renderer then pushes the glyph sideways onto its
/// lane of the carriageway, which is why the instance carries `ordinal`, `count` and `fan_offset`
/// rather than a baked offset (the offset is a screen measurement that changes with zoom). `angle`
/// is the road's heading at the end, in radians, so the glyph points the way the traffic flows.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ArrowInstance {
    /// The road's junction end on the centreline, tile-local. Not the glyph's position — see
    /// [`super::place::placed_anchor`].
    pub junction_end: (f32, f32),
    /// Heading of travel at the junction, radians (atan2(dy, dx)).
    pub angle: f32,
    /// How far back along `angle` the approach runs straight, tile-local: the furthest the setback
    /// may travel before it would leave the road. See [`super::place::straight_run`].
    pub run: f32,
    /// This lane's index from the left, and how many lanes this direction has, for the lateral
    /// fan. The count comes from the road's split rather than from the mask list, so the fan and
    /// the centre line divide the carriageway the same way.
    pub ordinal: u8,
    pub count: u8,
    /// The shift that puts this direction's fan on its own half of the carriageway, in lane
    /// widths, positive to the right of travel — see [`super::place::fan_offset`]. Zero on a one-way and
    /// wherever the road's total lane count is unknown, which keeps the fan centred.
    pub fan_offset: f32,
    /// The glyph to draw.
    pub arrow: TurnArrow,
    /// Absolute heading of the exit this lane leads to, radians, when the archive knows it.
    ///
    /// The angle the glyph actually bends through is `exit_angle - angle`, which is whatever the
    /// two roads happen to meet at. `None` means the archive carries only the `LANE_*` class and
    /// no geometry, and the glyph falls back to [`super::angle::nominal_turn_angle`]; see [`super::angle::turn_angle`].
    pub exit_angle: Option<f32>,
    /// Traffic keeps left on this tile, which is the side a U-turn is made from — see
    /// [`super::angle::nominal_turn_angle`], the only angle the convention changes.
    pub left_hand: bool,
}
