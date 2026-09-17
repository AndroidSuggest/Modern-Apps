use super::{
    anchors_for, argb_to_rgba, scale_alpha, Overlay, Renderer, ARROW_COLOR, ARROW_DP,
    BUILDINGS_DRAW_MIN_ZOOM, IDENTITY, PUCK_COLOR, PUCK_CONE_DP, PUCK_CONE_HALF_STROKE_DP,
    PUCK_DOT_DP, PUCK_QUAD_DP, PUCK_RIM_DP, QUAD_INDICES, SCRIM_COLOR, TRAFFIC_WIDTH_DP,
    VEHICLE_RING_DP, VEHICLE_RING_QUAD_DP,
};
use super::record_markers::emit_marker_corners;
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
    /// Draw the app's pins as billboarded atlas sprites, batched into one draw.
    ///
    /// The shared sprite/billboard path — WS-F's transit vehicles reuse it verbatim. Each marker
    /// is glued to its `lon`/`lat` and kept upright and screen-constant under tilt by
    /// [`Camera::screen_quad_to_clip`](crate::camera::Camera::screen_quad_to_clip), exactly as the
    /// puck is. Because a marker is *screen-anchored* (a fixed Dp size), its billboard quad is
    /// resolved to clip space on the CPU here — the per-marker perspective `w` is constant across
    /// the quad's four corners, so the divide can be done once — and every marker is emitted into
    /// one shared vertex/index buffer drawn with the identity matrix. That is what makes the bulk
    /// many-sprites case (dozens of vehicles) one upload and one draw rather than one per sprite.
    ///
    /// A marker whose icon the sheet does not carry, or which the tilt puts behind the eye, is
    /// skipped rather than drawn wrong. No `sprite_set` (a sheet that would not decode) draws no
    /// markers, exactly as it draws no POI icons.
    pub(super) unsafe fn draw_markers(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        palette: Palette,
        markers: &[Marker],
        submitted: &mut usize,
        // This frame's globe flag: far-side markers culled, screen quads placed
        // via the globe anchor — or the flat billboard path bit-identically.
        globe: bool,
    ) {
        let Some(sprite_set) = self.sprite_set else {
            return;
        };
        let atlas = crate::tile::sprite::atlas();
        // The sheet is the light half over the dark one; dark mode adds this to every `v`, exactly
        // as `emit_icon` does, so a palette switch stays a per-frame emit rather than a re-upload.
        let dv = if palette.variant == crate::style::Variant::Dark {
            atlas.dark_v_offset()
        } else {
            0.0
        };

        let mut vertices: Vec<f32> = Vec::with_capacity(markers.len() * 4 * 4);
        let mut indices: Vec<u32> = Vec::with_capacity(markers.len() * 6);
        for marker in markers {
            let Some(sprite) =
                crate::marker::icon_sprite_name(marker.icon).and_then(|n| atlas.get(n))
            else {
                continue;
            };
            // Globe: far-side markers are on the planet's far side — culled, not
            // drawn. Near-side markers resolve their clip quad through the globe
            // anchor (screen position of the lon/lat on the ball) with the same
            // screen-constant size as the flat path.
            let (cx, cy, xu, yu, xv, yv, w) = if globe {
                let Some((sx, sy)) = camera.globe_anchor_to_screen(marker.lon, marker.lat) else {
                    continue;
                };
                // Clip-space centre from Dp: clip = 2*dp/dim - 1 (y down, matching
                // the flat matrices' sign convention).
                let cx = (sx / camera.width_dp as f64 * 2.0 - 1.0) as f64;
                let cy = (sy / camera.height_dp as f64 * 2.0 - 1.0) as f64;
                // Dp axes in clip space: one Dp is 2/dim clip units.
                let xu = 2.0 / camera.width_dp as f64;
                let yv = 2.0 / camera.height_dp as f64;
                (cx, cy, xu, 0.0, 0.0, yv, 1.0)
            } else {
                // The billboard matrix for this marker (upright + screen-constant under tilt). Its
                // translation column is the quad centre in clip space; columns 0 and 1 are the local
                // Dp axes. All four corners share the same `w` (the matrix's `w` columns for the two
                // in-plane axes are zero on both the ortho and the tilted path), so the perspective
                // divide is one number per marker.
                let m = camera.screen_quad_to_clip(marker.lon, marker.lat, 1.0);
                let w = m[15] as f64;
                if w <= 0.0 {
                    continue; // behind the eye / above the horizon under tilt: nothing to draw.
                }
                let cx = m[12] as f64 / w;
                let cy = m[13] as f64 / w;
                let (xu, yu) = (m[0] as f64 / w, m[1] as f64 / w);
                let (xv, yv) = (m[4] as f64 / w, m[5] as f64 / w);
                (cx, cy, xu, yu, xv, yv, w)
            };
            let _ = w;
            // Draw the icon at `MARKER_SIZE_DP` on its larger side, keeping its aspect ratio. With
            // `radius_dp = 1.0` above, a local coordinate is one Dp, so these half-extents are Dp.
            // (On the globe the quad is axis-aligned clip — same units, same math.)
            let scale = MARKER_SIZE_DP / sprite.width_dp.max(sprite.height_dp).max(1e-3);
            let hw = (sprite.width_dp * scale * 0.5) as f64;
            let hh = (sprite.height_dp * scale * 0.5) as f64;
            let uv = sprite.uv;
            let (v0, v1) = (uv.v0 + dv, uv.v1 + dv);
            let base = (vertices.len() / 4) as u32;
            // Corners: (local_u, local_v, tex_u, tex_v). y-down in both clip and atlas, as
            // `emit_icon` documents, so v0 goes with the top edge.
            let corners = [
                (-hw, -hh, uv.u0, v0),
                (hw, -hh, uv.u1, v0),
                (hw, hh, uv.u1, v1),
                (-hw, hh, uv.u0, v1),
            ];
            emit_marker_corners(&mut vertices, &mut indices, base, corners, cx, cy, xu, yu, xv, yv, w);
        }
        if indices.is_empty() {
            return;
        }
        // Vertices are already in clip space, so the shader must not transform them: identity. Only
        // `color.a` is read by `sprite.frag` (an icon draws in its own colours), so full alpha.
        let push = Push {
            tile_to_clip: IDENTITY,
            color: [1.0, 1.0, 1.0, 1.0],
            line: [0.0; 4],
            misc: [0.0, 0.0, 0.0, camera.time_seconds],
            morph: MORPH_NONE,
        };
        self.draw_symbol_batch(
            command_buffer,
            self.pipelines.sprite,
            sprite_set,
            &vertices,
            &indices,
            &push,
            submitted,
        );
    }

    /// Draw the route-colour rings under coloured vehicles, so each reads in
    /// its line's colour.
    ///
    /// A dot-only pass on the puck pipeline (no cone, no white rim): one disc
    /// per vehicle with a nonzero [`Marker::colour`], sized just past the
    /// 28 Dp sprite, drawn before the sprites so they cover its middle and
    /// only the edge shows as an outline. The pipeline is bound once and each
    /// vehicle is one push-constant draw — the visible set is bounded by the
    /// host's bbox/zoom gate, so this stays a handful of draws per frame.
    /// Vehicles with colour `0` (the pack carries no `route_color`) and ones
    /// the tilt puts behind the eye draw nothing.
    pub(super) unsafe fn draw_vehicle_rings(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        vehicles: &[Marker],
        submitted: &mut usize,
        // This frame's globe flag: far-side vehicles culled via the globe anchor.
        globe: bool,
    ) {
        let density = camera.density;
        let device = &self.context.device;
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.puck,
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[self.quad.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            self.quad.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );
        for v in vehicles {
            if v.colour == 0 {
                continue;
            }
            // Globe: far-side vehicles culled; near-side placed via the globe anchor.
            // The ring quad is screen-constant either way, so only the matrix differs.
            let m = if globe {
                let Some((sx, sy)) = camera.globe_anchor_to_screen(v.lon, v.lat) else {
                    continue;
                };
                super::placement::globe_screen_quad(camera, sx, sy, VEHICLE_RING_QUAD_DP as f64)
            } else {
                camera.screen_quad_to_clip(v.lon, v.lat, VEHICLE_RING_QUAD_DP as f64)
            };
            if m[15] <= 0.0 {
                continue; // behind the eye / above the horizon under tilt.
            }
            let push = Push {
                tile_to_clip: m,
                color: argb_to_rgba(0xFF00_0000 | v.colour),
                // Dot only: rim 0 hides the white ring, cone sizes 0 and
                // misc.y 0 hide the bearing cone.
                line: [0.0, VEHICLE_RING_DP * density, 0.0, 0.0],
                misc: [
                    0.0,
                    0.0,
                    VEHICLE_RING_QUAD_DP * density,
                    camera.time_seconds,
                ],
                morph: MORPH_NONE,
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_draw_indexed(command_buffer, QUAD_INDICES.len() as u32, 1, 0, 0, 0);
            *submitted += 1;
        }
    }

    /// Draw one tile's one symbol layer: emit its shaped labels at the frame's
    /// text size, upload a transient buffer pair, and draw it with the symbol
    /// pipeline bound to the glyph atlas set.
    ///
    /// Only labels in `accepted` (see [`place_symbols`](Self::place_symbols))
    /// emit; the rest lost their collisions this frame.
    ///
    /// The emit + draw half lives in `record_symbol_emit` (file-length split):
    /// this function resolves the tile, its ground sampler and its billboard
    /// inputs, then hands them over.
    #[allow(clippy::too_many_arguments)]
    pub(super) unsafe fn record_symbol(
        &mut self,
        command_buffer: vk::CommandBuffer,
        key: u64,
        layer_index: usize,
        layer: &Layer,
        camera: &Camera,
        palette: Palette,
        accepted: &HashMap<u64, (bool, u32)>,
        submitted: &mut usize,
        bound: &mut Option<LayerKind>,
        // This frame's globe flag: globe symbol/icon pipelines + sphere push, or
        // the flat path bit-identically above.
        globe: bool,
    ) {
        use crate::tile::{placement, symbol};
        let Some(glyph_set) = self.glyph_set else {
            return;
        };
        // THE density fix (task 1): the size ramp is authored in Dp but the
        // tile span and the shader are device px — without ×density every
        // label renders at 1/density size (≈2px tall cap-height at 17px Dp on
        // a density-3 screen, exactly the reported symptom). Lines already do
        // this (`half_px(density)`); symbols must too.
        //
        // Resolved per label rather than per layer, because the authored size depends on
        // the place's population rank as well as the zoom.
        if !layer.text_visible_at(camera.zoom) {
            return;
        }
        let Some(tile) = self.tiles.get(&key) else {
            return;
        };
        // The tile's coordinates, copied out so the `self.tiles` borrow below is held only by
        // `tile.labels` and ends at the batch loop — the `&mut self` uploads come after it.
        let (tz, tx, ty) = (tile.z, tile.x, tile.y);
        let tile_span_px = camera.tile_span_px(tz);
        // The tile-normalised ground height sampler for label anchors: the same DEM the flat
        // drape reads, so a label hangs off exactly the relief its road drapes onto. 0.0 with
        // no heightmap, so covered output is unchanged. Normalised against this tile's own
        // ground width — the shader scales it back with the pushed Dp span.
        let ground_width = crate::tile::geometry::tile_ground_width_m(tz, ty);
        let heightmap = &tile.heightmap;
        let ground = |u: f32, v: f32| -> f32 {
            crate::tile::geometry::sample_ground_metres(heightmap, u, v).map_or(0.0, |m| {
                (m as f64 / ground_width.max(f64::MIN_POSITIVE)) as f32
            })
        };
        // Copy out what the draw needs before any `&mut self` call below: `tile`
        // borrows `self`, and buffer upload takes `&self.context` while retiring
        // takes `&mut self`.
        let tile_clip = camera.tile_to_clip(tz, tx, ty);
        // Billboarding under tilt (point labels and their icons): the shader projects each corner's
        // ground anchor through `tile_clip` (perspective) and hangs the corner off it at a constant
        // screen offset, reconstructed from the pitch-0 tile matrix's linear 2x2. At pitch 0 the
        // flag is clear and the shader draws straight through `tile_clip`, byte-identical to
        // before. Curved labels carry each vertex as its own anchor, so they stay on the ground
        // regardless — a road name lies along the road, which is the one case where flat is right.
        //
        // One derivation, not two checked: [`symbol::icon_push_billboard`] builds the flag and the
        // matrix the icon push *and* the text push below both read, so the pictogram cannot slide
        // off its name under tilt. Reverting either value in one copy compiles clean and passes —
        // which is exactly the silent failure this sharing exists to make unrepresentable.
        let flat_clip = Camera {
            pitch_deg: 0.0,
            ..*camera
        }
        .tile_to_clip(tz, tx, ty);
        let (billboard_flag, ortho2x2) = symbol::icon_push_billboard(&flat_clip, camera.pitch_deg);
        let (primary, alternate) = anchors_for(layer);
        // Labels counter-rotate about their anchor so they stay upright under a
        // heading-up camera, which is what a driver needs and what keeps the placer's
        // axis-aligned collision boxes describing the box the label actually occupies.
        // `(1, 0)` north-up, where `upright` is a no-op.
        let (cos, sin) = camera.rotation();
        let rotation = (cos as f32, sin as f32);
        // Batched by resolved size, not one batch per tile-layer. A label's size now
        // depends on its population rank, so one draw can hold two of them — and
        // `Push::line.x` carries the text size the fragment shader turns a halo width in
        // px into SDF units with. One value cannot serve both arms, so each gets a draw.
        // The style declares at most two arms, so this is at most two.
        let mut batches: Vec<(f32, Vec<f32>, Vec<u32>)> = Vec::new();
        // Icons take one batch of their own however many sizes the text has: they are a
        // constant screen size, and they sample a different atlas through a different
        // fragment shader, so they could not share a draw with the text regardless.
        let mut icon_vertices: Vec<f32> = Vec::new();
        let mut icon_indices: Vec<u32> = Vec::new();
        // Labels are enumerated in tile-list order — the same order (and the
        // same ids) the pre-pass used — and only accepted ones emit. A tile
        // whose every label collides emits nothing and skips its draw.
        //
        // Borrowed, not cloned. This used to deep-copy the whole label vector — every name
        // `String`, every shaped line, every curved centreline — once per symbol layer per
        // resident tile per frame, purely to release the `self.tiles` borrow before the uploads
        // further down. Nothing in this loop needs `&mut self`, so the borrow simply ends here.
        for (label_idx, label) in tile
            .labels
            .iter()
            .enumerate()
            .filter(|(_, l)| l.layer_index == layer_index)
        {
            let id = placement::candidate_id(tz, tx, ty, layer_index, label_idx);
            let Some(&(flipped, _)) = accepted.get(&id) else {
                continue;
            };
            // Draw at whichever anchor the placer actually accepted, or the label lands
            // on the side its box was rejected for.
            let anchor = if flipped {
                alternate.unwrap_or(primary)
            } else {
                primary
            };
            let text_px = layer.text_size_for(camera.zoom, label.pop) * camera.density;
            if text_px <= 0.0 {
                continue;
            }
            if let Some(sprite) = label.sprite {
                symbol::emit_icon(
                    label,
                    sprite,
                    palette.variant == crate::style::Variant::Dark,
                    camera.density,
                    tile_span_px,
                    rotation,
                    &ground,
                    &mut icon_vertices,
                    &mut icon_indices,
                );
            }
            let batch = match batches.iter_mut().find(|(size, _, _)| *size == text_px) {
                Some(batch) => batch,
                None => {
                    batches.push((text_px, Vec::new(), Vec::new()));
                    batches.last_mut().expect("just pushed")
                }
            };
            symbol::emit_label(
                label,
                anchor,
                layer.text_offset,
                text_px,
                tile_span_px,
                rotation,
                &ground,
                &mut batch.1,
                &mut batch.2,
            );
        }
        batches.retain(|(_, _, indices)| !indices.is_empty());
        if batches.is_empty() && icon_indices.is_empty() {
            return;
        }
        self.record_symbol_emit(
            command_buffer,
            layer,
            camera,
            palette,
            tz,
            tile_clip,
            tile_span_px,
            billboard_flag,
            ortho2x2,
            &batches,
            &icon_vertices,
            &icon_indices,
            glyph_set,
            submitted,
            bound,
            globe,
        );
    }

    /// Suballocate one vertex/index pair out of this frame's scratch ring, bind `pipeline` with
    /// `set`, and draw it.
    ///
    /// The icon and text paths differ only in which pipeline and atlas they bind, so they
    /// share this. Geometry goes into [`Renderer::scratch`] rather than into a buffer of its own:
    /// a screenful of labels is several hundred of these a frame, and a `vkAllocateMemory` per
    /// draw is both slow and bounded by `maxMemoryAllocationCount`. The ring is reset at the top
    /// of the frame under the in-flight fence, which is what makes the memory safe to reuse.
    #[allow(clippy::too_many_arguments)]
    pub(super) unsafe fn draw_symbol_batch(
        &mut self,
        command_buffer: vk::CommandBuffer,
        pipeline: vk::Pipeline,
        set: vk::DescriptorSet,
        vertices: &[f32],
        indices: &[u32],
        push: &Push,
        submitted: &mut usize,
    ) {
        let frame_index = self.frame_index;
        let device = &self.context.device;
        // Disjoint field borrows, deliberately not `self.context.device.clone()` as the frame path
        // does once per frame: `ash::Device` clones its whole function-pointer table, and this
        // runs a few hundred times a frame.
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
        device.cmd_bind_pipeline(command_buffer, vk::PipelineBindPoint::GRAPHICS, pipeline);
        device.cmd_bind_descriptor_sets(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.symbol_layout,
            0,
            &[set],
            &[],
        );
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.symbol_layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[vbuf], &[voffset]);
        device.cmd_bind_index_buffer(command_buffer, ibuf, ioffset, vk::IndexType::UINT32);
        device.cmd_draw_indexed(command_buffer, indices.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }
}
