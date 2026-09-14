//! The map handle and the state shared with the tile workers.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use crate::style::{Layer, Palette, SharedToggles};
use crate::tile::geometry::TileMesh;
use crate::tile::select::TileId;
use crate::tile::source::{basemap_origin, CachingRangeReader, JniRangeFetcher};
use crate::vulkan::renderer::Renderer;
use jni::sys::jlong;
use std::collections::{HashMap, HashSet};
use tilecodec::mamaps::MamapsArchive;
use std::sync::mpsc::{Receiver, Sender};
use std::sync::Arc;
/// The archive's zoom range, shared with the worker that reads it out of the header.
///
/// Atomics rather than a `Mutex` because `render` reads this every frame, and it must be
/// read live rather than copied once: the range is not known until the header has been
/// fetched, which is a network round trip *after* the surface exists. Copying it at startup
/// is how this was wrong before — it captured the guess and never corrected it, so the
/// renderer asked for a zoom level the archive does not contain and got nothing back.
pub(crate) struct ZoomRange {
    min: std::sync::atomic::AtomicU8,
    max: std::sync::atomic::AtomicU8,
}

impl ZoomRange {
    /// The published archive is z0-15. A wrong guess here is self-correcting once the
    /// header lands, but starting at the truth means the first frames are right too.
    pub(crate) fn unknown() -> ZoomRange {
        ZoomRange {
            min: std::sync::atomic::AtomicU8::new(0),
            max: std::sync::atomic::AtomicU8::new(15),
        }
    }

    pub(crate) fn set(&self, min: u8, max: u8) {
        self.min.store(min, std::sync::atomic::Ordering::Relaxed);
        self.max.store(max, std::sync::atomic::Ordering::Relaxed);
    }

    pub(crate) fn get(&self) -> (u8, u8) {
        (
            self.min.load(std::sync::atomic::Ordering::Relaxed),
            self.max.load(std::sync::atomic::Ordering::Relaxed),
        )
    }
}

/// How many tile workers run in parallel.
///
/// Each tile costs one or two **sequential** range requests — a leaf directory, then the
/// body — so its cost is dominated by round-trip latency, not CPU. One worker serialises
/// every tile behind every other: the archive probe measured a 24-tile screenful taking ~15
/// seconds that way, which is exactly the "really slow to change which elements load"
/// symptom. Four overlaps the waiting without opening enough sockets to matter.
pub(crate) const WORKER_COUNT: usize = 4;

/// The most tiles allowed resident on the GPU at once.
///
/// Eviction is the only thing bounding tile memory — there is no separate budget — so this is a
/// real limit rather than a safety net. It matters more now that descendants are kept: an
/// ancestor set is linear in depth, but a descendant set is not, and without a cap every deep
/// tile visited would stay resident for as long as the camera sat above it.
///
/// Sized from what the renderer reports rather than from theory. A dense z14 screenful logged
/// ~2.0 M triangles across 7 tiles, so a tile in a city centre costs single-digit megabytes of
/// vertex and index buffers; 64 leaves room for a ~12-tile viewport, its four ancestor levels and
/// a useful spread of descendants, while keeping the worst case in the hundreds of megabytes
/// rather than the gigabytes an uncapped depth-2 fan-out could reach. Going over drops
/// descendants first, so the cost of being wrong here is a briefly coarser zoom-out, not a blank
/// screen.
pub(crate) const RESIDENT_TILE_CAP: usize = 64;

/// How many finished tiles may be uploaded in one frame.
///
/// The drain used to be unbounded, so however many tiles the workers happened to finish between
/// two frames all landed in the next one. Uploading a tile is around a hundred `vkAllocateMemory`
/// calls, on the Choreographer callback, and a burst of them is what the frame-time tail is made
/// of. What is left stays in the channel and is picked up next frame — nothing is dropped, and
/// the tile is not re-requested, because its key is only removed from `in_flight` once it is
/// actually drained.
///
/// Four is a deliberate middle: a screenful is a couple of dozen tiles, so a cold pan fills in
/// over roughly ten frames, which is a fraction of the fetch and decode latency that preceded it
/// and so is not visible. Lower would start to look like a trickle; higher gives the tail back.
pub(crate) const UPLOADS_PER_FRAME: usize = 4;

