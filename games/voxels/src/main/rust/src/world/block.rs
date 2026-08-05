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
    Chest = 83,
    Lava = 84,
    EndStone = 85,
    NetherPortal = 86,
    EndPortal = 87,
    Beacon = 88,
    Purpur = 89,
    // Matcha alloy tier: silver in the overworld, sulfur and cinnabar in the Nether, feeding the
    // steel -> adamant smithing line.
    SilverOre = 90,
    SulfurOre = 91,
    CinnabarOre = 92,
    SilverBlock = 93,
    SteelBlock = 94,
    AdamantBlock = 95,
    BlastFurnace = 96,
    // Copper and gold round out Matcha's metals, feeding the bronze alloy.
    CopperOre = 97,
    GoldOre = 98,
    CopperBlock = 99,
    GoldBlock = 100,
    BronzeBlock = 101,
    // Matcha's building set. Each slab/stair pair borrows its parent material's atlas tiles; the
    // top/bottom half and the stair's facing live in the per-voxel meta byte, not in the id.
    StoneSlab = 102,
    StoneStairs = 103,
    CobbleSlab = 104,
    CobbleStairs = 105,
    PlankSlab = 106,
    PlankStairs = 107,
    BrickSlab = 108,
    BrickStairs = 109,
    SandstoneSlab = 110,
    SandstoneStairs = 111,
    DeepslateBrickSlab = 112,
    DeepslateBrickStairs = 113,
    NetherBrickSlab = 114,
    NetherBrickStairs = 115,
    PurpurSlab = 116,
    PurpurStairs = 117,
    Stonecutter = 118,
}

pub const MAX_BLOCK_ID: u8 = 118;

/// The geometry a block occupies within its cell.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Shape { Cube, Slab, Stairs }

/// Meta bit layout for non-cube blocks: bits 0-1 facing, bit 2 half. Four bits are spare for
/// future shapes (corner stairs).
pub const META_FACING: u8 = 0b11;
pub const META_TOP: u8 = 0b100;
/// Facing values, named for the direction the stair's *low* side looks toward.
pub const FACE_NORTH: u8 = 0; // -Z
pub const FACE_EAST: u8 = 1;  // +X
pub const FACE_SOUTH: u8 = 2; // +Z
pub const FACE_WEST: u8 = 3;  // -X

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Aabb { pub min: [f32; 3], pub max: [f32; 3] }

impl Aabb {
    pub const fn new(min: [f32; 3], max: [f32; 3]) -> Self { Self { min, max } }
    /// Does this box, placed in the cell at `cell`, overlap the world-space box `min`..`max`?
    pub fn overlaps_at(&self, cell: [f32; 3], min: [f32; 3], max: [f32; 3]) -> bool {
        (0..3).all(|i| cell[i] + self.max[i] > min[i] && cell[i] + self.min[i] < max[i])
    }
}

pub const FULL_CUBE: Aabb = Aabb::new([0.0, 0.0, 0.0], [1.0, 1.0, 1.0]);

/// The one or two boxes a block occupies. Returned by value to keep collision allocation-free.
#[derive(Clone, Copy, Debug)]
pub struct Boxes { len: usize, boxes: [Aabb; 2] }
impl Boxes {
    const fn one(a: Aabb) -> Self { Self { len: 1, boxes: [a, a] } }
    const fn two(a: Aabb, b: Aabb) -> Self { Self { len: 2, boxes: [a, b] } }
    const fn none() -> Self { Self { len: 0, boxes: [FULL_CUBE, FULL_CUBE] } }
    pub fn as_slice(&self) -> &[Aabb] { &self.boxes[..self.len] }
}

