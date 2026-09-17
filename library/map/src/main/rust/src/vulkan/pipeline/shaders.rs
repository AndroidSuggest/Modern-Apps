use ash::vk;
use std::ffi::CStr;

pub(super) const FILL_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/fill.vert.spv"));
pub(super) const FILL_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/fill.frag.spv"));
/// Orthographic-sphere globe variants: bend tile-local geometry onto the ball
/// (see the `*_globe.vert` headers). Only built into globe pipelines; the flat
/// pipelines above never reference them, so flat output is unchanged.
pub(super) const FILL_GLOBE_VERT: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/fill_globe.vert.spv"));
pub(super) const FILL_GLOBE_FRAG: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/fill_globe.frag.spv"));
pub(super) const LINE_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/line.vert.spv"));
pub(super) const LINE_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/line.frag.spv"));
pub(super) const LINE_GLOBE_VERT: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/line_globe.vert.spv"));
pub(super) const LINE_GLOBE_FRAG: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/line_globe.frag.spv"));
pub(super) const RIBBON_VERT: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/road_surface.vert.spv"));
pub(super) const RIBBON_FRAG: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/road_surface.frag.spv"));
pub(super) const RIBBON_GLOBE_VERT: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/road_surface_globe.vert.spv"));
pub(super) const RIBBON_GLOBE_FRAG: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/road_surface_globe.frag.spv"));
pub(super) const SYMBOL_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/symbol.vert.spv"));
pub(super) const SYMBOL_BILLBOARD_VERT: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/symbol_billboard.vert.spv"));
/// Globe label bending (see `symbol_globe.vert`): the globe symbol/icon path.
pub(super) const SYMBOL_GLOBE_VERT: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/symbol_globe.vert.spv"));
/// Moon raster pair (see `moon.vert`/`moon.frag`): the maps-only lunar globe.
pub(super) const MOON_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/moon.vert.spv"));
pub(super) const MOON_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/moon.frag.spv"));
pub(super) const SYMBOL_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/symbol.frag.spv"));
pub(super) const SPRITE_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/sprite.frag.spv"));
pub(super) const PUCK_VERT: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/puck.vert.spv"));
pub(super) const PUCK_FRAG: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/puck.frag.spv"));
pub(super) const BUILDING_VERT: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/building.vert.spv"));
pub(super) const BUILDING_FRAG: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/building.frag.spv"));
pub(super) const TERRAIN_VERT: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/terrain.vert.spv"));
pub(super) const TERRAIN_FRAG: &[u8] =
    include_bytes!(concat!(env!("OUT_DIR"), "/terrain.frag.spv"));
pub(super) unsafe fn shader_module(
    device: &ash::Device,
    spirv: &[u8],
) -> Result<vk::ShaderModule, String> {
    // SPIR-V is a stream of 32-bit words. `build.rs` guarantees a real module, so a
    // misaligned length here would be a build-system bug rather than bad input.
    if spirv.len() % 4 != 0 || spirv.len() < 20 {
        return Err(format!("{} bytes is not a SPIR-V module", spirv.len()));
    }
    let mut words = Vec::with_capacity(spirv.len() / 4);
    for chunk in spirv.chunks_exact(4) {
        words.push(u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
    }
    let info = vk::ShaderModuleCreateInfo::default().code(&words);
    device
        .create_shader_module(&info, None)
        .map_err(|e| format!("create_shader_module {e:?}"))
}

/// Unused, but kept so the entry-point name is stated once.
#[allow(dead_code)]
pub(super) const ENTRY_POINT: &CStr = c"main";
