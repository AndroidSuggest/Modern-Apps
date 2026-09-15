//! Decode COP90 (Copernicus 90 m DSM) GeoTIFF tiles into a per-map-tile `u16`
//! heightmap grid dataset — the build half of WS-G's 3D terrain relief and the
//! second of the two v6 `.mamaps` side tables.
//!
//! # What it does
//!
//! Given the COP90 tile directory (synced into `inputs/cop90/` — a `COP90_hh`
//! folder of `Copernicus_DSM_30_*_DEM.tif` Float32 tiles plus the `COP90_hh.vrt`
//! mosaic) and a bounding box, it produces one small square `u16` grid per
//! output map tile by sampling the DEM at each grid point. A sample is metres
//! above sea level **biased by 32768** — `stored = round(metres) + 32768` — the
//! same convention [`tilecodec::mamaps::body::Heightmap`] stores on the wire.
//!
//! Each 1°×1° tile file is located by flooring the sample lon/lat to its
//! south-west corner (`Copernicus_DSM_30_N00_00_E006_00_DEM.tif` covers
//! lat 0..1, lon 6..7) and sampled nearest-neighbour through its own GeoTIFF
//! tags (pixel scale + tiepoint), so high-latitude tiles with fewer columns
//! resample correctly. Missing files, nodata cells and NaNs read as sea level.
//!
//! # Why the network is not here
//!
//! The build scripts own network I/O for every stage; this crate does the
//! bytes-to-bytes work alone (GeoTIFF decode, height resample, downsample),
//! which is why its only raster dependency is the pure-Rust `tiff` crate —
//! no native GDAL.
//!
//! # Output
//!
//! A single `.mdem` dataset: a header, then per non-empty tile a pmtiles tile id
//! and its `dim * dim` samples, row-major from the tile's top-left. Ocean/flat
//! tiles are omitted with `--skip-flat`, matching the wire format's rule that an
//! elevation-free tile carries no section. Wiring this dataset into the
//! archive's `BODY_FLAG_HEIGHTMAP` section is a small follow-up step (or WS-G's,
//! at render integration); this tool produces the grids the format is already
//! able to carry and round-trip.

use std::collections::HashMap;
use std::f64::consts::PI;
use std::path::{Path, PathBuf};

use tilecodec::pmtiles::tile_id;

mod geotags;
use geotags::read_geotags;

mod progress;
use progress::fmt_hms;

const OUT_MAGIC: &[u8; 4] = b"MDEM";
const OUT_VERSION: u8 = 1;

/// Sea level in the biased `u16` grid: `round(0 m) + 32768`.
const SEA_LEVEL: u16 = 32768;

/// Default DEM input, relative to the repo's `scripts/maps/` working dir.
const DEFAULT_INPUT: &str = "inputs/cop90/COP90_hh.vrt";

fn main() {
    if let Err(e) = run() {
        eprintln!("dem_ingest: {e}");
        std::process::exit(1);
    }
}

struct Args {
    dem_dir: PathBuf,
    out: PathBuf,
    out_zoom: u8,
    dim: u16,
    bbox: (f64, f64, f64, f64),
    skip_flat: bool,
}

