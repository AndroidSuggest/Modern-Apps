use super::codec::{compress_body, hash64};
use super::options::Options;
use super::spill::Spill;
use crate::mamaps::body::{self, Body};
use crate::mamaps::header::MAX_ZOOM;
use crate::mamaps::index::{LeafEntry, RootEntry, self};
use crate::proto::{Result, err};
use std::collections::HashMap;
use std::fs::File;
use std::io::Write;
use std::path::Path;


/// Builds an archive, appending bodies in ascending tile-id order.
///
/// Bodies go to a scratch file as they arrive rather than into a `Vec`, so what this holds is the
/// index and the dedup buckets — 16 bytes per stored body and a hash entry per distinct one, not the
/// bodies. See the module header for why, and for what that costs the two dedup compares.
pub struct StreamWriter {
    pub(crate) options: Options,
    /// The data section, being written.
    ///
    /// Named `data` because that is what it is: the same bytes, in the same order, that used to be
    /// accumulated in memory here.
    pub(crate) data: Spill,
    /// One per stored body, ascending by tile id, partitioned into leaves at `finish`.
    pub(crate) entries: Vec<Pending>,
    /// `(hash, stored length) -> offsets already written`. Several per key only on a collision,
    /// which is why it is a list. Probed, never iterated.
    seen: HashMap<(u64, u32), Vec<u64>>,
    last_id: Option<u64>,
    /// The stored bytes of the body `entries.last()` points at, which is what a run-length compare
    /// is against.
    ///
    /// Kept so that compare stays in memory. It is *not* the last body appended: content dedup
    /// means the last entry may point at a body written for a much earlier tile, and this holds
    /// whatever those bytes are. A `debug_assert` in [`Self::append_encoded`] checks it against the
    /// spill on every append, which is how the shortcut is held to the file's answer rather than
    /// trusted to stay in step with it.
    last_body: Vec<u8>,
    pub(crate) tiles_addressed: u64,
    /// Bodies actually appended to `data`, which is what dedup reduces. Distinct from
    /// `entries.len()`, which counts *index* entries: a body shared by two non-adjacent tiles is
    /// one body and two entries.
    pub(crate) distinct: u64,
    pub(crate) runs_used: bool,
    /// The v8 shared section under construction, or `None` when
    /// [`Options::shared_table`] is off (the default) or no row has been
    /// interned yet.
    ///
    /// Rows arrive in ascending tile-id order — the same order bodies do —
    /// because the tiler interns through [`Self::shared_builder`] as it
    /// encodes each tile. Nothing here iterates a hash map at emit time:
    /// [`SharedBuilder::serialize`](crate::mamaps::shared::SharedBuilder::serialize)
    /// sorts rows by `logical_id`, so first-use order is tile-id order by
    /// construction, never hash order.
    shared: Option<crate::mamaps::shared::SharedBuilder>,
}

/// One stored body's index entry, before it knows which leaf it belongs to.
///
/// Deliberately not a [`LeafEntry`]: that type's `offset_delta` is 32-bit *relative to its leaf's*
/// `base_data_offset`, which is exactly what makes the format hold a data section past 4 GiB. Holding
/// an absolute offset in that field meant the writer could not build one -- a north-america z13
/// archive stopped at `a .mamaps data section past 4 GiB needs a wider offset field` after 56 minutes
/// of work, on a limit the format does not actually have. The narrowing belongs at
/// [`StreamWriter::partition`], where the leaf base is known.
#[derive(Debug, Clone, Copy)]
pub(crate) struct Pending {
    pub(crate) tile_id: u64,
    /// Absolute within the data section.
    pub(crate) offset: u64,
    pub(crate) run_length: u32,
    pub(crate) length: u32,
}

/// A root index and its leaves, as [`StreamWriter::partition`] produces them.
type Split = (Vec<RootEntry>, Vec<Vec<LeafEntry>>);