/// What a worker reports back about a tile.
pub(crate) enum TileResult {
    /// Tessellated and ready to upload.
    Ready(TileMesh),
    /// The archive genuinely does not contain it — ordinary off the edge of coverage.
    /// Never retried.
    Absent,
    /// The fetch or decode failed. **Must** clear the in-flight marker so it can be tried
    /// again: leaving it set meant one transient network error blanked that tile for the
    /// rest of the session.
    Failed,
}

/// Everything one map surface owns. Handed to Kotlin as an opaque `jlong`.
pub(crate) struct MapHandle {
    pub(crate) renderer: Renderer,
    pub(crate) layers: &'static [Layer],
    /// Meshes finished by the workers, waiting to be uploaded on the render thread.
    pub(crate) finished: Receiver<(u64, TileResult)>,
    /// Tiles the workers should fetch.
    pub(crate) wanted: Sender<TileId>,
    /// Requested but not yet arrived, so a tile is not asked for sixty times a second
    /// while it is in flight.
    pub(crate) in_flight: HashSet<u64>,
    /// Tiles the archive does not contain. Remembered so they are not re-requested every
    /// frame forever — most of a coastal viewport is ocean.
    pub(crate) absent: HashSet<u64>,
    /// Tiles whose fetch or decode failed, as `key -> (consecutive failures, earliest retry)`.
    ///
    /// [`TileResult::Failed`] deliberately does not mark a tile [`TileResult::Absent`], so it
    /// is tried again — but with nothing recording *when*, "again" meant on the very next
    /// frame, and a tile that keeps failing was re-requested sixty times a second for as long
    /// as it stayed visible. This is the missing half: the same retry, at
    /// [`retry_delay_ms`](crate::tile::source::retry_delay_ms) intervals.
    ///
    /// `Instant`, not the camera's `time_seconds`, because that clock wraps hourly and a
    /// deadline across a wrap would either fire an hour early or an hour late.
    ///
    /// Cleared per tile on success. Bounded in practice the same way [`absent`](Self::absent)
    /// is: it holds one small entry per distinct tile that has actually failed this session.
    pub(crate) retry: HashMap<u64, (u32, std::time::Instant)>,
    pub(crate) online: Arc<OnlineFlag>,
    /// Light or dark. Switching costs nothing: colour is a push constant and the layer set
    /// is identical, so no tile is re-tessellated or re-uploaded.
    /// Light or dark, muted or not. Switching costs nothing: colour is a push constant and
    /// the layer set is identical, so no tile is re-tessellated or re-uploaded.
    pub(crate) palette: Palette,
    /// Which optional layers (POI, transit) are on, plus the generation that identifies
    /// them. Shared with the tile workers, which gate tessellation on it and stamp every
    /// mesh they build with the generation they read.
    pub(crate) toggles: Arc<SharedToggles>,
    /// Read live every frame, because the worker only learns it after fetching the header.
    pub(crate) zoom_range: Arc<ZoomRange>,
    /// Frames drawn, for the once-a-second diagnostic log.
    pub(crate) frames: u32,
    /// Last frame's density (device px per Dp), for the task-17 pick path's
    /// Dp→device-px conversion. Written every render call.
    pub(crate) density: f32,
}

/// Shared so Kotlin's connectivity callback can reach the reader on the worker thread.
pub(crate) struct OnlineFlag(pub(crate) std::sync::atomic::AtomicBool);

impl OnlineFlag {
    pub(crate) fn set(&self, online: bool) {
        self.0.store(online, std::sync::atomic::Ordering::Relaxed);
    }
    pub(crate) fn get(&self) -> bool {
        self.0.load(std::sync::atomic::Ordering::Relaxed)
    }
}

pub(crate) fn handle_mut(handle: jlong) -> Option<&'static mut MapHandle> {
    if handle == 0 {
        return None;
    }
    // The handle is only ever the pointer `create` returned, and Kotlin drives all of
    // these from one thread.
    unsafe { Some(&mut *(handle as *mut MapHandle)) }
}
