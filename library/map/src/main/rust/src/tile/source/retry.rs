/// How long to wait before re-requesting a tile after its `attempts`th consecutive failure.
///
/// A failed tile **must** be retried — one transient error otherwise leaves a hole in the map
/// for the rest of the session — but until now it was retried with no delay at all, which
/// meant a tile that keeps failing was re-requested on every frame, sixty times a second, for
/// as long as it stayed visible. With no network, or behind a captive portal, that is a
/// request storm and a pinned CPU.
///
/// It also defeats on-demand rendering, which is the sharper consequence: the host stops
/// drawing when nothing is changing, and "a tile is in flight" is one of the things that
/// counts as changing. A tile that immediately re-enters the in-flight set on every frame
/// means the set is never durably empty and the map never idles — the hot-phone bug, moved
/// from sitting still to standing in a tunnel.
///
/// Doubles from [`RETRY_BASE_MS`] and saturates at [`RETRY_MAX_MS`], so a one-off blip costs
/// a quarter of a second and a genuinely unreachable archive settles to a poll rather than a
/// spin. `attempts` is 1 on the first failure; 0 is treated as 1.
pub fn retry_delay_ms(attempts: u32) -> u64 {
    let doublings = attempts.saturating_sub(1).min(RETRY_MAX_DOUBLINGS);
    (RETRY_BASE_MS << doublings).min(RETRY_MAX_MS)
}

/// The wait after a first failure. Short enough that recovering from a blip is invisible.
pub const RETRY_BASE_MS: u64 = 250;

/// The ceiling the backoff saturates at. A tile still failing after this long is not coming
/// back on its own, so anything longer only delays recovery once the network does return —
/// and reconnecting pushes `setOnline`, which wakes the loop regardless of where the backoff
/// had got to.
pub const RETRY_MAX_MS: u64 = 10_000;

/// Enough doublings to pass [`RETRY_MAX_MS`], bounded so the shift cannot overflow on a tile
/// that has failed thousands of times.
const RETRY_MAX_DOUBLINGS: u32 = 16;
