/// Where this renderer reads its tiles from: the planet-scale v7 single-file
/// full `.mamaps` archive (12 layers, 128-byte header).
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
/// The marker is computed **before** the cache opens from a header prefix fetch, and the cache is
/// opened once with the full marker: one-shot open, no two-step reset.
pub fn basemap_origin(url: &str, build_id: u64) -> String {
    format!("{CACHE_FORMAT}|{url}|{build_id:#018x}")
}
