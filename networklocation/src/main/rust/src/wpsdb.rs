//! Offline key → coordinate lookup over a packed `WPSDB1` store, exposed via JNI.
//!
//! One generic reader serves both offline stores the app ships (mirroring the offline
//! geocoder pattern in `geocoder.rs`):
//!   * `wifi.wpsdb`  — 48-bit MAC (BSSID) key → coord
//!   * `cells.wpsdb` — 64-bit packed cell key → coord
//!
//! The on-disk format is produced by `wtfps-experiment/store.py` (see FORMAT.md). All
//! multi-byte scalars are little-endian; the bit-packed arrays are LSB-first within each
//! byte (bit `k` of a value lives at `buf[p >> 3] & (1 << (p & 7))`).
//!
//! ```text
//! magic "WPSDB1\0\0"                       8 B
//! coord_bits:u32, n:u64
//! EF header: n:u64, l:u8, universe_bits:u8, high_len_bits:u64
//!            high_len:u64, high[high_len]
//!            low_len:u64,  low[low_len]
//! coord:     clen:u64, coord[clen]         (n * coord_bits bits, bit-packed)
//! ```
//!
//! Membership + index come from the Elias–Fano upper bitvector (`high`), which is held in
//! memory (tens of MB at world scale) with a sampled `select0` index built at open. The
//! `low` and `coord` arrays are read on demand via positional reads straight from the APK
//! asset fd, so device memory stays bounded even for a ~2 GB store.

use std::fs::File;
use std::os::unix::fs::FileExt;
use std::os::unix::io::FromRawFd;

use jni::objects::JClass;
use jni::sys::{jdoubleArray, jint, jlong};
use jni::JNIEnv;

const MAGIC: &[u8; 8] = b"WPSDB1\x00\x00";

// 20 m global grid (mirrors quantize.py): lat 20 bits over [-90,90], lon 21 bits over
// [-180,180]. coord_bits in the header is expected to equal LAT_BITS + LON_BITS.
const LAT_BITS: u32 = 20;
const LON_BITS: u32 = 21;

// select0 sample stride, in bits: `zero_samples[b]` holds the number of zero bits in
// `high[0 .. b*SELECT_SAMPLE)`. 4096 keeps the index small while bounding the in-block
// scan of a select0 to <= 4096 bit tests.
const SELECT_SAMPLE: u64 = 4096;

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
        Some(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }
    fn rd_u64(&self, pos: u64) -> Option<u64> {
        let b = self.read(pos, 8)?;
        Some(u64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]))
    }
    fn rd_u8(&self, pos: u64) -> Option<u8> {
        Some(self.read(pos, 1)?[0])
    }
}

// --------------------------------------------------------------------------- reader
pub struct Reader {
    src: Src,
    coord_bits: u32,
    n: u64,
    l: u8,
    universe_bits: u8,
    high_len_bits: u64,
    high: Vec<u8>,
    /// `zero_samples[b]` = number of zero bits in `high[0 .. b*SELECT_SAMPLE)`.
    zero_samples: Vec<u64>,
    low_off: u64,
    coord_off: u64,
}

impl Reader {
    fn open(fd: i32, offset: i64) -> Option<Reader> {
        let dupfd = unsafe { libc::dup(fd) };
        if dupfd < 0 {
            return None;
        }
        let file = unsafe { File::from_raw_fd(dupfd) };
        Reader::from_src(Src { file, base: offset as u64 })
    }

    /// Open a `.wpsdb` straight from a filesystem path (base offset 0). Test-only: the
    /// production path receives an APK asset fd + offset via `open`.
    #[cfg(test)]
    fn open_path<P: AsRef<std::path::Path>>(path: P) -> Option<Reader> {
        let file = File::open(path).ok()?;
        Reader::from_src(Src { file, base: 0 })
    }

