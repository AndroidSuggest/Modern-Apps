//! `.osm.pbf` container: blob framing, inflate, `PrimitiveBlock` decode, and the
//! parallel pass driver.
//!
//! A `.osm.pbf` is a flat sequence of
//! `[u32 big-endian header_len][BlobHeader][Blob]`. Because the framing is
//! self-describing we can cheaply *scan* the file once, recording each blob's
//! `(offset, len)`, and afterwards let N threads each seek to and inflate their
//! own blobs from their own `File` handle. That replaces the old C++
//! `ThreadPool`/`MoveTask`/throttling-condvar machinery — and the data race its
//! comments described — with a plain `std::thread::scope`.
//!
//! Work is split into fixed contiguous *chunks* of blobs (not dynamically per
//! blob) so results can be merged in chunk order. That is what makes the whole
//! tool deterministic: name-pool offsets and record order no longer depend on
//! thread scheduling, which the C++ generator's did.

use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom, Write};
use std::path::Path;
use std::sync::atomic::{AtomicU8, AtomicUsize, Ordering};
use std::sync::{Condvar, Mutex};

use crate::proto::{self, Error, Reader, Result, WIRE_BYTES};
use crate::pbf_extra::decode_blob_header;

pub use crate::pbf_extra::probe_compression;
pub use crate::pbf_extra::{load_blob_kinds, write_blob_kinds, BLOB_KINDS_FILE};

/// Kind bits: which entity types a pass wants, and which a blob contains.
pub const KIND_NODES: u8 = 1;
pub const KIND_WAYS: u8 = 2;
pub const KIND_RELATIONS: u8 = 4;

/// A blob is capped at 32 MiB uncompressed by the format spec; allow some slack
/// but refuse absurd sizes so a corrupt length can't trigger a huge allocation.
const MAX_BLOB_BYTES: usize = 128 * 1024 * 1024;

/// Blobs per work chunk. Fixed (rather than derived from the core count) so the
/// merge order — and therefore every output byte — is identical on any machine.
///
/// **It also sets peak memory**, because it is the multiplier on the reorder buffer.
/// [`run_pass_sink`] lets `2 * threads` chunk accumulators sit in [`Drain::slots`] at
/// once, and a pass 1 accumulator holds every classified way in its chunk *with that
/// way's node refs*. So the bytes in flight are `2 * threads * CHUNK_BLOBS` blobs' worth
/// of ways, and only this constant is not a property of the machine.
///
/// **Lowered from 64.** On a 64-core box the window is 128 chunks, which at 64 blobs a
/// chunk is 8192 blobs — more than a California extract has, so the "bound" bound nothing
/// and the reorder buffer was the whole build's peak: 2.19 GB, a few seconds into pass 1.
/// At 8 blobs a chunk the same window is 1024 blobs and the peak of stage A falls to
/// 1.58 GB, which is the node-location table underneath it. It costs eight times as many
/// `deposit` calls — one mutex acquisition each, against a chunk of work measured in
/// milliseconds — and it was inside the noise on a California build.
///
/// Byte-neutral, which is what makes it safe to tune: chunks are contiguous blob ranges
/// drained in ascending order, so regrouping them cannot reorder the ways inside them.
pub(crate) const CHUNK_BLOBS: usize = 8;

/// Where one `OSMData` blob lives in the file.
#[derive(Clone, Copy)]
pub struct BlobLoc {
    pub offset: u64,
    pub datasize: u32,
}

