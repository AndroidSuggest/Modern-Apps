/// The clip-space position the billboard vertex shader (`symbol_billboard.vert`) computes for one
/// text glyph vertex, mirrored on the CPU so the billboard math is testable without a GPU.
///
/// `tile_to_clip` is the per-tile matrix — perspective when the camera is pitched. `ortho2x2` is
/// the **pitch-0** tile matrix's linear part `[m0, m1, m4, m5]` (column-major), which the renderer
/// passes to the shader in the push `morph` slot. `billboard` is the per-draw flag (`Push::line.w`):
/// off when the camera is level, where the vertex is drawn straight through `tile_to_clip` — so the
/// flat map is byte-identical to the pre-billboard path.
///
/// When on, the label's ground `anchor` is projected through the perspective matrix and the glyph's
/// tile-local offset from it is added as a screen-constant clip offset (scaled by the anchor's `w`),
/// so the glyph stays pinned to the ground point but faces the screen upright at any pitch. A curved
/// label passes `position == anchor` (offset zero), which collapses this to the plain on-ground
/// projection — curved labels stay map-aligned.
///
/// # The single derivation for a symbol push's two billboard values
///
/// The push's `line.w` ([`billboard_push_flag`]) and `morph` ([`billboard_ortho2x2`]) are one value
/// with two copies, not two decisions: a POI icon and its label must be built from the *same* flag
/// and the *same* matrix, or the pictogram slides off its name under tilt. [`icon_push`] is the
/// host-testable record of that: the renderer builds an icon draw from it rather than filling the
/// two slots by hand, so reverting either value fails [`super::tests::an_icon_push_built_from_parts_matches_icon_push`]
/// instead of silently flattening icons back onto the ground.
pub fn billboard_clip(
    tile_to_clip: &[f32; 16],
    ortho2x2: [f32; 4],
    position: (f32, f32),
    anchor: (f32, f32),
    billboard: bool,
) -> [f32; 4] {
    let m = tile_to_clip;
    // `tile_to_clip * vec4(p, 0, 1)`: the same projection the flat symbol path uses (height 0).
    let project = |p: (f32, f32)| {
        [
            m[0] * p.0 + m[4] * p.1 + m[12],
            m[1] * p.0 + m[5] * p.1 + m[13],
            m[2] * p.0 + m[6] * p.1 + m[14],
            m[3] * p.0 + m[7] * p.1 + m[15],
        ]
    };
    if !billboard {
        return project(position);
    }
    let a = project(anchor);
    let off = (position.0 - anchor.0, position.1 - anchor.1);
    // Column-major 2x2 (pitch-0 linear part) times the tile-local offset → screen-constant clip.
    let off_clip = (
        ortho2x2[0] * off.0 + ortho2x2[2] * off.1,
        ortho2x2[1] * off.0 + ortho2x2[3] * off.1,
    );
    [a[0] + off_clip.0 * a[3], a[1] + off_clip.1 * a[3], a[2], a[3]]
}

/// The per-draw billboard flag the symbol shaders read as `Push::line.w`.
///
/// 1.0 whenever the camera is pitched (icons and their labels stand up under tilt), 0.0 at
/// pitch 0 (the shader draws straight through `tile_to_clip`, byte-identical to the flat path).
/// Derived from the pitch alone so the icon draw and the text draw beside it cannot disagree.
pub fn billboard_push_flag(pitch_deg: f64) -> f32 {
    if pitch_deg != 0.0 { 1.0 } else { 0.0 }
}

/// The pitch-0 tile matrix's linear 2x2 `[m0, m1, m4, m5]` (column-major) for one tile.
///
/// The shader needs the screen-constant offset the ortho matrix *would* give a corner while the
/// anchor itself goes through the perspective matrix — so this is derived from the pitch-0
/// matrix, never from the perspective one, and the icon draw and the text draw share it.
pub fn billboard_ortho2x2(flat_tile_to_clip: &[f32; 16]) -> [f32; 4] {
    [flat_tile_to_clip[0], flat_tile_to_clip[1], flat_tile_to_clip[4], flat_tile_to_clip[5]]
}

/// The two billboard values of an icon push as one `(line.w, morph)` pair.
///
/// The icon pipeline's `Push`: `line` is unread by `sprite.frag` (an icon draws in its own
/// colours, only the alpha is read) and `misc` carries the tile span, so `line.w` and `morph`
/// exist in this draw for exactly one job — standing the icon up under tilt on the same terms
/// as its label. Keeping them one derivation means reverting either one breaks the equality
/// with the hand-built push rather than silently flattening the icon.
///
/// `tile_to_clip` here is the *pitch-0* matrix only insofar as `morph` is read from it; the
/// caller passes the real per-tile matrix alongside in the push itself.
pub fn icon_push_billboard(flat_tile_to_clip: &[f32; 16], pitch_deg: f64) -> (f32, [f32; 4]) {
    (billboard_push_flag(pitch_deg), billboard_ortho2x2(flat_tile_to_clip))
}
