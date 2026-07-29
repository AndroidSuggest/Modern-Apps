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
    RedSand = 36,
    RedSandstone = 37,
    Sandstone = 38,
    Podzol = 39,
    CoarseDirt = 40,
    Mycelium = 41,
    PackedIce = 42,
    Ice = 43,
    BlueIce = 44,
    Mud = 45,
    RootedDirt = 46,
    DarkOakLog = 47,
    DarkOakLeaves = 48,
    DarkOakPlanks = 49,
    AcaciaLog = 50,
    JungleLog = 51,
    JunglePlanks = 52,
    GraniteBricks = 53,
    DeepslateBricks = 54,
    NetherBricks = 55,
    EndStoneBricks = 56,
    CobbledDeepslate = 57,
    HayBlock = 58,
    Farmland = 59,
    PackedDirt = 60,
    TubeCoral = 61,
    BrainCoral = 62,
    BubbleCoral = 63,
    FireCoral = 64,
    HornCoral = 65,
    Kelp = 66,
    SeaLantern = 67,
    Prismarine = 68,
    DarkPrismarine = 69,
    Dripstone = 70,
    MossBlock = 71,
    Sculk = 72,
    Amethyst = 73,
    Calcite = 74,
    Tuff = 75,
    Magma = 76,
    Glowstone = 77,
    Obsidian = 78,
    Clay = 79,
    AzaleaLeaves = 80,
    WardingStone = 81,
    Jukebox = 82,
}

pub const MAX_BLOCK_ID: u8 = 82;

impl Block {
    pub fn from_id(id: u8) -> Self {
        if id <= MAX_BLOCK_ID { unsafe { std::mem::transmute(id) } } else { Self::Air }
    }
    pub fn id(self) -> u8 { self as u8 }
    pub fn is_air(self) -> bool { matches!(self, Self::Air) }
    pub fn is_solid(self) -> bool { !matches!(self, Self::Air | Self::Water | Self::Glass) }
    pub fn is_transparent(self) -> bool {
        matches!(self, Self::Air | Self::Glass | Self::Leaves | Self::Water | Self::BirchLeaves | Self::SpruceLeaves | Self::DarkOakLeaves | Self::AzaleaLeaves)
    }
    pub fn is_opaque(self) -> bool { !self.is_transparent() }

