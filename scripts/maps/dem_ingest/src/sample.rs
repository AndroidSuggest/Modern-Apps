//! COP90 sampling: a bounded LRU of decoded 1° GeoTIFF tiles and the
//! nearest-neighbour resample that turns an output map tile's grid points into
//! biased `u16` heights. Decoding a tile is deterministic, so the cache only
//! affects speed — two threads with their own caches produce identical grids.

use std::collections::{HashMap, VecDeque};
use std::path::Path;

use crate::geotags::read_geotags;
use crate::tilemath::{tile_x_to_lon, tile_y_to_lat};

/// Sea level in the biased `u16` grid: `round(0 m) + 32768`.
pub const SEA_LEVEL: u16 = 32768;

/// Cap on decoded 1° COP90 tiles held at once (~6 MB each): 96 entries stay
/// well under a gigabyte while the id-order walk keeps neighbours close.
const DEM_CACHE_CAP: usize = 96;

/// One decoded COP90 tile: Float32 heights plus its geo transform from its own tags.
pub struct CopTile {
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

impl CopTile {
    /// Nearest-neighbour sample as a biased `u16`; out-of-range, nodata and NaN
    /// cells read as sea level.
    pub fn sample(&self, lon: f64, lat: f64) -> u16 {
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

/// Sample one output tile's grid, or `None` when no DEM tile under it exists.
pub fn sample_tile(
    dem_dir: &Path,
    ox: u64,
    oy: u64,
    out_zoom: u8,
    dim: usize,
    cache: &mut DemCache,
) -> Result<Option<Vec<u16>>, String> {
    let mut grid = Vec::with_capacity(dim * dim);
    let mut any_data = false;
    for row in 0..dim {
        // Grid point fractional position within the output tile, top-left origin.
        let fy = oy as f64 + row as f64 / (dim as f64 - 1.0);
        let lat = tile_y_to_lat(fy, out_zoom);
        for col in 0..dim {
            let fx = ox as f64 + col as f64 / (dim as f64 - 1.0);
            let lon = tile_x_to_lon(fx, out_zoom);
            // The 1° COP90 cell this lon/lat lands in, by its south-west corner.
            let key = (lon.floor() as i32, lat.floor() as i32);
            let sample = match cache.get_or_decode(dem_dir, key)? {
                Some(tile) => {
                    any_data = true;
                    tile.sample(lon, lat)
                }
                // No DEM here: sea level. A tile entirely over missing DEM is dropped by the caller.
                None => SEA_LEVEL,
            };
            grid.push(sample);
        }
    }
    Ok(any_data.then_some(grid))
}

/// Bounded LRU of decoded COP90 tiles (`None` = no tile file for that 1° cell).
pub struct DemCache {
    map: HashMap<(i32, i32), Option<CopTile>>,
    order: VecDeque<(i32, i32)>,
}

impl DemCache {
    pub fn new() -> Self {
        Self { map: HashMap::new(), order: VecDeque::new() }
    }

    pub fn get_or_decode(
        &mut self,
        dem_dir: &Path,
        key: (i32, i32),
    ) -> Result<&Option<CopTile>, String> {
        if !self.map.contains_key(&key) {
            let path = dem_dir.join(tile_filename(key.0, key.1));
            let decoded = if path.is_file() {
                Some(
                    decode_cop_tile(&path, key)
                        .map_err(|e| format!("decoding {}: {e}", path.display()))?,
                )
            } else {
                None
            };
            self.order.push_back(key);
            self.map.insert(key, decoded);
            while self.map.len() > DEM_CACHE_CAP {
                if let Some(old) = self.order.pop_front() {
                    self.map.remove(&old);
                } else {
                    break;
                }
            }
        }
        Ok(self.map.get(&key).expect("just inserted"))
    }
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
    if !m.is_finite() {
        return SEA_LEVEL;
    }
    (m.round() + 32768.0).clamp(0.0, u16::MAX as f64) as u16
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
}