fn run() -> Result<(), String> {
    let args = parse_args()?;
    if args.dim < 2 {
        return Err("--dim must be at least 2".to_string());
    }
    let dem_dir = resolve_dem_dir(&args.dem_dir)?;
    let n = 1u64 << args.out_zoom;
    // The output tile range the bbox covers, clamped to the world.
    let (min_lon, min_lat, max_lon, max_lat) = args.bbox;
    let x0 = lon_to_tile_x(min_lon, args.out_zoom).floor().max(0.0) as u64;
    let x1 = lon_to_tile_x(max_lon, args.out_zoom).ceil().min(n as f64) as u64;
    // Latitude runs the other way: max_lat is the smaller tile y.
    let y0 = lat_to_tile_y(max_lat, args.out_zoom).floor().max(0.0) as u64;
    let y1 = lat_to_tile_y(min_lat, args.out_zoom).ceil().min(n as f64) as u64;
    if x1 <= x0 || y1 <= y0 {
        return Err(format!(
            "the bbox {min_lon},{min_lat},{max_lon},{max_lat} covers no z{} tiles",
            args.out_zoom
        ));
    }

    // Decoded DEM tiles, cached across output tiles: an output tile's neighbours
    // share DEM tiles, and re-inflating a GeoTIFF per grid point would dominate.
    // `None` marks a 1° cell with no tile file on disk.
    let mut dem_cache: HashMap<(i32, i32), Option<CopTile>> = HashMap::new();
    let mut tiles: Vec<(u64, Vec<u16>)> = Vec::new();
    let mut missing = 0u64;
    let total = (y1 - y0) * (x1 - x0);
    let mut done = 0u64;
    let start = std::time::Instant::now();

    for oy in y0..y1 {
        for ox in x0..x1 {
            match sample_tile(&args, &dem_dir, ox, oy, &mut dem_cache)? {
                Some(grid) => {
                    if args.skip_flat && is_flat(&grid) {
                        continue;
                    }
                    tiles.push((tile_id(args.out_zoom, ox, oy), grid));
                }
                None => missing += 1,
            }
            done += 1;
            if done % 10000 == 0 || done == total {
                let el = start.elapsed().as_secs_f64();
                let rate = done as f64 / el.max(1e-6);
                eprint!(
                    "\rdem_ingest: {done}/{total} ({:.1}%) elapsed {} eta {} {:.1} tiles/s",
                    done as f64 / total as f64 * 100.0,
                    fmt_hms(el),
                    fmt_hms((total - done) as f64 / rate.max(1e-6)),
                    rate
                );
                let _ = std::io::Write::flush(&mut std::io::stderr());
            }
        }
    }
    eprintln!();

    // Ascending by tile id, so the dataset is deterministic and a merge into an
    // archive is a straight join in tile order.
    tiles.sort_by_key(|(id, _)| *id);
    write_dataset(&args, &tiles)?;
    println!(
        "dem_ingest: wrote {} tile(s) at z{} ({}x{}) to {} ({} output tile(s) had no DEM coverage)",
        tiles.len(),
        args.out_zoom,
        args.dim,
        args.dim,
        args.out.display(),
        missing,
    );
    Ok(())
}

/// One decoded COP90 tile: Float32 heights plus the geo transform from its own
/// tags, so sampling needs no global mosaic math.
struct CopTile {
    width: u32,
    height: u32,
    /// Longitude of the pixel (0,0) centre's west edge; latitude of its north edge.
    west: f64,
    north: f64,
    pixel_w: f64,
    pixel_h: f64,
    nodata: Option<f64>,
    data: Vec<f32>,
}

/// Sample one output tile's grid, or `None` when no DEM tile under it exists.
fn sample_tile(
    args: &Args,
    dem_dir: &Path,
    ox: u64,
    oy: u64,
    cache: &mut HashMap<(i32, i32), Option<CopTile>>,
) -> Result<Option<Vec<u16>>, String> {
    let dim = args.dim as usize;
    let mut grid = Vec::with_capacity(dim * dim);
    let mut any_data = false;
    for row in 0..dim {
        // Grid point fractional position within the output tile, top-left origin.
        let fy = oy as f64 + row as f64 / (dim as f64 - 1.0);
        let lat = tile_y_to_lat(fy, args.out_zoom);
        for col in 0..dim {
            let fx = ox as f64 + col as f64 / (dim as f64 - 1.0);
            let lon = tile_x_to_lon(fx, args.out_zoom);
            // The 1° COP90 cell this lon/lat lands in, by its south-west corner.
            let key = (lon.floor() as i32, lat.floor() as i32);
            let sample = match dem_tile(dem_dir, key, cache)? {
                Some(tile) => {
                    any_data = true;
                    tile.sample(lon, lat)
                }
                // No DEM here: sea level. A tile entirely over missing DEM is dropped below.
                None => SEA_LEVEL,
            };
            grid.push(sample);
        }
    }
    Ok(any_data.then_some(grid))
}

impl CopTile {
    /// Nearest-neighbour sample as a biased `u16`; out-of-range, nodata and NaN
    /// cells read as sea level.
    fn sample(&self, lon: f64, lat: f64) -> u16 {
        let col = ((lon - self.west) / self.pixel_w).floor() as i64;
        let row = ((self.north - lat) / self.pixel_h).floor() as i64;
        if col < 0 || row < 0 || col >= self.width as i64 || row >= self.height as i64 {
            return SEA_LEVEL;
        }
        let v = self.data[row as usize * self.width as usize + col as usize] as f64;
        if !v.is_finite() || self.nodata == Some(v) {
            return SEA_LEVEL;
        }
        elevation_to_stored(v)
    }
}

