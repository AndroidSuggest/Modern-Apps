use super::{
    ARROW_COLOR, ARROW_DP, BUILDINGS_DRAW_MIN_ZOOM, IDENTITY, Overlay, PUCK_COLOR, PUCK_CONE_DP,
    PUCK_CONE_HALF_STROKE_DP, PUCK_DOT_DP, PUCK_QUAD_DP, PUCK_RIM_DP, QUAD_INDICES, Renderer,
    SCRIM_COLOR, TRAFFIC_WIDTH_DP, UserPuck, anchors_for, argb_to_rgba, scale_alpha,
};
use crate::camera::Camera;
use crate::marker::{Marker, MARKER_SIZE_DP};
use crate::style::paint::Stroke;
use crate::style::{Layer, LayerKind, Palette};
use crate::tile::select;
use crate::vulkan::pipeline::{MORPH_NONE, NO_MARKINGS, Push};
use ash::vk;
use std::collections::{HashMap, HashSet};
use tilecodec::mamaps::dict::LAYER_JUNCTION;

impl Renderer {
    /// Record the frame's single render pass.
    ///
    /// Draw order is **layer-major across tiles**: for each style layer, every resident
    /// tile's geometry for it. Tile-major would let one tile's road casing land on top of
    /// the next tile's road fill, which shows as a seam along every tile boundary.
    ///
    /// `record` takes `&mut self` (not `&self` like before) because symbol layers
    /// upload transient per-frame buffers. The frame path is still single-threaded
    /// per `crate::bridge`'s docs; only one command buffer records at a time.
    pub(super) unsafe fn record(
        &mut self,
        command_buffer: vk::CommandBuffer,
        image_index: usize,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        clear: u32,
        filter: &crate::style::KindFilter,
    ) -> Result<(), String> {
        // `record_symbol` takes `&mut self` (transient uploads), so `record`
        // issues all fill/line draws through small helpers that re-borrow per
        // call — no `self.` reference lives across a `&mut self` call. The ash
        // `device` is `Copy`-free but its methods take `&self`; copy the few
        // Copy handles needed (pipeline ids, layout) per draw instead.
        let render_pass = self.swapchain.render_pass;
        let framebuffer = self.swapchain.framebuffers[image_index];
        let extent = self.swapchain.extent;
        unsafe {
            self.record_inner(command_buffer, render_pass, framebuffer, extent, camera, layers, palette, clear, filter)
        }
    }