    // Interactive block menu: 0 = none, 1 = crafting, 2 = furnace.
    pub fn menu(self) -> i32 {
        match self { Self::CraftingTable => 1, Self::Furnace => 2, Self::Jukebox => 3, _ => 0 }
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
            Self::RedSand => 64,
            Self::RedSandstone => 66,
            Self::Sandstone => 68,
            Self::Podzol => 70,
            Self::CoarseDirt => 72,
            Self::Mycelium => 73,
            Self::PackedIce => 74,
            Self::Ice => 75,
            Self::BlueIce => 76,
            Self::Mud => 77,
            Self::RootedDirt => 78,
            Self::DarkOakLog => 80,
            Self::DarkOakLeaves => 81,
            Self::DarkOakPlanks => 82,
            Self::AcaciaLog => 84,
            Self::JungleLog => 86,
            Self::JunglePlanks => 87,
            Self::GraniteBricks => 88,
            Self::DeepslateBricks => 89,
            Self::NetherBricks => 90,
            Self::EndStoneBricks => 91,
            Self::CobbledDeepslate => 92,
            Self::HayBlock => 94,
            Self::Farmland => 95,
            Self::PackedDirt => 96,
            Self::TubeCoral => 97,
            Self::BrainCoral => 98,
            Self::BubbleCoral => 99,
            Self::FireCoral => 100,
            Self::HornCoral => 101,
            Self::Kelp => 102,
            Self::SeaLantern => 103,
            Self::Prismarine => 104,
            Self::DarkPrismarine => 105,
            Self::Dripstone => 106,
            Self::MossBlock => 107,
            Self::Sculk => 108,
            Self::Amethyst => 109,
            Self::Calcite => 110,
            Self::Tuff => 111,
            Self::Magma => 112,
            Self::Glowstone => 113,
            Self::Obsidian => 114,
            Self::Clay => 115,
            Self::AzaleaLeaves => 116,
            Self::WardingStone => 117,
            Self::Jukebox => 118,
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
            Self::Sandstone => 69,
            Self::Podzol => 1,
            Self::Mycelium => 1,
            Self::Farmland => 1,
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
            Self::RedSandstone => 65,
            Self::Sandstone => 67,
            Self::Podzol => 71,
            Self::DarkOakLog => 79,
            Self::AcaciaLog => 83,
            Self::JungleLog => 85,
            Self::HayBlock => 93,
            Self::Farmland => 1,
            _ => self.tile_top(),
        }
    }
    pub fn color(self) -> [f32; 3] {
        match self {
            Self::Leaves => [0.42, 0.72, 0.33],
            Self::BirchLeaves => [0.55, 0.72, 0.35],
            Self::SpruceLeaves => [0.32, 0.55, 0.34],
            Self::DarkOakLeaves => [0.27, 0.45, 0.20],
            Self::Water => [0.6, 0.8, 1.0],
            _ => [1.0, 1.0, 1.0],
        }
    }
    // Stone/mineral blocks that only drop when mined with a pickaxe.
    pub fn needs_pickaxe(self) -> bool {
        matches!(self,
            Self::Stone | Self::Cobble | Self::Brick | Self::MossyCobble | Self::Diorite | Self::PolishedDiorite
            | Self::CoalOre | Self::IronOre | Self::DiamondOre | Self::RedstoneOre | Self::EmeraldOre
            | Self::IronBlock | Self::DiamondBlock | Self::EmeraldBlock | Self::Netherrack | Self::Furnace
            | Self::RedSandstone | Self::Sandstone | Self::GraniteBricks | Self::DeepslateBricks | Self::NetherBricks
            | Self::EndStoneBricks | Self::CobbledDeepslate | Self::Prismarine | Self::DarkPrismarine | Self::Dripstone
            | Self::Amethyst | Self::Calcite | Self::Tuff | Self::Magma | Self::Obsidian | Self::PackedIce | Self::BlueIce)
    }
    // Block light emitted (0..15) for dynamic lighting.
    pub fn light_emission(self) -> u8 {
        match self {
            Self::Glowstone | Self::SeaLantern => 15,
            Self::WardingStone => 13,
            Self::Magma => 11,
            _ => 0,
        }
    }
    pub fn tile_for_dir(self, _dx: i32, dy: i32, _dz: i32) -> u32 {
        if dy == 1 { self.tile_top() } else if dy == -1 { self.tile_bottom() } else { self.tile_side() }
    }
}

// Atlas is 16 tiles wide x 16 tall (256x256). Tiles 0..15 = base blocks, 16 = grass side overlay,
// 17..63 = the original extra blocks, 64.. = the biome/expansion blocks. GRASS_SIDE_TILE is a shader
// sentinel (dirt + tinted overlay composite).
pub const ATLAS_COLS: f32 = 16.0;
pub const ATLAS_ROWS: f32 = 16.0;
pub const GRASS_SIDE_OVERLAY: u32 = 16;
// Shader sentinel (dirt + tinted overlay composite). Kept far above any real tile index so the
// atlas can use tiles up to ~999.
pub const GRASS_SIDE_TILE: u32 = 1000;
pub fn tile_uv(tile_index: u32) -> (f32,f32,f32,f32) {
    let tx = (tile_index % 16) as f32;
    let ty = (tile_index / 16) as f32;
    let sx = 1.0 / ATLAS_COLS;
    let sy = 1.0 / ATLAS_ROWS;
    (tx*sx, ty*sy, (tx+1.0)*sx, (ty+1.0)*sy)
}
