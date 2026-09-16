use super::fog::{apply_fog, fog_factor};
use super::{
    anchors_for, argb_to_rgba, scale_alpha, Overlay, Renderer, UserPuck, ARROW_COLOR, ARROW_DP,
    BUILDINGS_DRAW_MIN_ZOOM, IDENTITY, PUCK_COLOR, PUCK_CONE_DP, PUCK_CONE_HALF_STROKE_DP,
    PUCK_DOT_DP, PUCK_QUAD_DP, PUCK_RIM_DP, QUAD_INDICES, SCRIM_COLOR, TRAFFIC_WIDTH_DP,
};
use crate::camera::Camera;
use crate::marker::{Marker, MARKER_SIZE_DP};
use crate::style::paint::Stroke;
use crate::style::{Layer, LayerKind, Palette};
use crate::tile::select;
use crate::vulkan::pipeline::{Push, MORPH_NONE, NO_MARKINGS};
use ash::vk;
use std::collections::{HashMap, HashSet};
use tilecodec::mamaps::dict::LAYER_JUNCTION;

impl Renderer {
    /// Draw the road carriageways: each resident tile's road surfaces, with the lane markings
    /// painted on by `road_surface.frag` as a function of the across-road coordinate rather than
    /// drawn as geometry.
    ///
    /// The lane connectors through junctions draw here too, and are why this loop needs no
    /// connector-specific branch: a connector is the same asphalt on the same pipeline, differing
    /// only in the lane count and one-way flag it pushes, both of which already ride on the mesh.
    /// It belongs in this pass rather than beside it because it is carriageway — it has to sit
    /// under the buildings and the deferred symbols exactly as the roads it joins do, and a second
    /// pass could only get that right by accident.
    ///
    /// Its own pass rather than a branch of the layer loop because the ribbon is a different vertex
    /// format on a different pipeline, and because three of its push slots mean something else —
    /// the `Push` doc comment in [`crate::vulkan::pipeline`] is the contract this fills in.
    ///
    /// Coarsest tile first, in `ordered`, for the same reason the layer loop is: a stale ancestor
    /// standing in for a tile that has not arrived must be drawn *under* its descendants, or its
    /// asphalt lands on top of the sharp child as it loads.
    pub(super) unsafe fn record_carriageways(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        ordered: &[u64],
        fog: u32,
        submitted: &mut usize,
    ) {
        let device = &self.context.device;
        let floor = camera.zoom.floor().clamp(0.0, 22.0) as u8;
        let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);
        let mut bound = false;
        for key in ordered {
            let Some(tile) = self.tiles.get(key) else {
                continue;
            };
            if tile.carriageways.is_empty() {
                continue;
            }
            // The ribbon pool, bound at offset 0; each road draws its slice by firstIndex.
            let Some((pool_v, pool_i)) = tile.ribbons.as_ref().map(|(v, i)| (v.buffer, i.buffer))
            else {
                continue;
            };
            let tile_to_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
            let tile_span_px = camera.tile_span_px(tile.z);
            let yellow = f32::from(tile.yellow_centre);
            for road in &tile.carriageways {
                let Some(layer) = layers.get(road.layer_index) else {
                    continue;
                };
                if !layer.draws_at(floor) {
                    continue;
                }
                // The style ramp is one *lane's* width, so this road's own width is that times
                // the lanes it carries — one number cannot describe both a two-lane street and an
                // eight-lane motorway. Halved because the vertex shader offsets each kerb from
                // the centreline, exactly as `Stroke::half_px` does for a stroke.
                let half_width_px =
                    layer.width.at(camera.zoom) * camera.density * road.lanes as f32 / 2.0;
                if half_width_px <= 0.0 {
                    continue;
                }
                if !bound {
                    device.cmd_bind_pipeline(
                        command_buffer,
                        vk::PipelineBindPoint::GRAPHICS,
                        self.pipelines.ribbon,
                    );
                    bound = true;
                }
                // The asphalt only. The markings are the shader's own palette, because they have
                // to match the white it antialiases them against. Distance-fogged like the
                // flat layers so carriageways haze with the ground under tilt.
                let asphalt = apply_fog(
                    scale_alpha(layer.color(palette), layer.opacity_at(camera.zoom)),
                    fog,
                    fog_factor(camera, tile.z, tile.x, tile.y),
                );
                // A lane connector carries no paint. An intersection is not marked out into
                // lanes on the ground, and a connector is drawn in the carriageway's own colour,
                // so its markings would be the only part of it with any contrast against the road
                // beneath — twelve hairline pairs per junction rather than a widening of the
                // asphalt. `NO_MARKINGS` rides in the centre-line slot, which a one-way leaves
                // dead; see the `Push` doc comment in [`crate::vulkan::pipeline`].
                let centre_t = if layer.source_layer_id == LAYER_JUNCTION {
                    NO_MARKINGS
                } else {
                    road.split
                };
                let push = Push {
                    tile_to_clip,
                    color: argb_to_rgba(asphalt),
                    line: [
                        half_width_px,
                        road.lanes as f32,
                        centre_t,
                        f32::from(road.oneway),
                    ],
                    misc: [tile_span_px, edge_aa, yellow, camera.time_seconds],
                    // The markings are static, so unlike the traffic draw there is no phase to
                    // animate and nothing to fade: `MORPH_NONE` is what the ribbon contract asks
                    // for. `morph.z` is the tile's world-px span (Dp) — the draped-`z` scale,
                    // mirroring the flat layer loop.
                    morph: [MORPH_NONE[0], 0.0, camera.tile_span_dp(tile.z) as f32, 0.0],
                };
                device.cmd_push_constants(
                    command_buffer,
                    self.pipelines.layout,
                    vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    0,
                    push.as_bytes(),
                );
                device.cmd_bind_vertex_buffers(command_buffer, 0, &[pool_v], &[0]);
                device.cmd_bind_index_buffer(command_buffer, pool_i, 0, vk::IndexType::UINT32);
                device.cmd_draw_indexed(
                    command_buffer,
                    road.index_count,
                    1,
                    road.first_index,
                    0,
                    0,
                );
                *submitted += 1;
            }
        }
    }

    /// Draw the per-lane turn arrows: one glyph per marked lane, bent by the manoeuvre the lane
    /// leads into and pushed sideways onto the lane the carriageway painted.
    ///
    /// Built per frame — rotation, screen size and lane offset all follow the camera, exactly as
    /// the carriageway's width does — and drawn through the **fill** pipeline as plain coloured
    /// triangles, so it needs no glyph atlas and no new pipeline. Gated to the carriageway layer's
    /// zoom floor (z16), so below it nothing is built or drawn.
    pub(super) unsafe fn record_arrows(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        submitted: &mut usize,
    ) {
        // The carriageway layer, whose zoom window gates the arrows and whose width ramp puts them
        // over the lanes its own markings drew. Resolved through
        // [`crate::style::road_carriageway_layer`], which is careful to pick the *road* layer and
        // not the first layer that happens to carry a spread — see its doc comment.
        let Some(carriageway) = crate::style::road_carriageway_layer(layers) else {
            return;
        };
        let floor = camera.zoom.floor().clamp(0.0, 22.0) as u8;
        if !carriageway.draws_at(floor) {
            return;
        }
        let density = camera.density;
        // One lane of the carriageway, in device px. The lane count is the arrow's own, so a
        // three-lane approach and a five-lane one are each spread across their whole road.
        let lane_px = carriageway.width.at(camera.zoom) * density;
        // Every glyph tessellates to the same vertex count whatever angle it bends through, so a
        // tile's buffer is sized exactly once up front and never grows.
        let verts_per_arrow = crate::tile::arrow::ARROW_VERTS * 2;
        // Build each tile's triangles first — this borrows `self.tiles` — then upload and draw,
        // which takes `&mut self`. One batch per tile carries its own tile-to-clip matrix.
        let mut batches: Vec<([f32; 16], Vec<f32>)> = Vec::new();
        for tile in self.tiles.values() {
            if tile.arrows.is_empty() {
                continue;
            }
            let span = camera.tile_span_px(tile.z);
            if span <= 0.0 {
                continue;
            }
            let scale = ARROW_DP * density / span; // tile-local 0..1 units per unit-arrow coord
                                                   // What turns the arrows' ground setback into this tile's units. A property of the tile
                                                   // and not of the camera, so the arrows stay the same distance behind the junction at
                                                   // every zoom — see `arrow::tile_local_per_metre`.
            let per_metre = crate::tile::arrow::tile_local_per_metre(tile.z, tile.y);
            let mut verts: Vec<f32> = Vec::with_capacity(tile.arrows.len() * verts_per_arrow);
            for a in &tile.arrows {
                // Sideways onto the lane, perpendicular to the road heading (not the glyph's
                // turn). `lane_centre` measures from the middle of the road out, in lane widths,
                // and already accounts for the half of the carriageway this direction occupies
                // under the tile's driving convention — an arrow that does not sit between the
                // dividers the markings drew is worse than no arrow at all.
                let lateral = crate::tile::arrow::lane_centre(a) * lane_px;
                crate::tile::arrow::arrow_verts(a, scale, lateral / span, per_metre, &mut verts);
            }
            if !verts.is_empty() {
                batches.push((camera.tile_to_clip(tile.z, tile.x, tile.y), verts));
            }
        }
        if batches.is_empty() {
            return;
        }
        let color = argb_to_rgba(ARROW_COLOR);
        for (tile_to_clip, verts) in batches {
            let indices: Vec<u32> = (0..(verts.len() / 2) as u32).collect();
            let push = Push {
                tile_to_clip,
                color,
                line: [0.0; 4],
                misc: [0.0, 0.0, 0.0, camera.time_seconds],
                morph: MORPH_NONE,
            };
            self.draw_fill_batch(command_buffer, &verts, &indices, &push, submitted);
        }
    }

    /// Draw one batch through the fill pipeline (colour from the push constant, no descriptor
    /// set), suballocating its vertices and indices from this frame's [`ScratchRing`].
    ///
    /// The arrow twin of [`draw_symbol_batch`], and now allocates the same way. It previously
    /// created two `Buffer`s per call and pushed them onto [`Renderer::transients`] to retire
    /// on the frames-in-flight grace count, which was merely wasteful while it was the symbol
    /// path's twin — but it is called **once per batch per frame** from
    /// [`record_arrows`](Self::record_arrows), and `transients` is a wake reason for the host's
    /// on-demand frame loop (see [`needs_frame`](Self::needs_frame)). A vec refilled every
    /// frame never drains, so the loop would wake itself forever and pin the map at 60fps with
    /// nothing changing. The ring has no such feedback: it is reset wholesale against the
    /// frame fence, so a draw leaves nothing behind for the next frame to notice.
    ///
    /// Off the drawn path today — `style::LANE_RENDERING` is false, so no arrows are built or
    /// drawn — which is precisely why this had to be fixed now rather than when someone
    /// switches lane rendering on and finds the phone hot again.
    pub(super) unsafe fn draw_fill_batch(
        &mut self,
        command_buffer: vk::CommandBuffer,
        vertices: &[f32],
        indices: &[u32],
        push: &Push,
        submitted: &mut usize,
    ) {
        let frame_index = self.frame_index;
        let device = &self.context.device;
        // Disjoint field borrows, for the same reason `draw_symbol_batch` takes them: cloning
        // `ash::Device` copies its whole function-pointer table, and this is a per-draw path.
        let Some((vbuf, voffset)) = self.scratch[frame_index].push(
            &self.context.instance,
            self.context.physical_device,
            device,
            vertices,
        ) else {
            return;
        };
        let Some((ibuf, ioffset)) = self.scratch[frame_index].push(
            &self.context.instance,
            self.context.physical_device,
            device,
            indices,
        ) else {
            return;
        };
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.fill,
        );
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[vbuf], &[voffset]);
        device.cmd_bind_index_buffer(command_buffer, ibuf, ioffset, vk::IndexType::UINT32);
        device.cmd_draw_indexed(command_buffer, indices.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }

    /// Draw the DEM-displaced ground grid: each resident tile's terrain mesh, depth-tested so hills
    /// occlude what is behind them and let buildings on the far side of a ridge be hidden by it.
    /// Drawn before the flat layer loop, so the flat 2D layers paint over it (see the drape-vs-offset
    /// note on [`record_inner`](Self::record_inner)). A tile with no heightmap has no terrain mesh
    /// and draws its flat `earth` fill in the loop instead; at pitch 0 the terrain vertex shader
    /// collapses the grid to the flat footprint, so the overhead map is unchanged.
    ///
    /// The ground colour is the `earth` style layer's, resolved for this palette and its fill
    /// opacity for this zoom — the same colour the flat earth fill would have used — pushed once and
    /// shared by every tile. `line.x` carries the tile's world-px span, the scale that turns the
    /// mesh's tile-normalised heights into the world-px height the WS0 matrix's `z` input expects.
    /// Distance-fogged per tile toward the earth land colour like the flat layers (buildings keep their
    /// per-vertex colours and are deliberately not fogged — no shader change for a z14+-only
    /// layer whose tiles sit inside the near ramp anyway).
    pub(super) unsafe fn record_terrain(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        fog: u32,
        submitted: &mut usize,
    ) {
        let Some(earth) = layers
            .iter()
            .find(|l| l.source_layer_id == tilecodec::mamaps::dict::LAYER_EARTH)
        else {
            return;
        };
        let opacity = earth.opacity_at(camera.zoom);
        if opacity <= 0.0 {
            return;
        }
        let unfogged = argb_to_rgba(scale_alpha(earth.color(palette), opacity));
        let fog_rgb = argb_to_rgba(fog);
        let device = &self.context.device;
        let mut bound = false;
        for tile in self.tiles.values() {
            let Some(terrain) = &tile.terrain else {
                continue;
            };
            if !bound {
                device.cmd_bind_pipeline(
                    command_buffer,
                    vk::PipelineBindPoint::GRAPHICS,
                    self.pipelines.terrain,
                );
                bound = true;
            }
            // Same fog as the flat layers, mixed one step later (linear floats rather than
            // packed ARGB — same ramp, rounding apart). Per tile: far ground hazes out.
            let f = fog_factor(camera, tile.z, tile.x, tile.y);
            let colour = [
                unfogged[0] + (fog_rgb[0] - unfogged[0]) * f,
                unfogged[1] + (fog_rgb[1] - unfogged[1]) * f,
                unfogged[2] + (fog_rgb[2] - unfogged[2]) * f,
                unfogged[3] + (fog_rgb[3] - unfogged[3]) * f,
            ];
            // Tile-local eye for the terrain specular, derived the way `fog_factor` derives
            // its tile geometry: camera centre in world px (`project`, at the camera zoom)
            // minus the tile origin, over the span (`tile_span_dp`). Tile u/v are world axes
            // normalised, so no bearing rotation enters — the matrix already owns that.
            let span = camera.tile_span_dp(tile.z);
            let (eye_u, eye_v, eye_h) = if span > 0.0 {
                let centre =
                    crate::camera::project(camera.center_lon, camera.center_lat, camera.zoom);
                (
                    ((centre.x - f64::from(tile.x) * span) / span) as f32,
                    ((centre.y - f64::from(tile.y) * span) / span) as f32,
                    ((1.5 * f64::from(camera.height_dp)) / span) as f32,
                )
            } else {
                (0.5, 0.5, 1.0e4)
            };
            let push = Push {
                tile_to_clip: camera.tile_to_clip(tile.z, tile.x, tile.y),
                color: colour,
                // `line.x`: the tile's world-px span, the tile-norm-height -> world-px scale.
                line: [camera.tile_span_dp(tile.z) as f32, 0.0, 0.0, 0.0],
                // `misc.xyz`: the tile-local eye for the Blinn-Phong specular — the camera
                // centre in world px minus the tile origin, over the span, so u/v land in
                // 0..1 (off-tile when the centre is elsewhere, which is fine: the view
                // vector stays well-defined). Eye height is the MapLibre-like eye distance
                // (1.5 viewport-heights, the `d` behind `Camera::perspective`) in the same
                // tile-norm units as the mesh heights, so `eye - frag` is a true direction
                // in tile space. At pitch 0 the fragment shader skips the specular outright,
                // so this only ever steers tilted highlights. A degenerate span falls back
                // to far-above-centre, where the view vector is straight down and the
                // highlight collapses to ~0.
                misc: [eye_u, eye_v, eye_h, camera.time_seconds],
                morph: MORPH_NONE,
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_bind_vertex_buffers(command_buffer, 0, &[terrain.vertices.buffer], &[0]);
            device.cmd_bind_index_buffer(
                command_buffer,
                terrain.indices.buffer,
                0,
                vk::IndexType::UINT32,
            );
            device.cmd_draw_indexed(command_buffer, terrain.index_count, 1, 0, 0, 0);
            *submitted += 1;
        }
    }

    /// Draw the extruded 3D buildings: each resident tile's combined building mesh, depth-tested so
    /// buildings occlude one another and sit above the flat basemap drawn before them. Only at
    /// z14+, where the extruded detail is legible; below that nothing is drawn and the map is the
    /// flat basemap it always was. At pitch 0 the building vertex shader collapses height to the
    /// footprint, so from directly overhead the buildings read as their 2D outline.
    ///
    /// The per-tile push carries the WS0 clip matrix and, in `line.x`, the tile's world-px span for
    /// this frame — the scale that turns the mesh's tile-normalised heights into the world-px height
    /// the matrix's `z` input expects. Colour is per-vertex, so there is no colour push and no
    /// atlas: the pass binds one pipeline and issues one draw per resident tile that has buildings.
    pub(super) unsafe fn record_buildings(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        submitted: &mut usize,
    ) {
        if camera.zoom.floor() < BUILDINGS_DRAW_MIN_ZOOM {
            return;
        }
        // The palette's building colour, for every wall and roof the archive did not colour
        // itself. Vertex colour is baked at tessellation time, where the palette is not reachable,
        // so the fallback rides in as a push instead and a light/dark switch recolours on the next
        // frame with no re-tessellation. `extrude_building` marks a vertex as wanting it by writing
        // alpha 0, which `building.frag` tests.
        let default_color = layers
            .iter()
            .find(|l| l.source_layer_id == tilecodec::mamaps::dict::LAYER_BUILDINGS)
            .map(|l| argb_to_rgba(l.color(palette)))
            .unwrap_or([0.8, 0.8, 0.8, 1.0]);
        let device = &self.context.device;
        let mut bound = false;
        for tile in self.tiles.values() {
            let Some(buildings) = &tile.buildings else {
                continue;
            };
            if !bound {
                device.cmd_bind_pipeline(
                    command_buffer,
                    vk::PipelineBindPoint::GRAPHICS,
                    self.pipelines.building,
                );
                bound = true;
            }
            let push = Push {
                tile_to_clip: camera.tile_to_clip(tile.z, tile.x, tile.y),
                // The palette fallback for vertices that carry no archive colour (alpha 0).
                color: default_color,
                // `line.x`: the tile's world-px span, the tile-norm-height -> world-px scale.
                line: [camera.tile_span_dp(tile.z) as f32, 0.0, 0.0, 0.0],
                misc: [0.0, 0.0, 0.0, camera.time_seconds],
                morph: MORPH_NONE,
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_bind_vertex_buffers(command_buffer, 0, &[buildings.vertices.buffer], &[0]);
            device.cmd_bind_index_buffer(
                command_buffer,
                buildings.indices.buffer,
                0,
                vk::IndexType::UINT32,
            );
            device.cmd_draw_indexed(command_buffer, buildings.index_count, 1, 0, 0, 0);
            *submitted += 1;
        }
    }
}
