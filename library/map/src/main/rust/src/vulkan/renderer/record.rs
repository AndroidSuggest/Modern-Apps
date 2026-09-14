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
}