/// How far back content dedup may point, in bytes of the data section.
///
/// [`LeafEntry::offset_delta`] is 32-bit relative to its leaf's `base_data_offset`, and that base is
/// the **minimum** offset in the leaf because dedup lets a tile reuse a body written for an earlier
/// one. So a leaf late in a large archive that reuses a body from the very beginning would need a
/// delta of the whole archive -- past 4 GiB on a north-america z13 build, which no `u32` holds.
///
/// Bounding the reach bounds the delta: every offset in a leaf is then within `MAX_DEDUP_REACH` of
/// the write head when that leaf was written, so the span cannot approach 4 GiB. The cost is a
/// re-appended body when a match is older than this, and it is small in practice because tile ids are
/// Hilbert-ordered -- tiles that share a body are neighbours, and neighbours are written close
/// together.
const MAX_DEDUP_REACH: u64 = 2 << 30;

impl StreamWriter {
    pub fn new(options: Options) -> Result<StreamWriter> {
        if options.min_zoom > options.max_zoom || options.max_zoom > MAX_ZOOM {
            return err(format!(
                "a .mamaps build needs a zoom range inside 0..={MAX_ZOOM}, got {}..={}",
                options.min_zoom, options.max_zoom,
            ));
        }
        if !options.leaf_entry_capacity.is_power_of_two() || options.leaf_entry_capacity == 0 {
            return err(format!(
                "a .mamaps leaf capacity must be a power of two, got {}",
                options.leaf_entry_capacity,
            ));
        }
        // After the option checks, so a build with an impossible zoom range fails without having
        // created a file to clean up.
        let data = Spill::create(options.spill_dir.as_deref())?;
        let shared = options.shared_table.then(crate::mamaps::shared::SharedBuilder::new);
        Ok(StreamWriter {
            options,
            data,
            entries: Vec::new(),
            seen: HashMap::new(),
            last_id: None,
            last_body: Vec::new(),
            tiles_addressed: 0,
            distinct: 0,
            runs_used: false,
            shared,
        })
    }

    /// The v8 shared-section builder, or `None` when [`Options::shared_table`] is off.
    ///
    /// Lane E's tiler interns logical rows and pushes slim refs through this
    /// as it encodes each tile, in ascending tile-id order — which is what
    /// keeps first-use order deterministic. `None` is not an error to ignore:
    /// a caller that asked for a shared table always gets one, and a caller
    /// that did not must not be writing shared rows.
    pub fn shared_builder(&mut self) -> Option<&mut crate::mamaps::shared::SharedBuilder> {
        self.shared.as_mut()
    }

    /// Encode and append one tile. Ids must ascend.
    pub fn append(&mut self, tile_id: u64, body: &Body) -> Result<()> {
        self.append_encoded(tile_id, &body::serialize(body)?)
    }

    /// Append an already-encoded body.
    ///
    /// Ascending ids are a requirement rather than something to sort into place: the index is built
    /// as bodies arrive, and the generator's zoom-major Hilbert buckets already deliver them in
    /// order. A caller that cannot is a caller whose bucketing broke, which is worth failing over.
    pub fn append_encoded(&mut self, tile_id: u64, encoded: &[u8]) -> Result<()> {
        // Parsed rather than trusted: `raw_len` is what a reader allocates from, and a body whose
        // declared length disagrees with its bytes would be caught on device instead of here.
        let raw_len = Body::raw_len(encoded)?;
        if raw_len as usize != encoded.len() {
            return err(format!(
                "a .mamaps body declares {raw_len} bytes but is {}",
                encoded.len(),
            ));
        }
        let stored = if self.options.compress { compress_body(encoded) } else { encoded.to_vec() };
        self.push(tile_id, &stored)
    }

