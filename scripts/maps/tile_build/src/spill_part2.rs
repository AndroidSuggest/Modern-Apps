/// Sequential reader over one bucket.
///
/// Errors if a record's `tile_id` falls outside the bucket's range, and at the end if
/// the record or byte count disagrees with what the writer tallied. The order the
/// encode pass depends on is asserted directly by its own test;
/// [`crate::pmtiles::StreamBuilder::add_tile_raw`] refusing a non-ascending id is the
/// second line of defence, not the first.
pub struct BucketReader {
    src: BufReader<File>,
    path: PathBuf,
    lo: u64,
    hi: u64,
    expect_records: u64,
    expect_bytes: u64,
    seen_records: u64,
    seen_bytes: u64,
    done: bool,
    buf: Vec<u8>,
}

impl BucketReader {
    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Result<Option<SpillRecord>> {
        if self.done {
            return Ok(None);
        }
        let mut head = [0u8; REC_HEADER_BYTES];
        let mut read = 0usize;
        while read < REC_HEADER_BYTES {
            let n = self
                .src
                .read(&mut head[read..])
                .map_err(|e| Error(format!("reading {}: {e}", self.path.display())))?;
            if n == 0 {
                break;
            }
            read += n;
        }
        if read == 0 {
            self.done = true;
            self.check_totals()?;
            return Ok(None);
        }
        if read < REC_HEADER_BYTES {
            // A partial header is a truncated file, never a legitimate end.
            return err(format!(
                "{} ends {read} byte(s) into a {REC_HEADER_BYTES}-byte record header",
                self.path.display()
            ));
        }
        let h = RecHeader::parse(&head)?;
        if h.tile_id < self.lo || h.tile_id >= self.hi {
            return err(format!(
                "{} holds tile id {} but covers {}..{}",
                self.path.display(),
                h.tile_id,
                self.lo,
                self.hi
            ));
        }
        let payload = h.geom_len as usize + h.props_len as usize;
        self.buf.clear();
        self.buf.resize(payload, 0);
        self.src
            .read_exact(&mut self.buf)
            .map_err(|e| Error(format!("reading {}'s record payload: {e}", self.path.display())))?;
        let geom = decode_int_geometry(h.kind, &self.buf[..h.geom_len as usize])?;
        let props = decode_props(&self.buf[h.geom_len as usize..])?;

        self.seen_records += 1;
        self.seen_bytes += h.rec_len as u64;
        Ok(Some(SpillRecord {
            tile_id: h.tile_id,
            seq: h.seq,
            extent: h.extent,
            geom,
            props,
        }))
    }

    fn check_totals(&self) -> Result<()> {
        if self.seen_records != self.expect_records || self.seen_bytes != self.expect_bytes {
            return err(format!(
                "{} read back {} record(s)/{} byte(s) but the writer tallied {}/{}",
                self.path.display(),
                self.seen_records,
                self.seen_bytes,
                self.expect_records,
                self.expect_bytes
            ));
        }
        Ok(())
    }
}

// --- the normalized file -----------------------------------------------------

/// What one pass over the geojsonseq learned, beyond the records themselves.
///
/// The streaming producer's header comes from here rather than from a second pass:
/// [`crate::pmtiles::StreamBuilder`]'s bounds fields are serialised last, so they can be
/// a streaming fold assigned any time before `finish`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct NormalizedSummary {
    pub count: u64,
    pub bounds: Option<Rect>,
    /// The FIRST feature's kind, which is the rule `dominant_geom_type` already applies:
    /// a layer is styled as one thing, so a mixed layer is a mistake upstream and should
    /// show as wrong rendering rather than hide as a silently split layer.
    pub geom_kind: Option<GeomKind>,
    pub skipped: u64,
    /// Byte offset of every `NORM_CHUNK_FEATURES`-th record, plus a final sentinel
    /// holding the file's total length — so chunk `i` spans `chunks[i]..chunks[i + 1]`
    /// and there are `chunks.len() - 1` of them.
    ///
    /// Built by the same single pass that writes the records, because the writer is the
    /// only place that already knows each record's length. Empty for an empty file.
    pub chunks: Vec<u64>,
}

