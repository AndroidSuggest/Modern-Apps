//! Native `.om` decoder for the Weather app map.
//!
//! Decodes Open-Meteo spatial `.om` files from an in-memory byte slice (fetched
//! in Kotlin via the existing HttpURLConnection/Ktor client). This avoids pulling
//! `ureq` + `rustls` + `ring` + `icu` + `url` (~90 crates) in Rust, reducing
//! supply-chain attack surface. All features preserved: grid covering-window,
//! bilinear resampling, wind_speed_10m derived measure, Web-Mercator row mapping.
//!
//! Color mapping stays in Kotlin; this crate returns raw `f32` values (NaN where no data).
//! Grid geometry and interpolation logic ported from open-meteo/weather-map-layer `src/grids/regular.ts`.

use std::ops::Range;
use std::sync::Arc;

use omfiles::reader::OmFileReader;
use omfiles::traits::{OmFileReadable, OmFileReaderBackend};
use omfiles::OmFilesError;

// ---------------------------------------------------------------------------
// In-memory backend – armv8-only
// ---------------------------------------------------------------------------

/// An [`OmFileReaderBackend`] that serves bytes from a fully-fetched `.om` slice.
/// Kotlin (NetworkClient/HttpUrlEngine) does the HTTP fetch with range coalescing,
///
/// The previous `HttpRangeBackend` used `ureq` (ring+rustls+icu_url) which duplicated
/// the Android HTTP stack and counted ~90 crates. Now the Rust side only decodes.
struct SliceBackend {
    data: Vec<u8>,
}

impl SliceBackend {
    fn new(data: Vec<u8>) -> Self {
        Self { data }
    }
}

impl OmFileReaderBackend for SliceBackend {
    type Bytes<'a> = &'a [u8] where Self: 'a;

    fn count(&self) -> usize {
        self.data.len()
    }

    fn prefetch_data(&self, _offset: usize, _count: usize) {}

    fn get_bytes(&self, offset: u64, count: u64) -> Result<Self::Bytes<'_>, OmFilesError> {
        if count == 0 {
            return Ok(&[]);
        }
        let start = offset as usize;
        let end = (offset + count) as usize;
        if end > self.data.len() {
            return Err(OmFilesError::InvalidBackendRead {
                offset,
                count,
                size: self.data.len(),
            });
        }
        Ok(&self.data[start..end])
    }
}

// Memory backend type for decoding: wraps SliceBackend in Arc for OmFileReader::new
type Backend = SliceBackend;

// ---------------------------------------------------------------------------
// Grid geometry (regular lat/lon grid)
// ---------------------------------------------------------------------------

/// A regular lat/lon grid, mirroring `RegularGridData` from the JS lib.
/// Grid node `(j, i)` sits at `lat = lat_min + dy*j`, `lon = lon_min + dx*i`.
/// The `.om` array is stored `[ny, nx]` (dim 0 = latitude, dim 1 = longitude).
struct Grid {
    nx: usize,
    ny: usize,
    lon_min: f64,
    lat_min: f64,
    dx: f64,
    dy: f64,
}

/// Inclusive-exclusive index window into the grid for a bbox, padded by one
/// cell so bilinear sampling has neighbours (see `getCoveringRanges`).
struct Window {
    y0: usize,
    y1: usize,
    x0: usize,
    x1: usize,
}

impl Grid {
    fn covering_window(&self, west: f64, south: f64, east: f64, north: f64) -> Option<Window> {
        let y0 = ((south - self.lat_min) / self.dy).floor() as i64 - 1;
        let y1 = ((north - self.lat_min) / self.dy).ceil() as i64 + 1;
        let x0 = ((west - self.lon_min) / self.dx).floor() as i64 - 1;
        let x1 = ((east - self.lon_min) / self.dx).ceil() as i64 + 1;

        let y0 = y0.clamp(0, self.ny as i64) as usize;
        let y1 = y1.clamp(0, self.ny as i64) as usize;
        let x0 = x0.clamp(0, self.nx as i64) as usize;
        let x1 = x1.clamp(0, self.nx as i64) as usize;

        if y1 <= y0 || x1 <= x0 {
            return None;
        }
        Some(Window { y0, y1, x0, x1 })
    }
}

