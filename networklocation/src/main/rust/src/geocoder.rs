//! Offline reverse/forward geocoding over the packed `geocoder-v3.geodb`, exposed via JNI.
//!
//! The on-disk format is produced by `scripts/networklocation/geodb_build`: grid-primary record
//! order for cheap reverse lookup, dictionary-indexed fields, delta+zigzag+varint columns in
//! per-4096-record Zstandard blocks, plus two sorted indexes for structured and by-name lookup.
//! All ints/longs are big-endian.
//!
//! Columns are read block-by-block straight from the file (positional `pread`, no copy), and
//! the search runs in Rust for speed. Runtime is decompress-only, so we use the pure-Rust
//! `ruzstd` decoder — no NDK C toolchain needed.
//!
//! ## v3
//!
//! v2 held **only** postal addresses, at e6 coordinates. v3 also carries named streets, points
//! of interest and populated places, each tagged with a `kind`, and stores coordinates at e7 —
//! the precision OSM itself uses, so nothing is thrown away in the conversion.
//!
//! JNI result layout: each result is 10 consecutive strings
//! `[lat, lon, name, house, street, city, state, country, postcode, kind]`, lat/lon to 7dp.
//! `reverse` returns one result or null; `forward` and `searchName` return 10*k strings.

use std::cmp::Ordering;
use std::fs::File;
use std::io::{Cursor, Read};
use std::os::unix::fs::FileExt;
use std::os::unix::io::FromRawFd;
use std::sync::Mutex;

use jni::objects::{JClass, JString};
use jni::sys::{jdouble, jint, jlong, jobjectArray};
use jni::JNIEnv;

const MAGIC: u32 = 0x4D41_4745;
const VERSION: u32 = 3;
const BLOCK: i32 = 4096;

// Grid geometry, in e7 units. A cell is 0.05 degrees, about 5.5 km of latitude.
const CELL_E7: i64 = 500_000;
const COLS: i64 = 3_600_000_000 / CELL_E7; // 7200
const MIN_LAT_E7: i32 = -900_000_000;
const MIN_LON_E7: i32 = -1_800_000_000;

/// How far the reverse search will expand before giving up, in cells.
const MAX_RADIUS: i64 = 32;

const DICTS: usize = 7;
const COLUMNS: usize = 10;

const C_LAT: usize = 0;
const C_LON: usize = 1;
const C_NAME: usize = 2;
const C_HOUSE: usize = 3;
const C_STREET: usize = 4;
const C_CITY: usize = 5;
const C_STATE: usize = 6;
const C_COUNTRY: usize = 7;
const C_POSTCODE: usize = 8;
const C_KIND: usize = 9;

/// Dictionary slot backing column `c`. Columns 0 and 1 are coordinates and column 9 is a small
/// integer, so only 2..=8 are dictionary-indexed.
const fn dict_of(col: usize) -> usize {
    col - 2
}

/// Strings per result in the JNI array.
const FIELDS: usize = 10;

// --------------------------------------------------------------------------- byte source
/// Positional reader over a region of a file (the APK asset), starting at `base`.
struct Src {
    file: File,
    base: u64,
}
impl Src {
    fn read(&self, pos: u64, len: usize) -> Option<Vec<u8>> {
        let mut b = vec![0u8; len];
        self.file.read_exact_at(&mut b, self.base + pos).ok()?;
        Some(b)
    }
    fn rd_u32(&self, pos: u64) -> Option<u32> {
        let b = self.read(pos, 4)?;
        Some(be32(&b))
    }
    fn rd_i32(&self, pos: u64) -> Option<i32> {
        Some(self.rd_u32(pos)? as i32)
    }
}

fn be32(b: &[u8]) -> u32 {
    ((b[0] as u32) << 24) | ((b[1] as u32) << 16) | ((b[2] as u32) << 8) | (b[3] as u32)
}
fn be64(b: &[u8]) -> i64 {
    let mut v: i64 = 0;
    for i in 0..8 {
        v = (v << 8) | (b[i] as i64);
    }
    v
}
fn unzigzag(v: u32) -> i32 {
    ((v >> 1) as i32) ^ -((v & 1) as i32)
}
fn read_varint(a: &[u8], p: &mut usize) -> u32 {
    let mut v: u32 = 0;
    let mut shift = 0;
    loop {
        let b = a[*p];
        *p += 1;
        v |= ((b & 0x7F) as u32) << shift;
        if b & 0x80 == 0 {
            break;
        }
        shift += 7;
    }
    v
}
fn zstd_decompress(comp: &[u8], raw_len: usize) -> Option<Vec<u8>> {
    let mut dec = ruzstd::StreamingDecoder::new(Cursor::new(comp)).ok()?;
    let mut out = Vec::with_capacity(raw_len);
    dec.read_to_end(&mut out).ok()?;
    if out.len() != raw_len {
        return None;
    }
    Some(out)
}
/// Kotlin `String.compareTo` compares UTF-16 code units; the searchable dictionaries
/// (street/city/state/country) are sorted that way, so the binary search must match. The
/// house/postcode dictionaries are frequency-ordered and only ever indexed for display.
fn cmp_utf16(a: &str, b: &str) -> Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}