/// Scan the framing once, returning every `OSMData` blob's location. Only blob
/// *headers* are read, so this is I/O-cheap even on a 1.3 GB extract.
pub fn scan_blobs(path: &Path) -> Result<Vec<BlobLoc>> {
    let file = File::open(path).map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
    let total = file
        .metadata()
        .map_err(|e| Error(format!("cannot stat {}: {e}", path.display())))?
        .len();
    let mut r = BufReader::with_capacity(1 << 20, file);
    let mut out = Vec::new();
    let mut pos: u64 = 0;
    let mut header = Vec::new();

    while pos < total {
        let mut len_buf = [0u8; 4];
        if let Err(e) = r.read_exact(&mut len_buf) {
            return Err(Error(format!("truncated blob header length at {pos}: {e}")));
        }
        let header_len = u32::from_be_bytes(len_buf) as usize;
        if header_len == 0 || header_len > 64 * 1024 {
            return proto::err(format!("implausible BlobHeader length {header_len} at {pos}"));
        }
        header.clear();
        header.resize(header_len, 0);
        r.read_exact(&mut header)
            .map_err(|e| Error(format!("truncated BlobHeader at {pos}: {e}")))?;

        let (kind, datasize) = decode_blob_header(&header)?;
        let blob_offset = pos + 4 + header_len as u64;
        if datasize as usize > MAX_BLOB_BYTES {
            return proto::err(format!("blob at {blob_offset} claims {datasize} bytes"));
        }
        if kind == b"OSMData" {
            out.push(BlobLoc {
                offset: blob_offset,
                datasize,
            });
        }
        pos = blob_offset + datasize as u64;
        // seek_relative keeps the read buffer when the target is already inside it.
        // Blobs average tens of KB, so one 1 MiB fill covers many headers; a plain
        // `seek` would discard the buffer and refill it for every single blob.
        r.seek_relative(datasize as i64)
            .map_err(|e| Error(format!("seek to {pos} failed: {e}")))?;
    }
    Ok(out)
}

/// Inflate one blob's payload into `out`.
///
/// `Blob { bytes raw = 1; int32 raw_size = 2; bytes zlib_data = 3; ... }`.
/// The compressions we cannot decode without a C library (lzma/bzip2/lz4/zstd)
/// are reported as a clear error rather than silently skipped — a skipped blob
/// would produce a quietly incomplete graph.
///
/// # `out` is the caller's buffer, and it is genuinely reused
///
/// [`run_pass_sink`]'s workers hoist one of these out of the blob loop each, so a pass
/// allocates one inflate buffer per thread rather than one per blob. That only holds if
/// this function inflates *into* the buffer: the obvious
/// `*out = decompress_to_vec_zlib_with_limit(..)?` throws the caller's allocation away on
/// every blob, and worse, the new buffer is allocated and zero-filled before the old one
/// is dropped, so each worker is briefly holding two.
///
/// It measured 1.5 GB of a 2.19 GB California peak. The peak of that whole build was one
/// second in, during the node region of pass 1 — before a single way had been classified
/// and long before any tile existed — and it scaled with the core count at ~24 MB a
/// thread, which is one and a bit blob buffers apiece.
///
/// `raw_size` is what makes inflating in place possible: the blob states its own
/// uncompressed length, so the buffer can be sized exactly before decompressing rather
/// than grown by doubling.
pub fn inflate_blob(blob: &[u8], out: &mut Vec<u8>) -> Result<()> {
    let mut r = Reader::new(blob);
    let mut raw: Option<&[u8]> = None;
    let mut raw_size: Option<usize> = None;
    let mut zlib: Option<&[u8]> = None;
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_BYTES) => raw = Some(r.bytes()?),
            (2, proto::WIRE_VARINT) => raw_size = Some(r.uvarint()? as usize),
            (3, WIRE_BYTES) => zlib = Some(r.bytes()?),
            (4, WIRE_BYTES) => return proto::err("blob uses lzma compression (unsupported)"),
            (5, WIRE_BYTES) => return proto::err("blob uses bzip2 compression (unsupported)"),
            (6, WIRE_BYTES) => return proto::err("blob uses lz4 compression (unsupported)"),
            (7, WIRE_BYTES) => return proto::err("blob uses zstd compression (unsupported)"),
            _ => r.skip(wire)?,
        }
    }

    out.clear();
    if let Some(z) = zlib {
        match raw_size.filter(|want| *want <= MAX_BLOB_BYTES) {
            Some(want) => {
                // Reuses the caller's capacity: `clear` kept it and `resize` fills it.
                out.resize(want, 0);
                // `zlib_header` true and `ignore_adler32` false, so this still parses the
                // zlib header and verifies the Adler-32 trailer — a mis-framed or corrupt
                // blob fails just as loudly as it did through the allocating path.
                let got = miniz_oxide::inflate::decompress_slice_iter_to_slice(
                    out,
                    std::iter::once(z),
                    true,
                    false,
                )
                .map_err(|e| Error(format!("inflate failed: {e:?}")))?;
                // `out` is `want` long whatever happened, so a short inflate would slip
                // past the raw_size check below. This is the one that catches it.
                if got != want {
                    return proto::err(format!(
                        "blob inflated {got} byte(s) but raw_size says {want}"
                    ));
                }
            }
            // No `raw_size`, so there is no length to size the buffer from and the growing
            // allocator path is all that is left. Every conforming writer emits it, so this
            // is the odd file rather than the hot path.
            None => {
                let limit = raw_size.unwrap_or(MAX_BLOB_BYTES).min(MAX_BLOB_BYTES);
                let data = miniz_oxide::inflate::decompress_to_vec_zlib_with_limit(z, limit)
                    .map_err(|e| Error(format!("inflate failed: {e:?}")))?;
                *out = data;
            }
        }
    } else if let Some(bytes) = raw {
        out.extend_from_slice(bytes);
    } else {
        return proto::err("blob has neither raw nor zlib_data");
    }

    if let Some(want) = raw_size {
        if out.len() != want {
            return proto::err(format!(
                "blob inflated to {} bytes but raw_size says {want}",
                out.len()
            ));
        }
    }
    Ok(())
}

