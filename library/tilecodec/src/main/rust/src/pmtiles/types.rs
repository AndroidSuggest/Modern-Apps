use crate::proto::{Result, err};

pub const MAGIC: &[u8; 7] = b"PMTiles";
pub const SPEC_VERSION: u8 = 3;
pub const HEADER_LEN: usize = 127;

/// Compression ids from the spec. We only ever emit `Gzip`.
pub const COMPRESSION_NONE: u8 = 1;
pub const COMPRESSION_GZIP: u8 = 2;

/// Tile type ids from the spec.
pub const TILE_TYPE_MVT: u8 = 1;

/// The spec advises keeping the root directory small enough to come back in the
/// same request as the header, so a cold open is one round trip.
pub(crate) const MAX_ROOT_BYTES: usize = 16_384 - HEADER_LEN;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Entry {
    pub tile_id: u64,
    /// Relative to `tile_data_offset`, or to `leaf_dirs_offset` when this is a
    /// leaf pointer.
    pub offset: u64,
    pub length: u32,
    /// `0` = leaf pointer; `n >= 1` = this entry covers `tile_id..tile_id + n`.
    pub run_length: u32,
}

#[derive(Debug, Clone)]
pub struct Header {
    pub root_offset: u64,
    pub root_length: u64,
    pub metadata_offset: u64,
    pub metadata_length: u64,
    pub leaf_offset: u64,
    pub leaf_length: u64,
    pub tile_data_offset: u64,
    pub tile_data_length: u64,
    pub addressed_tiles: u64,
    pub tile_entries: u64,
    pub tile_contents: u64,
    pub clustered: bool,
    pub internal_compression: u8,
    pub tile_compression: u8,
    pub tile_type: u8,
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
    pub center_zoom: u8,
    pub center_lon_e7: i32,
    pub center_lat_e7: i32,
}

impl Header {
    pub fn parse(buf: &[u8]) -> Result<Header> {
        if buf.len() < HEADER_LEN {
            return err("PMTiles header shorter than 127 bytes");
        }
        if &buf[0..7] != MAGIC {
            return err("not a PMTiles archive (bad magic)");
        }
        if buf[7] != SPEC_VERSION {
            return err(format!("unsupported PMTiles spec version {}", buf[7]));
        }
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                buf[o],
                buf[o + 1],
                buf[o + 2],
                buf[o + 3],
                buf[o + 4],
                buf[o + 5],
                buf[o + 6],
                buf[o + 7],
            ])
        };
        let i32_at =
            |o: usize| i32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        Ok(Header {
            root_offset: u64_at(8),
            root_length: u64_at(16),
            metadata_offset: u64_at(24),
            metadata_length: u64_at(32),
            leaf_offset: u64_at(40),
            leaf_length: u64_at(48),
            tile_data_offset: u64_at(56),
            tile_data_length: u64_at(64),
            addressed_tiles: u64_at(72),
            tile_entries: u64_at(80),
            tile_contents: u64_at(88),
            clustered: buf[96] != 0,
            internal_compression: buf[97],
            tile_compression: buf[98],
            tile_type: buf[99],
            min_zoom: buf[100],
            max_zoom: buf[101],
            min_lon_e7: i32_at(102),
            min_lat_e7: i32_at(106),
            max_lon_e7: i32_at(110),
            max_lat_e7: i32_at(114),
            center_zoom: buf[118],
            center_lon_e7: i32_at(119),
            center_lat_e7: i32_at(123),
        })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_LEN);
        out.extend_from_slice(MAGIC);
        out.push(SPEC_VERSION);
        for v in [
            self.root_offset,
            self.root_length,
            self.metadata_offset,
            self.metadata_length,
            self.leaf_offset,
            self.leaf_length,
            self.tile_data_offset,
            self.tile_data_length,
            self.addressed_tiles,
            self.tile_entries,
            self.tile_contents,
        ] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.push(self.clustered as u8);
        out.push(self.internal_compression);
        out.push(self.tile_compression);
        out.push(self.tile_type);
        out.push(self.min_zoom);
        out.push(self.max_zoom);
        for v in [self.min_lon_e7, self.min_lat_e7, self.max_lon_e7, self.max_lat_e7] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        out.push(self.center_zoom);
        out.extend_from_slice(&self.center_lon_e7.to_le_bytes());
        out.extend_from_slice(&self.center_lat_e7.to_le_bytes());
        debug_assert_eq!(out.len(), HEADER_LEN);
        out
    }
}

