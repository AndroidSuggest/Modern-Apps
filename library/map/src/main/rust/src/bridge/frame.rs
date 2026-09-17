//! One frame from a camera snapshot.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use super::handle::{handle_mut, TileResult, RESIDENT_TILE_CAP, UPLOADS_PER_FRAME};
use super::log::{log, log_info};
use crate::camera::Camera;
use crate::style;
use crate::tile::select;
use crate::tile::source::retry_delay_ms;
use crate::timing::{nanos_since, Step};
use jni::objects::JClass;
use jni::sys::{jboolean, jfloat, jlong};
use jni::JNIEnv;
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
    globe: jboolean,
    moon: jboolean,
) -> jboolean {
    let Some(map) = handle_mut(handle) else {
        return 0;
    };
    // Per-step timing for the `%60` rollup: `Instant` deltas only, never the camera clock
    // (which wraps hourly). The render thread is the only writer.
    let jni_start = std::time::Instant::now();

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
        globe: globe != 0,
        moon: moon != 0,
        time_seconds: ((frame_time_nanos.rem_euclid(crate::camera::CLOCK_WRAP_NANOS)) as f64
            / 1_000_000_000.0) as f32,
    };
    // Task-17 pick needs the frame's density for Dp→device-px; remember it.
    map.density = density;

    // Upload whatever the workers finished, up to `UPLOADS_PER_FRAME`. Doing it here rather than
    // on a worker keeps every Vulkan call on one thread; bounding it keeps a burst of finished
    // tiles from landing in a single frame.
    //
    // Skipped on Moon frames: the Moon draws one uploaded texture pair, not tiles —
    // no selection runs, nothing is in flight, and draining here would upload Earth
    // tiles the Moon frame never draws (wasted uploads + residency churn under the
    // Moon). The Earth set resumes untouched when the body switches back.
    //
    // The drain sample below still records (zero elapsed on Moon frames) so the
    // frame-time rollup keeps its shape.
    let drain_start = std::time::Instant::now();
    if !crate::camera::moon_active(&camera) {
    let mut uploads = 0usize;
    while uploads < UPLOADS_PER_FRAME {
        let Ok((key, result)) = map.finished.try_recv() else {
            break;
        };
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
                map.retry
                    .insert(key, (attempts, std::time::Instant::now() + wait));
            }
        }
    }
    } // end Moon drain skip.

    // Keep the visible tiles plus any ancestor of one that we already have, but **fetch
    // only the visible tiles**. An ancestor is a fallback for a tile that has not arrived yet, so
    // it is only useful if we **already have it** — fetching one costs a round trip to draw a
    // blurrier version of a tile that is being fetched anyway. (Moon frames skip this
    // whole block: `visible`/`keep` below are empty and `retain` keeps the Earth set
    // untouched — see the guard.)
    //
    // `visible` goes to `retain` as well as to the fetch loop, because the other half of the
    // fallback — already-resident *descendants*, which are what stops a zoom-out blanking the
    // map — cannot be named in a keep list without enumerating tiles that were never fetched.
    //
    // The drain sample lands here rather than right after the loop: it covers the channel
    // receives and the uploads, not the select below.
    map.renderer
        .step_times
        .borrow_mut()
        .record(Step::UploadDrain, nanos_since(drain_start));
    let select_start = std::time::Instant::now();
    let (min_zoom, max_zoom) = map.zoom_range.get();
    // A tile is "had" only if it was tessellated at the current toggle generation, so a
    // toggle change re-requests the resident set through this same loop rather than
    // needing a path of its own. The stale mesh keeps drawing until its replacement
    // arrives.
    let (_, _, generation) = map.toggles.get();
    // Moon frames: no selection, no fetch, no eviction. The Earth resident set,
    // in-flight set and backoffs below are all left exactly as they were, so
    // switching back to Earth resumes mid-stream rather than refetching.
    let (visible, keep): (Vec<select::TileId>, Vec<u64>) = if crate::camera::moon_active(&camera) {
        (Vec::new(), Vec::new())
    } else {
        let visible = select::visible(&camera, min_zoom, max_zoom);
        let keep: Vec<u64> = select::resident_set(&camera, min_zoom, max_zoom)
            .iter()
            .map(|t| t.key())
            .collect();
        (visible, keep)
    };
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
    // Skipped on Moon frames along with the fetch above: `visible` is empty, and
    // retaining against it would drop every Earth backoff (harmless but wasteful)
    // — worse, `retain` below with an empty keep would EVICT the Earth set. The
    // Moon guard keeps both calls out.
    //
    // Linear rather than a `HashSet` of the visible keys, deliberately: this runs per frame,
    // `retry` is empty in the ordinary case (so the closure never runs), and a viewport is a
    // couple of dozen tiles. Building a set here would allocate every frame to save nothing.
    let moon = crate::camera::moon_active(&camera);
    if !moon {
        if !map.retry.is_empty() {
            map.retry
                .retain(|key, _| visible.iter().any(|t| t.key() == *key));
        }
        map.renderer.retain(&keep, &visible, RESIDENT_TILE_CAP);
    }
    map.renderer
        .step_times
        .borrow_mut()
        .record(Step::Select, nanos_since(select_start));

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
        //
        // The step rollup is built (and its window reset) only on this frame: every other
        // frame pays integer stores only. `avg/max` per step in ms over the last 60 frames.
        let steps = map.renderer.step_times.borrow_mut().report(60);
        log_info(&format!(
            "z{:.2} @{:.4},{:.4} b{:.0} vp {}x{}dp {}x{}px msaa {}x | resident {} tiles, {} meshes, \
             {} draws, {} tris | {} in flight, {} absent | archive z{}..{} | {}",
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
            steps,
        ));
    }

    // The active category filter goes to the renderer as well as to tessellation: a chip both
    // narrows which POIs are drawn and pulls its own kinds in earlier than the ambient map shows
    // them. See `Layer::draws_at_focused`.
    let (_, kinds, _) = map.toggles.get();
    let outcome = map.renderer.render(
        &camera,
        &map.layers,
        map.palette,
        style::background(map.palette.variant),
        &kinds,
    );
    // The JNI-entry sample closes here: it covers drain + select + render, which is the whole
    // native half of the frame the host asked for.
    map.renderer
        .step_times
        .borrow_mut()
        .record(Step::JniEntry, nanos_since(jni_start));
    match outcome {
        Ok(drawn) => jboolean::from(drawn),
        Err(e) => {
            log(&format!("frame failed: {e}"));
            0
        }
    }
}

/// The last frame's per-step times in nanos, in [`Step::ALL`](crate::timing::Step) order.
///
/// For the debug overlay's slow poll (~2–4 Hz): a copy, so the render thread never blocks on it.
/// A dead handle (or a JNI failure) answers an empty array rather than crashing — the overlay
/// reads that as "no data yet". Must never drive `needs_frame` or alter the frame loop.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_lastFrameStepTimesNanos<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jni::sys::jlongArray {
    let empty = env.new_long_array(0).expect("step-times empty array");
    let Some(map) = handle_mut(handle) else {
        return empty.into_raw();
    };
    let last = map.renderer.step_times.borrow();
    let nanos = last.last_nanos();
    let out = match env.new_long_array(nanos.len() as i32) {
        Ok(a) => a,
        Err(_) => return empty.into_raw(),
    };
    // `i64` is only the JNI carrier: the samples are `u64` nanos, and no step of a frame can
    // reach the sign bit, so the cast is exact.
    let wide: Vec<i64> = nanos.iter().map(|&n| n as i64).collect();
    if env.set_long_array_region(&out, 0, &wide).is_err() {
        return empty.into_raw();
    }
    out.into_raw()
}