    /// Append a body already compressed by [`compress_body`].
    ///
    /// For a generator that compresses in parallel, which is the difference between using one core
    /// and using all of them. DEFLATE at level nine runs on the order of 15 MB/s, so a California
    /// build's 1.3 GB of bodies is about ninety seconds of a single core — and inside
    /// [`Self::append_encoded`] that sits *downstream* of the caller's parallel map and encode,
    /// serialising the whole pipeline behind the one step that cannot be stolen. Compressing in the
    /// worker and appending the result leaves the append what it should be: index bookkeeping.
    ///
    /// Dedup is unaffected. Entries are deduplicated on the **stored** bytes either way, and DEFLATE
    /// is deterministic, so two equal bodies still compress to two equal frames and still collapse.
    /// That is also why this is byte-identical to compressing here.
    pub fn append_stored(&mut self, tile_id: u64, stored: &[u8]) -> Result<()> {
        if !self.options.compress {
            return err(
                "append_stored was given a compressed body but this archive stores them raw"
                    .to_string(),
            );
        }
        // The 16-byte body header rides uncompressed ahead of the frame, so this still validates.
        Body::raw_len(stored)?;
        self.push(tile_id, stored)
    }

    /// The part both appends share: range and order checks, dedup, and the index entry.
    fn push(&mut self, tile_id: u64, stored: &[u8]) -> Result<()> {
        let (z, _, _) = crate::pmtiles::tile_zxy(tile_id);
        if z < self.options.min_zoom || z > self.options.max_zoom {
            return err(format!(
                "tile {tile_id} is at z{z}, outside the declared range {}..={}",
                self.options.min_zoom, self.options.max_zoom,
            ));
        }
        if let Some(previous) = self.last_id {
            if tile_id <= previous {
                return err(format!(
                    "a .mamaps build needs ascending tile ids, got {tile_id} after {previous}"
                ));
            }
        }
        self.last_id = Some(tile_id);
        self.tiles_addressed += 1;

        let length = u32::try_from(stored.len())
            .map_err(|_| crate::proto::Error("a .mamaps body is larger than 4 GiB".to_string()))?;

        // Run-length first: a consecutive repeat needs no dedup lookup and no new entry at all.
        // The compare is against `last_body` rather than the spill, because the bytes the last
        // entry points at are the one body always worth keeping to hand.
        if let Some(previous) = self.entries.last_mut() {
            debug_assert!(
                self.data.matches_at(previous.offset, &self.last_body)?,
                "last_body must be the bytes the last entry points at, or the shortcut below is \
                 answering for a body that is not there",
            );
            if previous.length == length
                && previous.tile_id + previous.run_length as u64 == tile_id
                && self.last_body == stored
            {
                previous.run_length += 1;
                self.runs_used = true;
                return Ok(());
            }
        }

        let key = (hash64(&stored), length);
        // `seen` and `data` are borrowed apart because confirming a candidate may read the spill,
        // which needs `&mut`, while the bucket being walked lives in the map.
        let StreamWriter { seen, data, distinct, .. } = &mut *self;
        let bucket = seen.entry(key).or_default();
        let mut hit = None;
        let head = data.len();
        for &at in bucket.iter() {
            // Too far back to be addressable from a leaf that ends here. Skipped rather than
            // matched, so the body is written again and the delta stays inside a `u32`.
            if head - at > MAX_DEDUP_REACH {
                continue;
            }
            if data.matches_at(at, &stored)? {
                hit = Some(at);
                break;
            }
        }
        let offset = match hit {
            Some(at) => at,
            None => {
                let at = data.len();
                data.append(&stored)?;
                bucket.push(at);
                *distinct += 1;
                at
            }
        };
        self.entries.push(Pending { tile_id, offset, run_length: 1, length });
        self.last_body.clear();
        self.last_body.extend_from_slice(stored);
        Ok(())
    }