/// A decoded COP90 tile's Float32 heights, or `None` if the file is absent.
fn dem_tile<'a>(
    dem_dir: &Path,
    key: (i32, i32),
    cache: &'a mut HashMap<(i32, i32), Option<CopTile>>,
) -> Result<&'a Option<CopTile>, String> {
    if !cache.contains_key(&key) {
        let path = dem_dir.join(tile_filename(key.0, key.1));
        let decoded = if path.is_file() {
            Some(decode_cop_tile(&path, key).map_err(|e| format!("decoding {}: {e}", path.display()))?)
        } else {
            None
        };
        cache.insert(key, decoded);
    }
    Ok(cache.get(&key).expect("just inserted"))
}

/// The COP90 file covering the 1° cell with south-west corner (`tlon`, `tlat`):
/// `Copernicus_DSM_30_N00_00_E006_00_DEM.tif` covers lat 0..1, lon 6..7.
fn tile_filename(tlon: i32, tlat: i32) -> String {
    let lat_part = if tlat >= 0 {
        format!("N{tlat:02}")
    } else {
        format!("S{:02}", -tlat)
    };
    let lon_part = if tlon >= 0 {
        format!("E{tlon:03}")
    } else {
        format!("W{:03}", -tlon)
    };
    format!("Copernicus_DSM_30_{lat_part}_00_{lon_part}_00_DEM.tif")
}

/// Decode one COP90 tile: Float32 pixels via the `tiff` crate, geo transform
/// from the file's own ModelPixelScale/ModelTiepoint tags (falling back to the
/// 1° filename grid when the tags are absent).
fn decode_cop_tile(path: &Path, key: (i32, i32)) -> Result<CopTile, String> {
    let file = std::fs::File::open(path).map_err(|e| format!("opening: {e}"))?;
    let mut decoder = tiff::decoder::Decoder::new(file).map_err(|e| format!("tiff header: {e}"))?;
    let (width, height) = decoder.dimensions().map_err(|e| format!("tiff dimensions: {e}"))?;
    if width == 0 || height == 0 {
        return Err("a COP90 tile has no pixels".to_string());
    }
    let data = match decoder.read_image().map_err(|e| format!("tiff pixels: {e}"))? {
        tiff::decoder::DecodingResult::F32(v) => v,
        _ => return Err("a COP90 tile is Float32, got another sample type".to_string()),
    };
    if data.len() != width as usize * height as usize {
        return Err(format!(
            "a COP90 tile has {}x{} pixels but {} samples",
            width,
            height,
            data.len()
        ));
    }
    // Filename grid fallback: the 1° cell the name promises.
    let geo = read_geotags(path).unwrap_or_default();
    Ok(CopTile {
        width,
        height,
        west: geo.west.unwrap_or(key.0 as f64),
        north: geo.north.unwrap_or((key.1 + 1) as f64),
        pixel_w: geo.pixel_w.unwrap_or(1.0 / width as f64),
        pixel_h: geo.pixel_h.unwrap_or(1.0 / height as f64),
        nodata: geo.nodata,
        data,
    })
}

/// Biased `u16` for metres: `round(m) + 32768`; non-finite reads as sea level, clamps.
fn elevation_to_stored(m: f64) -> u16 {
    if !m.is_finite() { return SEA_LEVEL; }
    (m.round() + 32768.0).clamp(0.0, u16::MAX as f64) as u16
}

/// A grid is "flat" when every sample is within ±2 m of one level; omitted as relief-free.
fn is_flat(grid: &[u16]) -> bool {
    let (mut lo, mut hi) = (u16::MAX, u16::MIN);
    for &s in grid { lo = lo.min(s); hi = hi.max(s); }
    hi.saturating_sub(lo) <= 2
}

/// Accept either the tile directory or the `.vrt` mosaic path: a `.vrt` file
/// resolves to the `COP90_hh` sibling its sources live in.
fn resolve_dem_dir(input: &Path) -> Result<PathBuf, String> {
    if input.is_dir() {
        return Ok(input.to_path_buf());
    }
    if input.is_file() {
        if let Some(parent) = input.parent() {
            let sibling = parent.join("COP90_hh");
            if sibling.is_dir() {
                return Ok(sibling);
            }
            return Ok(parent.to_path_buf());
        }
    }
    Err(format!(
        "no COP90 tiles at {} (want the COP90_hh directory or the COP90_hh.vrt mosaic)",
        input.display()
    ))
}