impl Block {
    pub fn from_id(id: u8) -> Self {
        if id <= MAX_BLOCK_ID { unsafe { std::mem::transmute(id) } } else { Self::Air }
    }
    pub fn id(self) -> u8 { self as u8 }
    pub fn is_air(self) -> bool { matches!(self, Self::Air) }
    pub fn is_solid(self) -> bool { !matches!(self, Self::Air | Self::Water | Self::Glass | Self::Lava | Self::NetherPortal | Self::EndPortal) }
    pub fn is_transparent(self) -> bool {
        // Slabs and stairs leave part of their cell empty, so they can never hide a neighbour's
        // face wholesale. `occludes_face` decides the per-direction cases.
        self.shape() != Shape::Cube
            || matches!(self, Self::Air | Self::Glass | Self::Leaves | Self::Water | Self::BirchLeaves | Self::SpruceLeaves | Self::DarkOakLeaves | Self::AzaleaLeaves | Self::Lava | Self::NetherPortal | Self::EndPortal)
    }
    pub fn is_opaque(self) -> bool { !self.is_transparent() }

    pub fn shape(self) -> Shape {
        match self {
            Self::StoneSlab | Self::CobbleSlab | Self::PlankSlab | Self::BrickSlab
            | Self::SandstoneSlab | Self::DeepslateBrickSlab | Self::NetherBrickSlab | Self::PurpurSlab => Shape::Slab,
            Self::StoneStairs | Self::CobbleStairs | Self::PlankStairs | Self::BrickStairs
            | Self::SandstoneStairs | Self::DeepslateBrickStairs | Self::NetherBrickStairs | Self::PurpurStairs => Shape::Stairs,
            _ => Shape::Cube,
        }
    }

    /// The full-cube material a slab or stair is cut from; its textures are reused verbatim.
    pub fn parent(self) -> Self {
        match self {
            Self::StoneSlab | Self::StoneStairs => Self::Stone,
            Self::CobbleSlab | Self::CobbleStairs => Self::Cobble,
            Self::PlankSlab | Self::PlankStairs => Self::Planks,
            Self::BrickSlab | Self::BrickStairs => Self::Brick,
            Self::SandstoneSlab | Self::SandstoneStairs => Self::Sandstone,
            Self::DeepslateBrickSlab | Self::DeepslateBrickStairs => Self::DeepslateBricks,
            Self::NetherBrickSlab | Self::NetherBrickStairs => Self::NetherBricks,
            Self::PurpurSlab | Self::PurpurStairs => Self::Purpur,
            other => other,
        }
    }

    /// The slab shape for a material, and the stair shape. Used by the stonecutter and by the
    /// "two slabs make a cube" merge at placement.
    pub fn slab_of(self) -> Option<Self> {
        Some(match self {
            Self::Stone => Self::StoneSlab,
            Self::Cobble => Self::CobbleSlab,
            Self::Planks => Self::PlankSlab,
            Self::Brick => Self::BrickSlab,
            Self::Sandstone => Self::SandstoneSlab,
            Self::DeepslateBricks => Self::DeepslateBrickSlab,
            Self::NetherBricks => Self::NetherBrickSlab,
            Self::Purpur => Self::PurpurSlab,
            _ => return None,
        })
    }
    pub fn stairs_of(self) -> Option<Self> {
        Some(match self {
            Self::Stone => Self::StoneStairs,
            Self::Cobble => Self::CobbleStairs,
            Self::Planks => Self::PlankStairs,
            Self::Brick => Self::BrickStairs,
            Self::Sandstone => Self::SandstoneStairs,
            Self::DeepslateBricks => Self::DeepslateBrickStairs,
            Self::NetherBricks => Self::NetherBrickStairs,
            Self::Purpur => Self::PurpurStairs,
            _ => return None,
        })
    }

