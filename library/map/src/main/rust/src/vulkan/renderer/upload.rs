use super::{
    BuildingBuffers, CarriagewayBuffers, LayerBuffers, RegionBuffers, Renderer, ResidentTile,
    TerrainBuffers, TrafficBuffers, FRAMES_IN_FLIGHT,
};
use crate::tile::geometry::TileMesh;
use crate::tile::select;
use crate::vulkan::buffers::Buffer;
use crate::vulkan::context::Context;
use crate::vulkan::images::{AtlasSet, SampledImage};
use ash::vk;
use std::collections::HashSet;

impl Renderer {
    /// Upload a tile's geometry, replacing anything already resident for it.
    ///
    /// Same-format meshes share one vertex + one index buffer per pool (see
    /// [`upload_packed`](crate::vulkan::buffers::upload_packed)): fills (+ the region-mask
    /// shapes, which are position-only fills) in [`flat`](ResidentTile::flat), strokes (+ the
    /// traffic segments, which ride the stroke vertex) in [`lines`](ResidentTile::lines), and
    /// ribbon carriageways in [`ribbons`](ResidentTile::ribbons). Buildings and terrain are
    /// already one mesh per tile and keep their own pair. A dense tile costs ~5 allocations
    /// instead of ~60-100, on the same Choreographer callback as before.
    pub fn upload(&mut self, key: u64, mesh: &TileMesh) -> Result<(), String> {
        use crate::style::LayerKind;
        use crate::tess::{fill, ribbon, stroke};
        use crate::vulkan::buffers::upload_packed;
        let instance = &self.context.instance;
        let physical = self.context.physical_device;
        let device = &self.context.device;
        // Fills and region shapes share the position-only flat pool; collect which flat mesh
        // each belongs to so the draw slices line up with the packed order below.
        let mut flat_meshes: Vec<(&[f32], &[u32])> = Vec::new();
        let mut flat_region_at: Vec<usize> = Vec::new();
        let mut flat_layer_at: Vec<usize> = Vec::new();
        for layer_mesh in &mesh.meshes {
            if layer_mesh.kind != LayerKind::Fill || layer_mesh.indices.is_empty() {
                continue;
            }
            flat_layer_at.push(flat_meshes.len());
            flat_meshes.push((&layer_mesh.vertices, &layer_mesh.indices));
        }
        for region in &mesh.regions {
            flat_region_at.push(flat_meshes.len());
            flat_meshes.push((&region.vertices, &region.indices));
        }
        // Strokes and traffic segments share the 7-float stroke pool.
        let mut line_meshes: Vec<(&[f32], &[u32])> = Vec::new();
        let mut line_layer_at: Vec<usize> = Vec::new();
        let mut line_traffic_at: Vec<usize> = Vec::new();
        for layer_mesh in &mesh.meshes {
            if layer_mesh.kind != LayerKind::Line || layer_mesh.indices.is_empty() {
                continue;
            }
            line_layer_at.push(line_meshes.len());
            line_meshes.push((&layer_mesh.vertices, &layer_mesh.indices));
        }
        for segment in &mesh.traffic {
            if segment.indices.is_empty() {
                continue;
            }
            line_traffic_at.push(line_meshes.len());
            line_meshes.push((&segment.vertices, &segment.indices));
        }
        let mut ribbon_meshes: Vec<(&[f32], &[u32])> = Vec::new();
        for road in &mesh.carriageways {
            if road.indices.is_empty() {
                continue;
            }
            ribbon_meshes.push((&road.vertices, &road.indices));
        }
        // SAFETY: uploading fresh GPU buffers for a tile whose previous buffers (if any)
        // are retired below, after every buffer below has landed.
        let flat = unsafe {
            upload_packed(
                instance,
                physical,
                device,
                fill::FLOATS_PER_VERTEX,
                &flat_meshes,
            )?
        };
        let lines = unsafe {
            upload_packed(
                instance,
                physical,
                device,
                stroke::FLOATS_PER_VERTEX,
                &line_meshes,
            )?
        };
        let ribbons = unsafe {
            upload_packed(
                instance,
                physical,
                device,
                ribbon::FLOATS_PER_VERTEX,
                &ribbon_meshes,
            )?
        };
        let mut flat_layer_iter = flat_layer_at.iter();
        let mut line_layer_iter = line_layer_at.iter();
        let mut layers: Vec<LayerBuffers> = Vec::with_capacity(mesh.meshes.len());
        for layer_mesh in &mesh.meshes {
            if layer_mesh.indices.is_empty() {
                continue;
            }
            // The `*_at` vectors parallel their pool's mesh order: the nth fill-kind mesh is
            // the nth entry of the flat pool's first-index list, and likewise for lines.
            let first_index = match layer_mesh.kind {
                LayerKind::Fill => {
                    let Some(at) = flat_layer_iter.next() else {
                        continue;
                    };
                    let Some((_, _, first)) = flat.as_ref() else {
                        continue;
                    };
                    first[*at]
                }
                LayerKind::Line => {
                    let Some(at) = line_layer_iter.next() else {
                        continue;
                    };
                    let Some((_, _, first)) = lines.as_ref() else {
                        continue;
                    };
                    first[*at]
                }
                LayerKind::Symbol => continue,
            };
            layers.push(LayerBuffers {
                layer_index: layer_mesh.layer_index,
                kind: layer_mesh.kind,
                first_index,
                index_count: layer_mesh.indices.len() as u32,
                color_override: layer_mesh.color_override,
                lane: layer_mesh.lane,
            });
        }
        let flat_first: Vec<u32> = flat.as_ref().map(|(_, _, f)| f.clone()).unwrap_or_default();
        let line_first: Vec<u32> = lines
            .as_ref()
            .map(|(_, _, f)| f.clone())
            .unwrap_or_default();
        let mut regions = Vec::with_capacity(mesh.regions.len());
        for (n, region) in mesh.regions.iter().enumerate() {
            let Some(&first_index) = flat_first.get(flat_region_at[n]) else {
                continue;
            };
            regions.push(RegionBuffers {
                id: region.id,
                first_index,
                index_count: region.indices.len() as u32,
                rings: region.rings.clone(),
                area: region.area,
                level: region.level,
            });
        }
        let mut traffic = Vec::with_capacity(mesh.traffic.len());
        for (n, segment) in mesh.traffic.iter().enumerate() {
            if segment.indices.is_empty() {
                continue;
            }
            let Some(&first_index) = line_first.get(line_traffic_at[n]) else {
                continue;
            };
            traffic.push(TrafficBuffers {
                id: segment.id,
                first_index,
                index_count: segment.indices.len() as u32,
            });
        }
        let ribbon_first: Vec<u32> = ribbons
            .as_ref()
            .map(|(_, _, f)| f.clone())
            .unwrap_or_default();
        let mut carriageways = Vec::with_capacity(mesh.carriageways.len());
        let mut ri = 0usize;
        for road in &mesh.carriageways {
            if road.indices.is_empty() {
                continue;
            }
            let Some(&first_index) = ribbon_first.get(ri) else {
                continue;
            };
            ri += 1;
            carriageways.push(CarriagewayBuffers {
                layer_index: road.layer_index,
                lanes: road.lanes,
                split: road.split,
                oneway: road.oneway,
                first_index,
                index_count: road.indices.len() as u32,
            });
        }
        // The tile's 3D buildings, if any: one combined mesh, uploaded like the rest.
        let buildings = if mesh.buildings.indices.is_empty() {
            None
        } else {
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &mesh.buildings.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &mesh.buildings.indices,
                ) {
                    Ok(buffer) => buffer,
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                Some(BuildingBuffers {
                    vertices,
                    indices,
                    index_count: mesh.buildings.indices.len() as u32,
                })
            }
        };
        // The tile's terrain grid, if any: one combined mesh, uploaded like the buildings above.
        let terrain = if mesh.terrain.indices.is_empty() {
            None
        } else {
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &mesh.terrain.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &mesh.terrain.indices,
                ) {
                    Ok(buffer) => buffer,
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                Some(TerrainBuffers {
                    vertices,
                    indices,
                    index_count: mesh.terrain.indices.len() as u32,
                })
            }
        };
        let tile = ResidentTile {
            flat: flat.map(|(v, i, _)| (v, i)),
            lines: lines.map(|(v, i, _)| (v, i)),
            ribbons: ribbons.map(|(v, i, _)| (v, i)),
            layers,
            buildings,
            terrain,
            regions,
            traffic,
            carriageways,
            yellow_centre: mesh.yellow_centre,
            labels: mesh.labels.clone(),
            arrows: mesh.arrows.clone(),
            heightmap: mesh.heightmap.clone(),
            z: mesh.z,
            x: mesh.x,
            y: mesh.y,
            // Stamp the tile with the latest frame clock so the fade below measures from the
            // moment its GPU buffers landed. Before the first frame there is no clock yet;
            // 0.0 makes `now - uploaded_at` large, so a startup tile is fully opaque at once
            // (nothing is under it to fade over anyway).
            uploaded_at: self.last_camera.map(|c| c.time_seconds).unwrap_or(0.0),
            generation: mesh.generation,
        };
        if let Some(previous) = self.tiles.insert(key, tile) {
            self.retire(previous);
        }
        Ok(())
    }

    /// Drop resident tiles that are neither wanted nor useful as a stand-in, and enforce the
    /// residency cap.
    ///
    /// `keep` is the visible tiles and their ancestors, in the order [`select::resident_set`]
    /// produced them — coarsest first. `visible` is needed separately because descendants cannot
    /// be enumerated into a keep list without naming tiles that were never fetched; they are
    /// recognised here, against what is actually resident.
    ///
    /// This is also the **only** bound on GPU memory. Tiles live until they fall out of this, so
    /// the cap is not belt-and-braces: without it, retaining descendants would mean every deep
    /// tile visited during a session stays resident for as long as the camera sits above it.
    pub fn retain(&mut self, keep: &[u64], visible: &[select::TileId], cap: usize) {
        let visible_keys: HashSet<u64> = visible.iter().map(|t| t.key()).collect();
        let wanted: HashSet<u64> = keep.iter().copied().collect();

        // Rank by how much is lost if it goes, because the cap has to evict *something* and the
        // stand-ins are what it should reach for first.
        let mut ranked: Vec<(u8, u64)> = self
            .tiles
            .keys()
            .map(|&key| {
                let rank = if visible_keys.contains(&key) {
                    0 // on screen at its own zoom; evicting this is the blank frame itself
                } else if wanted.contains(&key) {
                    1 // an ancestor: one coarse tile covers many fine ones, so cheap to hold
                } else if select::stands_in_for_visible(key, visible, select::DESCENDANT_DEPTH) {
                    2 // a descendant: only covers a fraction of the screen, so the first to go
                } else {
                    3 // unrelated to anything on screen
                };
                (rank, key)
            })
            .collect();
        ranked.sort_unstable();

        let doomed: Vec<u64> = ranked
            .iter()
            .enumerate()
            .filter(|(at, (rank, _))| *rank == 3 || *at >= cap)
            .map(|(_, (_, key))| *key)
            .collect();
        for key in doomed {
            if let Some(tile) = self.tiles.remove(&key) {
                self.retire(tile);
            }
        }
    }

    /// Hold a tile's buffers until every in-flight frame that might reference them has
    /// finished.
    ///
    /// Freeing them immediately is the classic Vulkan use-after-free: a command buffer
    /// submitted last frame can still be executing, and destroying its vertex buffer is
    /// undefined behaviour that usually looks like corrupted geometry rather than a crash.
    fn retire(&mut self, tile: ResidentTile) {
        self.retiring.push((FRAMES_IN_FLIGHT, tile));
    }

    /// Free anything whose grace period has expired.
    pub(super) fn collect_retired(&mut self) {
        self.collect_moon_retired();
        let device = &self.context.device;
        self.retiring.retain_mut(|(remaining, tile)| {
            if *remaining > 0 {
                *remaining -= 1;
                return true;
            }
            unsafe {
                // Draw slices are plain indices into the pools — nothing per draw to free.
                // Each pool is one pair no matter how many meshes packed into it.
                if let Some((v, i)) = &tile.flat {
                    v.destroy(device);
                    i.destroy(device);
                }
                if let Some((v, i)) = &tile.lines {
                    v.destroy(device);
                    i.destroy(device);
                }
                if let Some((v, i)) = &tile.ribbons {
                    v.destroy(device);
                    i.destroy(device);
                }
                if let Some(buildings) = &tile.buildings {
                    buildings.vertices.destroy(device);
                    buildings.indices.destroy(device);
                }
                if let Some(terrain) = &tile.terrain {
                    terrain.vertices.destroy(device);
                    terrain.indices.destroy(device);
                }
            }
            false
        });
        // Transient per-frame symbol buffers retire on the same grace count, in
        // their own queue — no device_wait_idle stall on the frame path.
        self.transients.retain_mut(|t| {
            if t.frames > 0 {
                t.frames -= 1;
                return true;
            }
            unsafe {
                t.vbuf.destroy(device);
                t.ibuf.destroy(device);
            }
            false
        });
    }
}

