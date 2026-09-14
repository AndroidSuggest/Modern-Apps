//! Build the compact **world** transit index from parsed GTFS tables (possibly
//! merged from many feeds) and serialize it to the on-disk `.transit` format.
//!
//! ON-DISK FORMAT v5 ("TRX2", little-endian, mmap-friendly, read via
//! `read_unaligned` on device). THIS LAYOUT MUST STAY IN SYNC WITH
//! `maps/src/main/rust/src/transit.rs`.
//!
//! v5 is v4 plus two purely additive sections (23-24) that let the device name a
//! stop in Transitous/MOTIS's own id space without a network round-trip, which is
//! what keeps the realtime `/stoptimes` overlay working now that the
//! `/map/stops` coordinate-to-id lookup is gone. A MOTIS stop id is
//! `<registry-file>-<source name>_<gtfs stop_id>`, so it is composed on device
//! from a per-feed prefix and the raw `stop_id`. They are parallel `u32` STRINGS
//! offsets rather than new `StopRec` fields because `StopRec` is a packed 16-byte
//! stride indexed by arithmetic — widening it would invalidate every older pack.
//!
//! v4 is v3 plus three purely additive sections (20-22) carrying GTFS
//! `shapes.txt` geometry, so a ride leg draws the path the vehicle actually
//! takes — and reports a truthful distance — instead of a line through its
//! stops. The device reader accepts v3 and v4, so a pack rebuild and an app
//! update can land in either order.
//!
//! v3 is v2 plus three purely additive sections (17-19); sections 0-16 are
//! byte-identical. They exist so the on-device planner can be correct and fast
//! on a world pack: `FEED_TZ` lets it route in the *feed's* timezone rather than
//! the device's, `EXCEPTIONS_IDX` turns the `calendar_dates` lookup from a full
//! scan per trip into a CSR range + binary search, and `STOP_ROUTE_POS` removes
//! the per-stop linear scan over a route's stop pattern.
//!
//! The v2 redesign exists to make a single global (world) pack feasible. v1
//! stored `(arr,dep)` u32 per stop *per trip* (8 B) and referenced them via a
//! `u32 first_stoptime`, so a worldwide pack was ~10–20 GB and bumped the 4.29 B
//! stop-time ceiling. v2 instead:
//!   * factors each trip into `{ start_time, profile_id }` where a **profile** is
//!     the varint-delta-encoded run-time *shape* (per-stop hop + dwell offsets),
//!     deduplicated across all trips sharing that shape — this removes both the
//!     per-trip stop-time table and the u32 ceiling;
//!   * packs each RAPTOR route's trips as varints (start-time deltas + ids);
//!   * adds a **spatial grid** (sparse CSR) so nearest-stop / bbox queries are
//!     cell-local instead of O(all stops);
//!   * adds a **FEEDS** table + per-route `feed_idx` so many agencies merge into
//!     one pack without id collisions (ids are namespaced per feed at build).
//!
//! Header (80 bytes; all u32/i32 little-endian):
//!   u32 magic (MAGIC), u32 version (VERSION=6), u32 section_count,
//!   u32 stop_count, u32 route_count, u32 trip_count, u32 service_count,
//!   u32 profile_count, u32 feed_count, u32 grid_cell_count, u32 feed_name_off,
//!   i32 min_lat_e7, i32 min_lon_e7, i32 max_lat_e7, i32 max_lon_e7,
//!   i32 grid_lat0_e7, i32 grid_lon0_e7, u32 grid_cell_e7, u32 grid_cols,
//!   u32 grid_rows
//! Section directory: section_count * { u64 offset, u64 len } (absolute byte
//!   offset from file start, byte length; 8-byte aligned, may exceed 4 GB).
//!
//! Sections (index -> payload):
//!   0  STRINGS         NUL-terminated UTF-8 string pool; offsets are byte
//!                      offsets into this section, sentinel NONE = no string.
//!   1  STOPS           StopRec[stop_count] = { i32 lat_e7, i32 lon_e7,
//!                        u32 name_off, u32 code_off }.
//!   2  ROUTES          RouteRec[route_count] = { u32 name_off, u32 color,
//!                        u32 route_type, u32 feed_idx, u32 n_stops,
//!                        u32 first_route_stop, u32 n_trips, u32 trips_off };
//!                        `trips_off` is a byte offset into ROUTE_TRIPS.
//!   3  ROUTE_STOPS     u32[] ordered stop indices; RouteRec slices it by
//!                        [first_route_stop, +n_stops].
//!   4  ROUTE_TRIPS     varint stream. Each route's block (at RouteRec.trips_off,
//!                        n_trips entries) is, per trip in start-time order:
//!                        uvarint start_delta (Δ from previous trip's start_time,
//!                        first = absolute), uvarint profile_id,
//!                        uvarint service_idx, uvarint headsign_off.
//!   5  PROFILES        varint stream. Each profile (at PROFILES_IDX[id]) is:
//!                        uvarint n, uvarint dwell0, then for k in 1..n
//!                        uvarint hop[k], uvarint dwell[k]. Given a trip start
//!                        time T: dep[0]=T, arr[0]=T-dwell0, and for k>=1
//!                        arr[k]=dep[k-1]+hop[k], dep[k]=arr[k]+dwell[k].
//!   6  PROFILES_IDX    u32[profile_count + 1] byte offsets into PROFILES.
//!   7  STOP_ROUTES     u32[] route indices serving each stop (flattened).
//!   8  STOP_ROUTES_IDX u32[stop_count + 1] prefix offsets into STOP_ROUTES.
//!   9  TRANSFERS       Transfer[] = { u32 to_stop, u32 secs } footpaths.
//!  10  TRANSFERS_IDX   u32[stop_count + 1] prefix offsets into TRANSFERS.
//!  11  SERVICES        ServiceRec[service_count] = { u8 weekday_mask,
//!                        u8[3] _pad, u32 start_date, u32 end_date }.
//!  12  EXCEPTIONS      ExcRec[] = { u32 service_idx, u32 date, u32 added }
//!                        (added: 1 = service added on date, 0 = removed).
//!                        Sorted by (service_idx, date), one row per pair.
//!  13  GRID_CELL_IDS   u32[grid_cell_count] non-empty cell ids, ascending.
//!                        cell_id = row*grid_cols + col, row/col from the header
//!                        grid origin + grid_cell_e7.
//!  14  GRID_CELL_OFF   u32[grid_cell_count + 1] prefix offsets into GRID_STOPS.
//!  15  GRID_STOPS      u32[stop_count] stop indices grouped by cell (in the
//!                        GRID_CELL_IDS order).
//!  16  FEEDS           FeedRec[feed_count] = { u32 name_off }.
//!  17  FEED_TZ         u32[feed_count] STRINGS offsets holding each feed's IANA
//!                        timezone from `agency.txt` (`agency_timezone`, row 0),
//!                        or NONE when the feed has no `agency.txt`.
//!  18  EXCEPTIONS_IDX  u32[service_count + 1] prefix offsets into EXCEPTIONS,
//!                        so one service's exceptions are a contiguous,
//!                        date-ascending range.
//!  19  STOP_ROUTE_POS  u32[] parallel to STOP_ROUTES: the position of that stop
//!                        within that route's stop pattern (first occurrence).
//!  20  SHAPE_COORDS    Concatenated polyline blobs. Each blob is `u32
//!                        point_count` then, per point, a zigzag-varint lat
//!                        delta and lon delta (1e-7 deg, cumulative from zero).
//!                        Blobs are self-delimiting and deduplicated, so routes
//!                        producing identical geometry share one.
//!  21  ROUTE_SHAPE_IDX u32[route_count + 1]. For route i, the byte offset of
//!                        its blob in SHAPE_COORDS, or NONE when the feed had no
//!                        usable shape for it. NOT a prefix sum: offsets repeat
//!                        where routes share a blob. The final entry is
//!                        SHAPE_COORDS' byte length, which the reader uses as a
//!                        bound.
//!  22  ROUTE_STOP_SHAPE u32[] parallel to ROUTE_STOPS: that pattern stop's
//!                        vertex index within its route's shape (NONE when the
//!                        route has none). Non-decreasing within a route, so the
//!                        device can slice `shape[vertex(board)..=vertex(alight)]`.
//!  23  FEED_MOTIS_PREFIX u32[feed_count] STRINGS offsets holding each feed's
//!                        Transitous id prefix (`us-ca-SF-bayarea`), or NONE when
//!                        the build did not know it. Interned, so the prefix costs
//!                        one pool entry shared by every stop in that feed.
//!  24  STOP_GTFS_ID    u32[stop_count] STRINGS offsets holding each stop's raw
//!                        GTFS `stop_id`. Joined to the prefix with `_` to form a
//!                        MOTIS stop id. Kept separate from `StopRec.code_off`,
//!                        which falls back to `stop_id` only when `stop_code` is
//!                        blank and so cannot be relied on.
//!  25  ROUTE_TRIP_RECS v6. Fixed-stride TripRec[trip_count] = { u32 start_time,
//!                        u32 profile_id, u32 service_idx, u32 headsign_off },
//!                        each route's trips contiguous and in start-time order.
//!                        The same trips as ROUTE_TRIPS, which is still written:
//!                        the device reader's version window accepts packs that
//!                        only have the varint stream, so both views ship.
//!                        A trip is addressable by index here, which is the point -
//!                        the varint stream has to be decoded from a route's first
//!                        trip, so a scan that breaks early still paid to decode
//!                        every trip of the route before it started.
//!  26  ROUTE_TRIP_OFF  v6. u32[route_count + 1] CSR prefix: route i's trips are
//!                        ROUTE_TRIP_RECS[off[i]..off[i+1]]. Disagreeing with
//!                        RouteRec.n_trips makes the reader fall back to the
//!                        varint stream rather than index past the table.

