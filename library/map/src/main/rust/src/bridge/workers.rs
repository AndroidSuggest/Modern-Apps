//! Tile workers: one archive view each, serving requests until the queue closes.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use crate::style::{self, SharedToggles};
use crate::tile::cache::{RangeCache, DEFAULT_MAX_BYTES};
use crate::tile::geometry;
use crate::tile::select::TileId;
use crate::tile::source::BASEMAP_ARCHIVE_URL;
use crate::tile::source::{
    basemap_origin, CachingRangeReader, FileRangeReader, JniRangeFetcher, RangeFetcher,
};
use tilecodec::mamaps::header::Header;
use tilecodec::mamaps::MamapsArchive;
use tilecodec::stream::RangeReader;
use tilecodec::stream::OPEN_PREFIX_BYTES;
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
use super::handle::{OnlineFlag, TileResult, ZoomRange};
use super::log::log;
/// A worker: opens its own view of the archive, then serves tile requests until the queue
/// closes.
///
/// Each worker holds its **own** [`MamapsArchive`] (and its own leaf-index cache and prefix
/// parse — small CPU work on an in-memory prefix), so its range reads need no lock. What is
/// shared across the surface's workers is everything that costs a *network round trip*: the
/// header `build_id` fetch (one JNI request per cold start, not one per worker, via `header`)
/// and the forced first-read revalidation (one prefix read, not one per worker, via
/// `prefix_gate`). The on-disk range cache is shared too, and is safe to share because every
/// entry is written temp-then-renamed.
///
/// `header`/`prefix_gate` are built once per surface in `lifecycle::create` and handed to every
/// worker: whichever worker arrives first does the fetch while the rest block on it.
#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_worker(
    index: usize,
    archive_url: String,
    cache_dir: String,
    local_path: Option<String>,
    queue: Arc<Mutex<Receiver<TileId>>>,
    finished: Sender<(u64, TileResult)>,
    online: Arc<OnlineFlag>,
    zoom_range: Arc<ZoomRange>,
    toggles: Arc<SharedToggles>,
    header: Arc<std::sync::OnceLock<Option<u64>>>,
    prefix_gate: Arc<std::sync::atomic::AtomicBool>,
) {
    let started = std::thread::Builder::new()
        .name(format!("map-tiles-{index}"))
        .spawn(move || {
            // A pushed local archive wins: when the file is present the reader opens it
            // straight from disk and skips the header fetch, the origin marker and the
            // range cache entirely — the file is the whole archive, so none of the
            // republished-under-a-stable-name machinery applies.
            if let Some(path) = local_path.as_deref() {
                if FileRangeReader::exists(path) {
                    let reader = match FileRangeReader::open(path) {
                        Ok(r) => r,
                        Err(e) => {
                            log(&format!(
                                "worker {index} cannot open the local mamaps archive {path}: {e}"
                            ));
                            return;
                        }
                    };
                    match MamapsArchive::open(reader) {
                        Ok(archive) => serve(index, archive, &queue, &finished, &zoom_range, &toggles),
                        Err(e) => log(&format!(
                            "worker {index} cannot open the local mamaps archive {path}: {e}"
                        )),
                    }
                    return;
                }
            }

            // Single open: the header prefix is fetched *once per surface* (the first worker
            // fetches, the rest block on `header`), then the `build_id` is read out of it and the
            // cache opens once per worker with the full origin marker — wiping on mismatch up
            // front. No two-step reset. A failed fetch poisons the lock for every worker, so one
            // dead network kills the surface's workers once instead of logging four times.
            let build_id = *header.get_or_init(|| {
                match JniRangeFetcher.fetch(&archive_url, "bytes=0-127") {
                    Ok(r)
                        if r.status == 206
                            && r.body.len() == tilecodec::mamaps::header::HEADER_LEN =>
                    {
                        match Header::parse(&r.body) {
                            Ok(h) => Some(h.build_id),
                            Err(e) => {
                                log(&format!("cannot parse the mamaps header: {e}"));
                                None
                            }
                        }
                    }
                    Ok(r) => {
                        log(&format!("cannot fetch the mamaps header: HTTP {}", r.status));
                        None
                    }
                    Err(e) => {
                        log(&format!("cannot fetch the mamaps header: {e}"));
                        None
                    }
                }
            });
            let Some(build_id) = build_id else { return };
            let cache =
                RangeCache::open(cache_dir, &basemap_origin(&archive_url, build_id), DEFAULT_MAX_BYTES);
            let reader = CachingRangeReader::new_shared(
                archive_url.clone(),
                cache,
                JniRangeFetcher,
                prefix_gate,
            );
            reader.set_online(online.get());

            let archive = match MamapsArchive::open(reader) {
                Ok(a) => a,
                Err(e) => {
                    log(&format!("worker {index} cannot open the mamaps archive: {e}"));
                    return;
                }
            };
            serve(index, archive, &queue, &finished, &zoom_range, &toggles);
        })
        .is_ok();
    if !started {
        log(&format!("cannot start tile worker {index}"));
    }
}

/// Serve tile requests from `archive` until the queue closes.
///
/// Generic over the reader so the same loop drives both the URL-backed
/// [`CachingRangeReader`] and the disk-backed [`FileRangeReader`]: only how the archive
/// is opened differs, never how tiles are served from it.
fn serve<R: RangeReader>(
    index: usize,
    mut archive: MamapsArchive<R>,
    queue: &Arc<Mutex<Receiver<TileId>>>,
    finished: &Sender<(u64, TileResult)>,
    zoom_range: &Arc<ZoomRange>,
    toggles: &Arc<SharedToggles>,
) {
    // Publish the real range. Until this lands the renderer works from a guess, and
    // a guess that is too high asks for a zoom the archive does not contain and
    // silently gets nothing back.
    zoom_range.set(archive.header.min_zoom, archive.header.max_zoom);
    let layers = style::layers();
    // Read once from the header rather than per tile: it is a property of the archive.
    let rings_validated = archive.header.rings_validated();

    loop {
        // Hold the queue lock only long enough to take one tile, never across the
        // fetch — otherwise the workers would run strictly in turn.
        let next = match queue.lock() {
            Ok(guard) => guard.recv(),
            Err(_) => return,
        };
        let Ok(tile) = next else { return };
        let key = tile.key();

        // Read per tile, not once per worker: a toggle change has to reach the
        // very next tile built, and all three parts come from one snapshot so the
        // stamp can never describe different flags than the mesh was built with.
        let (enabled, kinds, generation) = toggles.get();
        let result = match archive.tile(tile.z, tile.x, tile.y) {
            Ok(Some(body)) => TileResult::Ready(geometry::build_toggled(
                &body,
                layers,
                tile.z,
                tile.x,
                tile.y,
                rings_validated,
                enabled,
                &kinds,
                generation,
            )),
            Ok(None) => TileResult::Absent,
            Err(e) => {
                log(&format!("tile {}/{}/{} failed: {e}", tile.z, tile.x, tile.y));
                TileResult::Failed
            }
        };
        // A closed receiver means the surface went away mid-decode.
        if finished.send((key, result)).is_err() {
            return;
        }
    }
}