    /// The boxes this block fills, in cell-local 0..1 coordinates.
    pub fn collision_boxes(self, meta: u8) -> Boxes {
        if !self.is_solid() { return Boxes::none(); }
        let top = meta & META_TOP != 0;
        match self.shape() {
            Shape::Cube => Boxes::one(FULL_CUBE),
            Shape::Slab => Boxes::one(if top {
                Aabb::new([0.0, 0.5, 0.0], [1.0, 1.0, 1.0])
            } else {
                Aabb::new([0.0, 0.0, 0.0], [1.0, 0.5, 1.0])
            }),
            Shape::Stairs => {
                // A stair is a half-height slab plus a quarter block on the side opposite `facing`,
                // so you climb it walking against the way it faces.
                let (base, step_y) = if top {
                    (Aabb::new([0.0, 0.5, 0.0], [1.0, 1.0, 1.0]), [0.0, 0.5])
                } else {
                    (Aabb::new([0.0, 0.0, 0.0], [1.0, 0.5, 1.0]), [0.5, 1.0])
                };
                let step = match meta & META_FACING {
                    FACE_NORTH => Aabb::new([0.0, step_y[0], 0.5], [1.0, step_y[1], 1.0]),
                    FACE_EAST => Aabb::new([0.0, step_y[0], 0.0], [0.5, step_y[1], 1.0]),
                    FACE_SOUTH => Aabb::new([0.0, step_y[0], 0.0], [1.0, step_y[1], 0.5]),
                    _ => Aabb::new([0.5, step_y[0], 0.0], [1.0, step_y[1], 1.0]),
                };
                Boxes::two(base, step)
            }
        }
    }

    /// Whether this block completely hides a neighbour's face in direction (dx, dy, dz), pointing
    /// out of this block. Only a fully covered face may be culled; a half-covered one would leave
    /// a hole in the world.
    pub fn occludes_face(self, meta: u8, dx: i32, dy: i32, dz: i32) -> bool {
        let top = meta & META_TOP != 0;
        match self.shape() {
            Shape::Cube => self.is_opaque(),
            // A slab seals only the face its solid half rests against.
            Shape::Slab => if top { dy == 1 } else { dy == -1 },
            Shape::Stairs => {
                if if top { dy == 1 } else { dy == -1 } { return true; }
                // The tall side is opposite `facing`, and there the block spans the full face.
                match meta & META_FACING {
                    FACE_NORTH => dz == 1,
                    FACE_EAST => dx == -1,
                    FACE_SOUTH => dz == -1,
                    _ => dx == 1,
                }
            }
        }
    }