use crate::gtfs;
use crate::gtfs::{parse_gtfs_date, Csv, Shape};
use crate::shapes;
use std::collections::HashMap;
use std::io::Write;
use std::path::Path;

pub const MAGIC: u32 = 0x5452_4958; // "TRIX"
pub const VERSION: u32 = 6;
/// Oldest version the device reader still accepts, mirrored from
/// `maps/src/main/rust/src/transit.rs` so the host reader agrees on the range.
pub const VERSION_MIN: u32 = 3;
pub const NONE: u32 = 0xFFFF_FFFF;
pub const SECTION_COUNT: usize = 27;
pub const HEADER_LEN: usize = 80;

const MAX_TRANSFER_M: f64 = 400.0;
const WALK_SPEED_M_S: f64 = 1.33;
const CELL_DEG: f64 = 0.004; // ~450 m latitude buckets for the transfer grid
const MAX_TRANSFERS_PER_STOP: usize = 16;

/// Spatial-grid cell size for the on-device nearest/bbox index (degrees).
/// ~2.2 km at the equator; access/egress (≤1 km) searches ±1 cell.
const GRID_CELL_DEG: f64 = 0.02;

// --- varint (unsigned LEB128) ---

fn write_uvarint(v: &mut Vec<u8>, mut x: u64) {
    loop {
        let b = (x & 0x7f) as u8;
        x >>= 7;
        if x != 0 {
            v.push(b | 0x80);
        } else {
            v.push(b);
            break;
        }
    }
}

