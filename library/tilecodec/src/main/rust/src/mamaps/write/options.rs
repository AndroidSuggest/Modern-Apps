use std::path::PathBuf;

pub const DEFAULT_LEAF_CAPACITY: u32 = 4096;
/// Raw DEFLATE over everything but the body header.

/// What a build declares about itself before the first tile.
pub struct Options {
    pub min_zoom: u8,
    pub max_zoom: u8,
    /// Hashed over the format version, the generator revision, the input digest, the zoom range,
    /// the layer set, the simplification parameters and the schema-table version.
    ///
    /// The writer does not compute it: only the generator knows those inputs. It does carry it, and
    /// a reader's cache marker is `CACHE_FORMAT | URL | build_id`.
    pub build_id: u64,
    pub compress: bool,
    /// Set when the generator normalised ring winding and hole containment, which is what lets the
    /// renderer skip its repair pass.
    pub rings_validated: bool,
    pub leaf_entry_capacity: u32,
    /// Where the body scratch file goes, or the system temporary directory when unset.
    ///
    /// Worth naming rather than always taking `std::env::temp_dir()`, because the scratch file is
    /// the size of the data section: 652 MB for California and a projected 82 GB for a planet
    /// build, and a system temporary directory is routinely on a small system volume. The generator
    /// already puts its *feature* spill beside the output for the same reason.
    pub spill_dir: Option<PathBuf>,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
}

impl Default for Options {
    fn default() -> Options {
        Options {
            min_zoom: 0,
            max_zoom: 14,
            build_id: 0,
            compress: true,
            rings_validated: false,
            leaf_entry_capacity: DEFAULT_LEAF_CAPACITY,
            spill_dir: None,
            min_lon_e7: -1_800_000_000,
            min_lat_e7: -850_511_287,
            max_lon_e7: 1_800_000_000,
            max_lat_e7: 850_511_287,
        }
    }
}
