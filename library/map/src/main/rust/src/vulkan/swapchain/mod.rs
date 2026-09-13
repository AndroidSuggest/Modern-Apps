//! The swapchain, its render pass and framebuffers.
//!
//! # Depth: transient, and shared with the stencil
//!
//! The flat 2D basemap has nothing to occlude — correctness there comes from **draw order**
//! (layer-major across tiles) and alpha blending, so those layers run with depth test and
//! write off and are unaffected by the attachment below. Tilt and the 3D layers (buildings,
//! terrain) change that: extruded geometry must occlude itself under a perspective camera, so
//! the pass now carries a depth buffer that the depth-enabled pipeline variant tests against.
//!
//! It costs almost nothing on the target hardware. The attachment is the **same combined
//! depth-stencil image** the region mask already needed for its stencil, so depth adds no new
//! image — only a `CLEAR`/`DONT_CARE` on the depth aspect. Like the MSAA and stencil targets
//! it is `TRANSIENT_ATTACHMENT` + `LAZILY_ALLOCATED`: on a tile-based mobile GPU the depth
//! samples live and die inside tile memory and never reach main memory, so there is no real
//! backing store and no store-out bandwidth (`store_op` is `DONT_CARE`).
//!
//! # Multisampling
//!
//! Rendering was single-sampled, and with no coverage term in any shader that made every
//! polygon edge a staircase — coastlines, park boundaries and building corners all
//! stepped a pixel at a time, which is most of what read as the renderer being lower
//! resolution than MapLibre. The colour attachment is now multisampled and resolved into
//! the swapchain image at the end of the subpass.
//!
//! The bandwidth argument above still holds, which is why the multisampled image is
//! `TRANSIENT_ATTACHMENT` + `LAZILY_ALLOCATED` where the driver offers it: on a
//! tile-based GPU the samples live and die inside tile memory and never reach main
//! memory, so the resolve is close to free and the image needs no real backing store.
//! `store_op` is `DONT_CARE` for the same reason — only the resolve target is kept.
//!
//! # Two hard-won details carried over from `games/voxels`
//!
//! * **`B8G8R8A8_UNORM` in preference to any `SRGB` format.** `voxels/swapchain.rs:39`
//!   records that Pixel's gralloc rejects the SRGB format and the result is a black
//!   screen.
//! * **A classic `VkRenderPass`, not `KHR_dynamic_rendering`.**
//!   `voxels/swapchain.rs:74` records the crash — "Unable to load cmd_begin_rendering" —
//!   from assuming the extension is present.

mod chain;
mod msaa;
mod samples;
mod stencil;

pub use chain::Swapchain;
pub use samples::WANTED_SAMPLES;