/// FNV-1a, 64-bit. Only ever used to *find* dedup candidates, which are then
/// confirmed byte-for-byte against the buffer that already holds them — so a
/// collision costs a few duplicated bytes and never a wrong merge.
fn fnv1a(bytes: &[u8]) -> u64 {
    fnv1a_mix(0xcbf2_9ce4_8422_2325, bytes)
}

/// Continue an FNV-1a hash, for keys built from more than one piece.
fn fnv1a_mix(mut h: u64, bytes: &[u8]) -> u64 {
    for &b in bytes {
        h ^= b as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

/// Interning string pool producing byte offsets into the STRINGS section.
///
/// The pool does **not** keep a copy of what it interns: `bytes` is the only
/// copy, and the index is `hash -> offsets`, each candidate confirmed by
/// comparing it against `bytes`. On a world pack the second copy cost several
/// gigabytes on its own.
struct StringPool {
    bytes: Vec<u8>,
    by_hash: HashMap<u64, Vec<u32>>,
    /// Set when the pool would pass the 4 GiB that a u32 STRINGS offset can
    /// address. Checked once before the pack is assembled: silently wrapping
    /// here produces a pack the device will happily mmap and mis-read.
    overflowed: bool,
}

impl StringPool {
    fn new() -> StringPool {
        // Byte 0 is a lone NUL so offset 0 is a valid empty string, keeping
        // NONE (0xFFFFFFFF) unambiguous.
        StringPool { bytes: vec![0], by_hash: HashMap::new(), overflowed: false }
    }

    fn intern(&mut self, s: &str) -> u32 {
        if s.is_empty() {
            return NONE;
        }
        let h = fnv1a(s.as_bytes());
        if let Some(off) =
            self.by_hash.get(&h).and_then(|offs| {
                offs.iter().copied().find(|&off| self.matches_at(off, s))
            })
        {
            return off;
        }
        // `+ 1` for the terminating NUL; the last valid offset must still be a
        // u32, and NONE is reserved.
        if self.bytes.len() + s.len() + 1 >= NONE as usize {
            self.overflowed = true;
            return NONE;
        }
        let off = self.bytes.len() as u32;
        self.bytes.extend_from_slice(s.as_bytes());
        self.bytes.push(0);
        self.by_hash.entry(h).or_default().push(off);
        off
    }

    /// Whether the interned string at `off` is exactly `s`. The NUL check is
    /// what stops `s` matching a prefix of a longer entry.
    fn matches_at(&self, off: u32, s: &str) -> bool {
        let start = off as usize;
        let end = start + s.len();
        end < self.bytes.len()
            && self.bytes[end] == 0
            && &self.bytes[start..end] == s.as_bytes()
    }
}

/// The PROFILES section plus its dedup index: run-time shapes are shared by
/// every trip that runs them, which is what removed v1's per-trip stop-time
/// table. Like [`StringPool`], the index is `hash -> ids` and holds no second
/// copy of the bodies.
struct ProfileTable {
    bytes: Vec<u8>,
    /// Byte offset into `bytes` per profile id.
    offsets: Vec<u32>,
    by_hash: HashMap<u64, Vec<u32>>,
    /// Reused across trips so encoding one does not allocate.
    scratch: Vec<u8>,
}

impl ProfileTable {
    fn new() -> ProfileTable {
        ProfileTable {
            bytes: Vec::new(),
            offsets: Vec::new(),
            by_hash: HashMap::new(),
            scratch: Vec::new(),
        }
    }

    /// Encode a trip's `(arr, dep)` sequence as a profile body and return its id,
    /// reusing an identical existing profile.
    fn intern(&mut self, sts: &[(u32, u32)]) -> Result<u32, String> {
        let n = sts.len();
        self.scratch.clear();
        write_uvarint(&mut self.scratch, n as u64);
        let (arr0, dep0) = sts[0];
        write_uvarint(&mut self.scratch, dep0.saturating_sub(arr0) as u64);
        let mut prev_dep = dep0;
        for &(arr, dep) in &sts[1..] {
            write_uvarint(&mut self.scratch, arr.saturating_sub(prev_dep) as u64);
            write_uvarint(&mut self.scratch, dep.saturating_sub(arr) as u64);
            prev_dep = dep;
        }
        let h = fnv1a(&self.scratch);
        if let Some(id) = self.by_hash.get(&h).and_then(|ids| {
            ids.iter().copied().find(|&id| {
                let start = self.offsets[id as usize] as usize;
                self.bytes[start..].starts_with(&self.scratch)
            })
        }) {
            return Ok(id);
        }
        // PROFILES_IDX entries are u32 byte offsets into PROFILES.
        let off = u32::try_from(self.bytes.len()).map_err(|_| {
            "PROFILES exceeded 4 GiB, which a u32 PROFILES_IDX offset cannot address"
                .to_string()
        })?;
        let id = self.offsets.len() as u32;
        self.offsets.push(off);
        self.bytes.extend_from_slice(&self.scratch);
        self.by_hash.entry(h).or_default().push(id);
        Ok(id)
    }
}

/// The SHAPE_COORDS section plus its dedup index, so routes producing identical
/// geometry share one blob. Blobs are self-delimiting, so a candidate is
/// confirmed by a prefix comparison at its offset.
struct ShapeBlobs {
    bytes: Vec<u8>,
    by_hash: HashMap<u64, Vec<u32>>,
}

impl ShapeBlobs {
    fn new() -> ShapeBlobs {
        ShapeBlobs { bytes: Vec::new(), by_hash: HashMap::new() }
    }

    fn intern(&mut self, blob: &[u8]) -> Result<u32, String> {
        let h = fnv1a(blob);
        if let Some(off) = self.by_hash.get(&h).and_then(|offs| {
            offs.iter().copied().find(|&off| self.bytes[off as usize..].starts_with(blob))
        }) {
            return Ok(off);
        }
        // ROUTE_SHAPE_IDX holds u32 byte offsets and reserves NONE, so the
        // section itself has to stay below that.
        if self.bytes.len() >= NONE as usize {
            return Err(
                "SHAPE_COORDS exceeded the 4 GiB a u32 ROUTE_SHAPE_IDX offset can address"
                    .to_string(),
            );
        }
        let off = self.bytes.len() as u32;
        self.bytes.extend_from_slice(blob);
        self.by_hash.entry(h).or_default().push(off);
        Ok(off)
    }
}

/// One `stop_times.txt` row reduced to what the index needs: 24 bytes, against
/// the ~400 a `Csv` row costs and the 40 an `Option<f64>` dist and `i64`
/// sequence used to. This is the hot struct — a world corpus has billions.
struct StopTime {
    seq: u32,
    stop_idx: u32,
    arr: u32,
    dep: u32,
    /// `shape_dist_traveled` in the feed's own units, or NaN when absent. An
    /// ordering key only; see [`Shape::dist`] for why it is still `f64`.
    dist: f64,
}

/// One input GTFS feed (already parsed). Multiple feeds merge into one pack;
/// their GTFS ids are namespaced by feed so they never collide.
///
/// Holding `stop_times.txt` as a [`Csv`] costs roughly 10x the bytes of the file,
/// so the host tool uses [`FeedDir`] instead and lets the builder stream it.
pub struct FeedInput<'a> {
    pub name: String,
    /// Transitous id prefix for this feed (`us-ca-SF-bayarea`), used to compose
    /// MOTIS stop ids on device. Empty when the caller does not know it (the
    /// world build mangles feed names), which writes NONE and makes the device
    /// accessor return `None`.
    pub motis_prefix: String,
    pub stops: &'a Csv,
    pub routes: &'a Csv,
    pub trips: &'a Csv,
    pub stop_times: &'a Csv,
    pub calendar: Option<&'a Csv>,
    pub calendar_dates: Option<&'a Csv>,
    /// `agency.txt`, read only for `agency_timezone`. GTFS permits several
    /// agencies per feed; we take row 0 and assume one timezone per feed.
    pub agency: Option<&'a Csv>,
    /// `shapes.txt` keyed by `shape_id`. Optional: without it every route in this
    /// feed falls back to stop-to-stop ride geometry on device.
    pub shapes: Option<&'a HashMap<String, Shape>>,
}

/// The same feed, but with `stop_times.txt` left on disk to be streamed out of
/// `dir` rather than parsed into a [`Csv`] first. Everything else is small enough
/// to hold.
pub struct FeedDir<'a> {
    pub name: String,
    pub motis_prefix: String,
    /// The unzipped GTFS directory holding `stop_times.txt`.
    pub dir: &'a Path,
    pub stops: &'a Csv,
    pub routes: &'a Csv,
    pub trips: &'a Csv,
    pub calendar: Option<&'a Csv>,
    pub calendar_dates: Option<&'a Csv>,
    pub agency: Option<&'a Csv>,
    pub shapes: Option<&'a HashMap<String, Shape>>,
}