    /// The body of [`record`](Self::record): split out so the borrow structure
    /// reads linearly. All Vulkan calls go through raw handles copied out of
    /// `self` at each step; `record_symbol` is the only `&mut self` callee.
    ///
    /// # Draw order (the contract A/C/D/E/G extend)
    ///
    /// Everything happens in one subpass, so order *is* correctness for the flat layers (they
    /// blend, depth-off) and the depth attachment resolves it for the 3D ones. The sequence is:
    ///
    /// 0. **`record_terrain`** (WS-G) — the DEM-displaced ground, depth-tested, drawn first so it is
    ///    the ground the flat layers sit over. A tile with no heightmap draws nothing here and keeps
    ///    its flat `earth` fill in step 1; at pitch 0 the grid collapses to the flat footprint.
    /// 1. **Basemap layers**, layer-major across tiles, coarsest tile first (fill/line, then
    ///    symbols per layer). WS-D scales each tile draw's alpha through `Push.morph.x`.
    /// 2. **`record_carriageways`** — road surfaces and their lane markings at z16+, over the road
    ///    fills they replace and still under the buildings and the deferred symbols.
    /// 3. **`record_traffic`** — basemap detail, so it dims with the region scrim (WS-B animates
    ///    it via `Push.misc.w`).
    /// 4. **`record_arrows`** — lane turn arrows over the roads.
    /// 5. **`record_region_mask`** — stencil + scrim; dims 1–4, not the route/puck.
    /// 6. **`record_route`** — over the scrim (a followed route must not dim), under the puck.
    /// 7. **`record_overlays`** — markers (app pins; WS-F vehicles) billboarded upright under tilt,
    ///    then the puck on top; last.
    ///
    /// Where a new workstream slots in: **WS-A buildings** and **WS-G terrain** draw with the
    /// depth-enabled pipeline; terrain goes *before* step 1 (it is the ground the flat layers
    /// drape over / sit above) and buildings *after* step 1 at z14+ so they occlude the basemap
    /// by depth. **WS-C markers + id pass** slot beside `record_overlays`. **WS-E curved labels**
    /// ride the symbol path inside step 1. Each adds its own pass/branch; keep this list current
    /// and serialise merges so the passes do not collide.
    ///
    /// # Flat layers over terrain: the drape-vs-offset choice (WS-G)
    ///
    /// The flat 2D layers (roads, water, landuse) **stay at z = 0** and draw depth-off, painting
    /// over the terrain in draw order rather than draping onto it. WS0's `Push` z semantics already
    /// say the vertex z is 0 for every flat 2D layer, and those layers pass `Depth::Off`, so terrain
    /// writes depth for the 3D layers (buildings occlude against it) while the flat layers paint on
    /// top with no z-fighting and need no polygon depth offset. Draping — sampling the same
    /// heightmap for each flat layer's z — would lift roads and water onto the relief but change
    /// every flat layer's vertex format and re-tessellation, so it is deliberately not done here;
    /// under the 60° pitch cap and ~30 m DEM the painted-over approximation reads correctly.
    #[allow(clippy::too_many_arguments)]
    unsafe fn record_inner(
        &mut self,
        command_buffer: vk::CommandBuffer,
        render_pass: vk::RenderPass,
        framebuffer: vk::Framebuffer,
        extent: vk::Extent2D,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        clear: u32,
        filter: &crate::style::KindFilter,
    ) -> Result<(), String> {
        let device = self.context.device.clone();
        device
            .reset_command_buffer(command_buffer, vk::CommandBufferResetFlags::empty())
            .map_err(|e| format!("reset_command_buffer {e:?}"))?;
        let begin = vk::CommandBufferBeginInfo::default()
            .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
        device
            .begin_command_buffer(command_buffer, &begin)
            .map_err(|e| format!("begin_command_buffer {e:?}"))?;

        // One per attachment, in render-pass order, and the layout differs: multisampled is
        // [colour, resolve, depth-stencil] while single-sampled is [colour, depth-stencil]. The
        // depth-stencil is therefore at index 2 or index 1 depending on the device, so both
        // trailing entries carry the same depth+stencil clear — the resolve target is `DONT_CARE`
        // and ignores its entry, and a trailing extra entry is allowed. Depth clears to the far
        // plane (1.0) for the 3D layers; stencil clears to zero, which the scrim reads as
        // "outside the region".
        let depth_stencil_clear =
            vk::ClearValue { depth_stencil: vk::ClearDepthStencilValue { depth: 1.0, stencil: 0 } };
        let clear_values = [
            vk::ClearValue { color: vk::ClearColorValue { float32: argb_to_rgba(clear) } },
            depth_stencil_clear,
            depth_stencil_clear,
        ];
        let pass = vk::RenderPassBeginInfo::default()
            .render_pass(render_pass)
            .framebuffer(framebuffer)
            .render_area(vk::Rect2D { offset: vk::Offset2D { x: 0, y: 0 }, extent })
            .clear_values(&clear_values);
        device.cmd_begin_render_pass(command_buffer, &pass, vk::SubpassContents::INLINE);

        let viewport = vk::Viewport::default()
            .width(extent.width as f32)
            .height(extent.height as f32)
            .min_depth(0.0)
            .max_depth(1.0);
        device.cmd_set_viewport(command_buffer, 0, std::slice::from_ref(&viewport));
        let scissor = vk::Rect2D { offset: vk::Offset2D { x: 0, y: 0 }, extent };
        device.cmd_set_scissor(command_buffer, 0, std::slice::from_ref(&scissor));

        let mut bound: Option<LayerKind> = None;
        let mut submitted = 0usize;
        // One tile-layer's draws, reused across the whole frame.
        //
        // A layer used to have at most one mesh per tile, so the draw could be a single
        // `find`. Transit breaks that: a tile holding two route colours emits two meshes
        // for one layer, and a `find` would silently draw only the first line. Copying the
        // handles out (rather than iterating `self.tiles` in place) is what keeps
        // `record_symbol`'s `&mut self` call legal in the sibling arm below; hoisting the
        // `Vec` out of the loop and clearing it keeps that free of allocation.
        #[allow(clippy::type_complexity)]
        let mut draws: Vec<(
            u8,
            u32,
            u32,
            LayerKind,
            vk::Buffer,
            vk::Buffer,
            u32,
            Option<u32>,
            (u8, u8, u8),
        )> = Vec::new();
        // Coarsest tiles first, so an ancestor standing in for a tile that has not arrived
        // is drawn *under* its descendants and gets covered as they load. A HashMap's
        // iteration order is arbitrary, so without this a stale parent can land on top of
        // the sharp child. Keys (not refs) so `record_symbol` can take `&mut self`.
        let mut ordered: Vec<u64> = self.tiles.keys().copied().collect();
        ordered.sort_by_key(|k| self.tiles.get(k).map(|t| t.z).unwrap_or(0));

        // WS-D LOD cross-fade: one opacity per resident tile for this frame, written into each
        // tile draw's `Push.morph.x` below. A finer tile ramps 0→1 over `LOD_FADE_SECONDS` from
        // its `uploaded_at` stamp *while* a coarse ancestor is resident to stand in under the gap
        // (see `tile_lod_alpha`); a tile with nothing beneath it stays fully opaque, so a freshly
        // fetched area never fades up from the background. Computed once here, not per layer.
        let now = camera.time_seconds;
        let resident: HashSet<u64> = self.tiles.keys().copied().collect();
        let tile_alpha: HashMap<u64, f32> = ordered
            .iter()
            .map(|&key| {
                let uploaded_at = self.tiles.get(&key).map(|t| t.uploaded_at).unwrap_or(0.0);
                (
                    key,
                    select::tile_lod_alpha(
                        key,
                        uploaded_at,
                        now,
                        select::LOD_FADE_SECONDS,
                        &resident,
                    ),
                )
            })
            .collect();

        // Symbol pre-pass: collision runs GLOBALLY across tiles and layers, but
        // draws stay per (tile, layer) below. Build one candidate per shaped
        // label with its screen box at this frame's text size, run the greedy
        // rank-ordered placer once, and hand the accept-set to `record_symbol`.
        // Without this every shaped label draws and z10 is an unreadable pile.
        let accepted = self.place_symbols(camera, layers, &ordered, extent, filter);
        // Task-17 pick snapshot: the accepted labels with their screen boxes,
        // names, kinds and anchor geo — refreshed every frame so pickLabels
        // answers the frame the user sees, not a stale one.
        self.refresh_placed(camera, layers, &accepted, extent);

        // 3D terrain (WS-G): the DEM-displaced ground, drawn first (step 0) with depth on so it is
        // the ground the flat layers below sit over. A tile with no heightmap draws nothing here and
        // keeps its flat `earth` fill in the loop; at pitch 0 the grid collapses to the flat
        // footprint, so the overhead map is unchanged.
        self.record_terrain(command_buffer, camera, layers, palette, &mut submitted);

        // Symbol layers are collected here and drawn after the buildings pass rather than inside
        // the loop. Buildings have no depth interaction with symbols (the symbol pipelines are
        // `Depth::Off`, so they can never *fail* a test) — whichever is issued last simply paints
        // over the other, and issuing buildings last hid every tile-baked POI icon and label
        // behind them. `(key, layer index)`, replayed in the same order the loop met them.
        let mut deferred_symbols: Vec<(u64, usize)> = Vec::new();

        let camera_z = camera.zoom.floor().clamp(0.0, 22.0) as u8;
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
            for key in &ordered {
                // Symbol layers emit per frame at the frame's text size from the
                // tile's shaped candidates (see above): deferred to after the buildings
                // pass so labels are not painted over, then drawn with `&mut self` for
                // their transient uploads.
                if layer.kind == LayerKind::Symbol {
                    // Deeper tiles contribute no candidates (see `place_symbols`), so their
                    // labels are already suppressed by the accept set. Skipping the job here
                    // only avoids scanning their labels to emit nothing.
                    if self.tiles.get(key).is_some_and(|tile| tile.z > camera_z) {
                        continue;
                    }
                    deferred_symbols.push((*key, index));
                    continue;
                }
                // Copy the draw's inputs out, then issue them through the owned `device`
                // clone: `record_symbol` above takes `&mut self`, so this path must not
                // hold a `self.tiles` borrow either.
                draws.clear();
                if let Some(tile) = self.tiles.get(key) {
                    for mesh in tile.layers.iter().filter(|l| l.layer_index == index) {
                        draws.push((
                            tile.z,
                            tile.x,
                            tile.y,
                            mesh.kind,
                            mesh.vertices.buffer,
                            mesh.indices.buffer,
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
                for &(tz, tx, ty, kind, vbuf, ibuf, count, color_override, lane) in &draws
                {
                    if bound != Some(kind) {
                        let pipeline = match kind {
                            LayerKind::Fill => self.pipelines.fill,
                            LayerKind::Line => self.pipelines.line,
                            // Symbols never take this path (drawn above); this arm is
                            // unreachable but the match must stay exhaustive.
                            LayerKind::Symbol => continue,
                        };
                        device.cmd_bind_pipeline(
                            command_buffer,
                            vk::PipelineBindPoint::GRAPHICS,
                            pipeline,
                        );
                        bound = Some(kind);
                    }

                    let (half_width_px, half_gap_px) = stroke.half_px(camera.density);
                    // `misc.y` tells the fragment shader whether it has to antialias the edge
                    // itself. With MSAA the rasteriser already resolves partial coverage, and
                    // applying a second coverage term on top fades a diagonal road twice — at
                    // z6 a 2.9px highway crossing the screen at an angle lost all but one
                    // pixel of its strength, while the near-vertical stretches of the same
                    // road kept three.
                    let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);
                    // A mesh that carries its own colour keeps it **unshifted**: transit
                    // colours are operator brand colours, and the reference draws them
                    // literally. Every other layer goes through `color(palette)`, which
                    // swaps in the dark column and blends toward the background when muted;
                    // doing that to a route colour would turn a network's own red into
                    // whatever the dark basemap thinks red should be. The opacity ramp still
                    // applies, because that is per-frame paint rather than palette.
                    let base = color_override.unwrap_or_else(|| layer.color(palette));
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
                        misc: [camera.tile_span_px(tz), edge_aa, lateral_px, camera.time_seconds],
                        morph: [tile_fade, 0.0, 0.0, 0.0],
                    };
                    let layout = self.pipelines.layout;
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
                    // failing loudly.
                    device.cmd_bind_index_buffer(command_buffer, ibuf, 0, vk::IndexType::UINT32);
                    device.cmd_draw_indexed(command_buffer, count, 1, 0, 0, 0);
                    submitted += 1;
                }
            }
        }

        // Overlays last, over every tile and inside the same render pass, so they are
        // presented in the same frame and from the same camera value as the basemap under
        // them. Binding the overlay pipeline invalidates `bound`, which is why this comes
        // after the layer loop rather than anywhere inside it.
        //
        // The order between the three is the reading order the driver needs. The region
        // scrim is a property of the basemap, so it goes first and the route is *not*
        // dimmed by it — a route you are following must not fade because a details sheet
        // is open. The route then goes under the puck, because the puck is where you are
        // and it has to stay visible where it sits on top of the line it is following.
        // Traffic sits on the roads it colours, so it draws after the basemap layer loop but
        // before the region scrim — it is basemap detail and should dim with everything else
        // when a region is selected, unlike the route.
        // Road carriageways: over the flat layer loop, because the surface and its markings
        // replace the road fills at this zoom, and under the buildings and deferred symbols
        // below, because a carriageway is flat basemap like every other road layer.
        self.record_carriageways(command_buffer, camera, layers, palette, &ordered, &mut submitted);
        // 3D buildings: after the flat basemap so they paint over it, depth-tested so they occlude
        // one another. Gated to z14+; at pitch 0 the building matrix collapses height to the
        // footprint, so the flat overhead map is unchanged. Before the deferred symbols, so POI
        // icons and labels are not buried behind a tower.
        self.record_buildings(command_buffer, camera, layers, palette, &mut submitted);
        for (key, index) in deferred_symbols {
            self.record_symbol(
                command_buffer,
                key,
                index,
                &layers[index],
                camera,
                palette,
                &accepted,
                &mut submitted,
                &mut bound,
            );
        }
        self.record_traffic(command_buffer, camera, &mut submitted);
        self.record_arrows(command_buffer, camera, layers, &mut submitted);
        self.record_region_mask(command_buffer, camera, &mut submitted);
        self.record_route(command_buffer, camera, &mut submitted);
        self.record_overlays(command_buffer, camera, palette, &mut submitted);

        self.submitted_draws.set(submitted);
        device.cmd_end_render_pass(command_buffer);
        device.end_command_buffer(command_buffer).map_err(|e| format!("end_command_buffer {e:?}"))
    }

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
    unsafe fn record_carriageways(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        ordered: &[u64],
        submitted: &mut usize,
    ) {
        let device = &self.context.device;
        let floor = camera.zoom.floor().clamp(0.0, 22.0) as u8;
        let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);
        let mut bound = false;
        for key in ordered {
            let Some(tile) = self.tiles.get(key) else { continue };
            if tile.carriageways.is_empty() {
                continue;
            }
            let tile_to_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
            let tile_span_px = camera.tile_span_px(tile.z);
            let yellow = f32::from(tile.yellow_centre);
            for road in &tile.carriageways {
                let Some(layer) = layers.get(road.layer_index) else { continue };
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
                // to match the white it antialiases them against.
                let asphalt = scale_alpha(layer.color(palette), layer.opacity_at(camera.zoom));
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
                    // for.
                    morph: MORPH_NONE,
                };
                device.cmd_push_constants(
                    command_buffer,
                    self.pipelines.layout,
                    vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    0,
                    push.as_bytes(),
                );
                device.cmd_bind_vertex_buffers(command_buffer, 0, &[road.vertices.buffer], &[0]);
                device.cmd_bind_index_buffer(
                    command_buffer,
                    road.indices.buffer,
                    0,
                    vk::IndexType::UINT32,
                );
                device.cmd_draw_indexed(command_buffer, road.index_count, 1, 0, 0, 0);
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
    unsafe fn record_arrows(
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
        let Some(carriageway) = crate::style::road_carriageway_layer(layers) else { return };
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
    unsafe fn draw_fill_batch(
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
        device.cmd_bind_pipeline(command_buffer, vk::PipelineBindPoint::GRAPHICS, self.pipelines.fill);
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
    unsafe fn record_terrain(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        submitted: &mut usize,
    ) {
        let Some(earth) =
            layers.iter().find(|l| l.source_layer_id == tilecodec::mamaps::dict::LAYER_EARTH)
        else {
            return;
        };
        let opacity = earth.opacity_at(camera.zoom);
        if opacity <= 0.0 {
            return;
        }
        let colour = argb_to_rgba(scale_alpha(earth.color(palette), opacity));
        let device = &self.context.device;
        let mut bound = false;
        for tile in self.tiles.values() {
            let Some(terrain) = &tile.terrain else { continue };
            if !bound {
                device.cmd_bind_pipeline(
                    command_buffer,
                    vk::PipelineBindPoint::GRAPHICS,
                    self.pipelines.terrain,
                );
                bound = true;
            }
            let push = Push {
                tile_to_clip: camera.tile_to_clip(tile.z, tile.x, tile.y),
                color: colour,
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
    unsafe fn record_buildings(
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
            let Some(buildings) = &tile.buildings else { continue };
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

    /// Draw the live-traffic overlay: each resident component segment, coloured from the
    /// pushed table.
    ///
    /// The region-mask precedent, applied to lines: the geometry comes from the archive and is
    /// resident, and the only per-frame input is a lightweight dynamic table — here
    /// `component_id → ARGB` rather than one selected region id. A segment whose id is not in
    /// the table draws **nothing**: pushing only the segments the server has a reading for keeps
    /// the overlay to the roads traffic actually covers, rather than laying a neutral tint over
    /// the whole network (which would merely repaint roads the basemap already drew). The
    /// no-data look is therefore "unchanged basemap road", which is the cleaner of the two.
    ///
    /// Allocation-free and re-tessellation-free: colour is a push constant read from the map,
    /// the width is one constant evaluated per frame, and the buffers were uploaded with the
    /// tile. A new speed reading only replaces [`traffic_colors`](Self::traffic_colors), so this
    /// path picks it up on the next frame with no geometry work at all.
    unsafe fn record_traffic(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        submitted: &mut usize,
    ) {
        // Gated on the toggle and empty until the host pushes a reading, so the common case —
        // traffic off, or no data yet — is one comparison and out.
        if !self.traffic_enabled || self.traffic_colors.is_empty() {
            return;
        }
        let device = &self.context.device;
        let half_width_px = TRAFFIC_WIDTH_DP * camera.density / 2.0;
        let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);

        let mut bound = false;
        for tile in self.tiles.values() {
            if tile.traffic.is_empty() {
                continue;
            }
            let tile_to_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
            let tile_span_px = camera.tile_span_px(tile.z);
            for segment in &tile.traffic {
                // The dynamic half of the pass: a segment the host has no reading for is not
                // drawn, so the table's size — not the archive's — bounds the draw calls.
                let Some(&argb) = self.traffic_colors.get(&segment.id) else { continue };
                if !bound {
                    device.cmd_bind_pipeline(
                        command_buffer,
                        vk::PipelineBindPoint::GRAPHICS,
                        self.pipelines.line,
                    );
                    bound = true;
                }
                let push = Push {
                    tile_to_clip,
                    color: argb_to_rgba(argb),
                    // A solid band. `line.frag` short-circuits on a zero dash gap before it
                    // touches the clock, so this reads no animation at all.
                    line: [half_width_px, 0.0, 0.0, 0.0],
                    misc: [tile_span_px, edge_aa, 0.0, camera.time_seconds],
                    morph: [1.0, 0.0, 0.0, 0.0],
                };
                device.cmd_push_constants(
                    command_buffer,
                    self.pipelines.layout,
                    vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    0,
                    push.as_bytes(),
                );
                device.cmd_bind_vertex_buffers(command_buffer, 0, &[segment.vertices.buffer], &[0]);
                device.cmd_bind_index_buffer(
                    command_buffer,
                    segment.indices.buffer,
                    0,
                    vk::IndexType::UINT32,
                );
                device.cmd_draw_indexed(command_buffer, segment.index_count, 1, 0, 0, 0);
                *submitted += 1;
            }
        }
    }

    /// Rasterise the selected region into the stencil, then dim everything it did not cover.
    ///
    /// Two passes over one attachment rather than any geometric cut: the region arrives as one
    /// clipped polygon per tile, and rasterising every piece with `REPLACE` unions them in the
    /// stencil for free. Overlapping pieces and the self-touching rings the tile clipper produces
    /// are both harmless to a rasteriser, which is exactly what defeated the two attempts to do
    /// this with a polygon boolean.
    unsafe fn record_region_mask(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        submitted: &mut usize,
    ) {
        let Some(selected) = self.selected_region else { return };
        let device = &self.context.device;

        let mut any = false;
        for tile in self.tiles.values() {
            for region in tile.regions.iter().filter(|r| r.id == selected) {
                if !any {
                    device.cmd_bind_pipeline(
                        command_buffer,
                        vk::PipelineBindPoint::GRAPHICS,
                        self.pipelines.mask,
                    );
                    any = true;
                }
                let push = Push {
                    tile_to_clip: camera.tile_to_clip(tile.z, tile.x, tile.y),
                    color: [0.0; 4],
                    line: [0.0; 4],
                    misc: [0.0; 4],
                    morph: MORPH_NONE,
                };
                device.cmd_push_constants(
                    command_buffer,
                    self.pipelines.layout,
                    vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    0,
                    push.as_bytes(),
                );
                device.cmd_bind_vertex_buffers(
                    command_buffer,
                    0,
                    &[region.vertices.buffer],
                    &[0],
                );
                device.cmd_bind_index_buffer(
                    command_buffer,
                    region.indices.buffer,
                    0,
                    vk::IndexType::UINT32,
                );
                device.cmd_draw_indexed(command_buffer, region.index_count, 1, 0, 0, 0);
                *submitted += 1;
            }
        }
        // No piece of the region is resident - the map has been panned away from it, or its
        // tiles have not landed yet. Dimming the whole screen would be worse than dimming none
        // of it, so the scrim is skipped rather than drawn over everything.
        if !any {
            return;
        }

        let push = Push {
            // The quad is already in clip space, so the vertex shader must not move it.
            tile_to_clip: IDENTITY,
            color: argb_to_rgba(SCRIM_COLOR),
            line: [0.0; 4],
            misc: [0.0; 4],
            morph: MORPH_NONE,
        };
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.scrim,
        );
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[self.quad.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            self.quad.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );
        device.cmd_draw_indexed(command_buffer, QUAD_INDICES.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }

    /// Draw the navigation route, above the basemap and below the puck.
    ///
    /// The casing is one draw of the whole index buffer at the wider half-width in the
    /// casing colour; the fills are then one draw per coloured run, each of its own index
    /// slice in its own colour, over the casing. `stroke` bakes no width into a vertex, so
    /// the same geometry drawn wider underneath *is* the outline — no second mesh — and one
    /// casing over the lot keeps the outline continuous across the colour changes.
    ///
    /// Allocation-free, like every other per-frame path here: the buffers were uploaded
    /// when the route was set, and everything that varies per frame is a matrix and the
    /// push blocks built on the stack. The matrix is the overlay sibling of `tile_to_clip`,
    /// so the route picks up the camera's bearing exactly as the tiles under it do.
    unsafe fn record_route(
        &self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        submitted: &mut usize,
    ) {
        let Some(route) = &self.route else { return };
        let device = &self.context.device;
        let (origin, span) = route.placement.at_zoom(camera.zoom);
        let matrix = camera.world_quad_to_clip(origin, span);
        // What `line.vert` divides a pixel offset by to reach local units. The route's
        // square stands in for a tile here, which is the whole reason the two share a
        // vertex format.
        let span_px = (span * camera.density as f64) as f32;
        let edge_aa = f32::from(self.swapchain.samples == vk::SampleCountFlags::TYPE_1);

        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.line,
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[route.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            route.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );

        // Casing first (the whole route at the wider width, one colour), then each run's
        // fill over it (its own index slice, its own colour). A first entry with the whole
        // buffer stands in for the casing when there is one. Both are solid: the fills used to
        // carry a scrolling marching-ants, which was removed as unwanted motion. It carried no
        // meaning — `RouteOverlay` has no per-segment dashed flag, so walking, transit and
        // driving legs were all dashed alike and the mode is conveyed by colour.
        let casing = route
            .placement
            .casing_half(camera.density)
            .map(|half| (route.placement.style.casing_color, half, route.index_count, 0u32));
        let fill_half = route.placement.fill_half(camera.density);
        let fills =
            route.segments.iter().map(|s| (s.color, fill_half, s.index_count, s.index_offset));
        for (color, half_width_px, index_count, first_index) in casing.into_iter().chain(fills) {
            let push = Push {
                tile_to_clip: matrix,
                color: argb_to_rgba(color),
                line: [half_width_px, 0.0, 0.0, 0.0],
                misc: [span_px, edge_aa, 0.0, camera.time_seconds],
                morph: [1.0, 0.0, 0.0, 0.0],
            };
            device.cmd_push_constants(
                command_buffer,
                self.pipelines.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                push.as_bytes(),
            );
            device.cmd_draw_indexed(command_buffer, index_count, 1, first_index, 0, 0);
            *submitted += 1;
        }
    }

    /// Draw this frame's overlays on top of every tile.
    ///
    /// Called with the render pass still open: the viewport and scissor are already set
    /// and blending is the same straight src-alpha-over the tile layers use, so an
    /// overlay only has to bind its pipeline and push its own state.
    ///
    /// Draw order is fixed here rather than by vec position: markers (and WS-F's vehicles) first,
    /// the puck last, so the user's location stays on top of the pins around it. `&mut self`
    /// because the marker path uploads a transient buffer through
    /// [`draw_symbol_batch`](Self::draw_symbol_batch), same as the symbol layers.
    unsafe fn record_overlays(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        palette: Palette,
        submitted: &mut usize,
    ) {
        // Copy the overlay state out so no borrow of `self.overlays` lives across the `&mut self`
        // marker draw below (which uploads a transient buffer).
        let mut markers: Vec<Marker> = Vec::new();
        let mut vehicles: Vec<Marker> = Vec::new();
        let mut puck: Option<UserPuck> = None;
        for overlay in &self.overlays {
            match overlay {
                Overlay::Puck(p) => puck = Some(*p),
                Overlay::Markers(m) => markers.extend_from_slice(m),
                Overlay::Vehicles(v) => vehicles.extend_from_slice(v),
            }
        }

        // Vehicles first (lowest), then the app pins over them, then the puck on top: a pin the
        // user placed and can tap outranks a simulated vehicle sprite at the same spot, and the
        // user's own location outranks both. Both go through the shared billboarded sprite path.
        if !vehicles.is_empty() {
            self.draw_markers(command_buffer, camera, palette, &vehicles, submitted);
        }
        if !markers.is_empty() {
            self.draw_markers(command_buffer, camera, palette, &markers, submitted);
        }

        // The puck last, so it sits on top of any pin at the same spot. The shared unit quad, an
        // analytic shader, and one push constant — nothing allocated.
        let Some(puck) = puck else { return };
        let device = &self.context.device;
        let density = camera.density;
        let push = Push {
            tile_to_clip: camera.screen_quad_to_clip(puck.lon, puck.lat, PUCK_QUAD_DP as f64),
            color: argb_to_rgba(PUCK_COLOR),
            line: [
                PUCK_RIM_DP * density,
                PUCK_DOT_DP * density,
                PUCK_CONE_DP * density,
                PUCK_CONE_HALF_STROKE_DP * density,
            ],
            misc: [
                puck.bearing.unwrap_or(0.0).to_radians(),
                f32::from(puck.bearing.is_some()),
                PUCK_QUAD_DP * density,
                camera.time_seconds,
            ],
            morph: MORPH_NONE,
        };
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipelines.puck,
        );
        device.cmd_push_constants(
            command_buffer,
            self.pipelines.layout,
            vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
            0,
            push.as_bytes(),
        );
        device.cmd_bind_vertex_buffers(command_buffer, 0, &[self.quad.vertices.buffer], &[0]);
        device.cmd_bind_index_buffer(
            command_buffer,
            self.quad.indices.buffer,
            0,
            vk::IndexType::UINT32,
        );
        device.cmd_draw_indexed(command_buffer, QUAD_INDICES.len() as u32, 1, 0, 0, 0);
        *submitted += 1;
    }

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
    unsafe fn draw_markers(
        &mut self,
        command_buffer: vk::CommandBuffer,
        camera: &Camera,
        palette: Palette,
        markers: &[Marker],
        submitted: &mut usize,
    ) {
        let Some(sprite_set) = self.sprite_set else { return };
        let atlas = crate::tile::sprite::atlas();
        // The sheet is the light half over the dark one; dark mode adds this to every `v`, exactly
        // as `emit_icon` does, so a palette switch stays a per-frame emit rather than a re-upload.
        let dv = if palette.variant == crate::style::Variant::Dark { atlas.dark_v_offset() } else { 0.0 };

        let mut vertices: Vec<f32> = Vec::with_capacity(markers.len() * 4 * 4);
        let mut indices: Vec<u32> = Vec::with_capacity(markers.len() * 6);
        for marker in markers {
            let Some(sprite) = crate::marker::icon_sprite_name(marker.icon).and_then(|n| atlas.get(n))
            else {
                continue;
            };
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
            // Draw the icon at `MARKER_SIZE_DP` on its larger side, keeping its aspect ratio. With
            // `radius_dp = 1.0` above, a local coordinate is one Dp, so these half-extents are Dp.
            let scale = MARKER_SIZE_DP / sprite.width_dp.max(sprite.height_dp).max(1e-3);
            let hw = (sprite.width_dp * scale * 0.5) as f64;
            let hh = (sprite.height_dp * scale * 0.5) as f64;
            let (xu, yu) = (m[0] as f64, m[1] as f64); // local +u axis, clip space
            let (xv, yv) = (m[4] as f64, m[5] as f64); // local +v axis, clip space
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
            for (lu, lv, tu, tv) in corners {
                let ox = (lu * xu + lv * xv) / w;
                let oy = (lu * yu + lv * yv) / w;
                vertices.push((cx + ox) as f32);
                vertices.push((cy + oy) as f32);
                vertices.push(tu);
                vertices.push(tv);
            }
            indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
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

    /// Draw one tile's one symbol layer: emit its shaped labels at the frame's
    /// text size, upload a transient buffer pair, and draw it with the symbol
    /// pipeline bound to the glyph atlas set.
    ///
    /// Only labels in `accepted` (see [`place_symbols`](Self::place_symbols))
    /// emit; the rest lost their collisions this frame.
    #[allow(clippy::too_many_arguments)]
    unsafe fn record_symbol(
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
    ) {
        use crate::tile::{placement, symbol};
        let Some(glyph_set) = self.glyph_set else { return };
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
        let Some(tile) = self.tiles.get(&key) else { return };
        // The tile's coordinates, copied out so the `self.tiles` borrow below is held only by
        // `tile.labels` and ends at the batch loop — the `&mut self` uploads come after it.
        let (tz, tx, ty) = (tile.z, tile.x, tile.y);
        let tile_span_px = camera.tile_span_px(tz);
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
        let flat_clip = Camera { pitch_deg: 0.0, ..*camera }.tile_to_clip(tz, tx, ty);
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
        for (label_idx, label) in
            tile.labels.iter().enumerate().filter(|(_, l)| l.layer_index == layer_index)
        {
            let id = placement::candidate_id(tz, tx, ty, layer_index, label_idx);
            let Some(&(flipped, _)) = accepted.get(&id) else { continue };
            // Draw at whichever anchor the placer actually accepted, or the label lands
            // on the side its box was rejected for.
            let anchor = if flipped { alternate.unwrap_or(primary) } else { primary };
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
                &mut batch.1,
                &mut batch.2,
            );
        }
        batches.retain(|(_, _, indices)| !indices.is_empty());
        if batches.is_empty() && icon_indices.is_empty() {
            return;
        }
        let halo = argb_to_rgba(layer.halo_color(palette));
        let color = argb_to_rgba(scale_alpha(layer.color(palette), layer.opacity_at(camera.zoom)));
        let sdf_per_em = crate::tile::glyph::atlas().sdf_per_em;

        // Icons first, so the label's halo paints over the icon's edge rather than under
        // it — the order MapLibre draws them in.
        if let Some(sprite_set) = self.sprite_set.filter(|_| !icon_indices.is_empty()) {
            let push = Push {
                tile_to_clip: tile_clip,
                // Only the alpha is read by `sprite.frag`: an icon draws in its own
                // colours, since the reference sets no `icon-color`.
                color,
                // `sprite.frag` reads none of `line`; `w` is the billboard flag the shared vertex
                // shader reads, so an icon stands up under tilt on the same terms as its label.
                line: [0.0, 0.0, 0.0, billboard_flag],
                misc: [tile_span_px, 0.0, 0.0, 0.0],
                // The pitch-0 linear 2x2, as for the text below — the same matrix, so the icon and
                // the name beside it resolve their screen offsets identically.
                morph: [ortho2x2[0], ortho2x2[1], ortho2x2[2], ortho2x2[3]],
            };
            self.draw_symbol_batch(
                command_buffer,
                self.pipelines.icon,
                sprite_set,
                &icon_vertices,
                &icon_indices,
                &push,
                submitted,
            );
            // The icon pipeline shares `LayerKind::Symbol`, so this still forces the
            // fill/line path to rebind. The symbol pipeline is bound again immediately
            // below whenever there is any text — and a label with an icon always has
            // text, because `shape_label` returns nothing for an empty name.
            *bound = Some(LayerKind::Symbol);
        }

        for (text_px, vertices, indices) in &batches {
            // Halo width from the style (authored text-halo-width, 1px), in device px
            // like the text size beside it; text color + opacity per palette.
            let push = Push {
                tile_to_clip: tile_clip,
                color,
                line: [*text_px, layer.halo_width * camera.density, sdf_per_em, billboard_flag],
                misc: [tile_span_px, halo[0], halo[1], halo[2]],
                // Repurposed for the symbol billboard pipeline: the pitch-0 tile matrix's linear
                // 2x2, so the shader can add a screen-constant glyph offset under tilt. The symbol
                // fragment shader does not read `morph`, so this collides with nothing.
                morph: [ortho2x2[0], ortho2x2[1], ortho2x2[2], ortho2x2[3]],
            };
            self.draw_symbol_batch(
                command_buffer,
                self.pipelines.symbol,
                glyph_set,
                vertices,
                indices,
                &push,
                submitted,
            );
            *bound = Some(LayerKind::Symbol);
        }
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
    unsafe fn draw_symbol_batch(
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
