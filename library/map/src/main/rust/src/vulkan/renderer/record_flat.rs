use super::fog::{apply_fog, fog_factor};
use super::{
    argb_to_rgba, scale_alpha, Renderer, ResidentTile, ARROW_COLOR, ARROW_DP,
    BUILDINGS_DRAW_MIN_ZOOM, IDENTITY, PUCK_COLOR, PUCK_CONE_DP, PUCK_CONE_HALF_STROKE_DP,
    PUCK_DOT_DP, PUCK_QUAD_DP, PUCK_RIM_DP, QUAD_INDICES, SCRIM_COLOR, TRAFFIC_WIDTH_DP,
};
use crate::camera::Camera;
use crate::marker::{Marker, MARKER_SIZE_DP};
use crate::style::paint::Stroke;
use crate::style::{Layer, LayerKind, Palette};
use crate::tile::select;
use crate::timing::{nanos_since, Step};
use crate::vulkan::pipeline::{Push, MORPH_NONE, NO_MARKINGS};
use ash::vk;
use std::collections::HashMap;
use tilecodec::mamaps::dict::LAYER_JUNCTION;

impl Renderer {
    /// The flat-layer pass of `record_inner`: basemap fills, lines and deferred symbols,
    /// layer-major across tiles, coarsest tile first.
    ///
    /// Split from `record_inner` so `record.rs` stays under the file-length limit; the pass
    /// itself is unchanged. Takes every draw input by parameter (no `&mut self`) because the
    /// caller holds the scratch buffers as raw pointers across the `&mut self` symbol calls
    /// that follow this pass.
    #[allow(clippy::too_many_arguments)]
    unsafe fn record_flat_layers(
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        filter: &crate::style::KindFilter,
        fog: u32,
        camera_z: u8,
        ordered: &[u64],
        tile_alpha: &HashMap<u64, f32>,
        tiles: &HashMap<u64, super::ResidentTile>,
        draws: &mut Vec<(
            u8,
            u32,
            u32,
            LayerKind,
            vk::Buffer,
            vk::Buffer,
            u32,
            u32,
            Option<u32>,
            (u8, u8, u8),
        )>,
        deferred_symbols: &mut Vec<(u64, usize)>,
        device: &ash::Device,
        fill: vk::Pipeline,
        line: vk::Pipeline,
        fill_globe: vk::Pipeline,
        line_globe: vk::Pipeline,
        layout: vk::PipelineLayout,
        edge_aa: f32,
        bound: &mut Option<LayerKind>,
        submitted: &mut usize,
    ) {
        // The globe flag for this frame: the globe matrices + shaders + depth path
        // below, or the flat path bit-identically above. Read once (not per tile)
        // so a frame cannot mix the two.
        let globe = crate::camera::globe_active(camera);
        for (index, layer) in layers.iter().enumerate() {
            // `min_zoom`/`max_zoom` are a data-and-cost gate, not paint: they say which zooms
            // the archive is worth asking for this layer at. Paint is the ramp below.
            if !layer.draws_at_focused(camera_z, layer.focused_by(filter)) {
                continue;
            }
            // Width and opacity come from the flat style, evaluated against the *camera's*
            // fractional zoom rather than the tile's, so a stroke grows and a fill fades
            // smoothly while zooming instead of jumping a step at every level. Both are push
            // constants, so this re-tessellates nothing, and neither varies per tile.
            // Symbols are the exception: label quads are sized per frame (see
            // tile::symbol), so the text size is evaluated here and the mesh lookup
            // below re-tessellates the tile's symbol layers every frame. Cheap —
            // dozens of quads — and placement stays frame-correct.
            let (stroke, opacity) = match layer.kind {
                // The ramp is what makes a fill visible, and it is the *only* thing: gating a
                // fill on an integer zoom drew `landcover` at full strength at z6 where the
                // ramp asks for half, and popped `landuse_park` on at full strength at z7
                // where it asks for a fifth.
                LayerKind::Fill => {
                    let opacity = layer.opacity_at(camera.zoom);
                    if opacity <= 0.0 {
                        continue;
                    }
                    (Stroke::NONE, opacity)
                }
                LayerKind::Line => {
                    let stroke = layer.stroke(camera.zoom);
                    // The ramps reach zero outside the zooms a layer is meant for, and that is
                    // the style's own gate: several road layers carry no `min_zoom` and rely on
                    // it.
                    if !stroke.visible() {
                        continue;
                    }
                    // A line's own opacity ramp (today only rail's 0.5): the authored style
                    // paints some lines translucent, and folding it into the colour column
                    // would bake a constant while the ramp stays per-frame like a fill's.
                    (stroke, layer.opacity_at(camera.zoom))
                }
                LayerKind::Symbol => {
                    if !layer.text_visible_at(camera.zoom) {
                        continue;
                    }
                    (Stroke::NONE, layer.opacity_at(camera.zoom))
                }
            };
            for key in ordered.iter() {
                // Symbol layers emit per frame at the frame's text size from the
                // tile's shaped candidates (see above): deferred to after the buildings
                // pass so labels are not painted over, then drawn with `&mut self` for
                // their transient uploads.
                if layer.kind == LayerKind::Symbol {
                    // Deeper tiles contribute no candidates (see `place_symbols`), so their
                    // labels are already suppressed by the accept set. Skipping the job here
                    // only avoids scanning their labels to emit nothing.
                    if tiles.get(key).is_some_and(|tile| tile.z > camera_z) {
                        continue;
                    }
                    deferred_symbols.push((*key, index));
                    continue;
                }
                // Copy the draw's inputs out, then issue them through the owned `device`
                // clone: `record_symbol` above takes `&mut self`, so this path must not
                // hold a `self.tiles` borrow either. Draws read from the tile's pooled
                // buffers (bound once per tile below), so only the pool pair plus the
                // slice's firstIndex ride here.
                draws.clear();
                if let Some(tile) = tiles.get(key) {
                    let pool = match layer.kind {
                        LayerKind::Fill => tile.flat.as_ref().map(|(v, i)| (v.buffer, i.buffer)),
                        LayerKind::Line => tile.lines.as_ref().map(|(v, i)| (v.buffer, i.buffer)),
                        LayerKind::Symbol => None,
                    };
                    let Some((pool_vbuf, pool_ibuf)) = pool else {
                        // No pool means no mesh of this format packed — nothing to draw.
                        // (Symbols never take this path; see the deferred arm above.)
                        if layer.kind == LayerKind::Symbol {
                            continue;
                        }
                        continue;
                    };
                    for mesh in tile.layers.iter().filter(|l| l.layer_index == index) {
                        draws.push((
                            tile.z,
                            tile.x,
                            tile.y,
                            mesh.kind,
                            pool_vbuf,
                            pool_ibuf,
                            mesh.first_index,
                            mesh.index_count,
                            mesh.color_override,
                            mesh.lane,
                        ));
                    }
                }
                // This tile's LOD cross-fade opacity for the frame (WS-D), applied through
                // `Push.morph.x`. The fill fragment multiplies output alpha by it; the line path
                // (owned by WS-B's `line.frag`) ignores `morph.x`, so road casings stay crisp
                // while the fill fades — the fade reads as the flat basemap ramping in.
                let tile_fade = tile_alpha.get(key).copied().unwrap_or(1.0);
                    for &(tz, tx, ty, kind, vbuf, ibuf, first_index, count, color_override, lane) in
                    draws.iter()
                {
                if *bound != Some(kind) {
                    // The bound cache is per kind: globe and flat share the kind key
                    // but never mix in one frame (`globe` is read once above), so the
                    // first bind of the frame wins and stays for the frame.
                    let pipeline = match kind {
                        LayerKind::Fill if globe => fill_globe,
                        LayerKind::Fill => fill,
                        LayerKind::Line if globe => line_globe,
                        LayerKind::Line => line,
                        // Symbols never take this path (drawn above); this arm is
                        // unreachable but the match must stay exhaustive.
                        LayerKind::Symbol => continue,
                    };
                        device.cmd_bind_pipeline(
                            command_buffer,
                            vk::PipelineBindPoint::GRAPHICS,
                            pipeline,
                        );
                        *bound = Some(kind);
                    }

                    let (half_width_px, half_gap_px) = stroke.half_px(camera.density);
                    // `misc.y` tells the fragment shader whether it has to antialias the edge
                    // itself. With MSAA the rasteriser already resolves partial coverage, and
                    // applying a second coverage term on top fades a diagonal road twice — at
                    // z6 a 2.9px highway crossing the screen at an angle lost all but one
                    // pixel of its strength, while the near-vertical stretches of the same
                    // road kept three.
                    // A mesh that carries its own colour keeps it **unshifted**: transit
                    // colours are operator brand colours, and the reference draws them
                    // literally. Every other layer goes through `color(palette)`, which
                    // swaps in the dark column and blends toward the background when muted;
                    // doing that to a route colour would turn a network's own red into
                    // whatever the dark basemap thinks red should be. The opacity ramp still
                    // applies, because that is per-frame paint rather than palette.
                    let base = color_override.unwrap_or_else(|| layer.color(palette));
                    // Distance fog: haze the draw toward the land colour with its tile's
                    // distance, so tilted far tiles dissolve instead of ending at a hard
                    // ground edge. Zero at pitch 0 — the flat map is unchanged.
                    let base = apply_fog(base, fog, fog_factor(camera, tz, tx, ty));
                    // How far this mesh's whole band shifts sideways, in device pixels. The
                    // feature carries its colour's ordinal and its corridor's colour count,
                    // not an offset: how many lanes the corridor actually draws is a step
                    // function of the camera zoom, so the lane is chosen here rather than
                    // baked into the mesh. Zero for every layer but transit, where the count
                    // is absent and reads one.
                    let (ordinal, count_of_colours, taper) = lane;
                    let lateral_px = layer.lane_offset_px(
                        camera.zoom,
                        camera.density,
                        ordinal,
                        count_of_colours,
                        taper,
                    );
                    let push = Push {
                        tile_to_clip: camera.tile_to_clip(tz, tx, ty),
                        color: argb_to_rgba(scale_alpha(base, opacity)),
                        line: [half_width_px, half_gap_px, layer.dash.0, layer.dash.1],
                        misc: if globe {
                            // Globe: centre lon/lat (degrees) for the sphere basis.
                            [
                                camera.center_lon as f32,
                                camera.center_lat as f32,
                                lateral_px,
                                camera.time_seconds,
                            ]
                        } else {
                            [
                                camera.tile_span_px(tz),
                                edge_aa,
                                lateral_px,
                                camera.time_seconds,
                            ]
                        },
                        // `morph.z` is the tile's world-px span (Dp): the draped-`z` scale.
                        // On the globe it is the globe radius (Dp) instead, and morph.xy
                        // the half-viewport (Dp) — see the `*_globe.vert` headers.
                        morph: if globe {
                            let r = crate::camera::globe_radius(camera.zoom) as f32;
                            [
                                tile_fade,
                                camera.width_dp / 2.0,
                                r,
                                camera.height_dp / 2.0,
                            ]
                        } else {
                            [tile_fade, 0.0, camera.tile_span_dp(tz) as f32, 0.0]
                        },
                    };
                    device.cmd_push_constants(
                        command_buffer,
                        layout,
                        vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                        0,
                        push.as_bytes(),
                    );
                    device.cmd_bind_vertex_buffers(command_buffer, 0, &[vbuf], &[0]);
                    // Uint32 rather than Uint16: a dense z14 tile can exceed 65535 vertices in
                    // one layer, and overflowing folds geometry back on itself rather than
                    // failing loudly. Indices are rebased to absolute at upload, so the slice
                    // draws from its firstIndex inside the shared pool.
                    device.cmd_bind_index_buffer(command_buffer, ibuf, 0, vk::IndexType::UINT32);
                    device.cmd_draw_indexed(command_buffer, count, 1, first_index, 0, 0);
                    *submitted += 1;
                }
            }
        }
    }