impl NormalizedSummary {
    /// How many chunks the file has, for [`NormalizedChunks::read_into`].
    pub fn chunk_count(&self) -> usize {
        self.chunks.len().saturating_sub(1)
    }
}

/// Writes the geojsonseq once into a compact binary every zoom can re-read.
///
/// ```text
/// 0..4    u32 rec_len       whole record, this field included
/// 4..8    u32 geom_len
/// 8..12   u32 props_len
/// 12..13  u8  geom_kind
/// 13..16  reserved, zero
/// 16..    geometry (lon/lat e7 `i32` pairs), then properties
/// ```
///
/// Eight bytes a vertex rather than sixteen: see [`encode_geometry`] for why that is
/// lossless for OSM coordinates and what it costs a coastline.
pub struct NormalizedWriter {
    out: BufWriter<File>,
    path: PathBuf,
    summary: NormalizedSummary,
    rec: Vec<u8>,
    /// Bytes written so far, which is the next record's offset.
    at: u64,
}

impl NormalizedWriter {
    pub fn create(path: impl Into<PathBuf>) -> Result<NormalizedWriter> {
        let path = path.into();
        if let Some(dir) = path.parent() {
            if !dir.as_os_str().is_empty() {
                std::fs::create_dir_all(dir)
                    .map_err(|e| Error(format!("cannot create {}: {e}", dir.display())))?;
            }
        }
        let f = File::create(&path)
            .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
        Ok(NormalizedWriter {
            out: BufWriter::with_capacity(1 << 20, f),
            path,
            summary: NormalizedSummary::default(),
            rec: Vec::new(),
            at: 0,
        })
    }

    /// A line the caller could not use. Counted here so the summary is the one place
    /// the whole pass is described.
    pub fn skip(&mut self) {
        self.summary.skipped += 1;
    }

    pub fn push(&mut self, geometry: &Geometry, props: &[(String, Value)]) -> Result<()> {
        self.rec.clear();
        self.rec.resize(NORM_HEADER_BYTES, 0);
        encode_geometry(geometry, &mut self.rec)?;
        let geom_len = count(self.rec.len() - NORM_HEADER_BYTES)?;
        encode_props(props, &mut self.rec)?;
        let props_len = count(self.rec.len() - NORM_HEADER_BYTES - geom_len as usize)?;
        let rec_len = count(self.rec.len())?;
        self.rec[0..4].copy_from_slice(&rec_len.to_le_bytes());
        self.rec[4..8].copy_from_slice(&geom_len.to_le_bytes());
        self.rec[8..12].copy_from_slice(&props_len.to_le_bytes());
        self.rec[12] = GeomKind::of(geometry).tag();
        // Index before writing, so the offset recorded is this record's own start.
        if self.summary.count.is_multiple_of(NORM_CHUNK_FEATURES) {
            self.summary.chunks.push(self.at);
        }
        self.out
            .write_all(&self.rec)
            .map_err(|e| Error(format!("writing {}: {e}", self.path.display())))?;
        self.at += self.rec.len() as u64;

        if self.summary.geom_kind.is_none() {
            self.summary.geom_kind = Some(GeomKind::of(geometry));
        }
        self.summary.bounds = crate::pyramid::fold_bounds(self.summary.bounds, geometry);
        self.summary.count += 1;
        Ok(())
    }

    /// Flush, and hand back what the pass learned.
    pub fn finish(mut self) -> Result<NormalizedSummary> {
        self.out
            .flush()
            .map_err(|e| Error(format!("flushing {}: {e}", self.path.display())))?;
        // Close the last chunk. Without this the final partial chunk has no end, and
        // `chunk_count` would also over-report by one.
        if !self.summary.chunks.is_empty() {
            self.summary.chunks.push(self.at);
        }
        Ok(self.summary)
    }
}