/// Bilinear sample of a `[sub_ny, sub_nx]` row-major sub-grid at global
/// fractional grid coordinates `(gy, gx)`. Returns NaN outside the sub-grid or
/// when any of the four corners is missing.
fn bilinear(
    data: &[f32],
    sub_nx: usize,
    sub_ny: usize,
    win: &Window,
    gy: f64,
    gx: f64,
) -> f32 {
    let ly = gy - win.y0 as f64;
    let lx = gx - win.x0 as f64;
    if ly < 0.0 || lx < 0.0 {
        return f32::NAN;
    }
    let y0 = ly.floor() as usize;
    let x0 = lx.floor() as usize;
    if y0 + 1 >= sub_ny || x0 + 1 >= sub_nx {
        return f32::NAN;
    }
    let fy = (ly - y0 as f64) as f32;
    let fx = (lx - x0 as f64) as f32;

    let p00 = data[y0 * sub_nx + x0];
    let p01 = data[y0 * sub_nx + x0 + 1];
    let p10 = data[(y0 + 1) * sub_nx + x0];
    let p11 = data[(y0 + 1) * sub_nx + x0 + 1];

    if p00.is_finite() && p01.is_finite() && p10.is_finite() && p11.is_finite() {
        let w00 = (1.0 - fx) * (1.0 - fy);
        let w01 = fx * (1.0 - fy);
        let w10 = (1.0 - fx) * fy;
        let w11 = fx * fy;
        return p00 * w00 + p01 * w01 + p10 * w10 + p11 * w11;
    }
    // Graceful fallback: nearest finite corner (handles coastal masks).
    let nearest = if fy < 0.5 {
        if fx < 0.5 { p00 } else { p01 }
    } else if fx < 0.5 {
        p10
    } else {
        p11
    };
    if nearest.is_finite() {
        nearest
    } else {
        [p00, p01, p10, p11]
            .into_iter()
            .find(|v| v.is_finite())
            .unwrap_or(f32::NAN)
    }
}

// ---------------------------------------------------------------------------
// Decode
// ---------------------------------------------------------------------------

/// Read a single variable's covering sub-grid as a flat `[sub_ny, sub_nx]`
/// row-major `Vec<f32>`. Handles the derived `wind_speed_10m` measure by
/// combining the u/v components into wind magnitude.
fn read_subgrid(
    root: &OmFileReader<Backend>,
    variable: &str,
    win: &Window,
) -> Result<Vec<f32>, String> {
    let ranges: [Range<u64>; 2] = [
        (win.y0 as u64)..(win.y1 as u64),
        (win.x0 as u64)..(win.x1 as u64),
    ];

    let read_one = |name: &str| -> Result<Vec<f32>, String> {
        let child = root
            .get_child_by_name(name)
            .ok_or_else(|| format!("variable {name} not found"))?;
        let array = child
            .expect_array()
            .map_err(|e| format!("{name} is not an array: {e:?}"))?;
        let decoded = array
            .read::<f32>(&ranges)
            .map_err(|e| format!("read {name} failed: {e:?}"))?;
        Ok(decoded.iter().copied().collect())
    };

    if root.get_child_by_name(variable).is_none() {
        if let Some((u_name, v_name)) = wind_speed_components(variable) {
            let u = read_one(u_name)?;
            let v = read_one(v_name)?;
            let mag = u
                .iter()
                .zip(v.iter())
                .map(|(a, b)| (a * a + b * b).sqrt())
                .collect();
            return Ok(mag);
        }
    }
    read_one(variable)
}

/// For a derived wind-speed variable, the underlying u/v component names.
fn wind_speed_components(variable: &str) -> Option<(&'static str, &'static str)> {
    match variable {
        "wind_speed_10m" => Some(("wind_u_component_10m", "wind_v_component_10m")),
        _ => None,
    }
}

/// Decode `variable` from the `.om` byte slice over the bbox
/// `[west, south, east, north]`, resampling into an `out_w * out_h` raster in
/// row-major order with **row 0 = north** (top), suitable for a bitmap.
/// Missing/out-of-coverage pixels are `NaN`.
#[allow(clippy::too_many_arguments)]
fn decode_region_bytes(
    om_data: &[u8],
    variable: &str,
    grid: &Grid,
    west: f64,
    south: f64,
    east: f64,
    north: f64,
    out_w: usize,
    out_h: usize,
) -> Result<Vec<f32>, String> {
    if out_w == 0 || out_h == 0 {
        return Err("empty output size".to_string());
    }
    let win = grid
        .covering_window(west, south, east, north)
        .ok_or_else(|| "bbox does not intersect grid".to_string())?;
    let sub_nx = win.x1 - win.x0;
    let sub_ny = win.y1 - win.y0;

    let backend = Arc::new(SliceBackend::new(om_data.to_vec()));
    let root = OmFileReader::new(backend).map_err(|e| format!("open failed: {e:?}"))?;

    let data = read_subgrid(&root, variable, &win)?;
    if data.len() != sub_nx * sub_ny {
        return Err(format!(
            "unexpected sub-grid size: got {}, expected {}",
            data.len(),
            sub_nx * sub_ny
        ));
    }

    let mut out = vec![f32::NAN; out_w * out_h];
    let merc_y = |lat_deg: f64| {
        let lat = lat_deg.to_radians();
        (std::f64::consts::FRAC_PI_4 + lat / 2.0).tan().ln()
    };
    let inv_merc_y = |y: f64| (2.0 * y.exp().atan() - std::f64::consts::FRAC_PI_2).to_degrees();
    let y_north = merc_y(north);
    let y_south = merc_y(south);
    for r in 0..out_h {
        let t = (r as f64 + 0.5) / out_h as f64;
        let lat = inv_merc_y(y_north + t * (y_south - y_north));
        let gy = (lat - grid.lat_min) / grid.dy;
        for c in 0..out_w {
            let lon = west + (c as f64 + 0.5) * (east - west) / out_w as f64;
            let gx = (lon - grid.lon_min) / grid.dx;
            out[r * out_w + c] = bilinear(&data, sub_nx, sub_ny, &win, gy, gx);
        }
    }
    Ok(out)
}

