/// One archive, one id: hash of everything the bytes depend on.
///
/// FNV-1a over the five revisions/digests a republish can move independently:
/// the routing-graph revision, the OSM input digest, the GTFS digest, the DEM
/// digest and the tiler revision. Empty (a build without GTFS/DEM) hashes as
/// empty, never as absent — so a build that gains a feed changes its id.
///
/// The range cache keys on `(CACHE_FORMAT, url, build_id)` via
/// `basemap_origin`, so a new id wipes every stale byte range on next open
/// with no extra request: the id already rides the header every open fetches.
pub fn unified_build_id(
    graph_rev: &[u8],
    osm_digest: &[u8],
    gtfs_digest: &[u8],
    dem_digest: &[u8],
    tiler_rev: &[u8],
) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    for part in [graph_rev, osm_digest, gtfs_digest, dem_digest, tiler_rev] {
        for &b in part {
            h ^= b as u64;
            h = h.wrapping_mul(0x100_0000_01b3);
        }
        // Domain separator so (ab, c) and (a, bc) differ.
        h ^= 0xFF;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}