/// One decoded `PrimitiveBlock`. Strings and group bodies are borrowed from the
/// inflated buffer, so decoding a block allocates only the two index vectors.
pub struct PrimitiveBlock<'a> {
    pub strings: Vec<&'a [u8]>,
    pub granularity: i64,
    pub lat_offset: i64,
    pub lon_offset: i64,
    pub groups: Vec<&'a [u8]>,
}

impl<'a> PrimitiveBlock<'a> {
    pub fn decode(buf: &'a [u8]) -> Result<PrimitiveBlock<'a>> {
        let mut block = PrimitiveBlock {
            strings: Vec::new(),
            granularity: 100,
            lat_offset: 0,
            lon_offset: 0,
            groups: Vec::new(),
        };
        let mut r = Reader::new(buf);
        while let Some((field, wire)) = r.next_field()? {
            match (field, wire) {
                (1, WIRE_BYTES) => {
                    // StringTable { repeated bytes s = 1; }
                    let mut st = Reader::new(r.bytes()?);
                    while let Some((f, w)) = st.next_field()? {
                        if (f, w) == (1, WIRE_BYTES) {
                            block.strings.push(st.bytes()?);
                        } else {
                            st.skip(w)?;
                        }
                    }
                }
                (2, WIRE_BYTES) => block.groups.push(r.bytes()?),
                (17, proto::WIRE_VARINT) => block.granularity = r.ivarint()?,
                (19, proto::WIRE_VARINT) => block.lat_offset = r.ivarint()?,
                (20, proto::WIRE_VARINT) => block.lon_offset = r.ivarint()?,
                _ => r.skip(wire)?,
            }
        }
        if block.granularity <= 0 {
            return proto::err(format!("block granularity {}", block.granularity));
        }
        Ok(block)
    }

    #[inline]
    pub fn string(&self, idx: u32) -> &'a [u8] {
        self.strings.get(idx as usize).copied().unwrap_or(b"")
    }

    /// Raw coordinate delta -> hundredths of a nanodegree -> 1e-7 degrees, the
    /// integer libosmium's `Location` stores. Truncating division matches
    /// libosmium's `convert_pbf_lat`, so coordinates are bit-identical to what
    /// the old C++ tools saw.
    #[inline]
    pub fn lat_e7(&self, delta: i64) -> i32 {
        ((self.lat_offset + self.granularity * delta) / 100) as i32
    }

    #[inline]
    pub fn lon_e7(&self, delta: i64) -> i32 {
        ((self.lon_offset + self.granularity * delta) / 100) as i32
    }
}