/// One normalized feature.
#[derive(Debug, Clone, PartialEq)]
pub struct NormalizedFeature {
    pub geometry: Geometry,
    pub props: Vec<(String, Value)>,
}

/// Validate a normalized record's fixed header, returning its payload shape.
///
/// Shared by the sequential reader and the chunked one, so the two cannot diverge on
/// what they accept: a record one of them rejects must not be one the other decodes.
fn norm_header(head: &[u8; NORM_HEADER_BYTES]) -> Result<(usize, usize, GeomKind)> {
    if head[13..NORM_HEADER_BYTES].iter().any(|v| *v != 0) {
        return err("normalized record has a nonzero reserved tail");
    }
    let u32_at = |o: usize| u32::from_le_bytes(head[o..o + 4].try_into().expect("4 bytes"));
    let rec_len = u32_at(0) as u64;
    let geom_len = u32_at(4) as usize;
    let props_len = u32_at(8) as usize;
    let want = NORM_HEADER_BYTES as u64 + geom_len as u64 + props_len as u64;
    if rec_len != want {
        return err(format!(
            "normalized record claims {rec_len} byte(s) but its header adds up to {want}"
        ));
    }
    if want > MAX_RECORD_BYTES {
        return err(format!(
            "normalized record is {want} byte(s), which is corruption"
        ));
    }
    Ok((geom_len, props_len, GeomKind::from_tag(head[12])?))
}

/// Decode a validated record's payload, `geom_len` bytes of geometry then properties.
fn norm_payload(kind: GeomKind, geom_len: usize, payload: &[u8]) -> Result<NormalizedFeature> {
    Ok(NormalizedFeature {
        geometry: decode_geometry(kind, &payload[..geom_len])?,
        props: decode_props(&payload[geom_len..])?,
    })
}

/// Sequential reader over the normalized file, rewindable because every zoom re-reads
/// it from the front.
pub struct NormalizedReader {
    src: BufReader<File>,
    path: PathBuf,
    buf: Vec<u8>,
}

impl NormalizedReader {
    pub fn open(path: impl Into<PathBuf>) -> Result<NormalizedReader> {
        let path = path.into();
        let f = File::open(&path)
            .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
        Ok(NormalizedReader {
            src: BufReader::with_capacity(1 << 20, f),
            path,
            buf: Vec::new(),
        })
    }

    pub fn rewind(&mut self) -> Result<()> {
        use std::io::Seek;
        self.src
            .rewind()
            .map_err(|e| Error(format!("rewinding {}: {e}", self.path.display())))?;
        Ok(())
    }

    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Result<Option<NormalizedFeature>> {
        let mut head = [0u8; NORM_HEADER_BYTES];
        let mut read = 0usize;
        while read < NORM_HEADER_BYTES {
            let n = self
                .src
                .read(&mut head[read..])
                .map_err(|e| Error(format!("reading {}: {e}", self.path.display())))?;
            if n == 0 {
                break;
            }
            read += n;
        }
        if read == 0 {
            return Ok(None);
        }
        if read < NORM_HEADER_BYTES {
            return err(format!(
                "{} ends {read} byte(s) into a {NORM_HEADER_BYTES}-byte record header",
                self.path.display()
            ));
        }
        let (geom_len, props_len, kind) = norm_header(&head)?;
        self.buf.clear();
        self.buf.resize(geom_len + props_len, 0);
        self.src.read_exact(&mut self.buf).map_err(|e| {
            Error(format!(
                "reading {}'s record payload: {e}",
                self.path.display()
            ))
        })?;
        Ok(Some(norm_payload(kind, geom_len, &self.buf)?))
    }
}

