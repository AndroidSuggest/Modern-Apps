//! Offline reverse/forward geocoding over the packed `geocoder.geodb`, exposed via JNI.
//!
//! The on-disk format is produced by `scripts/geocoder_gen.cpp` and mirrors the design
//! documented in `GeoDb.kt`: grid-primary record order for cheap reverse lookup, dictionary
//! -indexed fields, delta+zigzag+varint columns in per-4096-record Zstandard blocks, plus a
//! forward-sorted index for structured lookup. All ints/longs are big-endian.
//!
//! This replaces the former Kotlin `GeoDbReader`: the address columns are read block-by-block
//! straight from the mmap-friendly APK asset fd (positional `pread`, no copy to disk), and the
//! search runs in Rust for speed. Runtime is decompress-only, so we use the pure-Rust `ruzstd`
//! decoder — no NDK C toolchain needed.
//!
//! JNI result layout: each address is 8 consecutive strings
//! `[lat, lon, house, street, city, state, country, postcode]` (lat/lon formatted to 6dp).
//! `reverse` returns one address (8 strings) or null; `forward` returns 8*k strings.

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
const VERSION: u32 = 2;
const BLOCK: i32 = 4096;
const CELL_MICRO: i64 = 50_000;
const COLS: i64 = 360_000_000 / CELL_MICRO; // 7200
const MIN_LAT_MICRO: i32 = -90_000_000;
const MIN_LON_MICRO: i32 = -180_000_000;

const C_LAT: usize = 0;
const C_LON: usize = 1;
const C_HOUSE: usize = 2;
const C_STREET: usize = 3;
const C_CITY: usize = 4;
const C_STATE: usize = 5;
const C_COUNTRY: usize = 6;
const C_POSTCODE: usize = 7;

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
/// Kotlin `String.compareTo` compares UTF-16 code units; the dictionaries are sorted that way,
/// so the binary search must compare the same way.
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
    dicts: Vec<Vec<String>>, // house, street, city, state, country, postcode
    cell_ids: Vec<i64>,
    cell_starts: Vec<i32>,
    cols: Vec<Column>, // 8
    fwd: Column,
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

impl Reader {
    fn open(fd: i32, offset: i64) -> Option<Reader> {
        let dupfd = unsafe { libc::dup(fd) };
        if dupfd < 0 {
            return None;
        }
        let file = unsafe { File::from_raw_fd(dupfd) };
        let src = Src { file, base: offset as u64 };

        if src.rd_u32(0)? != MAGIC || src.rd_u32(4)? != VERSION {
            return None;
        }
        let n = src.rd_i32(8)?;
        let mut cursor: u64 = 12;

        let mut dicts = Vec::with_capacity(6);
        for _ in 0..6 {
            dicts.push(decode_dict(&sect_bytes(&src, &mut cursor)?)?);
        }

        let delta = [true, true, false, false, false, false, false, false];
        let mut cols = Vec::with_capacity(8);
        for i in 0..8 {
            let off = sect_body(&src, &mut cursor)?;
            cols.push(Column::new(&src, off, delta[i])?);
        }

        let grid = sect_bytes(&src, &mut cursor)?;
        let count = be32(&grid[0..4]) as usize;
        let mut cell_ids = Vec::with_capacity(count);
        let mut cell_starts = Vec::with_capacity(count);
        let mut g = 4usize;
        for _ in 0..count {
            cell_ids.push(be64(&grid[g..g + 8]));
            cell_starts.push(be32(&grid[g + 8..g + 12]) as i32);
            g += 12;
        }

        let fwd_off = sect_body(&src, &mut cursor)?;
        let fwd = Column::new(&src, fwd_off, true)?;

        Some(Reader { src, n, dicts, cell_ids, cell_starts, cols, fwd })
    }