// --- slippy / web-mercator tile math ---

fn lon_to_tile_x(lon: f64, z: u8) -> f64 { (lon + 180.0) / 360.0 * (1u64 << z) as f64 }

fn lat_to_tile_y(lat: f64, z: u8) -> f64 {
    let r = lat.clamp(-85.05112878, 85.05112878).to_radians();
    (1.0 - (r.tan() + 1.0 / r.cos()).ln() / PI) / 2.0 * (1u64 << z) as f64
}

fn tile_x_to_lon(x: f64, z: u8) -> f64 { x / (1u64 << z) as f64 * 360.0 - 180.0 }

fn tile_y_to_lat(y: f64, z: u8) -> f64 {
    let n = PI * (1.0 - 2.0 * y / (1u64 << z) as f64);
    n.sinh().atan().to_degrees()
}

// --- output ---

fn write_dataset(args: &Args, tiles: &[(u64, Vec<u16>)]) -> Result<(), String> {
    let mut out = Vec::with_capacity(16 + tiles.len() * (8 + args.dim as usize * args.dim as usize * 2));
    out.extend_from_slice(OUT_MAGIC);
    out.push(OUT_VERSION);
    out.push(args.out_zoom);
    out.extend_from_slice(&args.dim.to_le_bytes());
    out.extend_from_slice(&(tiles.len() as u32).to_le_bytes());
    for (id, grid) in tiles {
        out.extend_from_slice(&id.to_le_bytes());
        for &s in grid {
            out.extend_from_slice(&s.to_le_bytes());
        }
    }
    std::fs::write(&args.out, &out).map_err(|e| format!("writing {}: {e}", args.out.display()))
}

// --- CLI ---

fn parse_args() -> Result<Args, String> {
    let mut tiles_dir: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut out_zoom = 14u8;
    let mut dim = 17u16;
    let mut bbox: Option<(f64, f64, f64, f64)> = None;
    let mut skip_flat = false;
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            // The COP90 tile directory, or the COP90_hh.vrt mosaic (the default);
            // kept under the old flag name so existing invocations still parse.
            "--tiles-dir" => tiles_dir = Some(PathBuf::from(next(&mut it, "--tiles-dir")?)),
            "--out" => out = Some(PathBuf::from(next(&mut it, "--out")?)),
            "--out-zoom" => out_zoom = next(&mut it, "--out-zoom")?.parse().map_err(|_| "--out-zoom must be a number")?,
            // Accepted for compatibility; COP90 samples at its native ~90 m everywhere.
            "--dem-zoom" => {
                next(&mut it, "--dem-zoom")?;
            }
            "--dim" => dim = next(&mut it, "--dim")?.parse().map_err(|_| "--dim must be a number")?,
            "--bbox" => bbox = Some(parse_bbox(&next(&mut it, "--bbox")?)?),
            "--skip-flat" => skip_flat = true,
            "-h" | "--help" => {
                print_help();
                std::process::exit(0);
            }
            other => return Err(format!("unknown argument {other} (try --help)")),
        }
    }
    Ok(Args {
        dem_dir: tiles_dir.unwrap_or_else(|| PathBuf::from(DEFAULT_INPUT)),
        out: out.ok_or("--out is required")?,
        out_zoom,
        dim,
        bbox: bbox.ok_or("--bbox is required")?,
        skip_flat,
    })
}

fn next(it: &mut impl Iterator<Item = String>, flag: &str) -> Result<String, String> {
    it.next().ok_or_else(|| format!("{flag} needs a value"))
}

fn parse_bbox(s: &str) -> Result<(f64, f64, f64, f64), String> {
    let parts: Vec<f64> = s
        .split(',')
        .map(|p| p.trim().parse::<f64>().map_err(|_| format!("bad bbox component `{p}`")))
        .collect::<Result<_, _>>()?;
    match parts.as_slice() {
        [min_lon, min_lat, max_lon, max_lat] => Ok((*min_lon, *min_lat, *max_lon, *max_lat)),
        _ => Err("--bbox is minlon,minlat,maxlon,maxlat".to_string()),
    }
}