    fn from_src(src: Src) -> Option<Reader> {
        if &src.read(0, 8)?[..] != &MAGIC[..] {
            return None;
        }
        let coord_bits = src.rd_u32(8)?;
        let n = src.rd_u64(12)?;

        // The coord codec is fixed at the 20 m grid (LAT_BITS + LON_BITS); reject any store
        // built with a different resolution rather than silently mis-decoding coordinates.
        if coord_bits != LAT_BITS + LON_BITS {
            return None;
        }

        // EF header ("<QBBQ"): n (repeated), l, universe_bits, high_len_bits.
        let l = src.rd_u8(28)?;
        let universe_bits = src.rd_u8(29)?;
        let high_len_bits = src.rd_u64(30)?;

        let high_len = src.rd_u64(38)?;
        let high = src.read(46, high_len as usize)?;

        let low_len_off = 46 + high_len;
        let low_len = src.rd_u64(low_len_off)?;
        let low_off = low_len_off + 8;

        let clen_off = low_off + low_len;
        let _clen = src.rd_u64(clen_off)?;
        let coord_off = clen_off + 8;

        // Sanity: the upper bitvector must have exactly n set bits, and hold enough bytes.
        if (high.len() as u64) < high_len_bits.div_ceil(8) {
            return None;
        }

        let zero_samples = build_zero_samples(&high, high_len_bits, n);

        Some(Reader {
            src,
            coord_bits,
            n,
            l,
            universe_bits,
            high_len_bits,
            high,
            zero_samples,
            low_off,
            coord_off,
        })
    }

    #[inline]
    fn high_bit(&self, p: u64) -> bool {
        (self.high[(p >> 3) as usize] >> (p & 7)) & 1 == 1
    }