/// Chunked reader over the normalized file, for reading it across a thread pool.
///
/// The sequential [`NormalizedReader`] is one cursor, so the decode of every feature —
/// which allocates a `Geometry` and a `Vec` of properties, six times over a z11..z16
/// build — happens on whichever single thread owns it, while the pool waits. This reads
/// a whole chunk's byte range positionally instead, so the decode happens on the worker
/// that will bucket those features.
///
/// Takes `&self` throughout: see [`crate::pmtiles::read_exact_at`]. One handle serves
/// every thread.
pub struct NormalizedChunks {
    file: File,
    path: PathBuf,
    /// Chunk boundaries, `chunk_count() + 1` of them. See [`NormalizedSummary::chunks`].
    chunks: Vec<u64>,
}

impl NormalizedChunks {
    /// `chunks` is [`NormalizedSummary::chunks`] for this file.
    pub fn open(path: impl Into<PathBuf>, chunks: Vec<u64>) -> Result<NormalizedChunks> {
        let path = path.into();
        let file = File::open(&path)
            .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
        Ok(NormalizedChunks { file, path, chunks })
    }

    pub fn chunk_count(&self) -> usize {
        self.chunks.len().saturating_sub(1)
    }

    /// Decode chunk `i` into `out`, which is cleared first.
    ///
    /// `scratch` is the caller's per-worker byte buffer, reused across chunks so a pass
    /// allocates once per thread rather than once per chunk.
    ///
    /// The first feature of chunk `i` is input feature `i * NORM_CHUNK_FEATURES`, by
    /// construction of the index — which is what lets the caller reproduce the exact
    /// `seq` the sequential path assigns.
    pub fn read_into(
        &self,
        i: usize,
        scratch: &mut Vec<u8>,
        out: &mut Vec<NormalizedFeature>,
    ) -> Result<()> {
        out.clear();
        let (Some(&start), Some(&end)) = (self.chunks.get(i), self.chunks.get(i + 1)) else {
            return err(format!(
                "normalized chunk {i} is past the {} in {}",
                self.chunk_count(),
                self.path.display()
            ));
        };
        if end < start {
            return err(format!(
                "normalized chunk {i} of {} ends before it starts",
                self.path.display()
            ));
        }
        let len = (end - start) as usize;
        scratch.clear();
        scratch.resize(len, 0);
        crate::pmtiles::read_exact_at(&self.file, scratch, start).map_err(|e| {
            Error(format!(
                "reading normalized chunk {i} of {}: {e}",
                self.path.display()
            ))
        })?;
        let mut at = 0usize;
        while at < len {
            if len - at < NORM_HEADER_BYTES {
                return err(format!(
                    "normalized chunk {i} of {} ends {} byte(s) into a \
                     {NORM_HEADER_BYTES}-byte record header",
                    self.path.display(),
                    len - at
                ));
            }
            let head: &[u8; NORM_HEADER_BYTES] = scratch[at..at + NORM_HEADER_BYTES]
                .try_into()
                .expect("a header's worth of bytes");
            let (geom_len, props_len, kind) = norm_header(head)?;
            let body = at + NORM_HEADER_BYTES;
            let rec_end = body + geom_len + props_len;
            if rec_end > len {
                return err(format!(
                    "normalized chunk {i} of {} holds a record running {} byte(s) past its end",
                    self.path.display(),
                    rec_end - len
                ));
            }
            out.push(norm_payload(kind, geom_len, &scratch[body..rec_end])?);
            at = rec_end;
        }
        Ok(())
    }
}

/// The normalized file's lifetime, so a run that dies mid-planet cannot strand it.
///
/// A guard rather than `impl Drop` on the reader, because the reader is handed around
/// and the file has to outlive every one of them.
pub struct NormalizedFile(PathBuf);

impl NormalizedFile {
    pub fn new(path: impl Into<PathBuf>) -> NormalizedFile {
        NormalizedFile(path.into())
    }

    pub fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for NormalizedFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}