/// Run one pass over the file in parallel, folding each chunk's accumulator as
/// soon as it is complete.
///
/// `make` builds a fresh accumulator per chunk, `body` is called once per blob
/// with that chunk's accumulator and must return the kind bits the blob held, and
/// `sink` receives finished accumulators **in chunk order** so merging stays
/// deterministic.
///
/// Prefer this to [`run_pass`] for anything whose accumulators are large. A
/// planet-scale pass cannot afford `run_pass`'s contract of handing back every
/// chunk at once: on California the pass-2 node accumulators are about a
/// gigabyte, and the caller then folds them into a second copy, so the two peak
/// together. Here a chunk is folded and freed while its neighbours are still
/// being decoded, which also overlaps the merge with the I/O.
///
/// `sink` is called under a lock, so it is serialised against the workers. That
/// is the same single-threaded merge [`run_pass`]'s callers already do, only
/// earlier.
///
/// `blob_kinds` (from a previous full pass) lets later passes skip blobs that
/// hold nothing they want — a real win because a PBF stores all nodes first,
/// then ways, then relations. A skipped blob's incoming kinds are carried into
/// the returned mask, so a filtered pass's mask is still a complete description
/// of the file and can be fed to the pass after it.
pub fn run_pass_sink<S, F, G, H>(
    path: &Path,
    blobs: &[BlobLoc],
    blob_kinds: Option<&[u8]>,
    want: u8,
    label: &str,
    make: F,
    body: G,
    sink: H,
) -> Result<Vec<u8>>
where
    S: Send,
    F: Fn() -> S + Sync,
    G: Fn(&mut S, &PrimitiveBlock) -> Result<u8> + Sync,
    H: FnMut(S) -> Result<()> + Send,
{
    let n_chunks = blobs.len().div_ceil(CHUNK_BLOBS).max(1);
    let drain: Mutex<Drain<S, H>> = Mutex::new(Drain {
        slots: (0..n_chunks).map(|_| None).collect(),
        next: 0,
        sink,
        err: None,
    });
    let kinds: Vec<AtomicU8> = (0..blobs.len()).map(|_| AtomicU8::new(0)).collect();

    let next_chunk = AtomicUsize::new(0);
    let done_blobs = AtomicUsize::new(0);
    let last_pct = AtomicUsize::new(usize::MAX);
    let n_threads = crate::par::threads().min(n_chunks);
    let mut first_err: Option<Error> = None;
    // How far ahead of the sink the workers may run.
    //
    // Without this the reorder buffer is unbounded: a worker deposits and immediately
    // claims another chunk, so one slow chunk 0 can park every other chunk's
    // accumulator in `slots` at once. On a planet pass those accumulators are the
    // largest things in the build, and raising the thread count widens the window,
    // which is exactly the failure this plan is shaped to avoid. Two chunks per thread
    // leaves slack for an uneven chunk without letting the buffer grow with the file.
    //
    // Note what this does NOT bound. It is a count of chunks, so the bytes it admits are
    // `window * CHUNK_BLOBS` blobs' worth of accumulator — and on a 64-core box the window
    // is 128 chunks, which exceeds the chunk count of anything smaller than a continent.
    // For those the window binds on nothing and the bound is really [`CHUNK_BLOBS`]; see
    // its doc for the 2.19 GB that cost. Bounding this in bytes would mean measuring `S`,
    // which is opaque here by design.
    let window = n_threads.saturating_mul(2).max(2);
    let progressed = Condvar::new();

    std::thread::scope(|scope| {
        let mut handles = Vec::with_capacity(n_threads);
        for _ in 0..n_threads {
            handles.push(scope.spawn(|| -> Result<()> {
                let mut file = File::open(path)
                    .map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
                let mut compressed = Vec::new();
                let mut inflated = Vec::new();
                loop {
                    let chunk = next_chunk.fetch_add(1, Ordering::Relaxed);
                    if chunk >= n_chunks {
                        return Ok(());
                    }
                    // Hold off until this chunk is inside the window. The worker that
                    // owns the lowest outstanding chunk always passes this test, so it
                    // always makes progress and the wait cannot deadlock.
                    {
                        let mut d = drain.lock().expect("chunk slot mutex");
                        while chunk >= d.next + window {
                            d = progressed.wait(d).expect("chunk slot mutex");
                        }
                    }
                    let start = chunk * CHUNK_BLOBS;
                    let end = ((chunk + 1) * CHUNK_BLOBS).min(blobs.len());
                    let mut state = make();
                    for (i, loc) in blobs[start..end].iter().enumerate() {
                        let idx = start + i;
                        match blob_kinds.filter(|k| k[idx] & want == 0) {
                            Some(k) => kinds[idx].store(k[idx], Ordering::Relaxed),
                            None => {
                                read_exact_at(&mut file, loc, &mut compressed)?;
                                inflate_blob(&compressed, &mut inflated)?;
                                let block = PrimitiveBlock::decode(&inflated)?;
                                let seen = body(&mut state, &block)?;
                                kinds[idx].store(seen, Ordering::Relaxed);
                            }
                        }
                        report(label, &done_blobs, &last_pct, blobs.len());
                    }
                    drain.lock().expect("chunk slot mutex").deposit(chunk, state);
                    progressed.notify_all();
                }
            }));
        }
        for h in handles {
            match h.join() {
                Ok(Ok(())) => {}
                Ok(Err(e)) => {
                    if first_err.is_none() {
                        first_err = Some(e);
                    }
                }
                Err(_) => {
                    if first_err.is_none() {
                        first_err = Some(Error("a reader thread panicked".into()));
                    }
                }
            }
        }
    });

    if let Some(e) = first_err {
        return Err(e);
    }
    let mut d = drain.into_inner().expect("chunk slot mutex");
    if let Some(e) = d.err.take() {
        return Err(e);
    }
    // Every chunk was deposited, and deposits drain in order, so nothing can be
    // left behind unless a sink call failed above.
    debug_assert_eq!(d.next, n_chunks, "chunks left undrained");
    eprintln!("\r{label:<28} [100%]");
    Ok(kinds.iter().map(|k| k.load(Ordering::Relaxed)).collect())
}

