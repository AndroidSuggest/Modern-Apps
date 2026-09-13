//! Tile workers: one archive view each, serving requests until the queue closes.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use crate::style::{self, SharedToggles};
use crate::tile::cache::{RangeCache, DEFAULT_MAX_BYTES};
use crate::tile::geometry;
use crate::tile::select::TileId;
use crate::tile::source::BASEMAP_ARCHIVE_URL;
use crate::tile::source::{basemap_origin, CachingRangeReader, JniRangeFetcher};
use tilecodec::mamaps::MamapsArchive;
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
            // Unchecked: the origin marker includes the archive's `build_id`, which is in the
            // header, which is read through this cache. Checked below, once, when it is known.
            let cache = RangeCache::open_unchecked(cache_dir, DEFAULT_MAX_BYTES);
            let reader = CachingRangeReader::new(archive_url.clone(), cache, JniRangeFetcher);
            reader.set_online(online.get());

            let mut archive = match MamapsArchive::open(reader) {
                Ok(a) => a,
                Err(e) => {
                    log(&format!("worker {index} cannot open the mamaps archive: {e}"));
                    return;
                }
            };
            // Now the `build_id` is known, so the cache can be told which build it holds. A
            // republish under the same name wipes it here rather than serving byte offsets from
            // the build before.
            archive
                .reader()
                .reset_origin(&basemap_origin(&archive_url, archive.header.build_id));
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

pub(crate) fn spawn_file_worker(
    index: usize,
    path: Option<std::path::PathBuf>,
    queue: Arc<Mutex<Receiver<TileId>>>,
    finished: Sender<(u64, TileResult)>,
    zoom_range: Arc<ZoomRange>,
    toggles: Arc<SharedToggles>,
) {
    let Some(path) = path else {
        // Empty archive_path == remote fallback already handled by caller printing a log,
        // but keep symmetry for direct callers.
        return;
    };
    let started = std::thread::Builder::new()
        .name(format!("map-tiles-file-{index}"))
        .spawn(move || {
            let reader = match crate::tile::source::FileRangeReader::open(&path) {
                Ok(r) => r,
                Err(e) => {
                    log(&format!("file worker {index} cannot open {}: {e}", path.display()));
                    return;
                }
            };
            let mut archive = match MamapsArchive::open(reader) {
                Ok(a) => a,
                Err(e) => {
                    log(&format!("file worker {index} cannot open mamaps archive {}: {e}", path.display()));
                    return;
                }
            };
            zoom_range.set(archive.header.min_zoom, archive.header.max_zoom);
            let layers = style::layers();
            let rings_validated = archive.header.rings_validated();
            loop {
                let next = match queue.lock() {
                    Ok(guard) => guard.recv(),
                    Err(_) => return,
                };
                let Ok(tile) = next else { return };
                let key = tile.key();
                let (enabled, kinds, generation) = toggles.get();
                let result = match archive.tile(tile.z, tile.x, tile.y) {
                    Ok(Some(body)) => TileResult::Ready(geometry::build_toggled(
                        &body, layers, tile.z, tile.x, tile.y, rings_validated, enabled, &kinds,
                        generation,
                    )),
                    Ok(None) => TileResult::Absent,
                    Err(e) => {
                        log(&format!("file tile {}/{}/{} failed: {e}", tile.z, tile.x, tile.y));
                        TileResult::Failed
                    }
                };
                if finished.send((key, result)).is_err() {
                    return;
                }
            }
        })
        .is_ok();
    if !started {
        log(&format!("cannot start file tile worker {index}"));
    }
}

pub(crate) enum ArchiveSource {
    Default,
    RemoteUrl(String),
    LocalFile(std::path::PathBuf),
}

pub(crate) fn normalize_local_archive_path(raw: &str) -> ArchiveSource {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return ArchiveSource::Default;
    }
    let stripped = if let Some(rest) = trimmed.strip_prefix("file://") {
        rest
    } else {
        trimmed
    };
    let stripped = stripped.trim();
    if stripped.is_empty() {
        return ArchiveSource::Default;
    }
    if stripped.starts_with("http://") || stripped.starts_with("https://") {
        return ArchiveSource::RemoteUrl(stripped.to_string());
    }
    if stripped.contains("://") {
        return ArchiveSource::Default;
    }
    let looks_local = stripped.starts_with('/')
        || stripped.starts_with("C:\\")
        || stripped.starts_with("C:/")
        || stripped.starts_with("/sdcard")
        || stripped.starts_with("/data/")
        || stripped.starts_with("/storage/");
    if looks_local {
        return ArchiveSource::LocalFile(std::path::PathBuf::from(stripped));
    }
    ArchiveSource::Default
}