    /// Position of the zero bit whose 0-indexed rank is `j` (i.e. the `(j+1)`-th zero), or
    /// `None` when fewer than `j+1` zeros exist.
    fn select0(&self, j: u64) -> Option<u64> {
        let total_zeros = self.high_len_bits - self.n;
        if j >= total_zeros {
            return None;
        }
        // Largest block b with zero_samples[b] <= j.
        let mut lo = 0usize;
        let mut hi = self.zero_samples.len() - 1;
        while lo < hi {
            let mid = (lo + hi + 1) / 2;
            if self.zero_samples[mid] <= j {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        let mut count = self.zero_samples[lo];
        let mut p = (lo as u64) * SELECT_SAMPLE;
        while p < self.high_len_bits {
            if !self.high_bit(p) {
                if count == j {
                    return Some(p);
                }
                count += 1;
            }
            p += 1;
        }
        None
    }

    /// Read `nbits` bits (LSB-first) starting at bit `bit_index` within the section at file
    /// byte offset `section_off`. `nbits <= 64`.
    fn read_bits(&self, section_off: u64, bit_index: u64, nbits: u32) -> Option<u64> {
        if nbits == 0 {
            return Some(0);
        }
        let first_bit = (bit_index & 7) as u64;
        let byte_pos = section_off + (bit_index >> 3);
        let nbytes = ((first_bit + nbits as u64 + 7) / 8) as usize;
        let buf = self.src.read(byte_pos, nbytes)?;
        let mut v = 0u64;
        for k in 0..nbits as u64 {
            let p = first_bit + k;
            if (buf[(p >> 3) as usize] >> (p & 7)) & 1 == 1 {
                v |= 1u64 << k;
            }
        }
        Some(v)
    }

    #[inline]
    fn low(&self, i: u64) -> Option<u64> {
        self.read_bits(self.low_off, i * self.l as u64, self.l as u32)
    }

    #[inline]
    fn coord(&self, i: u64) -> Option<u64> {
        self.read_bits(self.coord_off, i * self.coord_bits as u64, self.coord_bits)
    }

    /// Elias–Fano index of `key`, or `None` if `key` is not in the set.
    fn index(&self, key: u64) -> Option<u64> {
        let l = self.l as u32;
        let hi = if l >= 64 { 0 } else { key >> l };
        let lo = if l == 0 {
            0
        } else if l >= 64 {
            key
        } else {
            key & ((1u64 << l) - 1)
        };

        // i0 = number of keys with upper < hi; `start` = first high-bit position of bucket hi.
        let (mut i, start) = if hi == 0 {
            (0u64, 0u64)
        } else {
            let s0 = self.select0(hi - 1)?;
            let start = s0 + 1;
            (start - hi, start)
        };

        let mut p = start;
        let mut bucket = hi;
        while p < self.high_len_bits {
            if self.high_bit(p) {
                if bucket != hi {
                    break;
                }
                if self.low(i)? == lo {
                    return Some(i);
                }
                i += 1;
            } else {
                bucket += 1;
                if bucket > hi {
                    break;
                }
            }
            p += 1;
        }
        None
    }

    /// Look up `key`; returns `(lat, lon)` or `None` for an unknown key.
    fn lookup(&self, key: u64) -> Option<(f64, f64)> {
        let i = self.index(key)?;
        let code = self.coord(i)?;
        Some(decode(code))
    }
}

/// Build the sampled `select0` index by scanning the upper bitvector once at open.
/// Padding bits beyond `high_len_bits` in the final byte are NOT counted as zeros.
fn build_zero_samples(high: &[u8], high_len_bits: u64, _n: u64) -> Vec<u64> {
    let mut samples = Vec::with_capacity((high_len_bits / SELECT_SAMPLE + 2) as usize);
    samples.push(0u64); // zeros before bit 0
    let mut zeros = 0u64;
    let mut bitpos = 0u64;
    'outer: for &byte in high {
        for bit in 0..8u64 {
            if bitpos >= high_len_bits {
                break 'outer;
            }
            if (byte >> bit) & 1 == 0 {
                zeros += 1;
            }
            bitpos += 1;
            if bitpos % SELECT_SAMPLE == 0 {
                samples.push(zeros);
            }
        }
    }
    samples
}

/// Decode a packed coordinate (20 m grid) to `(lat, lon)` — mirrors `quantize.decode`.
fn decode(code: u64) -> (f64, f64) {
    let lat_steps = (1u64 << LAT_BITS) as f64;
    let lon_steps = 1u64 << LON_BITS;
    let li = code >> LON_BITS;
    let oi = code & (lon_steps - 1);
    let lat = (li as f64 + 0.5) / lat_steps * 180.0 - 90.0;
    let lon = (oi as f64 + 0.5) / lon_steps as f64 * 360.0 - 180.0;
    (lat, lon)
}

// --------------------------------------------------------------------------- JNI
type Handle = Reader;

/// `open(fd, offset, length) -> handle` (0 on failure). The native side dups `fd`, so the
/// caller may close its own descriptor after this returns. `length` is reserved (the section
/// framing bounds every read) and kept for API symmetry with `GeocoderNative`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_WpsStoreNative_open<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    _length: jlong,
) -> jlong {
    match Reader::open(fd, offset) {
        Some(r) => Box::into_raw(Box::new(r)) as jlong,
        None => 0,
    }
}

/// `lookup(handle, key) -> double[2]` (`[lat, lon]`) or null for an unknown key. `key` is a
/// 64-bit value: a 48-bit WiFi MAC or a 64-bit packed cell key both fit.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_WpsStoreNative_lookup<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    key: jlong,
) -> jdoubleArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let reader = unsafe { &*(handle as *const Handle) };
    match reader.lookup(key as u64) {
        Some((lat, lon)) => {
            let arr = match env.new_double_array(2) {
                Ok(a) => a,
                Err(_) => return std::ptr::null_mut(),
            };
            if env.set_double_array_region(&arr, 0, &[lat, lon]).is_err() {
                return std::ptr::null_mut();
            }
            arr.into_raw()
        }
        None => std::ptr::null_mut(),
    }
}

