use crate::tess::{fill, stroke};
use crate::tile::symbol;
use ash::vk;

use super::assemble::build;
use super::attributes::{fill_attributes, line_attributes, ribbon_attributes};
use super::push::PUSH_CONSTANT_BYTES;
use super::shaders::{
    shader_module, BUILDING_FRAG, BUILDING_VERT, FILL_FRAG, FILL_GLOBE_FRAG, FILL_GLOBE_VERT,
    FILL_VERT, LINE_FRAG, LINE_GLOBE_FRAG, LINE_GLOBE_VERT, LINE_VERT, MOON_FRAG, MOON_VERT,
    PUCK_FRAG, PUCK_VERT, RIBBON_FRAG, RIBBON_GLOBE_FRAG, RIBBON_GLOBE_VERT, RIBBON_VERT,
    SPRITE_FRAG, SYMBOL_BILLBOARD_VERT, SYMBOL_FRAG, SYMBOL_GLOBE_VERT, SYMBOL_VERT, TERRAIN_FRAG,
    TERRAIN_VERT,
};
use super::state::{Depth, Stencil};

pub use super::pipelines_extra::Pipelines;
impl Pipelines {
    /// # Safety
    ///
    /// `render_pass` must outlive these pipelines. `atlas_layout` is the
    /// descriptor set layout [`images::AtlasSet`] built — `None` on a host
    /// build that never creates pipelines (tests link this module for the
    /// [`Push`] size asserts only).
    ///
    /// `cache` is [`crate::vulkan::cache::ShaderCache`]'s handle, or
    /// [`vk::PipelineCache::null`] for no cache. Every one of the twelve pipelines below is built
    /// through it, and they share shader modules heavily — `fill` is also `depth`, `mask` and
    /// `scrim`, and `symbol`'s billboard vertex shader is also `icon`'s — so even a cold cache
    /// pays for itself within this one call: the driver populates it as it goes, and the repeats
    /// hit rather than recompile.
    pub unsafe fn new(
        device: &ash::Device,
        render_pass: vk::RenderPass,
        samples: vk::SampleCountFlags,
        atlas_layout: Option<vk::DescriptorSetLayout>,
        cache: vk::PipelineCache,
    ) -> Result<Pipelines, String> {
        let push_range = vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
            .offset(0)
            .size(PUSH_CONSTANT_BYTES);
        let layout_info = vk::PipelineLayoutCreateInfo::default()
            .push_constant_ranges(std::slice::from_ref(&push_range));
        let layout = device
            .create_pipeline_layout(&layout_info, None)
            .map_err(|e| format!("create_pipeline_layout {e:?}"))?;

        let fill_vert = shader_module(device, FILL_VERT)?;
        let fill_frag = shader_module(device, FILL_FRAG)?;
        let line_vert = shader_module(device, LINE_VERT)?;
        let line_frag = shader_module(device, LINE_FRAG)?;
        let ribbon_vert = shader_module(device, RIBBON_VERT)?;
        let ribbon_frag = shader_module(device, RIBBON_FRAG)?;
        let symbol_vert = shader_module(device, SYMBOL_VERT)?;
        let symbol_billboard_vert = shader_module(device, SYMBOL_BILLBOARD_VERT)?;
        let symbol_frag = shader_module(device, SYMBOL_FRAG)?;
        let sprite_frag = shader_module(device, SPRITE_FRAG)?;
        let puck_vert = shader_module(device, PUCK_VERT)?;
        let puck_frag = shader_module(device, PUCK_FRAG)?;
        let building_vert = shader_module(device, BUILDING_VERT)?;
        let building_frag = shader_module(device, BUILDING_FRAG)?;
        let terrain_vert = shader_module(device, TERRAIN_VERT)?;
        let terrain_frag = shader_module(device, TERRAIN_FRAG)?;
        let fill_globe_vert = shader_module(device, FILL_GLOBE_VERT)?;
        let fill_globe_frag = shader_module(device, FILL_GLOBE_FRAG)?;
        let line_globe_vert = shader_module(device, LINE_GLOBE_VERT)?;
        let line_globe_frag = shader_module(device, LINE_GLOBE_FRAG)?;
        let ribbon_globe_vert = shader_module(device, RIBBON_GLOBE_VERT)?;
        let ribbon_globe_frag = shader_module(device, RIBBON_GLOBE_FRAG)?;
        let symbol_globe_vert = shader_module(device, SYMBOL_GLOBE_VERT)?;
        let moon_vert = shader_module(device, MOON_VERT)?;
        let moon_frag = shader_module(device, MOON_FRAG)?;

        let fill_attributes = fill_attributes();
        let line_attributes = line_attributes();
        let ribbon_attributes = ribbon_attributes();
        // Sprite (app markers): position (already clip-space) + uv (atlas), 4 floats. Markers
        // resolve their corners on the CPU and draw through an identity matrix, so there is no
        // tile-local anchor to project and this format must not grow. POI icons no longer use it —
        // they moved to the billboard format below so they face the camera under tilt.
        // (Attribute tables live in `pipelines_attrs`; same values, file-length split.)
        let symbol_attributes = super::pipelines_attrs::symbol_attributes();
        // Symbol text and POI icons (billboarded): position (tile-local) + uv (atlas) + ground
        // anchor (tile-local) + the anchor's tile-normalised ground height, 7 floats. The anchor lets `symbol_billboard.vert` keep point labels
        // and their icons upright and pinned to the ground under tilt; at pitch 0 it is ignored and
        // output is unchanged. One format for both is what keeps an icon on top of its label.
        let symbol_billboard_attributes = super::pipelines_attrs::symbol_billboard_attributes();
        // Building (WS-A): position+height (3 floats), face normal (3 floats), then the per-vertex
        // ARGB colour as one `R8G8B8A8_UNORM` word the shader reads as a 0..1 vec4. 28-byte stride.
        let building_attributes = super::pipelines_attrs::building_attributes();

        // Terrain (WS-G): position+height (3 floats) then the surface normal (3 floats). 24-byte
        // stride, no colour — the ground colour is the pushed `earth` colour, not per-vertex.
        let terrain_attributes = super::pipelines_attrs::terrain_attributes();

        let fill = build(
            device,
            layout,
            render_pass,
            samples,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );
        let line = build(
            device,
            layout,
            render_pass,
            samples,
            line_vert,
            line_frag,
            (stroke::FLOATS_PER_VERTEX * 4) as u32,
            &line_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );
        // Road carriageways. Same fixed-function state as `line` — a carriageway is flat basemap,
        // so depth stays off and layer order does the compositing exactly as it does for a stroke.
        // Only the vertex format and the shaders differ, which is the whole point of splitting it
        // off rather than widening the format every stroked layer shares.
        let ribbon = build(
            device,
            layout,
            render_pass,
            samples,
            ribbon_vert,
            ribbon_frag,
            (crate::tess::ribbon::FLOATS_PER_VERTEX * 4) as u32,
            &ribbon_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );
        // The depth-tested variant of `fill`: same shaders and vertex format, depth test + write
        // on. WS-A/WS-G draw their extruded/relief geometry through pipelines built like this so
        // the 3D layers occlude correctly; the flat 2D layers never bind it.
        let depth = build(
            device,
            layout,
            render_pass,
            samples,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::Ignore,
            Depth::TestWrite,
            cache,
        );
        // The 3D building pipeline: its own shaders and 7-float vertex, depth test + write on so
        // buildings occlude correctly. Push-only layout — colour is per-vertex, not a uniform.
        let building = build(
            device,
            layout,
            render_pass,
            samples,
            building_vert,
            building_frag,
            (crate::tess::roof::FLOATS_PER_VERTEX * 4) as u32,
            &building_attributes,
            Stencil::Ignore,
            Depth::TestWrite,
            cache,
        );
        // The 3D terrain pipeline: its own shaders and 6-float vertex, depth test + write on so the
        // relief occludes correctly. Push-only layout — the ground colour is the pushed `earth`
        // colour. At pitch 0 its vertex shader collapses to the flat footprint, so the map is
        // unchanged.
        let terrain = build(
            device,
            layout,
            render_pass,
            samples,
            terrain_vert,
            terrain_frag,
            (crate::tess::terrain::FLOATS_PER_VERTEX * 4) as u32,
            &terrain_attributes,
            Stencil::Ignore,
            Depth::TestWrite,
            cache,
        );

        // The symbol pipeline needs the atlas descriptor set, so it gets its own
        // layout: same push-constant range plus set 0. The sprite pipeline reuses this
        // layout with a second set from the same pool.
        //
        // The Moon layout (two sampled images) lives in `pipelines_moon`
        // (`moon_layouts`), built before this match so both branches below can
        // use it; the descriptor pool + sets stay Moon-owned at upload time.
        let (moon_ds_layout, moon_layout) =
            super::pipelines_moon::moon_layouts(device, &push_range)?;
        let symbol_layout = match atlas_layout {
            Some(set_layout) => {
                let symbol_layout_info = vk::PipelineLayoutCreateInfo::default()
                    .push_constant_ranges(std::slice::from_ref(&push_range))
                    .set_layouts(std::slice::from_ref(&set_layout));
                device
                    .create_pipeline_layout(&symbol_layout_info, None)
                    .map_err(|e| format!("create_pipeline_layout(symbol) {e:?}"))?
            }
            None => {
                device.destroy_shader_module(fill_vert, None);
                device.destroy_shader_module(fill_frag, None);
                device.destroy_shader_module(line_vert, None);
                device.destroy_shader_module(line_frag, None);
                device.destroy_shader_module(ribbon_vert, None);
                device.destroy_shader_module(ribbon_frag, None);
                device.destroy_shader_module(symbol_vert, None);
                device.destroy_shader_module(symbol_billboard_vert, None);
                device.destroy_shader_module(symbol_globe_vert, None);
                device.destroy_shader_module(moon_vert, None);
                device.destroy_shader_module(moon_frag, None);
                device.destroy_shader_module(symbol_frag, None);
                device.destroy_shader_module(sprite_frag, None);
                device.destroy_shader_module(puck_vert, None);
                device.destroy_shader_module(puck_frag, None);
                device.destroy_shader_module(building_vert, None);
                device.destroy_shader_module(building_frag, None);
                device.destroy_shader_module(terrain_vert, None);
                device.destroy_shader_module(terrain_frag, None);
                device.destroy_shader_module(fill_globe_vert, None);
                device.destroy_shader_module(fill_globe_frag, None);
                device.destroy_shader_module(line_globe_vert, None);
                device.destroy_shader_module(line_globe_frag, None);
                device.destroy_shader_module(ribbon_globe_vert, None);
                device.destroy_shader_module(ribbon_globe_frag, None);
                device.destroy_pipeline_layout(layout, None);
                return Err("symbol pipeline needs an atlas descriptor set layout".into());
            }
        };
        let (symbol, icon, sprite) = super::pipelines_symbols::symbol_pipelines(
            device,
            symbol_layout,
            render_pass,
            samples,
            symbol_vert,
            symbol_billboard_vert,
            symbol_frag,
            sprite_frag,
            &symbol_attributes,
            &symbol_billboard_attributes,
            cache,
        );
        // Globe labels + icons: the globe vertex shader on the billboard format,
        // paired with the glyph and sprite fragment shaders respectively, through
        // the atlas layout. Depth-tested so far-side labels lose to the ball.
        let (symbol_globe, icon_globe) = super::pipelines_symbols::globe_symbol_pipelines(
            device,
            symbol_layout,
            render_pass,
            samples,
            symbol_globe_vert,
            symbol_frag,
            sprite_frag,
            &symbol_billboard_attributes,
            cache,
        );

        // Globe variants: same vertex formats as their flat twins, depth-tested
        // (TestWrite, LESS) so the near hemisphere occludes the far side and
        // coarser ancestors lose to finer descendants drawn later. The globe
        // shaders bend tile-local geometry onto the ball and write depth =
        // 1 - z (limb 0, sub-camera point ~1); with the pass cleared to 1.0,
        // nearer (larger z) wins the LESS test.
        let (fill_globe, line_globe, ribbon_globe) = super::pipelines_globe::globe_pipelines(
            device,
            layout,
            render_pass,
            samples,
            fill_globe_vert,
            fill_globe_frag,
            line_globe_vert,
            line_globe_frag,
            ribbon_globe_vert,
            ribbon_globe_frag,
            &fill_attributes,
            &line_attributes,
            &ribbon_attributes,
            cache,
        );
        // The Moon disc (see `pipelines_moon::moon_pipeline`): `moon.vert` /
        // `moon.frag` over the position-only fill format (the shared unit quad:
        // -1..1 local, the vertex shader bends it onto the disc). Depth-tested
        // so it participates in the same depth regime as the globe tiles it
        // replaces. Through `moon_layout` (two sampled images); the sets are
        // Moon-owned, allocated at upload time.
        let moon = super::pipelines_moon::moon_pipeline(
            device,
            moon_layout,
            render_pass,
            samples,
            moon_vert,
            moon_frag,
            &fill_attributes,
            cache,
        );
        // The overlay quad is position + draped height in -1..1, so it shares the fill vertex
        // format and its 12-byte stride.
        let puck = build(
            device,
            layout,
            render_pass,
            samples,
            puck_vert,
            puck_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );

        // The region mask and its scrim. Both are position-only quads/triangles in the same
        // vertex format as `fill`: the mask draws the region's tessellated shape, the scrim a
        // full-viewport quad that the stencil keeps off the region itself.
        let mask = build(
            device,
            layout,
            render_pass,
            samples,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::Write,
            Depth::Off,
            cache,
        );
        let scrim = build(
            device,
            layout,
            render_pass,
            samples,
            fill_vert,
            fill_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            &fill_attributes,
            Stencil::TestOutside,
            Depth::Off,
            cache,
        );

        // The modules are only needed while the pipelines are being created.
        device.destroy_shader_module(fill_vert, None);
        device.destroy_shader_module(fill_frag, None);
        device.destroy_shader_module(line_vert, None);
        device.destroy_shader_module(line_frag, None);
        device.destroy_shader_module(ribbon_vert, None);
        device.destroy_shader_module(ribbon_frag, None);
        device.destroy_shader_module(symbol_vert, None);
        device.destroy_shader_module(symbol_billboard_vert, None);
        device.destroy_shader_module(symbol_globe_vert, None);
        device.destroy_shader_module(symbol_frag, None);
        device.destroy_shader_module(sprite_frag, None);
        device.destroy_shader_module(puck_vert, None);
        device.destroy_shader_module(puck_frag, None);
        device.destroy_shader_module(building_vert, None);
        device.destroy_shader_module(building_frag, None);
        device.destroy_shader_module(terrain_vert, None);
        device.destroy_shader_module(terrain_frag, None);
        device.destroy_shader_module(fill_globe_vert, None);
        device.destroy_shader_module(fill_globe_frag, None);
        device.destroy_shader_module(line_globe_vert, None);
        device.destroy_shader_module(line_globe_frag, None);
        device.destroy_shader_module(ribbon_globe_vert, None);
        device.destroy_shader_module(ribbon_globe_frag, None);
        device.destroy_shader_module(moon_vert, None);
        device.destroy_shader_module(moon_frag, None);

        match (
            fill, line, ribbon, depth, building, terrain, symbol, icon, sprite, puck, mask, scrim,
            fill_globe, line_globe, ribbon_globe, symbol_globe, icon_globe, moon,
        ) {
            (
                Ok(fill),
                Ok(line),
                Ok(ribbon),
                Ok(depth),
                Ok(building),
                Ok(terrain),
                Ok(symbol),
                Ok(icon),
                Ok(sprite),
                Ok(puck),
                Ok(mask),
                Ok(scrim),
                Ok(fill_globe),
                Ok(line_globe),
                Ok(ribbon_globe),
                Ok(symbol_globe),
                Ok(icon_globe),
                Ok(moon),
            ) => Ok(Pipelines {
                layout,
                symbol_layout,
                moon_layout,
                moon_ds_layout,
                fill,
                line,
                ribbon,
                depth,
                building,
                terrain,
                symbol,
                icon,
                sprite,
                puck,
                mask,
                scrim,
                fill_globe,
                line_globe,
                ribbon_globe,
                symbol_globe,
                icon_globe,
                moon,
            }),
            (
                fill,
                line,
                ribbon,
                depth,
                building,
                terrain,
                symbol,
                icon,
                sprite,
                puck,
                mask,
                scrim,
                fill_globe,
                line_globe,
                ribbon_globe,
                symbol_globe,
                icon_globe,
                moon,
            ) => {
                for created in [
                    fill, line, ribbon, depth, building, terrain, symbol, icon, sprite, puck, mask,
                    scrim, fill_globe, line_globe, ribbon_globe, symbol_globe, icon_globe, moon,
                ]
                .into_iter()
                .flatten()
                {
                    device.destroy_pipeline(created, None);
                }
                device.destroy_pipeline_layout(symbol_layout, None);
                device.destroy_pipeline_layout(moon_layout, None);
                device.destroy_descriptor_set_layout(moon_ds_layout, None);
                device.destroy_pipeline_layout(layout, None);
                Err("pipeline creation failed".into())
            }
        }
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&self, device: &ash::Device) {
        device.destroy_pipeline(self.fill, None);
        device.destroy_pipeline(self.line, None);
        device.destroy_pipeline(self.ribbon, None);
        device.destroy_pipeline(self.depth, None);
        device.destroy_pipeline(self.building, None);
        device.destroy_pipeline(self.terrain, None);
        device.destroy_pipeline(self.symbol, None);
        device.destroy_pipeline(self.icon, None);
        device.destroy_pipeline(self.sprite, None);
        device.destroy_pipeline(self.puck, None);
        device.destroy_pipeline(self.mask, None);
        device.destroy_pipeline(self.scrim, None);
        device.destroy_pipeline(self.fill_globe, None);
        device.destroy_pipeline(self.line_globe, None);
        device.destroy_pipeline(self.ribbon_globe, None);
        device.destroy_pipeline(self.symbol_globe, None);
        device.destroy_pipeline(self.icon_globe, None);
        device.destroy_pipeline(self.moon, None);
        device.destroy_pipeline_layout(self.symbol_layout, None);
        device.destroy_pipeline_layout(self.moon_layout, None);
        device.destroy_descriptor_set_layout(self.moon_ds_layout, None);
        device.destroy_pipeline_layout(self.layout, None);
    }
}
