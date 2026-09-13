use super::metrics::{ATLAS_COLS, ATLAS_PX, CELL_PX, SDF_SPREAD_PX, UvRect};

/// Where a glyph's bitmap sits inside its atlas cell.
///
/// The single source of truth the quad and the UV rect are both derived from. Keeping
/// them apart is what made every glyph draw at roughly `dw / CELL_PX` of its proper
/// size inside a correctly-spaced advance: the ink is centred in the cell, but the UV
/// rect used to address the *whole* cell while the quad was sized to the glyph's true
/// metrics, so the shader stretched a half-empty cell over it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(super) struct Placement {
    /// Bitmap-to-cell scale. 1.0 unless the glyph is too big to fit with its margin.
    pub(super) scale: f32,
    /// Ink origin within the cell, in cell px.
    pub(super) ox: u32,
    pub(super) oy: u32,
    /// Ink size within the cell, in cell px.
    pub(super) dw: u32,
    pub(super) dh: u32,
}

/// Centre a `w`x`h` bitmap in a cell, leaving `SDF_SPREAD_PX` of margin all round.
///
/// The margin is what the distance transform writes its gradient into, so it must be
/// reserved before anything is rasterised. `ox`/`oy` are therefore always at least
/// `SDF_SPREAD_PX`, which is what lets `ink_uv` pad outwards without leaving the cell.
pub(super) fn place_in_cell(w: u32, h: u32) -> Placement {
    let avail = (CELL_PX - 2 * SDF_SPREAD_PX) as f32;
    let scale = (avail / w.max(h).max(1) as f32).min(1.0);
    let dw = ((w as f32 * scale).round() as u32).clamp(1, avail as u32);
    let dh = ((h as f32 * scale).round() as u32).clamp(1, avail as u32);
    Placement { scale, ox: (CELL_PX - dw) / 2, oy: (CELL_PX - dh) / 2, dw, dh }
}

/// The atlas rect covering a glyph's ink plus its spread margin.
///
/// No half-texel inset: the rect is strictly inside its cell by construction
/// (`ox >= SDF_SPREAD_PX`), so there is no neighbouring cell to bleed from.
pub(super) fn ink_uv(cell: u32, placement: Placement) -> UvRect {
    let col = cell % ATLAS_COLS;
    let row = cell / ATLAS_COLS;
    let (cx, cy) = (col * CELL_PX, row * CELL_PX);
    let pad = SDF_SPREAD_PX;
    let n = ATLAS_PX as f32;
    UvRect {
        u0: (cx + placement.ox - pad) as f32 / n,
        v0: (cy + placement.oy - pad) as f32 / n,
        u1: (cx + placement.ox + placement.dw + pad) as f32 / n,
        v1: (cy + placement.oy + placement.dh + pad) as f32 / n,
    }
}
