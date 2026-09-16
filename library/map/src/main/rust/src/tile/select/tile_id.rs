/// A tile to have resident, and where it sits.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub struct TileId {
    pub z: u8,
    pub x: u32,
    pub y: u32,
}

impl TileId {
    /// A key for the residency map, packing z/x/y into one integer.
    ///
    /// z up to 22 needs 5 bits and x/y up to 2^22 need 22 each, so 49 bits are used and
    /// no two tiles collide.
    pub fn key(&self) -> u64 {
        ((self.z as u64) << 44) | ((self.x as u64) << 22) | self.y as u64
    }

    /// Unpack a [`key`](Self::key). The residency map is keyed by integer, so this is what
    /// lets a tile's position be recovered without storing the id alongside it.
    pub fn from_key(key: u64) -> TileId {
        TileId {
            z: (key >> 44) as u8,
            x: ((key >> 22) & 0x3F_FFFF) as u32,
            y: (key & 0x3F_FFFF) as u32,
        }
    }

    /// Whether this tile lies within `depth` levels *beneath* `other` — that is, `other` is
    /// an ancestor of it, or the same tile.
    pub fn descends_from(&self, other: &TileId, depth: u8) -> bool {
        if self.z < other.z || self.z - other.z > depth {
            return false;
        }
        let shift = self.z - other.z;
        self.x >> shift == other.x && self.y >> shift == other.y
    }

    /// The tile `levels` levels *above* this one (its coarser ancestor), or `None` past the
    /// root. `levels == 0` is the tile itself. WS-D uses this to ask whether a coarser
    /// stand-in is resident under a fading finer tile.
    pub fn ancestor(&self, levels: u8) -> Option<TileId> {
        if self.z < levels {
            return None;
        }
        Some(TileId {
            z: self.z - levels,
            x: self.x >> levels,
            y: self.y >> levels,
        })
    }
}
