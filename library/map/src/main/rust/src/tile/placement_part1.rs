/// Build a curved label's per-glyph screen collision boxes from its tile-local layout.
///
/// The segmented analogue of [`anchored_rect`]: each [`CurvedGlyph`](crate::tess::text::CurvedGlyph)
/// pen point is projected to screen px through `tile_clip` (the same pitch-aware projection the
/// point box uses, so the two agree at every pitch), and the glyph becomes an [`Obb`] oriented to
/// screen-space tangent, sized by its own advance and the cap height at `text_px`, and inflated by
/// `pad_px`. The result feeds [`place_segmented`] as a [`SegmentedCandidate`]'s `boxes`.
pub fn curved_boxes(
    placements: &[crate::tess::text::CurvedGlyph],
    tile_clip: [f32; 16],
    extent_wh: (u32, u32),
    text_px: f32,
    pad_px: f32,
) -> Vec<Obb> {
    // Pens project with the perspective divide like the point boxes, so a curved label and a
    // point label collide where they draw under tilt. The tangent below stays the linear-part
    // approximation: exact only at pitch 0, but a glyph-scale segment is short enough that the
    // divide varies negligibly across it.
    let (w, h) = (extent_wh.0 as f32, extent_wh.1 as f32);
    let em = crate::tile::glyph::UP_EM as f32;
    let cap_px = crate::tess::text::CAP_HEIGHT_EM * text_px;
    let mut out = Vec::with_capacity(placements.len());
    for cg in placements {
        let advance_px = cg.glyph.advance / em * text_px;
        if advance_px <= 0.0 {
            continue;
        }
        // The pen projects with the perspective divide like a point anchor; a glyph on or
        // behind the eye has no screen position and is skipped rather than boxed.
        let Some((sx, sy)) = project_to_screen(tile_clip, cg.pen, extent_wh) else { continue };
        // Screen-space tangent: the tile-local tangent through the clip matrix's linear part, then
        // clip → px scaling, normalised. Falls back to axis-aligned if it degenerates.
        let dcx = tile_clip[0] * cg.tangent.0 + tile_clip[4] * cg.tangent.1;
        let dcy = tile_clip[1] * cg.tangent.0 + tile_clip[5] * cg.tangent.1;
        let (mut tx, mut ty) = (dcx * w, dcy * h);
        let len = (tx * tx + ty * ty).sqrt();
        if len > 1e-6 {
            tx /= len;
            ty /= len;
        } else {
            tx = 1.0;
            ty = 0.0;
        }
        // Box centre is half an advance along the tangent from the pen origin.
        out.push(Obb {
            cx: sx + tx * advance_px * 0.5,
            cy: sy + ty * advance_px * 0.5,
            hx: advance_px * 0.5 + pad_px,
            hy: cap_px * 0.5 + pad_px,
            cos: tx,
            sin: ty,
        });
    }
    out
}
