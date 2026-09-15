//! Tile workers: one archive view each, serving requests until the queue closes.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use crate::style::{self, SharedToggles};
use crate::tile::cache::{RangeCache, DEFAULT_MAX_BYTES};
use crate::tile::geometry;
use crate::tile::select::TileId;
use crate::tile::source::BASEMAP_ARCHIVE_URL;
use crate::tile::source::{basemap_origin, CachingRangeReader, JniRangeFetcher, RangeFetcher};
use tilecodec::mamaps::header::Header;
use tilecodec::mamaps::MamapsArchive;
use tilecodec::stream::OPEN_PREFIX_BYTES;
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
use super::handle::{OnlineFlag, TileResult, ZoomRange};
use super::log::log;
/// A worker: opens its own view of the archive, then serves tile requests until the queue
/// closes.
///
/// Each worker holds its **own** [`MamapsArchive`], so its leaf-index cache and range
/// reads need no lock. The cost is one 16 KB header fetch per worker at startup and a
/// duplicated leaf cache; the alternative — one archive behind a mutex — would serialise
/// exactly the round trips this is meant to overlap. The on-disk range cache is shared, and
/// is safe to share because every entry is written temp-then-renamed.
#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_worker(
    index: usize,
    archive_url: String,
    cache_dir: String,
    queue: Arc<Mutex<Receiver<TileId>>>,
    finished: Sender<(u64, TileResult)>,
    online: Arc<OnlineFlag>,
    zoom_range: Arc<ZoomRange>,
    toggles: Arc<SharedToggles>,
) {
    let started = std::thread::Builder::new()
        .name(format!("map-tiles-{index}"))
        .spawn(move || {
            // Single open: the header prefix is fetched *before* the cache opens, the
            // `build_id` is read out of it, and the cache opens once with the full
            // origin marker — wiping on mismatch up front. No two-step reset.
            let build_id = match JniRangeFetcher.fetch(&archive_url, "bytes=0-127") {
                Ok(r) if r.status == 206 && r.body.len() == tilecodec::mamaps::header::HEADER_LEN => {
                    match Header::parse(&r.body) {
                        Ok(h) => h.build_id,
                        Err(e) => {
                            log(&format!("worker {index} cannot parse the mamaps header: {e}"));
                            return;
                        }
                    }
                }
                Ok(r) => {
                    log(&format!(
                        "worker {index} cannot fetch the mamaps header: HTTP {}",
                        r.status
                    ));
                    return;
                }
                Err(e) => {
                    log(&format!("worker {index} cannot fetch the mamaps header: {e}"));
                    return;
                }
            };
            let cache =
                RangeCache::open(cache_dir, &basemap_origin(&archive_url, build_id), DEFAULT_MAX_BYTES);
            let reader = CachingRangeReader::new(archive_url.clone(), cache, JniRangeFetcher);
            reader.set_online(online.get());

            let mut archive = match MamapsArchive::open(reader) {
                Ok(a) => a,
                Err(e) => {
                    log(&format!("worker {index} cannot open the mamaps archive: {e}"));
                    return;
                }
            };
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
        })
        .is_ok();
    if !started {
        log(&format!("cannot start tile worker {index}"));
    }
}
