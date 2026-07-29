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
    Gravel = 14,
    MossyCobble = 15,
    Diorite = 16,
    PolishedDiorite = 17,
    CoalOre = 18,
    IronOre = 19,
    DiamondOre = 20,
    RedstoneOre = 21,
    EmeraldOre = 22,
    IronBlock = 23,
    DiamondBlock = 24,
    EmeraldBlock = 25,
    BirchLog = 26,
    BirchPlanks = 27,
    BirchLeaves = 28,
    SpruceLog = 29,
    SprucePlanks = 30,
    SpruceLeaves = 31,
    Netherrack = 32,
    Bookshelf = 33,
    CraftingTable = 34,
    Furnace = 35,
}

pub const MAX_BLOCK_ID: u8 = 35;

impl Block {
    pub fn from_id(id: u8) -> Self {
        if id <= MAX_BLOCK_ID { unsafe { std::mem::transmute(id) } } else { Self::Air }
    }
    pub fn id(self) -> u8 { self as u8 }
    pub fn is_air(self) -> bool { matches!(self, Self::Air) }
    pub fn is_solid(self) -> bool { !matches!(self, Self::Air | Self::Water | Self::Glass) }
    pub fn is_transparent(self) -> bool {
        matches!(self, Self::Air | Self::Glass | Self::Leaves | Self::Water | Self::BirchLeaves | Self::SpruceLeaves)
    }
    pub fn is_opaque(self) -> bool { !self.is_transparent() }

    // Interactive block menu: 0 = none, 1 = crafting, 2 = furnace.
    pub fn menu(self) -> i32 {
        match self { Self::CraftingTable => 1, Self::Furnace => 2, _ => 0 }
    }

    // Atlas tile indices (8x8 atlas). See the atlas generator's TILES order.
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
            Self::Gravel => 17,
            Self::MossyCobble => 18,
            Self::Diorite => 19,
            Self::PolishedDiorite => 20,
            Self::CoalOre => 21,
            Self::IronOre => 22,
            Self::DiamondOre => 23,
            Self::RedstoneOre => 24,
            Self::EmeraldOre => 25,
            Self::IronBlock => 26,
            Self::DiamondBlock => 27,
            Self::EmeraldBlock => 28,
            Self::BirchLog => 30,
            Self::BirchPlanks => 31,
            Self::BirchLeaves => 32,
            Self::SpruceLog => 34,
            Self::SprucePlanks => 35,
            Self::SpruceLeaves => 36,
            Self::Netherrack => 37,
            Self::Bookshelf => 10,
            Self::CraftingTable => 39,
            Self::Furnace => 42,
        }
    }
    pub fn tile_bottom(self) -> u32 {
        match self {
            Self::Grass => 1,
            Self::Wood => 5,
            Self::BirchLog => 30,
            Self::SpruceLog => 34,
            Self::Bookshelf => 10,
            Self::CraftingTable => 10,
            Self::Furnace => 43,
            _ => self.tile_top(),
        }
    }
    pub fn tile_side(self) -> u32 {
        match self {
            Self::Grass => 3,
            Self::Wood => 4,
            Self::BirchLog => 29,
            Self::SpruceLog => 33,
            Self::Bookshelf => 38,
            Self::CraftingTable => 40,
            Self::Furnace => 43,
            _ => self.tile_top(),
        }
    }
    pub fn color(self) -> [f32; 3] {
        match self {
            Self::Leaves => [0.42, 0.72, 0.33],
            Self::BirchLeaves => [0.55, 0.72, 0.35],
            Self::SpruceLeaves => [0.32, 0.55, 0.34],
            Self::Water => [0.6, 0.8, 1.0],
            _ => [1.0, 1.0, 1.0],
        }
    }
    pub fn tile_for_dir(self, _dx: i32, dy: i32, _dz: i32) -> u32 {
        if dy == 1 { self.tile_top() } else if dy == -1 { self.tile_bottom() } else { self.tile_side() }
    }
}

// Atlas is 8 tiles wide x 8 tall (128x128). Tiles 0..15 = base blocks, 16 = grass side overlay,
// 17.. = extra blocks. GRASS_SIDE_TILE is a shader sentinel (dirt + tinted overlay composite).
pub const ATLAS_COLS: f32 = 8.0;
pub const ATLAS_ROWS: f32 = 8.0;
pub const GRASS_SIDE_OVERLAY: u32 = 16;
pub const GRASS_SIDE_TILE: u32 = 100;
pub fn tile_uv(tile_index: u32) -> (f32,f32,f32,f32) {
    let tx = (tile_index % 8) as f32;
    let ty = (tile_index / 8) as f32;
    let sx = 1.0 / ATLAS_COLS;
    let sy = 1.0 / ATLAS_ROWS;
    (tx*sx, ty*sy, (tx+1.0)*sx, (ty+1.0)*sy)
}
