//! A minimal TIFF IFD reader for the three tags COP90 sampling needs —
//! ModelPixelScale (33550), ModelTiepoint (33922) and GDAL_NODATA (42113).
//! Pixel decoding stays with the `tiff` crate; this only reads tag values, in
//! either byte order, from the first image.

use std::path::Path;

/// GeoTIFF tags of interest, each optional: pixel scale, tiepoint origin and
/// the GDAL nodata value.
#[derive(Default)]
pub struct GeoTags {
    pub pixel_w: Option<f64>,
    pub pixel_h: Option<f64>,
    pub west: Option<f64>,
    pub north: Option<f64>,
    pub nodata: Option<f64>,
}

pub fn read_geotags(path: &Path) -> Result<GeoTags, String> {
    let b = std::fs::read(path).map_err(|e| format!("reading tags: {e}"))?;
    if b.len() < 8 {
        return Err("not a TIFF (too short)".to_string());
    }
    let le = match (b[0], b[1], u16::from_le_bytes([b[2], b[3]])) {
        (0x49, 0x49, 42) => true,
        (0x4D, 0x4D, 42) => false,
        _ => return Err("not a TIFF (bad byte order)".to_string()),
    };
    let u16at = |at: usize| -> Result<u16, String> {
        b.get(at..at + 2)
            .ok_or_else(|| "a TIFF tag runs past the file".to_string())
            .map(|s| {
                if le {
                    u16::from_le_bytes([s[0], s[1]])
                } else {
                    u16::from_be_bytes([s[0], s[1]])
                }
            })
    };
    let u32at = |at: usize| -> Result<u32, String> {
        b.get(at..at + 4)
            .ok_or_else(|| "a TIFF tag runs past the file".to_string())
            .map(|s| {
                if le {
                    u32::from_le_bytes([s[0], s[1], s[2], s[3]])
                } else {
                    u32::from_be_bytes([s[0], s[1], s[2], s[3]])
                }
            })
    };
    let f64at = |at: usize| -> Result<f64, String> {
        b.get(at..at + 8)
            .ok_or_else(|| "a TIFF tag runs past the file".to_string())
            .map(|s| {
                let a = [s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]];
                if le { f64::from_le_bytes(a) } else { f64::from_be_bytes(a) }
            })
    };
    let first_ifd = u32at(4)? as usize;
    let n = u16at(first_ifd)? as usize;
    let mut out = GeoTags::default();
    for i in 0..n {
        let e = first_ifd + 2 + i * 12;
        let (tag, typ, cnt) = (u16at(e)?, u16at(e + 2)?, u32at(e + 4)?);
        // Values longer than 4 bytes live at the offset in bytes 8..12.
        let data_at = match typ {
            5 | 12 if cnt as usize * 8 > 4 => u32at(e + 8)? as usize,
            2 if cnt > 4 => u32at(e + 8)? as usize,
            _ => e + 8,
        };
        match (tag, typ, cnt) {
            // ModelPixelScaleTag: DOUBLE[3] = (scale_x, scale_y, scale_z).
            (33550, 12, 3) => {
                out.pixel_w = Some(f64at(data_at)?);
                out.pixel_h = Some(f64at(data_at + 8)?);
            }
            // ModelTiepointTag: DOUBLE[6] = (i,j,k, x,y,z); the origin pixel's lon/lat.
            (33922, 12, 6) => {
                out.west = Some(f64at(data_at + 24)?);
                out.north = Some(f64at(data_at + 32)?);
            }
            // GDAL_NODATA: an ASCII float like "-32768".
            (42113, 2, _) => {
                let end = data_at + cnt as usize;
                let s = b
                    .get(data_at..end)
                    .ok_or_else(|| "a TIFF tag runs past the file".to_string())?;
                let s = std::str::from_utf8(s)
                    .map_err(|_| "GDAL_NODATA is not UTF-8".to_string())?
                    .trim_matches(['\0', ' ', '\n', '\r']);
                if let Ok(v) = s.parse::<f64>() {
                    out.nodata = Some(v);
                }
            }
            _ => {}
        }
    }
    Ok(out)
}
