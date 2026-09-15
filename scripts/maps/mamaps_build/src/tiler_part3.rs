/// K-way merge one zoom's chunks into ascending tiles, each carrying its layers in id order.
///
/// This is the reduce, and its ordering is the whole correctness argument. Keying the heap on
/// `(tile, layer, chunk index)` makes the entries for one tile arrive layer-major and, within a
/// layer, in **chunk order** — so appending them as they arrive lays a layer's features down in the
/// order the store yielded them. That is not merely *a* deterministic order; it is the order the
/// single-threaded tiler produced, which is why the archive did not move.
///
/// `chunks` is in read order and the index into it is the chunk index, exactly as when the chunks
/// were `BTreeMap`s in memory. Disk is inserted *inside* one partition element, order-preservingly:
/// a chunk's bytes are written in `BTreeMap::into_iter` order and read back in file order, so a
/// [`ChunkReader`] yields the identical key sequence its map did. The heap, `front` and the
/// `layers.last_mut()` adjacency shortcut below are untouched.
///
/// Streamed rather than merged into one map first, so a chunk's bytes are decoded as they are
/// consumed instead of every chunk and a merged copy of it being live at once.
fn merge<'a>(chunks: &[ChunkRef], spill: &'a ChunkSpill) -> Merged<'a> {
    let window = tilespill::read_window_bytes(chunks.len());
    let mut chunks: Vec<ChunkReader<'a>> =
        chunks.iter().map(|at| spill.reader(at, window)).collect();
    let mut front: Vec<Held> = Vec::with_capacity(chunks.len());
    let mut next = BinaryHeap::with_capacity(chunks.len());
    for (index, chunk) in chunks.iter_mut().enumerate() {
        let head = chunk.next().transpose();
        match &head {
            // A stream that fails on its first read still has to enter the heap, or its error would
            // be silently dropped and the archive would come out short.
            Some(Ok(((tile, layer), _))) => next.push(Reverse((*tile, *layer, index))),
            Some(Err(_)) => next.push(Reverse((0, 0, index))),
            None => {}
        }
        front.push(head);
    }
    Merged { chunks, front, next }
}

/// One stream's held entry: `None` past the end of its chunk, `Err` when its bytes were not what
/// the header claimed.
type Held = Option<Result<((u64, u8), ChunkEntry)>>;

/// A merge in progress: one held entry per chunk, and a heap over their keys. `Reverse`, because
/// `BinaryHeap` is a max-heap and the writer wants ascending ids.
struct Merged<'a> {
    chunks: Vec<ChunkReader<'a>>,
    front: Vec<Held>,
    next: BinaryHeap<Reverse<(u64, u8, usize)>>,
}

impl Iterator for Merged<'_> {
    /// Fallible, because a truncated scratch file must fail the build rather than quietly shorten
    /// the archive.
    type Item = Result<(u64, Vec<ChunkEntry>)>;

    fn next(&mut self) -> Option<Result<(u64, Vec<ChunkEntry>)>> {
        let Reverse((tile, _, _)) = *self.next.peek()?;
        let mut layers: Vec<ChunkEntry> = Vec::new();
        while let Some(&Reverse((at, layer_id, index))) = self.next.peek() {
            if at != tile {
                break;
            }
            self.next.pop();
            let held = self.front[index].take().expect("a heap key without its entry");
            let (_, layer) = match held {
                Ok(entry) => entry,
                Err(e) => return Some(Err(e)),
            };
            // Entries for one layer are adjacent, because the key sorts the layer before the chunk.
            // So the accumulator only ever has to look at the layer it started last.
            match layers.last_mut() {
                Some(last) if last.layer.layer_id == layer_id => concatenate(last, layer),
                _ => layers.push(layer),
            }
            let head = self.chunks[index].next().transpose();
            match &head {
                Some(Ok(((tile, layer_id), _))) => self.next.push(Reverse((*tile, *layer_id, index))),
                // Sorted first so the error surfaces on the next call rather than at the end of the
                // zoom, and before anything else is appended.
                Some(Err(_)) => self.next.push(Reverse((0, 0, index))),
                None => {}
            }
            self.front[index] = head;
        }
        Some(Ok((tile, layers)))
    }
}