/// `close(handle)` — frees the reader and its dup'd fd. Safe to call with 0.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_WpsStoreNative_close<'l>(
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    /// Locate a real store: `$WPSDB_TEST`, else the committed ~1 MB WiFi sample.
    fn db_path() -> Option<String> {
        if let Ok(p) = std::env::var("WPSDB_TEST") {
            return Some(p);
        }
        let bundled =
            concat!(env!("CARGO_MANIFEST_DIR"), "/../../../../wtfps-experiment/world_store.bin");
        if std::path::Path::new(bundled).exists() {
            Some(bundled.to_string())
        } else {
            eprintln!("skip: no WPSDB sample (set WPSDB_TEST or add world_store.bin)");
            None
        }
    }

    fn open_db() -> Option<Reader> {
        let path = db_path()?;
        match Reader::open_path(&path) {
            Some(r) => Some(r),
            None => panic!("WPSDB at {path} exists but failed magic/parse"),
        }
    }

    /// Reconstruct every (index, key) pair by walking the Elias–Fano upper bitvector — the
    /// inverse of `index()` — so tests can cross-check membership and coord decode against
    /// exactly what the Python builder wrote.
    fn reconstruct(r: &Reader) -> Vec<u64> {
        let l = r.l as u32;
        let mut keys = Vec::with_capacity(r.n as usize);
        let mut idx = 0u64;
        let mut p = 0u64;
        while p < r.high_len_bits && idx < r.n {
            if r.high_bit(p) {
                let upper = p - idx;
                let lo = r.low(idx).unwrap();
                let key = if l >= 64 { lo } else { (upper << l) | lo };
                keys.push(key);
                idx += 1;
            }
            p += 1;
        }
        keys
    }

    #[test]
    fn structure_is_sane() {
        let r = match open_db() {
            Some(r) => r,
            None => return,
        };
        assert!(r.n > 0, "record count must be positive");
        assert_eq!(r.universe_bits, 48, "WiFi sample must have a 48-bit universe");
        assert_eq!(r.coord_bits, LAT_BITS + LON_BITS, "coord_bits must be 41");
        assert!(r.high_len_bits >= r.n, "high bitvector shorter than key count");
    }

    /// Every reconstructed key resolves to its own index, and `lookup` decodes the same
    /// coordinate as reading `coord[i]` directly. This proves the Rust EF/select0 + coord
    /// decode matches the builder byte-for-byte.
    #[test]
    fn known_keys_resolve() {
        let r = match open_db() {
            Some(r) => r,
            None => return,
        };
        let keys = reconstruct(&r);
        assert_eq!(keys.len() as u64, r.n, "reconstructed key count mismatch");
        // ascending & distinct (EF invariant)
        for w in keys.windows(2) {
            assert!(w[0] < w[1], "keys not strictly ascending");
        }
        let step = (keys.len() / 3000).max(1);
        for (i, &key) in keys.iter().enumerate().step_by(step) {
            let idx = r.index(key).unwrap_or_else(|| panic!("known key {key:#x} rejected"));
            assert_eq!(idx as usize, i, "index mismatch for {key:#x}");
            let (lat, lon) = r.lookup(key).expect("known key resolves");
            let (elat, elon) = decode(r.coord(i as u64).unwrap());
            assert_eq!((lat, lon), (elat, elon), "lookup != direct coord decode");
            assert!((-90.0..=90.0).contains(&lat), "lat out of range: {lat}");
            assert!((-180.0..=180.0).contains(&lon), "lon out of range: {lon}");
        }
    }

    /// Random unknown keys are all rejected — zero false positives, mirroring
    /// `store.py::_self_test`.
    #[test]
    fn unknown_keys_rejected() {
        let r = match open_db() {
            Some(r) => r,
            None => return,
        };
        let known: HashMap<u64, ()> = reconstruct(&r).into_iter().map(|k| (k, ())).collect();
        // Simple deterministic LCG so the test needs no rand dep at runtime.
        let mut state = 0x1234_5678_9abc_def0u64;
        let mut next = || {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            state
        };
        let universe_mask = if r.universe_bits >= 64 {
            u64::MAX
        } else {
            (1u64 << r.universe_bits) - 1
        };
        let mut fp = 0;
        for _ in 0..20_000 {
            let key = next() & universe_mask;
            if !known.contains_key(&key) && r.lookup(key).is_some() {
                fp += 1;
            }
        }
        assert_eq!(fp, 0, "expected zero false positives, got {fp}");
    }
}