/// Holds finished chunks until their turn comes, so the sink sees chunk order
/// however the workers finish.
struct Drain<S, H> {
    slots: Vec<Option<S>>,
    next: usize,
    sink: H,
    err: Option<Error>,
}

impl<S, H: FnMut(S) -> Result<()>> Drain<S, H> {
    fn deposit(&mut self, chunk: usize, state: S) {
        self.slots[chunk] = Some(state);
        while self.next < self.slots.len() {
            let Some(s) = self.slots[self.next].take() else {
                break;
            };
            self.next += 1;
            // Keep draining after a failure: the pass reports the first error at
            // the end, and stopping here would strand the remaining chunks.
            if let Err(e) = (self.sink)(s) {
                if self.err.is_none() {
                    self.err = Some(e);
                }
            }
        }
    }
}

/// Run one pass over the file in parallel, returning every chunk's accumulator.
///
/// Convenience over [`run_pass_sink`] for passes whose accumulators are small
/// enough that holding them all costs nothing. Accumulators come back in chunk
/// order, so merging them is deterministic.
pub fn run_pass<S, F, G>(
    path: &Path,
    blobs: &[BlobLoc],
    blob_kinds: Option<&[u8]>,
    want: u8,
    label: &str,
    make: F,
    body: G,
) -> Result<(Vec<S>, Vec<u8>)>
where
    S: Send,
    F: Fn() -> S + Sync,
    G: Fn(&mut S, &PrimitiveBlock) -> Result<u8> + Sync,
{
    let mut out: Vec<S> = Vec::new();
    let kinds = run_pass_sink(path, blobs, blob_kinds, want, label, make, body, |s| {
        out.push(s);
        Ok(())
    })?;
    Ok((out, kinds))
}

pub(crate) fn read_exact_at(file: &mut File, loc: &BlobLoc, out: &mut Vec<u8>) -> Result<()> {
    file.seek(SeekFrom::Start(loc.offset))
        .map_err(|e| Error(format!("seek to {} failed: {e}", loc.offset)))?;
    out.clear();
    out.resize(loc.datasize as usize, 0);
    file.read_exact(out)
        .map_err(|e| Error(format!("short read at {}: {e}", loc.offset)))
}

fn report(label: &str, done: &AtomicUsize, last_pct: &AtomicUsize, total: usize) {
    let n = done.fetch_add(1, Ordering::Relaxed) + 1;
    if total == 0 {
        return;
    }
    let pct = n * 100 / total;
    let prev = last_pct.load(Ordering::Relaxed);
    if pct != prev
        && last_pct
            .compare_exchange(prev, pct, Ordering::Relaxed, Ordering::Relaxed)
            .is_ok()
    {
        eprint!("\r{label:<28} [{pct}%] ");
        let _ = std::io::stderr().flush();
    }
}