/// Where a feed's `stop_times.txt` comes from.
enum StopTimesSource<'a> {
    Table(&'a Csv),
    Dir(&'a Path),
}

/// The parts of a feed [`IndexBuilder::add`] needs, from either input form.
struct FeedView<'a> {
    name: &'a str,
    motis_prefix: &'a str,
    stops: &'a Csv,
    routes: &'a Csv,
    trips: &'a Csv,
    calendar: Option<&'a Csv>,
    calendar_dates: Option<&'a Csv>,
    agency: Option<&'a Csv>,
    shapes: Option<&'a HashMap<String, Shape>>,
    stop_times: StopTimesSource<'a>,
}

impl<'a> From<&'a FeedInput<'a>> for FeedView<'a> {
    fn from(f: &'a FeedInput<'a>) -> FeedView<'a> {
        FeedView {
            name: &f.name,
            motis_prefix: &f.motis_prefix,
            stops: f.stops,
            routes: f.routes,
            trips: f.trips,
            calendar: f.calendar,
            calendar_dates: f.calendar_dates,
            agency: f.agency,
            shapes: f.shapes,
            stop_times: StopTimesSource::Table(f.stop_times),
        }
    }
}

include!("index_part1.rs");
include!("index_part2.rs");
include!("index_part3.rs");
include!("index_part4.rs");
include!("index_part5.rs");
include!("index_part6.rs");