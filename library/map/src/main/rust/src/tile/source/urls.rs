/// The self-hosted basemap archive: Protomaps v4 schema, z0-16.
///
/// The same file `maps` streams through MapLibre (`MapTileCache.BASEMAP_PMTILES_URL`),
/// without the `pmtiles://` scheme prefix MapLibre needs to route it.
pub const BASEMAP_PMTILES_URL: &str = "https://data.vayunmathur.com/v4.pmtiles";

/// Where this renderer reads its tiles from.
///
/// Planet cutover: now points at the live planet.mamaps uploaded to
/// https://data.vayunmathur.com/planet.mamaps (67.85 GB, build_id 0x2e729b4449d0b1cc).
/// BASEMAP_PMTILES_URL is kept as revert fallback.
pub const BASEMAP_ARCHIVE_URL: &str = "https://data.vayunmathur.com/planet.mamaps";

/// Bumped when the on-disk cache layout changes, so old entries are dropped rather than
/// misread. The URL is in the marker too, because the archive is republished under the
/// same name and a cached directory chunk addresses the byte offsets of the build it came
/// from.
pub const CACHE_FORMAT: &str = "v1";

/// The cache's origin marker: the layout version, the URL, and the archive's own `build_id`.
///
/// `build_id` is what makes republishing under a stable name safe. The URL alone is not enough — it
/// is deliberately the *same* URL every build, so a cached leaf index would otherwise keep
/// addressing byte offsets from the build it came from, and a user would sit on a stale map forever
/// with no way to notice.
///
/// # Why it is checked after the archive opens rather than before
///
/// The id lives in the archive header, and the header is read *through* this cache. So the cache is
/// opened with [`crate::tile::cache::RangeCache::open_unchecked`], the archive opens, and *then* this marker is compared
/// once — see [`crate::tile::source::CachingRangeReader::reset_origin`]. Checking a partial marker first and this one
/// after would wipe the cache on every start, because the two can never match.
///
/// A prefix served from a stale cache entry reports the stale id, which is correct-but-late: that
/// entry is refetched within the refresh interval and the wipe happens then. Either way it costs no
/// extra request, because the header is already in the prefix a reader must fetch to open the
/// archive at all.
pub fn basemap_origin(url: &str, build_id: u64) -> String {
    format!("{CACHE_FORMAT}|{url}|{build_id:#018x}")
}