/// Upload the process-global glyph atlas and allocate its descriptor set.
///
/// Separate from [`Renderer::new`] so the error paths read linearly. Called once
/// at startup; the bytes come from `tile::glyph::atlas()` (SDF R8 built from the
/// bundled Noto Sans at first use).
///
/// # Safety
///
/// Same rules as the surrounding constructors: live device, idle queue.
pub(super) unsafe fn try_upload_glyph_atlas(
    context: &Context,
    atlas_set: &AtlasSet,
    command_pool: vk::CommandPool,
) -> Result<(SampledImage, vk::DescriptorSet), String> {
    use crate::tile::glyph;
    if !glyph::fonts_staged() {
        return Err("bundled fonts are not valid TTFs".into());
    }
    let atlas = glyph::atlas();
    let image = SampledImage::upload(
        &context.instance,
        context.physical_device,
        &context.device,
        context.queue,
        context.queue_family_index,
        command_pool,
        &atlas.pixels,
        glyph::ATLAS_PX,
        glyph::ATLAS_PX,
        vk::Format::R8_UNORM,
    )?;
    let set = atlas_set.allocate(&context.device, &image)?;
    Ok((image, set))
}

/// Upload the process-global POI sprite sheet and allocate its descriptor set.
///
/// [`try_upload_glyph_atlas`]'s counterpart, and the second of the two sets
/// [`AtlasSet`]'s pool is sized for. The sheet is already RGBA8, so
/// [`SampledImage::upload`] takes it unchanged — no expansion step like the glyph
/// atlas's R8 one.
///
/// # Safety
///
/// Same rules as the surrounding constructors: live device, idle queue.
pub(super) unsafe fn try_upload_sprite_atlas(
    context: &Context,
    atlas_set: &AtlasSet,
    command_pool: vk::CommandPool,
) -> Result<(SampledImage, vk::DescriptorSet), String> {
    use crate::tile::sprite;
    let atlas = sprite::atlas();
    // An empty atlas is what `sprite::atlas()` leaves behind when the sheet will not
    // decode; uploading a zero-sized image would fail in the driver instead of here.
    if atlas.is_empty() {
        return Err("the sprite sheet carries no icons".into());
    }
    let image = SampledImage::upload(
        &context.instance,
        context.physical_device,
        &context.device,
        context.queue,
        context.queue_family_index,
        command_pool,
        &atlas.pixels,
        atlas.width,
        atlas.height,
        vk::Format::R8G8B8A8_UNORM,
    )?;
    let set = atlas_set.allocate(&context.device, &image)?;
    Ok((image, set))
}