// ---------------------------------------------------------------------------
// JNI – armv8-only, no ureq
// ---------------------------------------------------------------------------

#[cfg(not(test))]
mod jni_bindings {
    use super::*;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jdouble, jfloatArray, jint};
    use jni::JNIEnv;

    /// Legacy entry point kept for compatibility: previously took om_url String and fetched via ureq.
    /// Now it returns null so callers migrate to decodeRegionBytes.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_weather_map_OmTilesNative_decodeRegion<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        _om_url: JString<'local>,
        _variable: JString<'local>,
        _nx: jint,
        _ny: jint,
        _lon_min: jdouble,
        _lat_min: jdouble,
        _dx: jdouble,
        _dy: jdouble,
        _west: jdouble,
        _south: jdouble,
        _east: jdouble,
        _north: jdouble,
        _out_w: jint,
        _out_h: jint,
    ) -> jfloatArray {
        // Deprecated – use decodeRegionBytes. Returning null forces Kotlin to use new path.
        std::ptr::null_mut()
    }

    /// New JNI entry point backing `OmTilesNative.decodeRegionBytes(ByteArray, ...)`.
    /// Returns a `float[]` of length `out_w*out_h` (row-major, row 0 = north; NaN where no data),
    /// or `null` on error.
    #[no_mangle]
    #[allow(clippy::too_many_arguments)]
    pub extern "system" fn Java_com_vayunmathur_weather_map_OmTilesNative_decodeRegionBytes<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        om_data: JByteArray<'local>,
        variable: JString<'local>,
        nx: jint,
        ny: jint,
        lon_min: jdouble,
        lat_min: jdouble,
        dx: jdouble,
        dy: jdouble,
        west: jdouble,
        south: jdouble,
        east: jdouble,
        north: jdouble,
        out_w: jint,
        out_h: jint,
    ) -> jfloatArray {
        let null = std::ptr::null_mut();

        let data: Vec<u8> = match env.convert_byte_array(&om_data) {
            Ok(d) => d,
            Err(_) => return null,
        };
        if data.is_empty() || data.len() > 200 * 1024 * 1024 {
            return null;
        }
        let var: String = match env.get_string(&variable) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };

        let grid = Grid {
            nx: nx.max(0) as usize,
            ny: ny.max(0) as usize,
            lon_min,
            lat_min,
            dx,
            dy,
        };

        let result = decode_region_bytes(
            &data,
            &var,
            &grid,
            west,
            south,
            east,
            north,
            out_w.max(0) as usize,
            out_h.max(0) as usize,
        );

        let values = match result {
            Ok(v) => v,
            Err(_) => return null,
        };

        match env.new_float_array(values.len() as jint) {
            Ok(arr) => {
                if env.set_float_array_region(&arr, 0, &values).is_err() {
                    return null;
                }
                arr.into_raw()
            }
            Err(_) => null,
        }
    }
}

// ---------------------------------------------------------------------------
// Tests – use in-memory backend now
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// dwd_icon global regular grid, per weather-map-layer/src/domains.ts.
    fn dwd_icon() -> Grid {
        Grid {
            nx: 2879,
            ny: 1441,
            lon_min: -180.0,
            lat_min: -90.0,
            dx: 0.125,
            dy: 0.125,
        }
    }

    fn json_string(body: &str, key: &str) -> String {
        let needle = format!("\"{key}\"");
        let start = body.find(&needle).unwrap() + needle.len();
        let colon = body[start..].find(':').unwrap() + start + 1;
        let q1 = body[colon..].find('"').unwrap() + colon + 1;
        let q2 = body[q1..].find('"').unwrap() + q1;
        body[q1..q2].to_string()
    }

    fn first_valid_time(body: &str) -> String {
        let key = "\"valid_times\"";
        let start = body.find(key).unwrap() + key.len();
        let br = body[start..].find('[').unwrap() + start + 1;
        let q1 = body[br..].find('"').unwrap() + br + 1;
        let q2 = body[q1..].find('"').unwrap() + q1;
        body[q1..q2].to_string()
    }

    #[test]
    fn grid_covering_window_basic() {
        let grid = dwd_icon();
        let win = grid.covering_window(5.0, 47.0, 15.0, 55.0).expect("window");
        assert!(win.x1 > win.x0);
        assert!(win.y1 > win.y0);
    }

    #[test]
    fn bilinear_finite() {
        let data = vec![1.0f32, 2.0, 3.0, 4.0];
        let win = Window { y0: 0, y1: 2, x0: 0, x1: 2 };
        let v = bilinear(&data, 2, 2, &win, 0.5, 0.5);
        assert!(v > 2.0 && v < 3.0);
    }
}
