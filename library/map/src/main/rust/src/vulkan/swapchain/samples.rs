use crate::vulkan::context::Context;
use ash::vk;

/// How many samples per pixel to ask for.
///
/// Four is the usual sweet spot and the level mobile GPUs implement most efficiently;
/// eight costs proportionally more tile memory for a difference that does not show at
/// map line widths. Clamped to what the device reports, so this is a ceiling.
pub const WANTED_SAMPLES: vk::SampleCountFlags = vk::SampleCountFlags::TYPE_4;

/// The most samples the device supports for a colour attachment, capped at
/// [`WANTED_SAMPLES`].
pub(super) unsafe fn supported_samples(context: &Context) -> vk::SampleCountFlags {
    let limits = context
        .instance
        .get_physical_device_properties(context.physical_device)
        .limits;
    let available = limits.framebuffer_color_sample_counts;
    for candidate in [
        vk::SampleCountFlags::TYPE_8,
        vk::SampleCountFlags::TYPE_4,
        vk::SampleCountFlags::TYPE_2,
    ] {
        if candidate.as_raw() <= WANTED_SAMPLES.as_raw() && available.contains(candidate) {
            return candidate;
        }
    }
    vk::SampleCountFlags::TYPE_1
}