    // Interactive block menu: 0 = none, 1 = crafting, 2 = furnace, 3 = jukebox, 4 = blast furnace,
    // 5 = stonecutter.
    pub fn menu(self) -> i32 {
        match self { Self::CraftingTable => 1, Self::Furnace => 2, Self::Jukebox => 3, Self::BlastFurnace => 4, Self::Stonecutter => 5, _ => 0 }
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
            Self::Chest => 119,
            Self::Lava => 120,
            Self::EndStone => 121,
            Self::NetherPortal => 122,
            Self::EndPortal => 123,
            Self::Beacon => 124,
            Self::Purpur => 125,
            Self::SilverOre => 128,
            Self::SulfurOre => 129,
            Self::CinnabarOre => 130,
            Self::SilverBlock => 131,
            Self::SteelBlock => 132,
            Self::AdamantBlock => 133,
            Self::BlastFurnace => 134,
            Self::CopperOre => 136,
            Self::GoldOre => 137,
            Self::CopperBlock => 138,
            Self::GoldBlock => 139,
            Self::BronzeBlock => 140,
            Self::Stonecutter => 141,
            // Slabs and stairs are textured entirely from their parent material.
            Self::StoneSlab | Self::StoneStairs => Self::Stone.tile_top(),
            Self::CobbleSlab | Self::CobbleStairs => Self::Cobble.tile_top(),
            Self::PlankSlab | Self::PlankStairs => Self::Planks.tile_top(),
            Self::BrickSlab | Self::BrickStairs => Self::Brick.tile_top(),
            Self::SandstoneSlab | Self::SandstoneStairs => Self::Sandstone.tile_top(),
            Self::DeepslateBrickSlab | Self::DeepslateBrickStairs => Self::DeepslateBricks.tile_top(),
            Self::NetherBrickSlab | Self::NetherBrickStairs => Self::NetherBricks.tile_top(),
            Self::PurpurSlab | Self::PurpurStairs => Self::Purpur.tile_top(),
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
            Self::BlastFurnace => 135,
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
            Self::BlastFurnace => 135,
            Self::Stonecutter => 142,
            _ => self.tile_top(),
        }
    }
    // Slabs and stairs draw with their parent's faces; the geometry, not the texture, is what makes
    // them a different block.
    pub fn tile_for_dir(self, _dx: i32, dy: i32, _dz: i32) -> u32 {
        let m = self.parent();
        if dy == 1 { m.tile_top() } else if dy == -1 { m.tile_bottom() } else { m.tile_side() }
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
            | Self::Amethyst | Self::Calcite | Self::Tuff | Self::Magma | Self::Obsidian | Self::PackedIce | Self::BlueIce | Self::Purpur
            | Self::SilverOre | Self::SulfurOre | Self::CinnabarOre | Self::SilverBlock | Self::SteelBlock | Self::AdamantBlock | Self::BlastFurnace
            | Self::CopperOre | Self::GoldOre | Self::CopperBlock | Self::GoldBlock | Self::BronzeBlock
            | Self::Stonecutter
            | Self::StoneSlab | Self::StoneStairs | Self::CobbleSlab | Self::CobbleStairs
            | Self::BrickSlab | Self::BrickStairs | Self::SandstoneSlab | Self::SandstoneStairs
            | Self::DeepslateBrickSlab | Self::DeepslateBrickStairs
            | Self::NetherBrickSlab | Self::NetherBrickStairs | Self::PurpurSlab | Self::PurpurStairs)
    }
    // Block light emitted (0..15) for dynamic lighting.
    pub fn light_emission(self) -> u8 {
        match self {
            Self::Glowstone | Self::SeaLantern | Self::Lava | Self::Beacon => 15,
            Self::WardingStone => 13,
            Self::NetherPortal | Self::EndPortal => 12,
            Self::Magma => 11,
            _ => 0,
        }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_id_up_to_the_max_maps_to_a_distinct_block() {
        for id in 0..=MAX_BLOCK_ID {
            assert_eq!(Block::from_id(id).id(), id, "id {id} did not round-trip");
        }
        // Ids above the max must not be mistaken for real blocks.
        assert_eq!(Block::from_id(MAX_BLOCK_ID + 1), Block::Air);
        // Blocks and items share one u8 space; crossing ITEM_BASE would make a block unplaceable.
        assert!(MAX_BLOCK_ID < crate::item::ITEM_BASE);
    }

    #[test]
    fn slabs_and_stairs_know_their_material() {
        for m in [Block::Stone, Block::Cobble, Block::Planks, Block::Brick,
                  Block::Sandstone, Block::DeepslateBricks, Block::NetherBricks, Block::Purpur] {
            let slab = m.slab_of().unwrap_or_else(|| panic!("{m:?} needs a slab"));
            let stairs = m.stairs_of().unwrap_or_else(|| panic!("{m:?} needs stairs"));
            assert_eq!(slab.shape(), Shape::Slab);
            assert_eq!(stairs.shape(), Shape::Stairs);
            assert_eq!(slab.parent(), m);
            assert_eq!(stairs.parent(), m);
            // They borrow the parent's faces, so no new atlas art is needed.
            for (dx, dy, dz) in [(1, 0, 0), (0, 1, 0), (0, -1, 0)] {
                assert_eq!(slab.tile_for_dir(dx, dy, dz), m.tile_for_dir(dx, dy, dz));
                assert_eq!(stairs.tile_for_dir(dx, dy, dz), m.tile_for_dir(dx, dy, dz));
            }
            // A partial block can never be treated as a solid occluder wholesale.
            assert!(!slab.is_opaque());
            assert!(!stairs.is_opaque());
            assert!(slab.is_solid() && stairs.is_solid());
        }
        assert_eq!(Block::Stone.shape(), Shape::Cube);
        assert_eq!(Block::Stone.parent(), Block::Stone);
        assert_eq!(Block::Dirt.slab_of(), None);
    }

    #[test]
    fn a_slab_fills_the_half_its_meta_says() {
        let bottom = Block::StoneSlab.collision_boxes(0);
        assert_eq!(bottom.as_slice(), &[Aabb::new([0.0, 0.0, 0.0], [1.0, 0.5, 1.0])]);
        let top = Block::StoneSlab.collision_boxes(META_TOP);
        assert_eq!(top.as_slice(), &[Aabb::new([0.0, 0.5, 0.0], [1.0, 1.0, 1.0])]);

        // A bottom slab seals only the floor; a top slab only the ceiling.
        assert!(Block::StoneSlab.occludes_face(0, 0, -1, 0));
        assert!(!Block::StoneSlab.occludes_face(0, 0, 1, 0));
        assert!(!Block::StoneSlab.occludes_face(0, 1, 0, 0));
        assert!(Block::StoneSlab.occludes_face(META_TOP, 0, 1, 0));
        assert!(!Block::StoneSlab.occludes_face(META_TOP, 0, -1, 0));
    }

    #[test]
    fn a_stair_is_a_slab_plus_a_step_opposite_its_facing() {
        let boxes = Block::StoneStairs.collision_boxes(FACE_NORTH);
        let s = boxes.as_slice();
        assert_eq!(s.len(), 2);
        assert_eq!(s[0], Aabb::new([0.0, 0.0, 0.0], [1.0, 0.5, 1.0]), "base half-slab");
        assert_eq!(s[1], Aabb::new([0.0, 0.5, 0.5], [1.0, 1.0, 1.0]), "step on the far side of north");

        // The tall side is fully covered, so it may hide a neighbour; the low side may not.
        assert!(Block::StoneStairs.occludes_face(FACE_NORTH, 0, 0, 1), "tall side seals");
        assert!(!Block::StoneStairs.occludes_face(FACE_NORTH, 0, 0, -1), "low side is only half filled");
        assert!(Block::StoneStairs.occludes_face(FACE_NORTH, 0, -1, 0), "the base covers the floor");
        assert!(!Block::StoneStairs.occludes_face(FACE_NORTH, 0, 1, 0));

        // Flipping to the top half mirrors the geometry vertically.
        let flipped = Block::StoneStairs.collision_boxes(FACE_NORTH | META_TOP);
        let f = flipped.as_slice();
        assert_eq!(f[0], Aabb::new([0.0, 0.5, 0.0], [1.0, 1.0, 1.0]));
        assert_eq!(f[1], Aabb::new([0.0, 0.0, 0.5], [1.0, 0.5, 1.0]));
        assert!(Block::StoneStairs.occludes_face(FACE_NORTH | META_TOP, 0, 1, 0));
    }

    #[test]
    fn each_facing_puts_the_step_on_the_opposite_side() {
        let step = |facing: u8| Block::StoneStairs.collision_boxes(facing).as_slice()[1];
        assert_eq!(step(FACE_NORTH).min[2], 0.5, "north-facing steps sit on +Z");
        assert_eq!(step(FACE_SOUTH).max[2], 0.5, "south-facing steps sit on -Z");
        assert_eq!(step(FACE_EAST).max[0], 0.5, "east-facing steps sit on -X");
        assert_eq!(step(FACE_WEST).min[0], 0.5, "west-facing steps sit on +X");
    }

    #[test]
    fn cubes_are_unchanged() {
        assert_eq!(Block::Stone.collision_boxes(0).as_slice(), &[FULL_CUBE]);
        assert!(Block::Stone.occludes_face(0, 1, 0, 0));
        assert!(!Block::Glass.occludes_face(0, 1, 0, 0), "glass never hid faces");
        assert!(Block::Air.collision_boxes(0).as_slice().is_empty(), "air has no collision");
        assert!(Block::Water.collision_boxes(0).as_slice().is_empty());
    }
}
