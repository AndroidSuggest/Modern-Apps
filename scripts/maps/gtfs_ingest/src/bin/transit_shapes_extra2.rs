//! The `run` pipeline for `transit_shapes`, moved out of `transit_shapes.rs`
//! to keep the bin under the file-size limit. Behaviour-identical.

use super::*;

pub(crate) fn run(out_path: &Path, specs: &[FeedSpec]) -> Result<(), String> {
    let mut seen: HashSet<(u64, u32)> = HashSet::new();
    let mut lines: Vec<Line> = Vec::new();
    // Track already drawn, per colour and per mode. Two of one colour must never draw as
    // parallel lines — nothing distinguishes them, so the second is pure over-draw — and what
    // stops that is the slot, which is assigned per colour so they land on top of each other.
    // This drops the ones that are wholly redundant as well, so the layer does not carry a
    // route's two directions and every other feed's copy of them.
    //
    // The per-mode cover is for a line wearing its mode's fallback colour, which means the
    // feed gave it none: if that track is already drawn in *someone's* colour, a line with
    // no colour of its own should not be drawn over it in a guessed one.
    let mut by_color: BTreeMap<(&'static str, u32), bundle::Covered> = BTreeMap::new();
    let mut by_mode: BTreeMap<&'static str, bundle::Covered> = BTreeMap::new();
    let (mut kept, mut without_shape, mut deduped, mut fell_back) = (0usize, 0usize, 0usize, 0usize);
    let (mut shapes_read, mut merged) = (0usize, 0usize);
    let mut crowded = 0usize;

    // Feeds are parsed in parallel and folded in sequentially.
    //
    // Parsing is pure per feed and was most of the wall clock: a world-scale set spent ~40 of
    // its 54 minutes here, single-threaded, on machines with dozens of idle cores. The fold
    // below cannot move — `seen`, `by_color` and `by_mode` are order-dependent, and feed order
    // is what decides which duplicate of a shared alignment survives — so only the reads are
    // spread out, and their results are put back into spec order before any of them is folded.
    // The output is therefore bit-identical to the sequential version.
    //
    // Chunked rather than read-all-then-fold: a chunk of twice the pool keeps every core busy
    // while capping how many parsed feeds are alive at once, so peak memory follows the pool
    // size and not the number of feeds.
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    let chunk_size = threads.saturating_mul(2).max(1);

    for chunk in specs.chunks(chunk_size) {
        let next = AtomicUsize::new(0);
        let parsed: Mutex<Vec<(usize, Result<FeedLines, String>)>> =
            Mutex::new(Vec::with_capacity(chunk.len()));
        std::thread::scope(|scope| {
            for _ in 0..threads.min(chunk.len()) {
                scope.spawn(|| loop {
                    let i = next.fetch_add(1, Ordering::Relaxed);
                    if i >= chunk.len() {
                        break;
                    }
                    let read = read_feed(&chunk[i]);
                    parsed.lock().expect("feed reader pool").push((i, read));
                });
            }
        });
        let mut parsed = parsed.into_inner().expect("feed reader pool");
        // Back into spec order, so the fold below sees exactly the sequence it used to.
        parsed.sort_unstable_by_key(|(i, _)| *i);

        for (i, feed) in parsed {
            let spec = &chunk[i];
            let feed = feed?;
            kept += feed.routes_kept;
            without_shape += feed.routes_without_shape;
            shapes_read += feed.shapes_read;
            let mut new = 0usize;
            for line in feed.lines {
                // Across feeds as well as within one, so an alignment published by both a city
                // feed and the regional feed that merges it draws once.
                //
                // Keyed on the colour too, which the hash alone was not: two services running
                // the same track are routinely published against one `shape_id`, and dropping
                // one of them because its geometry had been seen is how a corridor loses a
                // line. This is only the fast path for the subtraction below, which would reach
                // the same answer the slow way.
                if !seen.insert((polyline_hash(&line.points), line.color)) {
                    deduped += 1;
                    continue;
                }
                let mode_cover = by_mode.entry(line.mode).or_default();
                let redundant = if line.fallback {
                    mode_cover.covered_fraction(&line.points) >= MOSTLY_DRAWN
                } else {
                    by_color
                        .entry((line.mode, line.color))
                        .or_default()
                        .covered_fraction(&line.points)
                        >= MOSTLY_DRAWN
                };
                if redundant {
                    merged += 1;
                    continue;
                }
                // The gates above are colour-scoped, deliberately: two services sharing a track
                // are two real services and both should draw. On a planet that stops being true.
                // One alignment is republished by a city feed, the regional feed containing it and
                // a national feed on top, each with its own `route_color` and often its own
                // `route_type`, so each reads as a distinct service and claims its own lane. That
                // is what makes one railway render as fifteen jagged parallel lines.
                //
                // A ceiling rather than a ban, so the two-services-on-one-track case the tests
                // pin still works. Above it the track is already saying everything it can.
                if mode_cover.crowd_reaches(&line.points, MAX_SERVICES_PER_TRACK) {
                    crowded += 1;
                    continue;
                }
                mode_cover.add_tagged(&line.points, line.color);
                by_color.entry((line.mode, line.color)).or_default().add(&line.points);
                if line.fallback {
                    fell_back += 1;
                }
                lines.push(line);
                new += 1;
            }
            eprintln!("transit_shapes: feed '{}': {new} new line(s)", spec.0);
        }
    }
    drop(by_color);
    drop(by_mode);

    // Which routes share a corridor, which lane each takes in it, and where each line has
    // to be cut for that lane to change. Once, over the whole set: neither the tiler nor
    // the renderer sees more than one tile, so deciding it there is what puts a jog in every
    // route at every tile seam.
    let bundled = expand_corridor_spans(&mut lines);

    // Deterministic output, so a rebuild produces a byte-identical layer and the
    // tile diff is empty when nothing changed. Feed order already fixes which
    // duplicate survives; this fixes the order they are written in.
    lines.sort_by(|a, b| {
        a.points
            .cmp(&b.points)
            .then_with(|| a.color.cmp(&b.color))
            .then_with(|| a.mode.cmp(b.mode))
            .then_with(|| (a.ordinal, a.lanes, a.taper).cmp(&(b.ordinal, b.lanes, b.taper)))
            .then_with(|| a.route.cmp(&b.route))
    });

    let file = std::fs::File::create(out_path)
        .map_err(|e| format!("cannot write {}: {e}", out_path.display()))?;
    let mut out = BufWriter::new(file);
    let mut buf: Vec<u8> = Vec::new();
    for line in &lines {
        buf.clear();
        buf.extend_from_slice(
            b"{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[",
        );
        for (i, (lat, lon)) in line.points.iter().enumerate() {
            if i > 0 {
                buf.push(b',');
            }
            write!(buf, "[{:.7},{:.7}]", *lon as f64 * 1e-7, *lat as f64 * 1e-7)
                .map_err(io_err)?;
        }
        write!(
            buf,
            "]}},\"properties\":{{\"color\":\"{:06X}\",\"mode\":\"{}\",\"ordinal\":{},\
             \"lanes\":{},\"taper\":{},\"route\":\"",
            line.color, line.mode, line.ordinal, line.lanes, line.taper,
        )
        .map_err(io_err)?;
        json_escape(line.route.as_bytes(), &mut buf);
        buf.extend_from_slice(b"\"}}\n");
        out.write_all(&buf).map_err(io_err)?;
    }
    out.flush().map_err(io_err)?;

    eprintln!(
        "transit_shapes: wrote {} ({} feed(s), {kept} rail route(s) kept, \
         {without_shape} with no usable shape, {shapes_read} shape(s) read, \
         {deduped} republished byte-for-byte, {merged} already drawn in their colour, \
         {crowded} over track already carrying {MAX_SERVICES_PER_TRACK} services, \
         {} line(s), {bundled} in a shared corridor, \
         {fell_back} on a per-mode fallback colour)",
        out_path.display(),
        specs.len(),
        lines.len(),
    );
    Ok(())
}