// --------------------------------------------------------------------------- column reader
/// One column, decoding a single block on demand (cf. `GeoDbReader.Column`).
struct Column {
    n: i32,
    delta: bool,
    comp_lens: Vec<i32>,
    raw_lens: Vec<i32>,
    block_off: Vec<u64>,
    cached_block: i32,
    cached: Vec<i32>,
}
impl Column {
    fn new(src: &Src, body_off: u64, delta: bool) -> Option<Column> {
        let n = src.rd_i32(body_off)?;
        let blocks = src.rd_i32(body_off + 4)? as usize;
        let mut raw_lens = Vec::with_capacity(blocks);
        let mut comp_lens = Vec::with_capacity(blocks);
        let mut p = body_off + 8;
        for _ in 0..blocks {
            raw_lens.push(src.rd_i32(p)?);
            comp_lens.push(src.rd_i32(p + 4)?);
            p += 8;
        }
        let mut block_off = Vec::with_capacity(blocks);
        let mut off = p;
        for b in 0..blocks {
            block_off.push(off);
            off += comp_lens[b] as u64;
        }
        Some(Column { n, delta, comp_lens, raw_lens, block_off, cached_block: -1, cached: Vec::new() })
    }
    fn ensure(&mut self, src: &Src, block: i32) -> bool {
        if block == self.cached_block {
            return true;
        }
        let bi = block as usize;
        let comp = match src.read(self.block_off[bi], self.comp_lens[bi] as usize) {
            Some(c) => c,
            None => return false,
        };
        let raw = match zstd_decompress(&comp, self.raw_lens[bi] as usize) {
            Some(r) => r,
            None => return false,
        };
        let count = std::cmp::min(BLOCK, self.n - block * BLOCK) as usize;
        let mut vals = vec![0i32; count];
        let mut rp = 0usize;
        let mut prev = 0i32;
        for k in 0..count {
            let dv = unzigzag(read_varint(&raw, &mut rp));
            let actual = if self.delta { prev + dv } else { dv };
            vals[k] = actual;
            prev = actual;
        }
        self.cached = vals;
        self.cached_block = block;
        true
    }
    fn get(&mut self, src: &Src, i: i32) -> i32 {
        let block = i / BLOCK;
        if !self.ensure(src, block) {
            return 0;
        }
        self.cached[(i - block * BLOCK) as usize]
    }
}

fn decode_dict(section: &[u8]) -> Option<Vec<String>> {
    let raw_size = be32(&section[0..4]) as usize;
    let comp_size = be32(&section[4..8]) as usize;
    let raw = zstd_decompress(&section[8..8 + comp_size], raw_size)?;
    let mut r = 0usize;
    let count = be32(&raw[r..r + 4]) as usize;
    r += 4;
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        let len = be32(&raw[r..r + 4]) as usize;
        r += 4;
        out.push(String::from_utf8_lossy(&raw[r..r + len]).into_owned());
        r += len;
    }
    Some(out)
}

// --------------------------------------------------------------------------- reader
pub struct Reader {
    src: Src,
    n: i32,
    /// name, house, street, city, state, country, postcode.
    dicts: Vec<Vec<String>>,
    cell_ids: Vec<i64>,
    cell_starts: Vec<i32>,
    cols: Vec<Column>,
    fwd: Column,
    /// Name ids, ascending; parallel to [`Reader::nm_rec`].
    nm_name: Column,
    /// Record index for each entry of [`Reader::nm_name`].
    nm_rec: Column,
}

fn sect_bytes(src: &Src, cur: &mut u64) -> Option<Vec<u8>> {
    let size = src.rd_i32(*cur)? as usize;
    *cur += 4;
    let b = src.read(*cur, size)?;
    *cur += size as u64;
    Some(b)
}
fn sect_body(src: &Src, cur: &mut u64) -> Option<u64> {
    let size = src.rd_i32(*cur)? as usize;
    *cur += 4;
    let off = *cur;
    *cur += size as u64;
    Some(off)
}

include!("geocoder_part1.rs");
include!("geocoder_part2.rs");