    fn grid_index(&self, cell: i64) -> i32 {
        let mut lo = 0i32;
        let mut hi = self.cell_ids.len() as i32 - 1;
        while lo <= hi {
            let mid = (lo + hi) >> 1;
            let v = self.cell_ids[mid as usize];
            if v < cell {
                lo = mid + 1;
            } else if v > cell {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        -1
    }

    fn dict_index(&self, field: usize, key: &str) -> i32 {
        let dict = &self.dicts[field];
        let mut lo = 0i32;
        let mut hi = dict.len() as i32 - 1;
        while lo <= hi {
            let mid = (lo + hi) >> 1;
            match cmp_utf16(&dict[mid as usize], key) {
                Ordering::Less => lo = mid + 1,
                Ordering::Greater => hi = mid - 1,
                Ordering::Equal => return mid,
            }
        }
        -1
    }

    fn reverse(&mut self, lat: f64, lon: f64) -> Option<i32> {
        if self.n == 0 {
            return None;
        }
        let q_lat = to_micro(lat);
        let q_lon = to_micro(lon);
        let row = ((q_lat - MIN_LAT_MICRO) as i64 / CELL_MICRO) as i64;
        let col = ((q_lon - MIN_LON_MICRO) as i64 / CELL_MICRO) as i64;
        let lon_scale = (lat.to_radians()).cos();

        let mut best_rec = -1i32;
        let mut best_dist = f64::MAX;
        let mut radius = 1i64;
        while best_rec < 0 && radius <= 32 {
            let mut r = row - radius;
            while r <= row + radius {
                if r >= 0 {
                    let mut c = col - radius;
                    while c <= col + radius {
                        let on_edge = !(radius > 1
                            && r > row - radius
                            && r < row + radius
                            && c > col - radius
                            && c < col + radius);
                        if on_edge {
                            let cc = ((c % COLS) + COLS) % COLS;
                            let cell = r * COLS + cc;
                            let gi = self.grid_index(cell);
                            if gi >= 0 {
                                let start = self.cell_starts[gi as usize];
                                let end = if (gi as usize) + 1 < self.cell_starts.len() {
                                    self.cell_starts[gi as usize + 1]
                                } else {
                                    self.n
                                };
                                for i in start..end {
                                    let d_lat = (self.cols[C_LAT].get(&self.src, i) - q_lat) as f64;
                                    let d_lon =
                                        (self.cols[C_LON].get(&self.src, i) - q_lon) as f64 * lon_scale;
                                    let d = d_lat * d_lat + d_lon * d_lon;
                                    if d < best_dist {
                                        best_dist = d;
                                        best_rec = i;
                                    }
                                }
                            }
                        }
                        c += 1;
                    }
                }
                r += 1;
            }
            radius += 1;
        }
        if best_rec < 0 {
            None
        } else {
            Some(best_rec)
        }
    }

    fn compare_key(&mut self, rec: i32, target: [i32; 4]) -> i32 {
        let c = self.cols[C_COUNTRY].get(&self.src, rec) - target[0];
        if c != 0 {
            return c;
        }
        let c = self.cols[C_STATE].get(&self.src, rec) - target[1];
        if c != 0 {
            return c;
        }
        let c = self.cols[C_CITY].get(&self.src, rec) - target[2];
        if c != 0 {
            return c;
        }
        self.cols[C_STREET].get(&self.src, rec) - target[3]
    }

    fn forward(&mut self, country: &str, state: &str, city: &str, street: &str, limit: i32) -> Vec<i32> {
        let k_country = self.dict_index(C_COUNTRY - 2, country);
        let k_state = self.dict_index(C_STATE - 2, state);
        let k_city = self.dict_index(C_CITY - 2, city);
        let k_street = self.dict_index(C_STREET - 2, street);
        if k_country < 0 || k_state < 0 || k_city < 0 || k_street < 0 {
            return Vec::new();
        }
        let target = [k_country, k_state, k_city, k_street];
        let mut lo = 0i32;
        let mut hi = self.n;
        while lo < hi {
            let mid = (lo + hi) >> 1;
            let rec = self.fwd.get(&self.src, mid);
            if self.compare_key(rec, target) < 0 {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        let mut out = Vec::new();
        let mut k = lo;
        while k < self.n && (out.len() as i32) < limit {
            let rec = self.fwd.get(&self.src, k);
            if self.compare_key(rec, target) != 0 {
                break;
            }
            out.push(rec);
            k += 1;
        }
        out
    }

    /// `[lat, lon, house, street, city, state, country, postcode]` for a grid-ordered record.
    fn resolve(&mut self, rec: i32) -> [String; 8] {
        let lat = self.cols[C_LAT].get(&self.src, rec) as f64 / 1e6;
        let lon = self.cols[C_LON].get(&self.src, rec) as f64 / 1e6;
        let h = self.cols[C_HOUSE].get(&self.src, rec);
        let s = self.cols[C_STREET].get(&self.src, rec);
        let ci = self.cols[C_CITY].get(&self.src, rec);
        let st = self.cols[C_STATE].get(&self.src, rec);
        let co = self.cols[C_COUNTRY].get(&self.src, rec);
        let pc = self.cols[C_POSTCODE].get(&self.src, rec);
        [
            format!("{:.6}", lat),
            format!("{:.6}", lon),
            self.dicts[0][h as usize].clone(),
            self.dicts[1][s as usize].clone(),
            self.dicts[2][ci as usize].clone(),
            self.dicts[3][st as usize].clone(),
            self.dicts[4][co as usize].clone(),
            self.dicts[5][pc as usize].clone(),
        ]
    }
}

fn to_micro(deg: f64) -> i32 {
    (deg * 1_000_000.0 + 0.5).floor() as i32
}

// --------------------------------------------------------------------------- JNI
type Handle = Mutex<Reader>;

fn build_string_array(env: &mut JNIEnv, reader: &mut Reader, recs: &[i32]) -> jobjectArray {
    let string_class = match env.find_class("java/lang/String") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let empty = match env.new_string("") {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let arr = match env.new_object_array((recs.len() * 8) as i32, &string_class, &empty) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (idx, &rec) in recs.iter().enumerate() {
        let fields = reader.resolve(rec);
        for (j, val) in fields.iter().enumerate() {
            let s = match env.new_string(val) {
                Ok(s) => s,
                Err(_) => return std::ptr::null_mut(),
            };
            if env
                .set_object_array_element(&arr, (idx * 8 + j) as i32, &s)
                .is_err()
            {
                return std::ptr::null_mut();
            }
        }
    }
    arr.into_raw()
}

/// `open(fd, offset, length) -> handle` (0 on failure). `length` is currently unused (the
/// section framing bounds every read) but kept for API symmetry / future validation.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_open<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    _length: jlong,
) -> jlong {
    match Reader::open(fd, offset) {
        Some(r) => Box::into_raw(Box::new(Mutex::new(r))) as jlong,
        None => 0,
    }
}

/// `reverse(handle, lat, lon) -> String[8]` (nearest address) or null.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_reverse<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    lat: jdouble,
    lon: jdouble,
) -> jobjectArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let m = unsafe { &*(handle as *const Handle) };
    let mut reader = match m.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };
    match reader.reverse(lat, lon) {
        Some(rec) => build_string_array(&mut env, &mut reader, &[rec]),
        None => std::ptr::null_mut(),
    }
}

/// `forward(handle, country, state, city, street, limit) -> String[8*k]` (may be empty).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_forward<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    country: JString<'l>,
    state: JString<'l>,
    city: JString<'l>,
    street: JString<'l>,
    limit: jint,
) -> jobjectArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let m = unsafe { &*(handle as *const Handle) };
    let mut reader = match m.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };
    let get = |env: &mut JNIEnv<'l>, s: &JString<'l>| -> String {
        env.get_string(s).map(|js| js.into()).unwrap_or_default()
    };
    let country = get(&mut env, &country);
    let state = get(&mut env, &state);
    let city = get(&mut env, &city);
    let street = get(&mut env, &street);
    let limit = limit.clamp(1, 50);
    let recs = reader.forward(&country, &state, &city, &street, limit);
    build_string_array(&mut env, &mut reader, &recs)
}

/// `close(handle)` — frees the reader and its dup'd fd.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_close<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Handle));
        }
    }
}
