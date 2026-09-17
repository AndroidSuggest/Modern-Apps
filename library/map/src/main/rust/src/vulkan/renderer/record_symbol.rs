//! Symbol emit + draw: the icon/text push blocks and their draws.
//!
//! Split from `record_extra3.rs` (file-length limit). `record_symbol` resolves
//! the tile, its batches and its billboard inputs; this function builds the
//! globe/flat push blocks and issues the draws. Same draws, moved verbatim.
use super::{argb_to_rgba, scale_alpha, Renderer};
use crate::camera::Camera;
use crate::style::{Layer, LayerKind, Palette};
use crate::vulkan::pipeline::Push;
use ash::vk;

impl Renderer {
    /// Build the icon + text push blocks for one tile's symbol batches and draw
    /// them. See [`record_symbol`](Self::record_symbol) for the emit half.
    #[allow(clippy::too_many_arguments)]
    pub(super) unsafe fn record_symbol_emit(
        &mut self,
        command_buffer: vk::CommandBuffer,
        layer: &Layer,
        camera: &Camera,
        palette: Palette,
        tz: u8,
        tile_clip: [f32; 16],
        _tile_span_px: f32,
        billboard_flag: f32,
        ortho2x2: [f32; 4],
        batches: &[(f32, Vec<f32>, Vec<u32>)],
        icon_vertices: &[f32],
        icon_indices: &[u32],
        glyph_set: vk::DescriptorSet,
        submitted: &mut usize,
        bound: &mut Option<LayerKind>,
        // This frame's globe flag: globe symbol/icon pipelines + sphere push, or
        // the flat path bit-identically above.
        globe: bool,
    ) {
        let halo = argb_to_rgba(layer.halo_color(palette));
        let color = argb_to_rgba(scale_alpha(
            layer.color(palette),
            layer.opacity_at(camera.zoom),
        ));
        let sdf_per_em = crate::tile::glyph::atlas().sdf_per_em;

        // Icons first, so the label's halo paints over the icon's edge rather than under
        // it — the order MapLibre draws them in. On the globe both go through the
        // globe pipelines with the sphere push (centre + radius); flat path below
        // is unchanged.
        let (globe_misc, globe_morph) = if globe {
            let r = crate::camera::globe_radius(camera.zoom) as f32;
            (
                [camera.center_lon as f32, camera.center_lat as f32, 0.0, 0.0],
                [camera.width_dp / 2.0, camera.height_dp / 2.0, r, 0.0],
            )
        } else {
            (
                [camera.tile_span_dp(tz) as f32, 0.0, 0.0, 0.0],
                [ortho2x2[0], ortho2x2[1], ortho2x2[2], ortho2x2[3]],
            )
        };
        if let Some(sprite_set) = self.sprite_set.filter(|_| !icon_indices.is_empty()) {
            let push = Push {
                tile_to_clip: tile_clip,
                // Only the alpha is read by `sprite.frag`: an icon draws in its own
                // colours, since the reference sets no `icon-color`.
                color,
                // `sprite.frag` reads none of `line`; `w` is the billboard flag the shared vertex
                // shader reads, so an icon stands up under tilt on the same terms as its label.
                // On the globe the flag is clear: `symbol_globe.vert` bends instead.
                line: [0.0, 0.0, 0.0, if globe { 0.0 } else { billboard_flag }],
                // `misc.x` is the tile's world-px span (Dp): the anchor-height scale the
                // billboard shader multiplies by. (Was the device-px span; the vertex
                // shader documented it unused and nothing reads it.)
                misc: globe_misc,
                // The pitch-0 linear 2x2, as for the text below — the same matrix, so the icon and
                // the name beside it resolve their screen offsets identically.
                morph: globe_morph,
            };
            self.draw_symbol_batch(
                command_buffer,
                if globe {
                    self.pipelines.icon_globe
                } else {
                    self.pipelines.icon
                },
                sprite_set,
                icon_vertices,
                icon_indices,
                &push,
                submitted,
            );
            // The icon pipeline shares `LayerKind::Symbol`, so this still forces the
            // fill/line path to rebind. The symbol pipeline is bound again immediately
            // below whenever there is any text — and a label with an icon always has
            // text, because `shape_label` returns nothing for an empty name.
            *bound = Some(LayerKind::Symbol);
        }

        for (text_px, vertices, indices) in batches {
            // Halo width from the style (authored text-halo-width, 1px), in device px
            // like the text size beside it; text color + opacity per palette.
            let push = Push {
                tile_to_clip: tile_clip,
                color,
                line: [
                    *text_px,
                    layer.halo_width * camera.density,
                    sdf_per_em,
                    if globe { 0.0 } else { billboard_flag },
                ],
                // `misc.x` is the tile's world-px span (Dp): the anchor-height scale.
                // `yzw` stay the halo rgb the fragment shader reads. On the globe the
                // centre rides here instead (see above); halo rgb is then unread, and
                // labels draw without halo rather than with a wrong-colour one.
                misc: if globe {
                    globe_misc
                } else {
                    [camera.tile_span_dp(tz) as f32, halo[0], halo[1], halo[2]]
                },
                // Repurposed for the symbol billboard pipeline: the pitch-0 tile matrix's linear
                // 2x2, so the shader can add a screen-constant glyph offset under tilt. The symbol
                // fragment shader does not read `morph`, so this collides with nothing.
                morph: globe_morph,
            };
            self.draw_symbol_batch(
                command_buffer,
                if globe {
                    self.pipelines.symbol_globe
                } else {
                    self.pipelines.symbol
                },
                glyph_set,
                vertices,
                indices,
                &push,
                submitted,
            );
            *bound = Some(LayerKind::Symbol);
        }
    }
}
