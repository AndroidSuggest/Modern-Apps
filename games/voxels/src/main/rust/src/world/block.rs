use serde::{Deserialize, Serialize};

pub type BlockId = u8;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[repr(u8)]
pub enum Block {
    Air = 0,
    Stone = 1,
    Dirt = 2,
    Grass = 3,
    Wood = 4,
    Leaves = 5,
    Sand = 6,
    Glass = 7,
    Cobble = 8,
    Brick = 9,
    Planks = 10,
    Snow = 11,
    Water = 12,
    Bedrock = 13,
}

impl Block {
    pub fn from_id(id: u8) -> Self {
        match id {
            1 => Self::Stone,
            2 => Self::Dirt,
            3 => Self::Grass,
            4 => Self::Wood,
            5 => Self::Leaves,
            6 => Self::Sand,
            7 => Self::Glass,
            8 => Self::Cobble,
            9 => Self::Brick,
            10 => Self::Planks,
            11 => Self::Snow,
            12 => Self::Water,
            13 => Self::Bedrock,
            _ => Self::Air,
        }
    }
    pub fn id(self) -> u8 { self as u8 }
    pub fn is_air(self) -> bool { matches!(self, Self::Air) }
    pub fn is_solid(self) -> bool { !matches!(self, Self::Air | Self::Water | Self::Glass) }
    pub fn is_transparent(self) -> bool { matches!(self, Self::Air | Self::Glass | Self::Leaves | Self::Water) }
    pub fn is_opaque(self) -> bool { !self.is_transparent() }

    // Atlas tile indices based on order: 0:deepslate(stone),1:dirt,2:grass_top,3:grass_side,4:oak_log,5:oak_log_top,6:oak_leaves,7:sand,8:cobble,9:bricks,10:planks,11:packed_ice(snow),12:ice(glass),13:blue_ice(water),14:bedrock,15:grass_snow
    pub fn tile_top(self) -> u32 {
        match self {
            Self::Air => 0,
            Self::Stone => 0,
            Self::Dirt => 1,
            Self::Grass => 2,
            Self::Wood => 5,
            Self::Leaves => 6,
            Self::Sand => 7,
            Self::Glass => 12,
            Self::Cobble => 8,
            Self::Brick => 9,
            Self::Planks => 10,
            Self::Snow => 11,
            Self::Water => 13,
            Self::Bedrock => 14,
        }
    }
    pub fn tile_bottom(self) -> u32 {
        match self { Self::Grass => 1, Self::Wood => 5, _ => self.tile_top() }
    }
    pub fn tile_side(self) -> u32 {
        match self { Self::Grass => 3, Self::Wood => 4, _ => self.tile_top() }
    }
    pub fn color(self) -> [f32; 3] {
        match self {
            Self::Leaves => [0.7, 1.0, 0.7],
            Self::Water => [0.6, 0.8, 1.0],
            _ => [1.0, 1.0, 1.0],
        }
    }
    pub fn tile_for_dir(self, _dx: i32, dy: i32, _dz: i32) -> u32 {
        if dy == 1 { self.tile_top() } else if dy == -1 { self.tile_bottom() } else { self.tile_side() }
    }
}

pub const TILES_PER_ROW: f32 = 4.0;
pub fn tile_uv(tile_index: u32) -> (f32,f32,f32,f32) {
    let tx = (tile_index % 4) as f32;
    let ty = (tile_index / 4) as f32;
    let s = 1.0 / TILES_PER_ROW;
    (tx*s, ty*s, (tx+1.0)*s, (ty+1.0)*s)
}
