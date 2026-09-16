//! Slippy / web-mercator tile math shared by the bbox walk and the sampler.

use std::f64::consts::PI;

pub fn lon_to_tile_x(lon: f64, z: u8) -> f64 {
    (lon + 180.0) / 360.0 * (1u64 << z) as f64
}

pub fn lat_to_tile_y(lat: f64, z: u8) -> f64 {
    let r = lat.clamp(-85.05112878, 85.05112878).to_radians();
    (1.0 - (r.tan() + 1.0 / r.cos()).ln() / PI) / 2.0 * (1u64 << z) as f64
}

pub fn tile_x_to_lon(x: f64, z: u8) -> f64 {
    x / (1u64 << z) as f64 * 360.0 - 180.0
}

pub fn tile_y_to_lat(y: f64, z: u8) -> f64 {
    let n = PI * (1.0 - 2.0 * y / (1u64 << z) as f64);
    n.sinh().atan().to_degrees()
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
