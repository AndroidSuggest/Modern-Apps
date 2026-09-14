//! `mamaps_build` — one `.osm.pbf` in, one `.mamaps` archive out.
//!
//! The generator this project exists to build. Today it produces `water` and `buildings`; `roads`,
//! `boundaries`, `landcover`, `landuse` and `earth` follow, and the shape does not change when they
//! do because every layer is one module under [`schema`].
//!
//! ```text
//! mamaps_build --input california.osm.pbf --out california.mamaps
//!              [--layers water,buildings] [--min-zoom 0] [--max-zoom 14]
//!              [--simplification 1.0] [--build-id N] [--report FILE]
//!              [--keep-store] [--reuse-store]
//!
//! `--keep-store` leaves the feature spill and writes a small index beside it; `--reuse-store` then
//! skips stage A entirely and tiles from that spill. Stage A is 17.6 minutes of a north-america
//! build and identical every run for the same input and layer set, so the pair is what makes
//! iterating on the tiler affordable. The index records the source's length and mtime plus the layer
//! selection, and reuse refuses a spill that does not match — a stale spill would otherwise produce
//! an archive that looks fine and is missing most of the world.
//! ```
//!
//! # Why not through the existing tiler
//!
//! `tile_build` already turns GeoJSON-seq into PMTiles, and routing through it would have been less
//! new code. But it would also mean encoding to MVT and decoding again, which loses exactly the
//! attributes this format was built to carry: a road's `is_bridge`, a boundary's numeric admin
//! level, a ring's stated winding. So the geometry is reused — projection, significance,
//! simplification, tile bisection, clipping, all of `tile_build` — and only the last step, turning a
//! clipped shape into bytes, is this crate's.

use std::path::PathBuf;
use std::process::ExitCode;

mod coalesce;
mod coalesce_extra;
mod corridor;
mod dem;
mod extract;
mod lanefill;
mod layercodec;
mod rings;
mod shapefile;
mod schema;
mod store;
mod tiler;
mod tilespill;

/// The allocator, replaced because the default one was three quarters of the build.
///
/// A build tool has no business caring which allocator it gets, and this one did not until the
/// encode pass was measured against the machine it runs on: **1358 s of worker CPU against 180 s of
/// wall on a 32-core, 64-thread box** — a speed-up of 7.5 where the pool can give 64. Nothing in
/// that pass is shared. [`rings::normalise`], `body::serialize_into` and DEFLATE each read one tile
/// and write one tile, there is no lock and no atomic in the path with `MAPS_TIMING` off, and the
/// memory traffic works out at a few hundred MB/s against eight channels of DDR5. The only thing
/// all sixty-four threads still contend on is the heap.
///
/// And they hit it hard. `normalise` allocates a `Vec` per polygon group, another for its exterior
/// and one per hole it keeps, on the way to rebuilding a layer's arenas; z14 of a us-west build is
/// 44.4 M features across 943,401 tiles, which is tens of millions of allocate/free pairs on
/// Windows' process heap in about two and a half minutes.
///
/// Swapping it out took the tiling stage from **351 s to 110 s** on the same spill, for a
/// byte-identical archive:
///
/// | phase | default heap | mimalloc |
/// |---|---|---|
/// | map | 125.1 s | 72.9 s |
/// | merge | 33.2 s | 9.6 s |
/// | encode | 180.3 s | 21.6 s |
/// | append | 12.4 s | 3.3 s |
/// | *encode CPU: stage C* | *452.6 s* | *54.4 s* |
/// | *encode CPU: serialise* | *355.9 s* | *5.4 s* |
/// | *encode CPU: deflate* | *550.0 s* | *181.1 s* |
///
/// Serialisation is the one that says what this really was. It is a memcpy into a reused
/// [`tilecodec::mamaps::body::Scratch`] and it cost 356 s of CPU; it now costs 5.4 s. That was never
/// encoding, it was the handful of buffer growths per tile going to `HeapAlloc` under sixty-four
/// threads of contention.
///
/// Two things this is not. It is not a substitute for allocating less — `normalise` should be
/// threading scratch buffers through rather than building a `Vec` per ring, and a cheaper allocator
/// only makes that less urgent. And it is not free: mimalloc holds freed pages back rather than
/// returning them promptly, which took peak RSS from 6.86 GB to 10.14 GB on the same build. That
/// trade is worth revisiting if memory becomes the binding constraint again.
///
/// It cannot change a byte of the output — an allocator decides *where*, never *what* — so it needed
/// no argument about ordering, only a measurement. The us-west archive hashes the same either way.
#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

