use crate::camera::Camera;
use crate::vulkan::buffers::ScratchRing;
use crate::vulkan::cache::ShaderCache;
use crate::vulkan::context::{ANativeWindow, Context};
use crate::vulkan::images::{AtlasSet, SampledImage};
use crate::vulkan::pick::Pick;
use crate::vulkan::pipeline::Pipelines;
use crate::vulkan::swapchain::Swapchain;
use ash::vk;
use std::cell::Cell;
use std::collections::HashMap;

use super::{
    AcceptSet, Frame, Overlay, PlacedHit, PlacementKey, Quad, ResidentTile, RouteBuffers,
    TransientBuffers,
};
use crate::style::LayerKind;

pub struct Renderer {
    pub(crate) context: Context,
    pub(crate) swapchain: Swapchain,
    pub(crate) pipelines: Pipelines,
    /// Sampled-image infra shared by the glyph atlas and the sprite atlas: one
    /// pool/layout, one set per atlas. Uploaded once at startup from the
    /// CPU-built atlas bytes.
    pub(crate) atlas_set: AtlasSet,
    pub(crate) glyph_atlas: Option<SampledImage>,
    pub(crate) glyph_set: Option<vk::DescriptorSet>,
    /// The POI icon sheet, uploaded beside the glyphs. `None` when the sheet would
    /// not decode, which leaves POI labels drawing without icons rather than not at
    /// all.
    pub(crate) sprite_atlas: Option<SampledImage>,
    pub(crate) sprite_set: Option<vk::DescriptorSet>,
    pub(crate) command_pool: vk::CommandPool,
    /// Per-frame scratch, reused across frames rather than reallocated per record.
    ///
    /// `record_inner` runs on every frame on the Choreographer callback; allocating its working
    /// sets (`draws`, `ordered`, `resident`, `tile_alpha`, `deferred_symbols`) fresh each time
    /// is allocator traffic for values that are recomputed wholesale. These live on the renderer
    /// and are cleared at the top of each record instead.
    pub(crate) scratch_draws: Vec<(
        u8,
        u32,
        u32,
        LayerKind,
        ash::vk::Buffer,
        ash::vk::Buffer,
        u32,
        u32,
        Option<u32>,
        (u8, u8, u8),
    )>,
    /// Resident-tile keys sorted coarsest-first for draw order (see `record_inner`).
    pub(crate) scratch_ordered: Vec<u64>,
    /// Resident-tile key set for the LOD cross-fade + placement key.
    pub(crate) scratch_resident: std::collections::HashSet<u64>,
    /// Per-tile LOD opacity for the frame (see `tile_lod_alpha`).
    pub(crate) scratch_tile_alpha: std::collections::HashMap<u64, f32>,
    /// `(tile key, layer index)` symbol draws deferred past the buildings pass.
    pub(crate) scratch_deferred_symbols: Vec<(u64, usize)>,
    /// Per-frame synchronisation and its command buffer.
    pub(crate) frames: Vec<Frame>,
    pub(crate) frame_index: usize,
    pub(crate) tiles: HashMap<u64, ResidentTile>,
    /// Retired buffers waiting for the frames that might still reference them.
    pub(crate) retiring: Vec<(usize, ResidentTile)>,
    /// The persistent shader-compilation cache, seeded from disk at startup and shared by every
    /// pipeline and by [`Pick`]. Outlives [`Pipelines`], which is destroyed and rebuilt whenever
    /// the render pass changes — that is the whole point, since the rebuild is what used to
    /// recompile twelve pipelines inside a frame.
    pub(crate) pipeline_cache: ShaderCache,
    /// Transient per-frame symbol buffers, same grace rule as `retiring`.
    pub(crate) transients: Vec<TransientBuffers>,
    /// One scratch bump allocator per frame in flight, which every symbol draw's geometry is
    /// suballocated from. Indexed by [`frame_index`](Self::frame_index) and reset once that
    /// frame's fence has signalled. See [`ScratchRing`].
    pub(crate) scratch: Vec<ScratchRing>,
    pub(crate) window: *mut ANativeWindow,
    pub width: u32,
    pub height: u32,
    /// Set when the swapchain needs rebuilding: a resize, a rotation, or an out-of-date
    /// present.
    pub(crate) needs_rebuild: bool,
    /// Draw calls actually submitted by the last recorded frame.
    ///
    /// Counted where they are issued rather than re-derived, because a layer can be resident
    /// and still not drawn — the authored style ramps a road's width to zero outside the zooms
    /// it is meant for, and [`record`](Self::record) skips it. Any second implementation of
    /// that test would drift out of step with the one that matters and the number would start
    /// lying again, more subtly.
    ///
    /// A `Cell` because `record` takes `&self`; the frame path is single-threaded, as the
    /// module docs of [`crate::bridge`] set out.
    pub(crate) submitted_draws: Cell<usize>,
    /// Per-step frame timing (see [`crate::timing`]): last-frame steps plus the rolling
    /// sum/max the `%60` rollup reports. A `RefCell` because the record sub-passes take `&self`
    /// — the same reason `placed` is one — and each record is a short leaf borrow.
    pub(crate) step_times: std::cell::RefCell<crate::timing::StepTimes>,
    /// Task-17 pick state: the last frame's PLACED labels — accept-set id,
    /// screen box in DEVICE px, layer index, display name, kind string, and
    /// anchor lon/lat — so `pick_labels` answers without re-tessellating.
    /// Refreshed by `record_inner` only when the accept-set changes (see the
    /// `last_accept_ids` guard there); read by the JNI pick path.
    pub(crate) placed: std::cell::RefCell<Vec<PlacedHit>>,
    /// Sorted accept-set ids of the last `refresh_placed`, so `record_inner` can skip the
    /// per-frame String clones when the placement was reused (see `PLACE_REUSE_MS`).
    pub(crate) last_accept_ids: std::cell::RefCell<Option<Vec<u64>>>,
    /// The last symbol placement and the state it was computed from.
    ///
    /// [`place_symbols`](Self::place_symbols) projects a collision box for every glyph of every
    /// curved label and then runs a solver that is quadratic in accepted boxes, all of it on the
    /// Choreographer callback. None of that depends on the frame clock, so a camera that has not
    /// moved gets last frame's answer instead of the same computation again — and a camera that
    /// has only *panned* within [`PLACE_REUSE_MS`](super::PLACE_REUSE_MS) reuses it
    /// too, which is what keeps panning from re-placing every frame.
    pub(crate) placement_cache:
        std::cell::RefCell<Option<(PlacementKey, AcceptSet, std::time::Instant)>>,
    /// What this frame draws on top of every tile, in order. See [`Overlay`].
    pub(crate) overlays: Vec<Overlay>,
    /// The geometry every overlay shares, uploaded once.
    pub(crate) quad: Quad,
    /// The OSM relation whose shape is punched out of the mask scrim, if any.
    ///
    /// Not an [`Overlay`]: an overlay draws itself over the tiles, while this one is a property
    /// of how every tile is drawn — two pipelines and a stencil rather than one quad.
    pub(crate) selected_region: Option<u64>,
    /// The navigation route line, or `None` when no route is set.
    pub(crate) route: Option<RouteBuffers>,
    /// The pack-driven rail-lines overlay, or `None` when the transit layer
    /// is off or the pack carries no shapes for the viewport. A second
    /// `RouteBuffers` slot beside the navigation route (same mesh, upload and
    /// draw path) so the network and a selected route coexist — the network
    /// draws first, the route over it.
    pub(crate) rail_lines: Option<RouteBuffers>,
    /// The live-traffic colour table: `component_id → ARGB`, pushed from the host each update.
    ///
    /// The device owns the theme and palette, so it sends fully-resolved colours; the renderer
    /// only looks them up. A segment whose id is absent draws nothing (see [`record_traffic`]),
    /// which keeps the overlay to the roads traffic actually covers rather than flooding the
    /// whole network with a neutral tint. Replacing this map is the whole of a recolour — no
    /// geometry is touched — so new speeds cost no tessellation.
    ///
    /// [`record_traffic`]: Self::record_traffic
    pub(crate) traffic_colors: HashMap<u64, u32>,
    /// Whether the traffic overlay is drawn this frame. Set from the host's layer toggle; the
    /// geometry is also gated at tessellation, so this is the cheap per-frame guard that stops
    /// resident traffic meshes drawing in the window before a toggle-off re-tessellation lands.
    pub(crate) traffic_enabled: bool,
    /// The offscreen id-buffer pass, for tap picking. Self-contained (its own render pass, pipeline
    /// and target); invoked out of band by [`pick_at`](Self::pick_at), never in the frame loop.
    pub(crate) pick: Pick,
    /// The last camera a frame was recorded with, so [`pick_at`](Self::pick_at) can place markers
    /// against the frame the user is actually looking at. `None` before the first frame.
    pub(crate) last_camera: Option<Camera>,
}