/// Append one chunk's share of a layer onto another chunk's.
///
/// Rebased, not rebuilt: a `BodyLayer` is three parallel arenas and a feature addresses its parts by
/// index, so concatenating them means shifting `parts_offset` by the parts already there and
/// `coord_start` by the coordinates. The result is byte for byte what [`push`] would have produced
/// had both chunks' features gone into one layer in this order, which is the claim the whole design
/// rests on and what `concatenating_two_chunks_of_a_layer_is_one_layer` holds it to.
///
/// Names remap alongside: each chunk's `name_idx` addresses its own table, so every incoming
/// feature's index is translated into the accumulator's (interning on first use, in merge order —
/// which is chunk order, which is deterministic).
fn concatenate(into: &mut ChunkEntry, from: ChunkEntry) {
    let parts_base = into.layer.parts.len() as u32;
    let coords_base = into.layer.coords.len() as u32;
    // Names remap first, while both tables are still borrowed shared: each incoming index is
    // translated into the accumulator's (interning on first use, in merge order — which is chunk
    // order, which is deterministic). Only then do the arenas move.
    let remap: Vec<u16> = from
        .layer
        .features
        .iter()
        .map(|feature| {
            if feature.name_idx == tilecodec::mamaps::body::NAME_NONE {
                tilecodec::mamaps::body::NAME_NONE
            } else {
                into.intern(Some(&from.names[feature.name_idx as usize - 1]))
            }
        })
        .collect();
    into.layer.features.extend(from.layer.features.into_iter().zip(remap).map(
        |(mut feature, name_idx)| {
            feature.parts_offset += parts_base;
            feature.name_idx = name_idx;
            feature
        },
    ));
    into.layer.parts.extend(from.layer.parts.into_iter().map(|mut part| {
        part.coord_start += coords_base;
        part
    }));
    into.layer.coords.extend_from_slice(&from.layer.coords);
    // Ids are values, not indices into a table, so they concatenate with no remap. Appended in
    // the same order the features were, which is what keeps the two parallel.
    into.ids.extend_from_slice(&from.ids);
    // The turn masks ride the same way: values parallel to features, concatenated in feature order.
    into.turn_lanes.extend(from.turn_lanes);
    // The building attrs ride the same way, dense-parallel to the buildings layer's features.
    into.buildings.extend(from.buildings);
    // And the carriageways, dense-parallel to the roads layer's.
    into.carriageways.extend(from.carriageways);
}

