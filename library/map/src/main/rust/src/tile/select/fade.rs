use super::resident::ANCESTOR_DEPTH;

use super::tile_id::TileId;

/// How long a finer LOD tile takes to cross-fade in over its coarse ancestor, in seconds (WS-D).
///
/// Short enough to read as an anti-pop rather than an animation; measured against the shared
/// clock (`Camera::time_seconds` / `Push.misc.w`) from the tile's `uploaded_at` stamp.
pub const LOD_FADE_SECONDS: f32 = 0.3;

/// The per-tile opacity of a tile `now - uploaded_at` seconds after its GPU buffers landed: 0 at
/// upload, ramping linearly to 1 over `duration` (WS-D LOD cross-fade). Both times share the
/// `Camera::time_seconds` epoch. A non-positive `duration` disables the fade (opaque at once).
pub fn lod_fade_alpha(now: f32, uploaded_at: f32, duration: f32) -> f32 {
    if duration <= 0.0 {
        return 1.0;
    }
    ((now - uploaded_at) / duration).clamp(0.0, 1.0)
}

/// Whether a tile stamped `uploaded_at` is still inside its cross-fade, and so would draw
/// differently on the next frame even from an identical camera.
///
/// This is what stops the on-demand frame loop idling mid-fade and freezing a tile at
/// half opacity. It is deliberately **not** `lod_fade_alpha(..) < 1.0`: the fade is gated on
/// a resident ancestor (see [`tile_lod_alpha`]) and this is not, so it answers "is this tile
/// young enough that the fade *could* be running" rather than "is it running". Erring that
/// way costs a few frames on a tile that was never going to fade; erring the other way stops
/// the clock on one that was.
///
/// The elapsed time is taken **modulo [`CLOCK_WRAP_SECONDS`]**, because the shared clock wraps
/// hourly (see [`Camera::time_seconds`](crate::camera::Camera::time_seconds)). A plain
/// subtraction is not merely imprecise across a wrap, it is wrong in the worst direction: a
/// tile stamped at 3599.5 against a clock that has just wrapped to 0.0 gives -3599.5, which is
/// below any sane `duration`, so the window would read as open and pin the frame loop awake
/// for the remaining hour — the exact thing on-demand rendering exists to prevent.
pub fn fade_in_progress(now: f32, uploaded_at: f32, duration: f32) -> bool {
    if duration <= 0.0 {
        return false;
    }
    (now - uploaded_at).rem_euclid(crate::camera::CLOCK_WRAP_SECONDS) < duration
}

/// Whether a coarser ancestor of `key` (up to [`ANCESTOR_DEPTH`] levels up) is currently
/// resident, and so is drawn underneath as an opaque stand-in.
///
/// This gates the fade: a finer tile may fade in only when there is an ancestor to show through
/// the gap. A tile with no resident ancestor draws fully opaque immediately, so a freshly
/// fetched area never fades up from the background.
pub fn has_resident_ancestor(key: u64, resident: &std::collections::HashSet<u64>) -> bool {
    let tile = TileId::from_key(key);
    (1..=ANCESTOR_DEPTH)
        .any(|levels| tile.ancestor(levels).is_some_and(|a| resident.contains(&a.key())))
}

/// The per-tile LOD cross-fade opacity the renderer writes into `Push.morph.x` for tile `key`.
///
/// Ramps 0→1 over `duration` from `uploaded_at` when a coarse ancestor is resident to stand in
/// under the gap; otherwise fully opaque, so a tile with nothing beneath it never fades up from
/// the background. `resident` is the set of currently resident tile keys.
pub fn tile_lod_alpha(
    key: u64,
    uploaded_at: f32,
    now: f32,
    duration: f32,
    resident: &std::collections::HashSet<u64>,
) -> f32 {
    if has_resident_ancestor(key, resident) {
        lod_fade_alpha(now, uploaded_at, duration)
    } else {
        1.0
    }
}
