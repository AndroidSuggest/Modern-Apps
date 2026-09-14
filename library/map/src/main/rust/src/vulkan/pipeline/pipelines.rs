use ash::vk;
use crate::tess::{fill, stroke};
use crate::tile::symbol;

use super::assemble::build;
use super::push::PUSH_CONSTANT_BYTES;
use super::shaders::{
    BUILDING_FRAG, BUILDING_VERT, FILL_FRAG, FILL_VERT, LINE_FRAG, LINE_VERT, PUCK_FRAG,
    PUCK_VERT, RIBBON_FRAG, RIBBON_VERT, SPRITE_FRAG, SYMBOL_BILLBOARD_VERT, SYMBOL_FRAG,
    SYMBOL_VERT, TERRAIN_FRAG, TERRAIN_VERT, shader_module,
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

        let fill_attributes = [vk::VertexInputAttributeDescription::default()
            .location(0)
            .binding(0)
            .format(vk::Format::R32G32_SFLOAT)
            .offset(0)];
        let line_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(8),
            vk::VertexInputAttributeDescription::default()
                .location(2)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(16),
            vk::VertexInputAttributeDescription::default()
                .location(3)
                .binding(0)
                .format(vk::Format::R32_SFLOAT)
                .offset(24),
        ];
        // Ribbon (road carriageways): position (2 floats), join normal (2 floats), the normalised
        // across-road coordinate `t`, and tile-local distance-along. 24-byte stride. `t` is both the
        // vertex shader's extrusion multiplier and the fragment shader's marking coordinate, which
        // is what keeps this to six floats rather than seven.
        let ribbon_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(8),
            vk::VertexInputAttributeDescription::default()
                .location(2)
                .binding(0)
                .format(vk::Format::R32_SFLOAT)
                .offset(16),
            vk::VertexInputAttributeDescription::default()
                .location(3)
                .binding(0)
                .format(vk::Format::R32_SFLOAT)
                .offset(20),
        ];
        // Sprite (app markers): position (already clip-space) + uv (atlas), 4 floats. Markers
        // resolve their corners on the CPU and draw through an identity matrix, so there is no
        // tile-local anchor to project and this format must not grow. POI icons no longer use it —
        // they moved to the billboard format below so they face the camera under tilt.
        let symbol_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(8),
        ];
        // Symbol text and POI icons (billboarded): position (tile-local) + uv (atlas) + ground
        // anchor (tile-local), 6 floats. The anchor lets `symbol_billboard.vert` keep point labels
        // and their icons upright and pinned to the ground under tilt; at pitch 0 it is ignored and
        // output is unchanged. One format for both is what keeps an icon on top of its label.
        let symbol_billboard_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(8),
            vk::VertexInputAttributeDescription::default()
                .location(2)
                .binding(0)
                .format(vk::Format::R32G32_SFLOAT)
                .offset(16),
        ];
        // Building (WS-A): position+height (3 floats), face normal (3 floats), then the per-vertex
        // ARGB colour as one `R8G8B8A8_UNORM` word the shader reads as a 0..1 vec4. 28-byte stride.
        let building_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(12),
            vk::VertexInputAttributeDescription::default()
                .location(2)
                .binding(0)
                .format(vk::Format::R8G8B8A8_UNORM)
                .offset(24),
        ];

        // Terrain (WS-G): position+height (3 floats) then the surface normal (3 floats). 24-byte
        // stride, no colour — the ground colour is the pushed `earth` colour, not per-vertex.
        let terrain_attributes = [
            vk::VertexInputAttributeDescription::default()
                .location(0)
                .binding(0)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(0),
            vk::VertexInputAttributeDescription::default()
                .location(1)
                .binding(0)
                .format(vk::Format::R32G32B32_SFLOAT)
                .offset(12),
        ];

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
                device.destroy_shader_module(symbol_frag, None);
                device.destroy_shader_module(sprite_frag, None);
                device.destroy_shader_module(puck_vert, None);
                device.destroy_shader_module(puck_frag, None);
                device.destroy_shader_module(building_vert, None);
                device.destroy_shader_module(building_frag, None);
                device.destroy_shader_module(terrain_vert, None);
                device.destroy_shader_module(terrain_frag, None);
                device.destroy_pipeline_layout(layout, None);
                return Err("symbol pipeline needs an atlas descriptor set layout".into());
            }
        };
        let symbol = build(
            device,
            symbol_layout,
            render_pass,
            samples,
            symbol_billboard_vert,
            symbol_frag,
            (symbol::FLOATS_PER_VERTEX * 4) as u32,
            &symbol_billboard_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );
        // POI icons: the billboard vertex shader (so they face the camera under tilt, exactly as
        // the text beside them does) paired with the sprite fragment shader (because an icon is a
        // picture, not a distance field). No new shader — both halves already existed.
        let icon = build(
            device,
            symbol_layout,
            render_pass,
            samples,
            symbol_billboard_vert,
            sprite_frag,
            (symbol::ICON_FLOATS_PER_VERTEX * 4) as u32,
            &symbol_billboard_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );
        let sprite = build(
            device,
            symbol_layout,
            render_pass,
            samples,
            symbol_vert,
            sprite_frag,
            (symbol::MARKER_FLOATS_PER_VERTEX * 4) as u32,
            &symbol_attributes,
            Stencil::Ignore,
            Depth::Off,
            cache,
        );

        // The overlay quad is position-only in -1..1, so it shares the fill vertex
        // format and its 8-byte stride.
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
        device.destroy_shader_module(symbol_frag, None);
        device.destroy_shader_module(sprite_frag, None);
        device.destroy_shader_module(puck_vert, None);
        device.destroy_shader_module(puck_frag, None);
        device.destroy_shader_module(building_vert, None);
        device.destroy_shader_module(building_frag, None);
        device.destroy_shader_module(terrain_vert, None);
        device.destroy_shader_module(terrain_frag, None);

        match (fill, line, ribbon, depth, building, terrain, symbol, icon, sprite, puck, mask, scrim)
        {
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
            ) => Ok(Pipelines {
                layout,
                symbol_layout,
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
            ) => {
                for created in [
                    fill, line, ribbon, depth, building, terrain, symbol, icon, sprite, puck, mask,
                    scrim,
                ]
                .into_iter()
                .flatten()
                {
                    device.destroy_pipeline(created, None);
                }
                device.destroy_pipeline_layout(symbol_layout, None);
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
        device.destroy_pipeline_layout(self.symbol_layout, None);
        device.destroy_pipeline_layout(self.layout, None);
    }
}