/// The deepest zoom an archive is built to unless `--max-zoom` says otherwise.
///
/// Named rather than inlined because layer code is bounded by it: a layer's tiler-side `min_zoom`
/// past this lands its features in no tile at all, so [`schema::junction`] pins itself against
/// this constant and lowering it fails that test instead of silently emptying a layer.
pub const DEFAULT_MAX_ZOOM: u8 = 14;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut input: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut report: Option<PathBuf> = None;
    let mut keep_store = false;
    let mut reuse_store = false;
    let mut shared_table = false;
    let mut layers = schema::Layers::all();
    let mut min_zoom = 0u8;
    let mut max_zoom = DEFAULT_MAX_ZOOM;
    let mut simplification = tiler::DEFAULT_SIMPLIFICATION;
    let mut build_id: Option<u64> = None;
    let mut coastline: Option<PathBuf> = None;
    let mut transit_routes: Option<PathBuf> = None;
    let mut graph: Option<PathBuf> = None;
    let mut dem: Option<PathBuf> = None;

    let mut i = 1;
    while i < args.len() {
        let value = |name: &str| -> Result<String, String> {
            args.get(i + 1).cloned().ok_or_else(|| format!("{name} needs a value"))
        };
        let taken = match args[i].as_str() {
            "--input" => value("--input").map(|v| {
                input = Some(PathBuf::from(v));
                2
            }),
            "--out" => value("--out").map(|v| {
                out = Some(PathBuf::from(v));
                2
            }),
            "--report" => value("--report").map(|v| {
                report = Some(PathBuf::from(v));
                2
            }),
            "--keep-store" => {
                keep_store = true;
                Ok(1)
            }
            "--reuse-store" => {
                reuse_store = true;
                Ok(1)
            }
            "--shared-table" => {
                shared_table = true;
                Ok(1)
            }
            "--coastline" => value("--coastline").map(|v| {
                coastline = Some(PathBuf::from(v));
                2
            }),
            "--transit-routes" => value("--transit-routes").map(|v| {
                transit_routes = Some(PathBuf::from(v));
                2
            }),
            "--graph" => value("--graph").map(|v| {
                graph = Some(PathBuf::from(v));
                2
            }),
            "--dem" => value("--dem").map(|v| {
                dem = Some(PathBuf::from(v));
                2
            }),
            "--layers" => value("--layers").and_then(|v| {
                layers = schema::Layers::parse(&v)?;
                Ok(2)
            }),
            "--min-zoom" => value("--min-zoom").and_then(|v| {
                min_zoom = v.parse().map_err(|_| "--min-zoom must be a number".to_string())?;
                Ok(2)
            }),
            "--max-zoom" => value("--max-zoom").and_then(|v| {
                max_zoom = v.parse().map_err(|_| "--max-zoom must be a number".to_string())?;
                Ok(2)
            }),
            "--simplification" => value("--simplification").and_then(|v| {
                simplification =
                    v.parse().map_err(|_| "--simplification must be a number".to_string())?;
                Ok(2)
            }),
            "--build-id" => value("--build-id").and_then(|v| {
                build_id = Some(v.parse().map_err(|_| "--build-id must be a number".to_string())?);
                Ok(2)
            }),
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other => Err(format!("unexpected argument '{other}'")),
        };
        match taken {
            Ok(step) => i += step,
            Err(e) => {
                eprintln!("mamaps_build: {e}");
                usage();
                return ExitCode::from(2);
            }
        }
    }

    let (Some(input), Some(out)) = (input, out) else {
        usage();
        return ExitCode::from(2);
    };
    if min_zoom > max_zoom {
        eprintln!("mamaps_build: --min-zoom {min_zoom} is deeper than --max-zoom {max_zoom}");
        return ExitCode::from(2);
    }

    let settings = RunSettings {
        report,
        keep_store,
        reuse_store,
        shared_table,
        coastline,
        transit_routes,
        graph,
        dem,
        layers,
        min_zoom,
        max_zoom,
        simplification,
        build_id,
    };
    match run(&input, &out, &settings) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("mamaps_build: {e}");
            ExitCode::FAILURE
        }
    }
}

/// Everything a run needs beyond its input and output paths.
struct RunSettings {
    report: Option<PathBuf>,
    /// A prepared land polygon for `earth`'s mainland. Required whenever `earth` is being
    /// built — see [`check_coastline`].
    coastline: Option<PathBuf>,
    /// A prepared GTFS export for `transit`'s coloured rail lines. Without it the layer is empty:
    /// nothing in the `.osm.pbf` produces one.
    transit_routes: Option<PathBuf>,
    /// The v6 routing graph directory (`nodes.bin`/`edges.bin`/`intermediate.bin`/`metadata.bin`)
    /// for `traffic`'s per-component lines. Without it the layer is empty: its geometry is the
    /// graph, not the `.osm.pbf`.
    graph: Option<PathBuf>,
    /// The `.mdem` heightmap dataset `dem_ingest` produced (via `build_all.sh --dem`). Without it
    /// every tile's `heightmap` stays `None` and the archive is a valid v6 with no terrain grids;
    /// with it, each output tile carries the DEM grid sampled to its own z/x/y.
    dem: Option<PathBuf>,
    layers: schema::Layers,
    min_zoom: u8,
    max_zoom: u8,
    simplification: f64,
    build_id: Option<u64>,
    /// Keep the feature spill and write its index, so a later run can `--reuse-store`.
    keep_store: bool,
    /// Skip stage A and read the spill an earlier `--keep-store` run left behind.
    reuse_store: bool,
    /// Intern v8 shared-table logical rows while tiling; see [`tiler::Settings::shared_table`].
    /// Off by default, and off is byte-identical v7.
    shared_table: bool,
}

include!("main_part1.rs");
include!("main_part2.rs");