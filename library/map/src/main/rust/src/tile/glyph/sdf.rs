use super::metrics::{ATLAS_COLS, ATLAS_PX, CELL_PX, SDF_SPREAD_PX};
use super::placement::Placement;

/// 8SSEDT-lite: exact brute-force distance transform over a small cell.
///
/// Cells are ≤128px; brute force is ~16k×256 ops per glyph worst case, once per
/// process — simpler than Felzenszwalb and exact. Inside/outside comes from the
/// 50% coverage threshold; distance is normalised by `SDF_SPREAD_PX`.
///
/// `placement` is where the caller has decided the bitmap sits in the cell, so the
/// same rect `ink_uv` addresses is the rect sampled here.
pub(super) fn sdf_from_coverage(coverage: &[u8], w: u32, h: u32, placement: Placement) -> Vec<u8> {
    let inside = |x: i32, y: i32| -> bool {
        x >= 0
            && y >= 0
            && (x as u32) < w
            && (y as u32) < h
            && coverage[(y as u32 * w + x as u32) as usize] >= 128
    };
    let spread = SDF_SPREAD_PX as f32;
    let mut out = vec![0u8; (CELL_PX * CELL_PX) as usize];
    let Placement {
        scale,
        ox,
        oy,
        dw,
        dh,
    } = placement;
    let sample = |cx: u32, cy: u32| -> bool {
        if cx < ox || cy < oy || cx >= ox + dw || cy >= oy + dh {
            return false;
        }
        let gx = ((cx - ox) as f32 / scale) as i32;
        let gy = ((cy - oy) as f32 / scale) as i32;
        inside(gx, gy)
    };
    // Search radius in *cell* px: the spread in glyph px scaled up. Distances are
    // divided back down by the same scale so the SDF is in glyph px everywhere.
    let r_cell = ((SDF_SPREAD_PX as f32 * scale).ceil() as u32).max(2);
    for cy in 0..CELL_PX {
        for cx in 0..CELL_PX {
            let me = sample(cx, cy);
            // Inside the stroke the distance is capped by the search radius, so a
            // thick stem never reaches full bright — but the shader only needs the
            // 0.5 crossing plus a smoothing band either side. Scale the inside
            // distance so the stem centre hits 1.0: full dynamic range at the edge.
            let mut best = spread;
            for dy in -(r_cell as i32)..=(r_cell as i32) {
                for dx in -(r_cell as i32)..=(r_cell as i32) {
                    let d_cell = ((dx * dx + dy * dy) as f32).sqrt();
                    if d_cell < best * scale
                        && sample(cx.saturating_add_signed(dx), cy.saturating_add_signed(dy)) != me
                    {
                        best = d_cell / scale;
                    }
                }
            }
            let value = if me {
                // Inside: 0.5 at the edge → 1.0 one px in. Stems thicker than 2px
                // saturate, which is correct for an SDF edge function.
                (0.5 + (best / 2.0).min(0.5)).clamp(0.0, 1.0)
            } else {
                (0.5 - best / (2.0 * spread)).clamp(0.0, 1.0)
            };
            out[(cy * CELL_PX + cx) as usize] = (value * 255.0) as u8;
        }
    }
    out
}

/// Expand one R8 SDF row into RGBA8 for upload: `[v, v, v, v]` per texel.
///
/// THE upload contract the fragment shader depends on (`shaders/symbol.frag`
/// samples `.r`). The SDF value goes in ALL FOUR channels deliberately: the
/// Stage-B device verdict proved a running binary sampling `.r == 0` with the
/// SDF in `.a` only — a channel divorce no in-tree step can produce (the view
/// uses identity swizzle, the staging copy cannot reorder UNORM channels), so
/// it came from a stale binary predating the RGBA8 expansion. Writing `v`
/// everywhere makes `.r` correct under ANY single-channel placement the bytes
/// ever had, and any future swap that breaks it fails
/// `the_rgba8_expansion_carries_sdf_in_every_channel` instead of the capture.
///
/// Lives here (host-compiled) rather than in `vulkan::images` (Android-only) so
/// the contract is testable without a device.
pub fn expand_sdf_r8_to_rgba8(r8: &[u8]) -> Vec<u8> {
    r8.iter().flat_map(|&v| [v, v, v, v]).collect()
}

/// Copy one cell's SDF into the atlas image.
pub(super) fn blit_cell(atlas: &mut [u8], cell: u32, sdf: &[u8]) {
    let col = cell % ATLAS_COLS;
    let row = cell / ATLAS_COLS;
    for cy in 0..CELL_PX {
        let dst = ((row * CELL_PX + cy) * ATLAS_PX + col * CELL_PX) as usize;
        let src = (cy * CELL_PX) as usize;
        atlas[dst..dst + CELL_PX as usize].copy_from_slice(&sdf[src..src + CELL_PX as usize]);
    }
}
