//! One frame from a camera snapshot.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use crate::camera::Camera;
use crate::style;
use crate::tile::select;
use crate::tile::source::retry_delay_ms;
use jni::objects::JClass;
use jni::sys::{jboolean, jfloat, jlong};
use jni::JNIEnv;
use super::handle::{RESIDENT_TILE_CAP, TileResult, UPLOADS_PER_FRAME, handle_mut};
use super::log::{log, log_info};
/// Draw one frame from a camera snapshot. Returns false if the frame was skipped.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_render<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    center_lon: jfloat,
    center_lat: jfloat,
    zoom: jfloat,
    bearing: jfloat,
    pitch: jfloat,
    width_dp: jfloat,
    height_dp: jfloat,
    density: jfloat,
    frame_time_nanos: jlong,
) -> jboolean {
    let Some(map) = handle_mut(handle) else { return 0 };

    // The camera zoom crosses the boundary untouched. MapLibre parity is
    // `camera::TILE_SIZE` being 512, the convention the archives are authored on, so
    // tile addressing is the plain floor of this zoom and every style ramp is
    // evaluated at the zoom the authored `basemap.json` meant by it. This was
    // previously a 256 grid with a +1 offset applied here, which reached the same
    // ground scale but fetched z+1 tiles — four times as many as MapLibre for the
    // same screenful — and read every ramp one level deep.
    //
    // `bearing` is degrees clockwise from north for whatever points up the screen: 0 on
    // every phone frame, and the car's heading during heading-up navigation.
    //
    // `pitch` is the tilt away from straight-down, clamped to the renderer's supported band
    // here so no matrix has to defend against a wild value. `frame_time_nanos` is the host's
    // Choreographer clock, reduced modulo an hour before it becomes an `f32` so a long uptime
    // does not blow past the ~7 significant digits an `f32` has and coarsen the animation clock
    // to tens of milliseconds — a once-an-hour wrap is invisible to the periodic effects that
    // read it.
    let camera = Camera {
        center_lon: center_lon as f64,
        center_lat: center_lat as f64,
        zoom: zoom as f64,
        width_dp,
        height_dp,
        density,
        bearing_deg: bearing as f64,
        pitch_deg: (pitch as f64).clamp(0.0, crate::camera::PITCH_MAX_DEG),
        time_seconds: ((frame_time_nanos.rem_euclid(crate::camera::CLOCK_WRAP_NANOS)) as f64 / 1_000_000_000.0) as f32,
    };
    // Task-17 pick needs the frame's density for Dp→device-px; remember it.
    map.density = density;

    // Upload whatever the workers finished, up to `UPLOADS_PER_FRAME`. Doing it here rather than
    // on a worker keeps every Vulkan call on one thread; bounding it keeps a burst of finished
    // tiles from landing in a single frame.
    let mut uploads = 0usize;
    while uploads < UPLOADS_PER_FRAME {
        let Ok((key, result)) = map.finished.try_recv() else { break };
        map.in_flight.remove(&key);
        match result {
            TileResult::Ready(mesh) => {
                uploads += 1;
                // It arrived, so whatever was failing has stopped. Anything else would leave a
                // tile that recovered still carrying a ten-second backoff for the session.
                map.retry.remove(&key);
                if let Err(e) = map.renderer.upload(key, &mesh) {
                    log(&format!("uploading a tile failed: {e}"));
                }
            }
            // Remembered, so a mostly-ocean viewport does not re-request the same empty
            // tiles every frame for the life of the surface.
            TileResult::Absent => {
                map.absent.insert(key);
                map.retry.remove(&key);
            }
            // Deliberately not recorded as absent: clearing `in_flight` above is what lets it
            // be tried again, which is the whole point of distinguishing this from `Absent`.
            // What is recorded is *when* — without a deadline the retry lands on the very next
            // frame, so a tile that keeps failing is re-requested sixty times a second and the
            // in-flight set never empties, which both storms the network and stops the
            // on-demand frame loop ever idling.
            TileResult::Failed => {
                let attempts = map.retry.get(&key).map_or(0, |(n, _)| *n).saturating_add(1);
                let wait = std::time::Duration::from_millis(retry_delay_ms(attempts));
                map.retry.insert(key, (attempts, std::time::Instant::now() + wait));
            }
        }
    }

    // Keep the visible tiles plus any ancestor of one that we already have, but **fetch
    // only the visible tiles**. An ancestor is a fallback for a tile still in flight, so
    // it is only worth drawing if it is already resident — fetching one spends a round trip
    // to show a blurrier version of a tile that is being fetched anyway, and because
    // ancestors sort first it spent that latency before requesting what the user is
    // actually looking at.
    //
    // `visible` goes to `retain` as well as to the fetch loop, because the other half of the
    // fallback — already-resident *descendants*, which are what stops a zoom-out blanking the
    // map — cannot be named in a keep list without enumerating tiles that were never fetched.
    let (min_zoom, max_zoom) = map.zoom_range.get();
    // A tile is "had" only if it was tessellated at the current toggle generation, so a
    // toggle change re-requests the resident set through this same loop rather than
    // needing a path of its own. The stale mesh keeps drawing until its replacement
    // arrives.
    let (_, _, generation) = map.toggles.get();
    let visible = select::visible(&camera, min_zoom, max_zoom);
    let keep: Vec<u64> =
        select::resident_set(&camera, min_zoom, max_zoom).iter().map(|t| t.key()).collect();
    let now = std::time::Instant::now();
    for tile in &visible {
        let key = tile.key();
        if map.renderer.has_tile(key, generation)
            || map.absent.contains(&key)
            // Still inside its backoff after a failure. Left in `retry` rather than removed
            // here, so the attempt count keeps climbing if it fails again.
            || map.retry.get(&key).is_some_and(|(_, at)| now < *at)
            || !map.in_flight.insert(key)
        {
            continue;
        }
        // A closed channel means every worker died; the map keeps drawing what it has.
        let _ = map.wanted.send(*tile);
    }
    // Drop backoffs for tiles that are no longer visible. Not just housekeeping: an entry whose
    // deadline has passed but which nothing re-requests would make `nextFrameDelayMillis`
    // answer "draw now" forever, spinning the on-demand loop at 60fps for a tile that is off
    // screen. Only the visible set is ever fetched, so only the visible set may hold a backoff.
    //
    // Linear rather than a `HashSet` of the visible keys, deliberately: this runs per frame,
    // `retry` is empty in the ordinary case (so the closure never runs), and a viewport is a
    // couple of dozen tiles. Building a set here would allocate every frame to save nothing.
    if !map.retry.is_empty() {
        map.retry.retain(|key, _| visible.iter().any(|t| t.key() == *key));
    }
    map.renderer.retain(&keep, &visible, RESIDENT_TILE_CAP);

    // Once a second, state what the renderer actually has. Every bug in this file so far has
    // been invisible from the outside: a viewport nobody measured, a zoom level the archive
    // does not contain, a tile stuck in flight forever. All of them would have been one line
    // of this away.
    map.frames += 1;
    if map.frames % 60 == 0 {
        let (tiles, meshes, draws, triangles) = map.renderer.stats();
        let (width_px, height_px) = map.renderer.extent();
        // `meshes` is what is resident, `draws` what the last frame actually submitted. They
        // differ wherever the authored style ramps a layer's width to zero, so reporting only
        // the first would claim roads are being drawn at zooms where they are gated out.
        log_info(&format!(
            "z{:.2} @{:.4},{:.4} b{:.0} vp {}x{}dp {}x{}px msaa {}x | resident {} tiles, {} meshes, \
             {} draws, {} tris | {} in flight, {} absent | archive z{}..{}",
            camera.zoom,
            camera.center_lon,
            camera.center_lat,
            camera.bearing_deg,
            camera.width_dp,
            camera.height_dp,
            width_px,
            height_px,
            map.renderer.samples(),
            tiles,
            meshes,
            draws,
            triangles,
            map.in_flight.len(),
            map.absent.len(),
            min_zoom,
            max_zoom,
        ));
    }

    // The active category filter goes to the renderer as well as to tessellation: a chip both
    // narrows which POIs are drawn and pulls its own kinds in earlier than the ambient map shows
    // them. See `Layer::draws_at_focused`.
    let (_, kinds, _) = map.toggles.get();
    match map.renderer.render(
        &camera,
        &map.layers,
        map.palette,
        style::background(map.palette.variant),
        &kinds,
    ) {
        Ok(drawn) => jboolean::from(drawn),
        Err(e) => {
            log(&format!("frame failed: {e}"));
            0
        }
    }
}