    /// Write the whole archive to `path`, never holding more than one section of it.
    ///
    /// This is the finish a real build wants: the prefix is assembled in memory — it is the index,
    /// which is small next to the bodies — and the data section is copied straight from the scratch
    /// file onto the end. Nothing ever holds the archive.
    ///
    /// The header is parsed before the destination is touched, so a build that would not open does
    /// not leave a file behind that looks like it might.
    ///
    /// With [`Options::shared_table`] on, the shared section follows the tile
    /// data immediately — it starts exactly at `data_offset + data_len` — and
    /// `file_len` covers it. Off, nothing is appended and the file is
    /// byte-identical v7.
    pub fn finish_to_path(mut self, path: &Path) -> Result<()> {
        let shared = self.take_shared_bytes()?;
        let (shared_len, shared_pools) = match &shared {
            Some((bytes, pools)) => (bytes.len() as u64, *pools),
            None => (0, 0),
        };
        let (header, prefix) = self.prefix(shared_len, shared_pools)?;
        let mut out = File::create(path).map_err(|e| {
            crate::proto::Error(format!("cannot write {}: {e}", path.display()))
        })?;
        out.write_all(&prefix)
            .map_err(|e| crate::proto::Error(format!("cannot write {}: {e}", path.display())))?;
        let copied = self.data.copy_to(&mut out, path)?;
        if copied != header.data_len {
            return err(format!(
                "a .mamaps build declared a {} byte data section and copied {copied}",
                header.data_len,
            ));
        }
        if let Some((shared, _)) = &shared {
            out.write_all(shared).map_err(|e| {
                crate::proto::Error(format!("cannot write {}: {e}", path.display()))
            })?;
        }
        Ok(())
    }

    /// The whole file, in memory.
    ///
    /// Kept for callers small enough not to care — the tests, and the tools that read an archive
    /// back before writing it — and for them the peak is one copy of the archive rather than the two
    /// it used to be. Anything the size of a region should use [`Self::finish_to_path`].
    ///
    /// With [`Options::shared_table`] on, the shared section is appended after
    /// the tile data exactly as [`Self::finish_to_path`] appends it, so the two
    /// finishes stay two ways of emitting one archive.
    pub fn finish(mut self) -> Result<Vec<u8>> {
        let shared = self.take_shared_bytes()?;
        let (shared_len, shared_pools) = match &shared {
            Some((bytes, pools)) => (bytes.len() as u64, *pools),
            None => (0, 0),
        };
        let (header, prefix) = self.prefix(shared_len, shared_pools)?;
        // TEMPORARY instrumentation.
        eprintln!(
            "spill: {} confirms, {} from file ({:.2}%), {} bytes read back",
            self.data.confirms,
            self.data.confirms_from_file,
            100.0 * self.data.confirms_from_file as f64 / self.data.confirms.max(1) as f64,
            self.data.bytes_from_file,
        );
        let mut out = Vec::with_capacity(header.file_len as usize);
        out.extend_from_slice(&prefix);
        let copied = self.data.copy_to_vec(&mut out)?;
        if copied != header.data_len {
            return err(format!(
                "a .mamaps build declared a {} byte data section and copied {copied}",
                header.data_len,
            ));
        }
        if let Some((shared, _)) = &shared {
            out.extend_from_slice(shared);
        }
        debug_assert_eq!(out.len() as u64, header.file_len);
        Ok(out)
    }

    /// The shared section bytes plus its pool count, or `None` when the flag is off.
    ///
    /// Taken (not borrowed) because [`SharedBuilder::serialize`](crate::mamaps::shared::SharedBuilder::serialize)
    /// consumes the builder to sort its rows. Called once at the top of each
    /// finish, before [`Self::prefix`], so the header's `file_len` already
    /// covers the section both finishes then append. The pool count comes out
    /// of the section's own header — never a literal — so it stays right
    /// however many pools the builder emits.
    fn take_shared_bytes(&mut self) -> Result<Option<(Vec<u8>, u32)>> {
        let Some(builder) = self.shared.take() else { return Ok(None) };
        let bytes = builder.serialize()?;
        let pools = crate::mamaps::shared::SharedHeader::parse(&bytes)?.pool_count;
        Ok(Some((bytes, pools)))
    }

    /// Chop the entries into leaves