/// Stage C and body encoding for a batch of merged tiles, in parallel, results in tile order.
///
/// Both halves are pure functions of a single tile, which is the only reason this can fan out at
/// all: [`crate::rings::normalise`] rewrites one layer's own arenas and nothing else, and
/// [`tilecodec::mamaps::body::serialize`] only reads. `map(..).collect()` preserves the input's
/// order — rayon's collect is positional, not completion-ordered — so the caller appends in
/// ascending tile id with no sort of its own.
///
/// A tile whose layers all emptied out comes back as `None` rather than being dropped here, because
/// its stage C counters still belong in the build report.
#[allow(clippy::type_complexity)]
/// Stage C, serialise and **compress** one batch of merged tiles, in parallel.
///
/// Compression belongs here rather than in the writer's append. DEFLATE at level nine is the single
/// most expensive step per tile and the one least able to be stolen: left in `append_encoded` it ran
/// on the appending thread, downstream of this whole parallel phase, and on a California z14 it was
/// ~900 MB through one core while sixty-three sat idle. That is why a 64-thread build was *slower*
/// than the sequential one it replaced.
///
/// Returns the raw length alongside the compressed frame, because the per-zoom `bytes` column has
/// always reported *uncompressed* body size and moving compression must not silently change what a
/// build report means.
/// Where the encode pass's CPU actually goes, accumulated across workers.
///
/// Nanoseconds, summed per tile. Split three ways because the three steps have nothing to do with
/// each other and the totals cannot say which is which: stage C is geometry with a quadratic
/// hole-versus-hole test in it, serialisation is a memcpy, and DEFLATE at level nine is a
/// throughput-bound compressor. This exists for the same reason
/// [`tile_build::pyramid`]'s `MVT_NANOS` and `GZIP_NANOS` do -- four rounds of plausible-sounding
/// optimisation there bought 3% between them, and splitting the measurement is what found the real
/// cost. Here it found a `clone` of every tile's coordinate arena.
///
/// **Off unless `MAPS_TIMING` is set**, and that is not tidiness. `pyramid`'s counters are hit once
/// per gzip probe, a handful per tile; these are hit three times per tile, and at 943,401 tiles
/// across 64 workers three contended atomics per tile is itself a scalability problem. Leaving them
/// always-on would mean the instrument changed what it measured.
static STAGE_C_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static SERIALIZE_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static DEFLATE_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Whether to time the encode split at all. Read once; an `Instant::now()` pair and an atomic per
/// step per tile is not free at this tile count.
fn timing() -> bool {
    static ON: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ON.get_or_init(|| std::env::var_os("MAPS_TIMING").is_some())
}

/// The encode split in seconds of CPU: `(stage C, serialise, deflate)`. All zero without
/// `MAPS_TIMING`.
pub fn encode_seconds() -> (f64, f64, f64) {
    let s = |c: &std::sync::atomic::AtomicU64| c.load(Ordering::Relaxed) as f64 / 1e9;
    (s(&STAGE_C_NANOS), s(&SERIALIZE_NANOS), s(&DEFLATE_NANOS))
}

/// The most features any one tile has carried in each layer.
///
/// `body::serialize` caps a tile-layer at [`u16::MAX`] features, and that cap **has already fired**:
/// `layer 4 has 79407 features` on north-america, fixed by [`crate::coalesce`]. Coalescing merges
/// *lines* only -- polygons pass straight through -- and a planet build's dense cities are far past
/// anything in a continent, so the question of whether the field has to widen to `u32` is a question
/// about a number nobody has measured. This measures it.
///
/// Sampled **after** coalescing and stage C, which is where the count that actually reaches the cap
/// is. Always on, unlike the timing counters: this is one `fetch_max` per layer per tile against a
/// cache line that is almost never written after the first few thousand tiles, where those are three
/// unconditional adds per tile.
static WIDEST_LAYER: [AtomicU64; 256] = [const { AtomicU64::new(0) }; 256];

/// The widest tile-layer seen per layer id, taken and reset.
///
/// Only the layers that appeared, so a build that carried three layers reports three rows.
pub fn widest_layers() -> Vec<(u8, u64)> {
    WIDEST_LAYER
        .iter()
        .enumerate()
        .filter_map(|(id, seen)| match seen.swap(0, Ordering::Relaxed) {
            0 => None,
            n => Some((id as u8, n)),
        })
        .collect()
}

/// Time `f` into `counter`, or just run it when timing is off.
#[inline]
fn timed<R>(on: bool, counter: &std::sync::atomic::AtomicU64, f: impl FnOnce() -> R) -> R {
    if !on {
        return f();
    }
    let at = std::time::Instant::now();
    let out = f();
    counter.fetch_add(at.elapsed().as_nanos() as u64, Ordering::Relaxed);
    out
}

/// One encoded tile on its way back from [`encode_batch`]: its id, the compressed body with its raw
/// length, and what stage C and coalescing did to it.
///
/// Folded by the caller in `tile_id` order rather than accumulated across workers, so the counters
/// need no atomics and a million tiles do not contend on three cache lines.
type Encoded = (u64, Option<(Vec<u8>, usize)>, crate::rings::Stats, crate::coalesce::Stats);