fn print_help() {
    println!(
        "dem_ingest --bbox minlon,minlat,maxlon,maxlat --out FILE [--tiles-dir DIR]\n\
         \n\
         Decode COP90 GeoTIFF tiles (COP90_hh/Copernicus_DSM_30_*_DEM.tif, Float32) into a\n\
         per-map-tile u16 heightmap grid dataset (metres + 32768 bias).\n\
         \n\
         Options:\n\
         \x20 --tiles-dir DIR   COP90_hh tile directory or the COP90_hh.vrt mosaic\n\
         \x20                   (default {DEFAULT_INPUT})\n\
         \x20 --bbox BOX        minlon,minlat,maxlon,maxlat to cover (required)\n\
         \x20 --out FILE        the .mdem dataset to write (required)\n\
         \x20 --out-zoom Z      map tile zoom to sample a grid per (default 14)\n\
         \x20 --dem-zoom Z      accepted for compatibility; COP90 is ~90 m everywhere\n\
         \x20 --dim N           grid side length, N*N samples per tile (default 17)\n\
         \x20 --skip-flat       omit tiles with no relief (ocean/flat), matching the wire format\n"
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn elevation_bias_puts_sea_level_at_32768() {
        assert_eq!(elevation_to_stored(0.0), 32768);
        assert_eq!(elevation_to_stored(1.0), 32769);
        // Sub-metre precision rounds to the nearest metre.
        assert_eq!(elevation_to_stored(0.6), 32769);
        // The Dead Sea shore, ~-430 m, sits below the bias.
        assert_eq!(elevation_to_stored(-430.0), 32338);
        // Non-finite and out-of-range input reads as sea level / clamps.
        assert_eq!(elevation_to_stored(f64::NAN), 32768);
        assert_eq!(elevation_to_stored(f64::INFINITY), 32768);
        assert_eq!(elevation_to_stored(-40000.0), 0);
    }

    #[test]
    fn tile_filenames_cover_all_hemispheres() {
        assert_eq!(tile_filename(6, 0), "Copernicus_DSM_30_N00_00_E006_00_DEM.tif");
        assert_eq!(tile_filename(-70, -38), "Copernicus_DSM_30_S38_00_W070_00_DEM.tif");
        assert_eq!(tile_filename(138, 73), "Copernicus_DSM_30_N73_00_E138_00_DEM.tif");
        assert_eq!(tile_filename(-88, -89), "Copernicus_DSM_30_S89_00_W088_00_DEM.tif");
    }

    #[test]
    fn the_slippy_math_round_trips_a_tile_corner() {
        // A tile's top-left corner maps to a lon/lat that maps back to the tile origin.
        for z in [0u8, 8, 12, 14] {
            let (x, y) = (5u64.min((1 << z) - 1), 9u64.min((1 << z) - 1));
            let lon = tile_x_to_lon(x as f64, z);
            let lat = tile_y_to_lat(y as f64, z);
            assert!((lon_to_tile_x(lon, z) - x as f64).abs() < 1e-6, "z{z} lon round trip");
            assert!((lat_to_tile_y(lat, z) - y as f64).abs() < 1e-6, "z{z} lat round trip");
        }
    }

    #[test]
    fn cop_tile_samples_nearest_neighbour_and_sea_level() {
        let tile = CopTile {
            width: 2,
            height: 2,
            west: 6.0,
            north: 1.0,
            pixel_w: 0.5,
            pixel_h: 0.5,
            nodata: Some(-32768.0),
            data: vec![100.0, -32768.0, f32::NAN, -50.0],
        };
        assert_eq!(tile.sample(6.1, 0.9), 32868);
        assert_eq!(tile.sample(6.6, 0.9), 32768, "nodata reads as sea level");
        assert_eq!(tile.sample(6.1, 0.4), 32768, "NaN reads as sea level");
        assert_eq!(tile.sample(6.6, 0.4), 32718);
        assert_eq!(tile.sample(99.0, 99.0), 32768, "out of range reads as sea level");
    }

    #[test]
    fn is_flat_omits_a_level_grid_but_keeps_relief() {
        assert!(is_flat(&vec![32768; 289]), "an all-sea-level grid is flat");
        let mut relief = vec![32768u16; 289];
        relief[100] = 32768 + 50;
        assert!(!is_flat(&relief), "50 m of relief is not flat");
    }
}