    /// Chop the entries into leaves of at most `capacity`, rebasing each leaf's ids and offsets.
    ///
    /// `None` when some leaf's tile-id span will not fit the `u32` a [`LeafEntry::tile_id_lo`]
    /// carries, which the caller answers by choosing a different capacity. A leaf is also
    /// closed early when its data span would exceed u32::MAX — at planet scale the data
    /// section is >4 GiB and a count-only leaf can span 4294968552 bytes (u32::MAX+1257
    /// in the failed run) which the old `chunks(capacity)` path turned into a hard
    /// `u32::try_from` abort. Splitting by span as well as count bounds every
    /// LeafEntry.offset_delta to u32 without changing the wire format.
    ///
    /// Byte-identity: when no leaf's span nears 4 GiB (NA, us-west), this yields
    /// exactly the same leaf boundaries as `chunks(capacity)` — the span check
    /// only adds *earlier* splits for planet and never alters count-based splits.
    pub(crate) fn partition(&self, capacity: u32) -> Result<Option<Split>> {
        let mut root = Vec::new();
        let mut leaves = Vec::new();
        let cap = capacity as usize;
        let mut start = 0usize;
        let mut leaf_data_offset: u64 = 0; // cumulative leaf section offset (packed, not stride)
        while start < self.entries.len() {
            // Greedily grow this leaf until capacity or 4 GiB span would be exceeded.
            // Base is the *minimum* offset in the leaf, since content dedup lets a
            // later entry point at a body written for an earlier tile (negative delta
            // if based on first entry). Track min/max incrementally O(cap) per leaf.
            let mut end = start + 1;
            let mut cur_min = self.entries[start].offset;
            let mut cur_max = cur_min;
            while end < self.entries.len() && (end - start) < cap {
                let next_off = self.entries[end].offset;
                let new_min = cur_min.min(next_off);
                let new_max = cur_max.max(next_off);
                if new_max - new_min > u32::MAX as u64 {
                    break;
                }
                cur_min = new_min;
                cur_max = new_max;
                end += 1;
            }
            let chunk = &self.entries[start..end];
            let base_data_offset = chunk.iter().map(|e| e.offset).min().unwrap_or(0);
            let base_tile_id = chunk[0].tile_id;
            let last = &chunk[chunk.len() - 1];
            if !index::span_fits(last.tile_id + last.run_length as u64 - 1 - base_tile_id) {
                return Ok(None);
            }
            let mut leaf = Vec::with_capacity(chunk.len());
            for entry in chunk {
                let delta = u32::try_from(entry.offset - base_data_offset).map_err(|_| {
                    crate::proto::Error(format!(
                        "a .mamaps leaf spans {} bytes of the data section, past the {} a leaf entry \
                         addresses",
                        entry.offset - base_data_offset,
                        u32::MAX,
                    ))
                })?;
                leaf.push(LeafEntry {
                    tile_id_lo: (entry.tile_id - base_tile_id) as u32,
                    run_length: entry.run_length,
                    offset_delta: delta,
                    length: entry.length,
                });
            }
            let leaf_len_before = leaf.len() as u64;
            root.push(RootEntry {
                base_tile_id,
                leaf_offset: leaf_data_offset,
                base_data_offset,
                leaf_entry_count: chunk.len() as u32,
            });
            leaf_data_offset += leaf_len_before * index::LEAF_ENTRY_LEN as u64;
            leaves.push(leaf);
            start = end;
        }
        Ok(Some((root, leaves)))
    }

    #[cfg(all(test, feature = "write"))]
    #[allow(dead_code)]
    pub(crate) fn partition_for_test(
        entries: &[Pending],
        capacity: u32,
    ) -> Result<Option<Split>> {
        let w = StreamWriter {
            options: Options::default(),
            data: Spill::create(None).expect("spill"),
            entries: entries.to_vec(),
            seen: std::collections::HashMap::new(),
            last_id: None,
            last_body: Vec::new(),
            tiles_addressed: entries.len() as u64,
            distinct: entries.len() as u64,
            runs_used: false,
            shared: None,
        };
        w.partition(capacity)
    }
}
