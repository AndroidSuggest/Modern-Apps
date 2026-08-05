use serde::{Deserialize, Serialize};
use crate::world::block::{Block, Id};

pub const SLOTS: usize = 36;   // 0..9 = hotbar, 9..36 = main inventory
pub const HOTBAR: usize = 9;
pub const STACK: i32 = 64;

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct InvSlot { pub id: Id, pub count: i32 }
impl Default for InvSlot { fn default() -> Self { Self { id: 0, count: 0 } } }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Inventory {
    pub selected: usize,
    #[serde(with = "serde_slots")]
    pub slots: [InvSlot; SLOTS],
    pub placed: i32,
    pub broken: i32,
    // Equipped armor: 0 helmet, 1 chestplate, 2 leggings, 3 boots. count = remaining durability.
    #[serde(default)]
    pub armor: [InvSlot; 4],
}

// Item ids 154+ are materials/tools (see item.rs). Ore -> material conversions live in SMELTING
// instead, since those need a furnace, fuel and time.
/// One crafting recipe. `in2 == 0` means a single ingredient.
///
/// `unlocked_by` is the tech tree: the recipe stays hidden and uncraftable until the recipe whose
/// output is `unlocked_by` has actually been crafted. 0 means it is available from the first minute.
/// Naming the prerequisite by its *output id* rather than by index keeps the table readable and
/// survives reordering.
pub struct Recipe {
    pub in1: Id, pub n1: i32,
    pub in2: Id, pub n2: i32,
    pub out: Id, pub out_n: i32,
    pub unlocked_by: Id,
}
#[allow(clippy::too_many_arguments)]
const fn r(in1: Id, n1: i32, in2: Id, n2: i32, out: Id, out_n: i32, unlocked_by: Id) -> Recipe {
    Recipe { in1, n1, in2, n2, out, out_n, unlocked_by }
}

pub const RECIPES: [Recipe; 96] = [
    r(154, 1, 157, 1, 186, 1, 165), // iron + coal -> flint & steel
    r(154, 2, 0, 0, 252, 1, 165),   // iron            -> shears
    r(159, 3, 137, 2, 253, 1, 159), // sticks + leather -> fishing rod
    r(159, 1, 236, 1, 255, 1, 159), // stick + copper   -> archaeologist's brush
    r(251, 3, 0, 0, 131, 1, 250),   // wheat            -> bread
    r(Block::HayBlock as Id, 1, 0, 0, 250, 4, 0), // hay bale -> wheat seeds
    r(187, 1, Block::Glass as Id, 5, Block::Beacon as Id, 1, 169), // nether star + glass -> beacon
    r(138, 2, 0, 0, 189, 3, 0), // gunpowder -> firework rockets
    r(Block::Snow as Id, 1, 0, 0, 190, 4, 0), // snow -> snowballs
    r(Block::Wood as Id, 1, 0, 0, Block::Planks as Id, 4, 0),
    r(Block::BirchLog as Id, 1, 0, 0, Block::BirchPlanks as Id, 4, 0),
    r(Block::SpruceLog as Id, 1, 0, 0, Block::SprucePlanks as Id, 4, 0),
    r(Block::Planks as Id, 4, 0, 0, Block::CraftingTable as Id, 1, 0),
    r(Block::Cobble as Id, 8, 0, 0, Block::Furnace as Id, 1, 0),
    r(Block::Diorite as Id, 4, 0, 0, Block::PolishedDiorite as Id, 4, 0),
    r(Block::Planks as Id, 2, 0, 0, 159 /*Stick*/, 4, 0),
    // Material -> block.
    r(154, 9, 0, 0, Block::IronBlock as Id, 1, 165),
    r(155, 9, 0, 0, Block::DiamondBlock as Id, 1, 167),
    r(156, 9, 0, 0, Block::EmeraldBlock as Id, 1, 167),
    // Tools: material + stick(159).
    r(Block::Planks as Id, 3, 159, 2, 163, 1, 0), // wood pickaxe
    r(Block::Planks as Id, 2, 159, 1, 164, 1, 0), // wood sword
    r(Block::Cobble as Id, 3, 159, 2, 165, 1, 163), // stone pickaxe
    r(Block::Cobble as Id, 2, 159, 1, 166, 1, 163), // stone sword
    r(154, 3, 159, 2, 167, 1, 165),                 // iron pickaxe
    r(154, 2, 159, 1, 168, 1, 165),                 // iron sword
    r(155, 3, 159, 2, 169, 1, 167),                 // diamond pickaxe
    r(155, 2, 159, 1, 170, 1, 167),                 // diamond sword
    // Armor: material only.
    r(154, 5, 0, 0, 171, 1, 167), r(154, 8, 0, 0, 172, 1, 167), r(154, 7, 0, 0, 173, 1, 167), r(154, 4, 0, 0, 174, 1, 167), // iron
    r(155, 5, 0, 0, 175, 1, 169), r(155, 8, 0, 0, 176, 1, 169), r(155, 7, 0, 0, 177, 1, 169), r(155, 4, 0, 0, 178, 1, 169), // diamond
    // --- Matcha alloy tier ---
    // Adamant gear: the tier above diamond.
    r(196, 3, 159, 2, 197, 1, Block::BlastFurnace as Id), // adamant pickaxe
    r(196, 2, 159, 1, 198, 1, 197), // adamant sword
    r(196, 5, 0, 0, 199, 1, 197), r(196, 8, 0, 0, 200, 1, 197), r(196, 7, 0, 0, 201, 1, 197), r(196, 4, 0, 0, 202, 1, 197),
    // Metal storage blocks (silver/steel/adamant also power beacons).
    r(193, 9, 0, 0, Block::SilverBlock as Id, 1, Block::BlastFurnace as Id),
    r(195, 9, 0, 0, Block::SteelBlock as Id, 1, Block::BlastFurnace as Id),
    r(196, 9, 0, 0, Block::AdamantBlock as Id, 1, Block::BlastFurnace as Id),
    // A steel-lined furnace: the only place the alloy recipes will smelt.
    r(Block::Furnace as Id, 1, 195, 5, Block::BlastFurnace as Id, 1, Block::Furnace as Id),
    // Blessings: quicksilver charms bound to a thematic offering. Attuning one grants a permanent
    // passive (see blessing.rs), so the ingredient cost tracks roughly how strong the passive is.
    r(194, 2, 193, 1, 160, 1, Block::BlastFurnace as Id),  // silver          -> Clement, swift of foot
    r(194, 2, 154, 4, 161, 1, Block::BlastFurnace as Id),  // iron            -> Ares, might
    r(194, 2, 156, 2, 162, 1, Block::BlastFurnace as Id),  // emerald         -> Yamm, the deep
    r(194, 2, 195, 3, 203, 1, Block::BlastFurnace as Id),  // steel           -> Daedalus, tools never wear
    r(194, 2, 189, 4, 204, 1, Block::BlastFurnace as Id),  // fireworks       -> Icarus, no fall damage
    r(194, 2, 192, 6, 205, 1, Block::BlastFurnace as Id),  // sulfur          -> Yama, immune to fire
    r(194, 2, Block::Obsidian as Id, 4, 206, 1, Block::BlastFurnace as Id),   // Talos, crushing blows
    r(194, 2, 187, 1, 207, 1, Block::BlastFurnace as Id),  // nether star     -> the God King, smite the undead
    r(194, 2, 138, 8, 208, 1, Block::BlastFurnace as Id),  // gunpowder       -> Arachnae, bane of horrors
    r(194, 2, 196, 2, 209, 1, Block::BlastFurnace as Id),  // adamant         -> Prometheus, armor never wears
    r(194, 2, 155, 3, 210, 1, Block::BlastFurnace as Id),  // diamond         -> Lu Ban, mending
    r(194, 2, Block::EmeraldBlock as Id, 1, 211, 1, Block::BlastFurnace as Id), // Eros, fortune
    r(194, 2, 191, 3, 212, 1, Block::BlastFurnace as Id),  // ender pearls    -> Will, reach
    r(194, 2, Block::Glowstone as Id, 4, 213, 1, Block::BlastFurnace as Id),  // Hyacinthus, second jump
    r(194, 2, Block::Purpur as Id, 6, 214, 1, Block::BlastFurnace as Id),     // Aeolus, wind burst
    r(194, 2, Block::Sculk as Id, 8, 215, 1, Block::BlastFurnace as Id),      // Cronus, swift sneak
    r(194, 2, Block::BlueIce as Id, 4, 216, 1, Block::BlastFurnace as Id),    // Demeter, frost walker
    r(194, 2, Block::SeaLantern as Id, 4, 217, 1, Block::BlastFurnace as Id), // Glaucus, sea luck
    r(194, 2, Block::Amethyst as Id, 6, 218, 1, Block::BlastFurnace as Id),   // Apollo, marksman
    r(194, 2, 190, 16, 219, 1, Block::BlastFurnace as Id), // snowballs       -> Artemis, multishot
    r(194, 2, Block::WardingStone as Id, 2, 220, 1, Block::BlastFurnace as Id), // Warding, thorns
    r(194, 2, Block::DiamondBlock as Id, 1, 221, 1, Block::BlastFurnace as Id), // Paris, infinity
    // The five late additions cost the alloy tier, so they arrive after the Blast Furnace does.
    r(194, 2, Block::IronBlock as Id, 2, 245, 1, Block::BlastFurnace as Id),    // Athena, absorption shield
    r(194, 2, Block::Magma as Id, 4, 246, 1, Block::BlastFurnace as Id),        // Sekhmet, bloodrage
    r(194, 2, 222, 12, 247, 1, Block::BlastFurnace as Id), // raw meat        -> Camazotz, lifesteal
    r(194, 2, Block::Prismarine as Id, 8, 248, 1, Block::BlastFurnace as Id),   // Tangaroa, conduit
    r(194, 2, Block::Sculk as Id, 4, 249, 1, Block::BlastFurnace as Id),        // Anubis, ward undead
    // --- Matcha's kitchen. Cooked meat is the base ingredient; everything else builds on it. ---
    r(223, 1, 131, 1, 224, 1, 131),  // cooked meat + bread          -> ramen
    r(223, 2, 135, 2, 225, 1, 131),  // cooked meat + carrot         -> japanese curry
    r(132, 2, 135, 2, 226, 1, 131),  // cooked fish + carrot         -> green curry
    r(146, 2, 131, 1, 227, 1, 131),  // baked potato + bread         -> gnocchi
    r(131, 2, 0, 0, 228, 2, 131),    // bread                        -> naan
    r(131, 1, 223, 2, 229, 1, 131),  // bread + cooked meat          -> pupusa
    r(146, 3, 0, 0, 230, 1, 131),    // baked potato                 -> latke
    r(131, 1, 130, 2, 231, 1, 131),  // bread + apple                -> bruschetta
    r(131, 1, 149, 1, 232, 1, 131),  // bread + fried egg            -> french toast
    r(131, 1, 152, 1, 233, 1, 131),  // bread + glow berry crumble   -> sweet berry danish
    r(136, 2, Block::Snow as Id, 2, 234, 1, 131), // melon + snow    -> melon sorbet
    r(223, 3, 131, 1, 235, 1, 131),  // cooked meat + bread          -> stroganoff
    r(130, 2, 131, 1, 151, 1, 131),  // apple + bread                -> apple empanada
    r(130, 1, Block::Glowstone as Id, 1, 152, 1, 131), // apple + glowstone -> glow berry crumble
    // --- Bronze: Matcha alloys copper with gold, landing between iron and diamond. ---
    // 236 copper ingot, 237 gold ingot, 238 bronze ingot.
    r(238, 3, 159, 2, 239, 1, 167), // bronze pickaxe
    r(238, 2, 159, 1, 240, 1, 239), // bronze sword
    r(238, 5, 0, 0, 241, 1, 239), r(238, 8, 0, 0, 242, 1, 239), r(238, 7, 0, 0, 243, 1, 239), r(238, 4, 0, 0, 244, 1, 239),
    r(236, 9, 0, 0, Block::CopperBlock as Id, 1, 167),
    r(237, 9, 0, 0, Block::GoldBlock as Id, 1, 167),
    r(238, 9, 0, 0, Block::BronzeBlock as Id, 1, 167),
    // The stonecutter itself: an iron blade on a stone bed.
    r(Block::Stone as Id, 3, 154, 1, Block::Stonecutter as Id, 1, 165),
];

// Furnace recipes. Unlike crafting these cost fuel and take `secs` of real time, and the ones marked
// `blast` only run in a Blast Furnace — that gate is what makes the steel/adamant line an unlock
// rather than just another recipe.
pub struct Smelt {
    pub in1: Id, pub n1: i32,
    pub in2: Id, pub n2: i32,
    pub out: Id, pub out_n: i32,
    pub secs: f32,
    pub blast: bool,
}
const fn smelt(in1: Id, n1: i32, in2: Id, n2: i32, out: Id, out_n: i32, secs: f32, blast: bool) -> Smelt {
    Smelt { in1, n1, in2, n2, out, out_n, secs, blast }
}
pub const SMELTING: [Smelt; 19] = [
    smelt(254, 1, 0, 0, 132, 1, 6.0, false), // raw fish -> cooked fish
    smelt(Block::CoalOre as Id,     1, 0, 0, 157, 1,  6.0, false), // coal
    smelt(Block::IronOre as Id,     1, 0, 0, 154, 1,  8.0, false), // iron ingot
    smelt(Block::DiamondOre as Id,  1, 0, 0, 155, 1, 10.0, false),
    smelt(Block::EmeraldOre as Id,  1, 0, 0, 156, 1, 10.0, false),
    smelt(Block::RedstoneOre as Id, 1, 0, 0, 158, 1,  6.0, false),
    smelt(Block::SilverOre as Id,   1, 0, 0, 193, 1,  9.0, false), // silver ingot
    smelt(Block::Sand as Id,        2, 0, 0, Block::Glass as Id,  1, 5.0, false),
    smelt(Block::Cobble as Id,      4, 0, 0, Block::Stone as Id,  1, 5.0, false),
    smelt(Block::Clay as Id,        4, 0, 0, Block::Brick as Id,  1, 6.0, false),
    smelt(222, 1, 0, 0, 223, 1, 6.0, false), // raw meat -> cooked meat
    smelt(131, 1, 0, 0, 147, 3, 5.0, false), // bread -> cookies (baking)
    smelt(Block::CopperOre as Id, 1, 0, 0, 236, 1, 6.0, false), // copper ingot
    smelt(Block::GoldOre as Id,   1, 0, 0, 237, 1, 8.0, false), // gold ingot
    smelt(236, 6, 237, 1, 238, 1, 14.0, true),                  // copper + gold -> bronze
    // Blast furnace only: the alloy line.
    smelt(Block::SulfurOre as Id,   1, 0, 0, 192, 2,  5.0, true),  // sulfur
    smelt(Block::CinnabarOre as Id, 1, 0, 0, 194, 1,  7.0, true),  // quicksilver
    smelt(154, 1, 192, 1, 195, 1, 12.0, true),                     // iron + sulfur -> steel
    smelt(195, 4, 194, 2, 196, 1, 20.0, true),                     // steel + quicksilver -> adamant
];

// Seconds of furnace burn a stack item is worth. Anything not listed can't be used as fuel.
pub fn fuel_secs(id: Id) -> f32 {
    match id {
        157 => 80.0,                                        // coal
        84 => 200.0,                                        // a lava block
        192 => 60.0,                                        // sulfur burns hot
        4 | 26 | 29 | 47 | 50 | 51 => 15.0,                 // logs
        10 | 27 | 30 | 49 | 52 => 15.0,                     // planks
        159 => 5.0,                                         // sticks
        _ => 0.0,
    }
}
// Fuels with no use other than burning, tried before anything a player might be saving.
const DEDICATED_FUELS: [Id; 3] = [157, 84, 192];

// Villager trades live in `villager.rs`, tiered per profession. Emerald = item 156.

/// Whether recipe `idx` is available, given which recipes have already been crafted. A recipe with no
/// prerequisite is always available; otherwise the recipe that produces `unlocked_by` must have been
/// crafted at least once.
pub fn recipe_unlocked(idx: usize, crafted: &[bool]) -> bool {
    let Some(recipe) = RECIPES.get(idx) else { return false; };
    if recipe.unlocked_by == 0 { return true; }
    RECIPES.iter().enumerate()
        .any(|(i, other)| other.out == recipe.unlocked_by && crafted.get(i).copied().unwrap_or(false))
}

// Which shelf of the crafting menu a recipe belongs on. Purely for the UI — the recipe table itself
// stays a flat list so indices remain stable.
pub fn recipe_category(out: Id) -> &'static str {
    if crate::blessing::is_blessing(out) { return "blessing"; }
    if crate::item::food_effects(out).is_some() { return "food"; }
    if crate::item::is_tool(out) { return "tool"; }
    if crate::item::is_armor(out) { return "armor"; }
    if crate::item::is_item(out) { return "material"; }
    "block"
}

// The stonecutter: one block in, one shape out, no fuel and no waiting. This is Matcha's stonecutting
// book condensed — the pack's hundreds of recipes are almost all "material -> slab/stairs/variant",
// which is exactly what `cut_variants` enumerates.
pub struct Cut { pub input: Id, pub output: Id, pub count: i32 }

/// Every stonecutter conversion, derived from the block table so a new slab family is picked up for
/// free. A cube yields two slabs or one stair, and either shape converts back to the other.
pub fn cut_variants() -> Vec<Cut> {
    let mut out = Vec::new();
    for material in CUTTABLE {
        let (Some(slab), Some(stairs)) = (material.slab_of(), material.stairs_of()) else { continue; };
        let (m, s, st) = (material as Id, slab as Id, stairs as Id);
        out.push(Cut { input: m, output: s, count: 2 });
        out.push(Cut { input: m, output: st, count: 1 });
        out.push(Cut { input: s, output: st, count: 1 });
        out.push(Cut { input: st, output: s, count: 1 });
    }
    // Decorative conversions between whole blocks of the same family.
    for &(input, output) in DECOR_CUTS {
        out.push(Cut { input: input as Id, output: output as Id, count: 1 });
    }
    out
}

// Materials that have a slab and a stair shape.
const CUTTABLE: [Block; 8] = [
    Block::Stone, Block::Cobble, Block::Planks, Block::Brick,
    Block::Sandstone, Block::DeepslateBricks, Block::NetherBricks, Block::Purpur,
];
// Whole-block decorative swaps the stonecutter also offers.
const DECOR_CUTS: &[(Block, Block)] = &[
    (Block::Stone, Block::Cobble),
    (Block::Stone, Block::Brick),
    (Block::Cobble, Block::MossyCobble),
    (Block::Diorite, Block::PolishedDiorite),
    (Block::CobbledDeepslate, Block::DeepslateBricks),
    (Block::Sandstone, Block::RedSandstone),
    (Block::EndStone, Block::EndStoneBricks),
    (Block::Netherrack, Block::NetherBricks),
];

impl Default for Inventory {
    fn default() -> Self {
        let mut slots = [InvSlot::default(); SLOTS];
        let start = [
            (Block::Grass, 64), (Block::Dirt, 64), (Block::Stone, 64), (Block::Wood, 16),
            (Block::Planks, 32), (Block::Sand, 32), (Block::Glass, 16), (Block::Cobble, 16), (Block::Brick, 16),
        ];
        for (i, (b, c)) in start.iter().enumerate() { slots[i] = InvSlot { id: *b as Id, count: *c }; }
        Self { selected: 0, slots, placed: 0, broken: 0, armor: [InvSlot::default(); 4] }
    }
}

impl Inventory {
    pub fn selected_block(&self) -> Id {
        if self.selected < HOTBAR { let s = &self.slots[self.selected]; if s.count > 0 { s.id } else { 0 } } else { 0 }
    }
    pub fn consume_selected(&mut self) -> Option<Id> {
        if self.selected >= HOTBAR { return None; }
        let slot = &mut self.slots[self.selected];
        if slot.count <= 0 || slot.id == 0 { return None; }
        let id = slot.id;
        slot.count -= 1;
        if slot.count <= 0 { *slot = InvSlot::default(); }
        self.placed += 1;
        Some(id)
    }
    pub fn add_block(&mut self, id: Id) { self.try_add_block(id); }
    // Add one unit, reporting whether it actually fit anywhere.
    pub fn try_add_block(&mut self, id: Id) -> bool {
        if id == 0 { return false; }
        for slot in self.slots.iter_mut() { if slot.id == id && slot.count < STACK { slot.count += 1; return true; } }
        for slot in self.slots.iter_mut() { if slot.id == 0 { slot.id = id; slot.count = 1; return true; } }
        false
    }
    pub fn select(&mut self, idx: usize) { if idx < HOTBAR { self.selected = idx; } }

    // Drag-and-drop: move/merge/swap the stack in `from` into `to`.
    pub fn move_item(&mut self, from: usize, to: usize) {
        if from >= SLOTS || to >= SLOTS || from == to { return; }
        let a = self.slots[from];
        let b = self.slots[to];
        if a.id == 0 { return; }
        if b.id == 0 {
            self.slots[to] = a; self.slots[from] = InvSlot::default();
        } else if a.id == b.id {
            let space = STACK - b.count;
            let mv = a.count.min(space);
            self.slots[to].count += mv;
            self.slots[from].count -= mv;
            if self.slots[from].count <= 0 { self.slots[from] = InvSlot::default(); }
        } else {
            self.slots[from] = b; self.slots[to] = a;
        }
    }

    // Creative catalog: put a full stack of `id` into the first empty (or matching) slot.
    pub fn give(&mut self, id: Id) {
        if id == 0 { return; }
        for slot in self.slots.iter_mut() { if slot.id == 0 { slot.id = id; slot.count = STACK; return; } }
        for slot in self.slots.iter_mut() { if slot.id == id { slot.count = STACK; return; } }
    }

    pub fn count_of(&self, id: Id) -> i32 { self.slots.iter().filter(|s| s.id == id).map(|s| s.count).sum() }
    fn remove_count(&mut self, id: Id, mut n: i32) {
        for slot in self.slots.iter_mut() {
            if slot.id == id && n > 0 {
                let take = slot.count.min(n);
                slot.count -= take; n -= take;
                if slot.count <= 0 { *slot = InvSlot::default(); }
            }
        }
    }
    /// Craft one batch. `crafted` is the set of recipes already made, which is what gates the tech
    /// tree: a locked recipe consumes nothing and reports failure.
    pub fn craft(&mut self, recipe: usize, crafted: &[bool]) -> bool {
        if !recipe_unlocked(recipe, crafted) { return false; }
        let Some(&Recipe { in1, n1, in2, n2, out, out_n, .. }) = RECIPES.get(recipe) else { return false; };
        if self.count_of(in1) < n1 { return false; }
        if in2 != 0 && self.count_of(in2) < n2 { return false; }
        // `has_room_for` already knows a tool needs a whole empty slot, so this covers both cases.
        // Skipping it for durable output would craft a tool into a full inventory and drop it.
        if !self.has_room_for(out, out_n) { return false; }
        self.remove_count(in1, n1);
        if in2 != 0 { self.remove_count(in2, n2); }
        if crate::item::has_durability(out) { self.add_item_with_count(out, crate::item::max_durability(out)); }
        else { for _ in 0..out_n { self.add_block(out); } }
        true
    }
    // Place a non-stacking item (tool/armor) into the first empty slot with a given count (durability).
    pub fn add_item_with_count(&mut self, id: Id, count: i32) -> bool {
        for slot in self.slots.iter_mut() { if slot.id == 0 { slot.id = id; slot.count = count; return true; } }
        false
    }
    pub fn selected_count(&self) -> i32 { if self.selected < HOTBAR { self.slots[self.selected].count } else { 0 } }
    // Remove the whole selected stack, returning (id, count). Used to equip armor without losing durability.
    pub fn take_selected(&mut self) -> Option<(Id, i32)> {
        if self.selected >= HOTBAR { return None; }
        let s = self.slots[self.selected];
        if s.id == 0 { return None; }
        self.slots[self.selected] = InvSlot::default();
        Some((s.id, s.count))
    }
    // Decrement the selected tool's durability (called on block break / attack); clear it at 0.
    pub fn damage_selected(&mut self) {
        if self.selected >= HOTBAR { return; }
        let s = &mut self.slots[self.selected];
        if s.id != 0 && crate::item::has_durability(s.id) { s.count -= 1; if s.count <= 0 { *s = InvSlot::default(); } }
    }
    // Equip an armor item (with its durability) into its slot; returns any displaced piece.
    pub fn equip_armor(&mut self, id: Id, dur: i32) -> Option<InvSlot> {
        let slot = crate::item::armor_slot(id);
        let old = self.armor[slot];
        self.armor[slot] = InvSlot { id, count: dur };
        if old.id != 0 { Some(old) } else { None }
    }
    // Execute a villager trade if the player can afford it and has somewhere to put the goods.
    // Both costs are checked before either is taken, so a half-affordable trade consumes nothing.
    pub fn trade_offer(&mut self, o: &crate::villager::Offer) -> bool {
        let give_n = crate::villager::give_count(o);
        if self.count_of(o.cost) < o.cost_n { return false; }
        if o.cost2 != 0 && self.count_of(o.cost2) < o.cost2_n { return false; }
        if !self.has_room_for(o.give, give_n) { return false; }
        self.remove_count(o.cost, o.cost_n);
        if o.cost2 != 0 { self.remove_count(o.cost2, o.cost2_n); }
        if crate::item::has_durability(o.give) { self.add_item_with_count(o.give, give_n); }
        else { for _ in 0..give_n { self.add_block(o.give); } }
        true
    }
    // Execute a stonecutter conversion if the player has the input and room for the output.
    pub fn cut(&mut self, idx: usize) -> bool {
        let cuts = cut_variants();
        let Some(c) = cuts.get(idx) else { return false; };
        if self.count_of(c.input) < 1 { return false; }
        if !self.has_room_for(c.output, c.count) { return false; }
        self.remove_count(c.input, 1);
        for _ in 0..c.count { self.add_block(c.output); }
        true
    }
    pub fn armor_defense(&self) -> f32 { self.armor.iter().map(|s| if s.id != 0 { crate::item::armor_defense(s.id) } else { 0.0 }).sum() }

    // ---- Smelting ----
    pub fn can_smelt(&self, s: &Smelt) -> bool {
        self.count_of(s.in1) >= s.n1 && (s.in2 == 0 || self.count_of(s.in2) >= s.n2)
    }
    // Consume one batch of a smelt recipe's inputs and bank its output. Callers must check
    // `can_smelt` first; this is only reached once the furnace has finished a cycle.
    pub fn take_smelt_inputs(&mut self, s: &Smelt) {
        self.remove_count(s.in1, s.n1);
        if s.in2 != 0 { self.remove_count(s.in2, s.n2); }
    }
    pub fn give_smelt_output(&mut self, s: &Smelt) {
        for _ in 0..s.out_n { self.add_block(s.out); }
    }
    // Whether `n` of `id` would actually fit. Used to pause a furnace rather than smelt into a full
    // inventory and drop the result on the floor.
    pub fn has_room_for(&self, id: Id, n: i32) -> bool {
        if crate::item::has_durability(id) { return self.slots.iter().any(|s| s.id == 0); }
        let mut need = n;
        for s in self.slots.iter() {
            if s.id == 0 { need -= STACK; } else if s.id == id { need -= STACK - s.count; }
            if need <= 0 { return true; }
        }
        need <= 0
    }
    // Burn one fuel item, returning how many seconds of heat it provides. `spare` lists ids the
    // active recipe needs as ingredients so the furnace never eats its own input (sulfur is both a
    // fuel and the second half of the steel recipe). Dedicated fuels are preferred over logs and
    // planks so a smelt doesn't quietly consume the player's building stock.
    pub fn consume_fuel(&mut self, spare: &[Id]) -> Option<f32> {
        let usable = |s: &InvSlot| s.count > 0 && fuel_secs(s.id) > 0.0 && !spare.contains(&s.id);
        let idx = self.slots.iter().position(|s| usable(s) && DEDICATED_FUELS.contains(&s.id))
            .or_else(|| self.slots.iter().position(usable))?;
        let secs = fuel_secs(self.slots[idx].id);
        self.slots[idx].count -= 1;
        if self.slots[idx].count <= 0 { self.slots[idx] = InvSlot::default(); }
        Some(secs)
    }

    // ---- Containers ----
    // Pull a stack out of a container slot. Whatever doesn't fit stays in `slot`, so a full
    // inventory can never silently void a chest.
    pub fn take_from(&mut self, slot: &mut InvSlot) {
        if slot.id == 0 || slot.count <= 0 { return; }
        if crate::item::has_durability(slot.id) {
            if self.add_item_with_count(slot.id, slot.count) { *slot = InvSlot::default(); }
            return;
        }
        while slot.count > 0 {
            if !self.try_add_block(slot.id) { return; }
            slot.count -= 1;
        }
        *slot = InvSlot::default();
    }
    // Wear down each equipped piece by 1 when the player is hurt.
    pub fn damage_armor(&mut self) {
        for s in self.armor.iter_mut() { if s.id != 0 { s.count -= 1; if s.count <= 0 { *s = InvSlot::default(); } } }
    }
    // Lu Ban: restore a single point of durability to the most worn piece of gear carried or worn.
    pub fn mend_one(&mut self) {
        let worst = self.slots.iter_mut().chain(self.armor.iter_mut())
            .filter(|s| s.id != 0 && crate::item::has_durability(s.id) && s.count < crate::item::max_durability(s.id))
            .min_by_key(|s| s.count);
        if let Some(s) = worst { s.count += 1; }
    }

    pub fn to_json(&self) -> String { serde_json::to_string(self).unwrap_or_else(|_| r#"{"selected":0,"slots":[]}"#.to_string()) }
}

// Serialize the fixed-size slot array as a plain list (serde can't derive for [T; 36]).
mod serde_slots {
    use super::{InvSlot, SLOTS};
    use serde::{Deserializer, Serializer, Deserialize, Serialize};
    pub fn serialize<S: Serializer>(slots: &[InvSlot; SLOTS], s: S) -> Result<S::Ok, S::Error> {
        slots.as_slice().serialize(s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<[InvSlot; SLOTS], D::Error> {
        let v: Vec<InvSlot> = Vec::deserialize(d)?;
        let mut out = [InvSlot::default(); SLOTS];
        for (i, slot) in v.into_iter().enumerate().take(SLOTS) { out[i] = slot; }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Every crafting and smelting output must have a sane recipe: no zero ids, no free lunches.
    // The crafting menu only renders the six shelves it knows about. A recipe landing outside them
    // would be unreachable in the UI even though the engine can still craft it.
    #[test]
    fn every_recipe_lands_on_a_known_shelf() {
        const SHELVES: [&str; 6] = ["block", "tool", "armor", "material", "food", "blessing"];
        for rec in RECIPES.iter() {
            let (out, cat) = (rec.out, recipe_category(rec.out));
            assert!(SHELVES.contains(&cat), "recipe for {out} has unknown category {cat}");
        }
        // Each shelf should actually have something on it.
        for shelf in SHELVES {
            assert!(RECIPES.iter().any(|r| recipe_category(r.out) == shelf), "shelf {shelf} is empty");
        }
    }

    // Widening the id space means a stray number in a table no longer lands on a block by
    // accident — it lands on nothing at all. Every id in every table has to name something real.
    #[test]
    fn every_id_in_the_tables_names_something_real() {
        use crate::world::block::is_real_id;
        for (i, &Recipe { in1, in2, out, .. }) in RECIPES.iter().enumerate() {
            assert!(is_real_id(in1), "recipe {i} takes {in1}, which is nothing");
            assert!(in2 == 0 || is_real_id(in2), "recipe {i} takes {in2}, which is nothing");
            assert!(is_real_id(out), "recipe {i} yields {out}, which is nothing");
        }
        for (i, s) in SMELTING.iter().enumerate() {
            assert!(is_real_id(s.in1), "smelt {i} takes {}, which is nothing", s.in1);
            assert!(s.in2 == 0 || is_real_id(s.in2), "smelt {i} takes {}, which is nothing", s.in2);
            assert!(is_real_id(s.out), "smelt {i} yields {}, which is nothing", s.out);
        }
        for c in cut_variants() {
            assert!(is_real_id(c.input) && is_real_id(c.output), "a cut moves an id that is nothing");
        }
        for b in crate::blessing::PANTHEON.iter() {
            assert!(is_real_id(b.id), "{} has an id that is nothing", b.name);
        }
        for id in DEDICATED_FUELS { assert!(is_real_id(id), "fuel {id} is nothing"); }
    }

    // The tech tree is the one structure in the game that can strand a player: a cycle or an
    // orphaned prerequisite would make a recipe permanently uncraftable. These three properties are
    // what make that impossible.
    #[test]
    fn the_crafting_tree_is_sound() {
        // Every prerequisite must be something another recipe actually produces.
        for (i, rec) in RECIPES.iter().enumerate() {
            if rec.unlocked_by == 0 { continue; }
            assert!(
                RECIPES.iter().any(|o| o.out == rec.unlocked_by),
                "recipe {i} (yields {}) waits on {}, which nothing makes",
                rec.out, rec.unlocked_by,
            );
            assert_ne!(rec.unlocked_by, rec.out, "recipe {i} unlocks itself");
        }

        // There has to be somewhere to start.
        let roots = RECIPES.iter().filter(|r| r.unlocked_by == 0).count();
        assert!(roots > 0, "every recipe is locked behind another one");

        // Walk the graph from the roots. Anything still locked when this settles is unreachable,
        // which also rules out cycles: a cycle can never be entered.
        let mut crafted = vec![false; RECIPES.len()];
        loop {
            let mut progressed = false;
            for i in 0..RECIPES.len() {
                if crafted[i] || !recipe_unlocked(i, &crafted) { continue; }
                crafted[i] = true;
                progressed = true;
            }
            if !progressed { break; }
        }
        let stranded: Vec<_> = crafted.iter().enumerate()
            .filter(|(_, c)| !**c)
            .map(|(i, _)| format!("{} (needs {})", RECIPES[i].out, RECIPES[i].unlocked_by))
            .collect();
        assert!(stranded.is_empty(), "these recipes can never be crafted: {stranded:?}");
    }

    // The gate has to actually withhold: a locked recipe must not quietly consume the ingredients.
    #[test]
    fn a_locked_recipe_crafts_nothing() {
        // Diamond armour sits behind the diamond pickaxe.
        let idx = RECIPES.iter().position(|r| r.out == 176).expect("diamond chestplate");
        let need = RECIPES[idx].n1;
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot::default(); }
        inv.slots[0] = InvSlot { id: 155, count: need };

        let mut crafted = vec![false; RECIPES.len()];
        assert!(!recipe_unlocked(idx, &crafted), "it should start locked");
        assert!(!inv.craft(idx, &crafted), "a locked recipe must refuse");
        assert_eq!(inv.count_of(155), need, "and must not take the diamonds");
        assert_eq!(inv.count_of(176), 0);

        // Crafting the prerequisite opens it.
        let pick = RECIPES.iter().position(|r| r.out == RECIPES[idx].unlocked_by).unwrap();
        crafted[pick] = true;
        assert!(recipe_unlocked(idx, &crafted));
        assert!(inv.craft(idx, &crafted), "now it should go through");
        assert_eq!(inv.count_of(155), 0);
        assert!(inv.count_of(176) > 0);
    }

    // A root recipe has to work from a standing start, or a new world is unplayable.
    #[test]
    fn the_roots_need_nothing_crafted_first() {
        let none = vec![false; RECIPES.len()];
        let planks = RECIPES.iter().position(|r| r.out == Block::Planks as Id).unwrap();
        assert!(recipe_unlocked(planks, &none));

        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot::default(); }
        inv.slots[0] = InvSlot { id: Block::Wood as Id, count: 1 };
        assert!(inv.craft(planks, &none), "logs into planks must work on the first day");
        assert!(inv.count_of(Block::Planks as Id) > 0);
    }

    // Crafting into a full inventory must not eat the ingredients and drop the result.
    #[test]
    fn crafting_into_a_full_inventory_is_refused() {
        let planks = RECIPES.iter().position(|r| r.out == Block::Planks as Id).unwrap();
        let none = vec![false; RECIPES.len()];
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot { id: Block::Dirt as Id, count: STACK }; }
        inv.slots[0] = InvSlot { id: Block::Wood as Id, count: STACK };

        assert!(!inv.craft(planks, &none), "nowhere to put the planks");
        assert_eq!(inv.count_of(Block::Wood as Id), STACK, "the logs must survive");
    }

    // A tool needs a whole empty slot. Crafting one into a full inventory used to consume the
    // ingredients and drop the tool on the floor, because the room check skipped durable output.
    #[test]
    fn crafting_a_tool_with_no_empty_slot_is_refused() {
        let idx = RECIPES.iter().position(|r| r.out == 163).expect("wood pickaxe");
        let none = vec![false; RECIPES.len()];
        let mut inv = Inventory::default();
        // Every slot occupied, but the two ingredients are present in the stacks that fill it.
        for s in inv.slots.iter_mut() { *s = InvSlot { id: Block::Dirt as Id, count: STACK }; }
        inv.slots[0] = InvSlot { id: Block::Planks as Id, count: STACK };
        inv.slots[1] = InvSlot { id: 159, count: STACK };

        assert!(!inv.craft(idx, &none), "there is nowhere to put a pickaxe");
        assert_eq!(inv.count_of(Block::Planks as Id), STACK, "the planks must survive");
        assert_eq!(inv.count_of(159), STACK, "the sticks must survive");
        assert_eq!(inv.count_of(163), 0);

        // Free one slot and it goes through.
        inv.slots[5] = InvSlot::default();
        assert!(inv.craft(idx, &none));
        assert!(inv.count_of(163) > 0);
    }

    // Same hazard on the trade path: a forged tool with nowhere to go must not eat the payment.
    #[test]
    fn trading_for_a_tool_with_no_empty_slot_is_refused() {
        use crate::villager::{Offer, EMERALD};
        let forge = Offer { cost: EMERALD, cost_n: 4, cost2: 154, cost2_n: 2, give: 167, give_n: 1 };
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot { id: Block::Dirt as Id, count: STACK }; }
        inv.slots[0] = InvSlot { id: EMERALD, count: STACK };
        inv.slots[1] = InvSlot { id: 154, count: STACK };

        assert!(!inv.trade_offer(&forge), "nowhere to put the pickaxe");
        assert_eq!(inv.count_of(EMERALD), STACK, "the emeralds must survive");
        assert_eq!(inv.count_of(154), STACK);
    }

    #[test]
    fn recipe_tables_are_well_formed() {
        for (i, &Recipe { in1, n1, in2, n2, out, out_n, .. }) in RECIPES.iter().enumerate() {
            assert!(in1 != 0 && n1 > 0, "recipe {i} has no first ingredient");
            assert!(out != 0 && out_n > 0, "recipe {i} has no output");
            assert!(in2 != 0 || n2 == 0, "recipe {i} has a count for a missing second ingredient");
            assert!(in2 == 0 || n2 > 0, "recipe {i} has a second ingredient with no count");
        }
        for (i, s) in SMELTING.iter().enumerate() {
            assert!(s.in1 != 0 && s.n1 > 0, "smelt {i} has no input");
            assert!(s.out != 0 && s.out_n > 0, "smelt {i} has no output");
            assert!(s.secs > 0.0, "smelt {i} would finish instantly");
        }
    }

    // The alloy line is the Matcha progression gate: it must stay blast-furnace only, and the
    // three Blessings must remain craftable so they aren't creative-only content again.
    #[test]
    fn alloy_line_is_gated_and_blessings_are_craftable() {
        let steel = SMELTING.iter().find(|s| s.out == 195).expect("steel must be smeltable");
        let adamant = SMELTING.iter().find(|s| s.out == 196).expect("adamant must be smeltable");
        assert!(steel.blast && adamant.blast, "the alloy line must require a blast furnace");
        // Every blessing in the pantheon must be reachable in normal play, not creative-only.
        for b in crate::blessing::PANTHEON.iter() {
            assert!(RECIPES.iter().any(|r| r.out == b.id), "{} has no recipe", b.name);
        }
    }

    // Blessings are attuned, not eaten. If one ever regains a food entry it would be consumed for a
    // short buff instead of being bound.
    #[test]
    fn blessings_are_not_edible() {
        for b in crate::blessing::PANTHEON.iter() {
            assert!(crate::item::food_effects(b.id).is_none(), "{} must not be food", b.name);
        }
    }

    #[test]
    fn smelting_consumes_inputs_and_fuel() {
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot::default(); }
        inv.slots[0] = InvSlot { id: 19, count: 3 };  // 3 iron ore
        inv.slots[1] = InvSlot { id: 157, count: 1 }; // 1 coal

        let recipe = SMELTING.iter().find(|s| s.out == 154).unwrap();
        assert!(inv.can_smelt(recipe));
        assert_eq!(inv.consume_fuel(&[recipe.in1, recipe.in2]), Some(fuel_secs(157)));
        assert_eq!(inv.count_of(157), 0, "the coal should be burnt");

        inv.take_smelt_inputs(recipe);
        inv.give_smelt_output(recipe);
        assert_eq!(inv.count_of(19), 2, "one ore should be consumed");
        assert_eq!(inv.count_of(154), 1, "one ingot should be produced");

        // With no fuel left the furnace can't run again.
        assert_eq!(inv.consume_fuel(&[recipe.in1, recipe.in2]), None);
    }

    // Sulfur is both a fuel and the second half of the steel recipe. Burning it would consume the
    // input and leave the player with nothing.
    #[test]
    fn a_recipe_never_burns_its_own_ingredients() {
        let steel = SMELTING.iter().find(|s| s.out == 195).unwrap();
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot::default(); }
        inv.slots[0] = InvSlot { id: 154, count: 1 };  // iron ingot
        inv.slots[1] = InvSlot { id: 192, count: 1 };  // the only sulfur, also a valid fuel

        assert!(inv.can_smelt(steel));
        assert_eq!(inv.consume_fuel(&[steel.in1, steel.in2]), None, "the sulfur input must be spared");
        assert_eq!(inv.count_of(192), 1);
        assert!(inv.can_smelt(steel), "the recipe must still be runnable");
    }

    // Coal exists to be burnt; planks and logs are building material, so reach for coal first.
    #[test]
    fn dedicated_fuel_is_burnt_before_building_material() {
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot::default(); }
        inv.slots[0] = InvSlot { id: 10, count: 4 };   // planks, earlier in the inventory
        inv.slots[5] = InvSlot { id: 157, count: 2 };  // coal

        assert_eq!(inv.consume_fuel(&[]), Some(fuel_secs(157)));
        assert_eq!(inv.count_of(10), 4, "the planks should be untouched");
        assert_eq!(inv.count_of(157), 1);
    }

    // A full inventory must not let a furnace eat its inputs and throw the result away.
    #[test]
    fn a_full_inventory_has_no_room() {
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot { id: 2, count: STACK }; }
        assert!(!inv.has_room_for(154, 1), "no free slot and no matching stack");
        assert!(!inv.has_room_for(2, 1), "every dirt stack is already full");

        inv.slots[3] = InvSlot { id: 154, count: STACK - 2 };
        assert!(inv.has_room_for(154, 2));
        assert!(!inv.has_room_for(154, 3), "only 2 of 3 would fit");

        inv.slots[4] = InvSlot::default();
        assert!(inv.has_room_for(154, 3));
        assert!(inv.has_room_for(169, 1), "an empty slot can hold a tool");
    }

    // The stonecutter is the only route to slabs and stairs, so every shape must be reachable and
    // no cut may create something from nothing.
    #[test]
    fn the_stonecutter_reaches_every_shape() {
        let cuts = cut_variants();
        for m in CUTTABLE {
            let slab = m.slab_of().unwrap() as Id;
            let stairs = m.stairs_of().unwrap() as Id;
            assert!(cuts.iter().any(|c| c.input == m as Id && c.output == slab), "{m:?} -> slab missing");
            assert!(cuts.iter().any(|c| c.input == m as Id && c.output == stairs), "{m:?} -> stairs missing");
            assert!(cuts.iter().any(|c| c.input == slab && c.output == stairs), "slab -> stairs missing");
            assert!(cuts.iter().any(|c| c.input == stairs && c.output == slab), "stairs -> slab missing");
        }
        for c in &cuts {
            assert!(c.input != 0 && c.output != 0, "a cut with no input or output");
            assert_ne!(c.input, c.output, "a cut must actually change the block");
            assert!(c.count >= 1 && c.count <= 2, "cut yields should stay modest, got {}", c.count);
        }
    }

    #[test]
    fn cutting_consumes_one_block_and_yields_the_shape() {
        let cuts = cut_variants();
        let idx = cuts.iter().position(|c| c.input == Block::Stone as Id && c.output == Block::StoneSlab as Id).unwrap();
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot::default(); }
        inv.slots[0] = InvSlot { id: Block::Stone as Id, count: 3 };

        assert!(inv.cut(idx));
        assert_eq!(inv.count_of(Block::Stone as Id), 2, "one stone consumed");
        assert_eq!(inv.count_of(Block::StoneSlab as Id), 2, "a stone block yields two slabs");

        // With no input left the cut must refuse rather than conjure slabs.
        inv.remove_count(Block::Stone as Id, 99);
        assert!(!inv.cut(idx));
        assert_eq!(inv.count_of(Block::StoneSlab as Id), 2);
    }

    // A full inventory must not let the stonecutter eat the input and drop the result.
    #[test]
    fn cutting_into_a_full_inventory_is_refused() {
        let cuts = cut_variants();
        let idx = cuts.iter().position(|c| c.input == Block::Stone as Id && c.output == Block::StoneSlab as Id).unwrap();
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot { id: Block::Dirt as Id, count: STACK }; }
        inv.slots[0] = InvSlot { id: Block::Stone as Id, count: STACK };

        assert!(!inv.cut(idx), "nowhere to put the slabs");
        assert_eq!(inv.count_of(Block::Stone as Id), STACK, "the input must survive a refused cut");
    }

    #[test]
    fn only_listed_items_burn() {
        assert!(fuel_secs(157) > 0.0);   // coal
        assert!(fuel_secs(10) > 0.0);    // planks
        assert_eq!(fuel_secs(155), 0.0); // diamonds are not firewood
        assert_eq!(fuel_secs(0), 0.0);
    }

    // A two-ingredient trade must be all-or-nothing: the emeralds can't disappear when the player is
    // short on the material half.
    #[test]
    fn a_half_affordable_trade_takes_nothing() {
        use crate::villager::{Offer, EMERALD};
        let forge = Offer { cost: EMERALD, cost_n: 4, cost2: 154, cost2_n: 2, give: 167, give_n: 1 };
        let mut inv = Inventory::default();
        inv.slots[0] = InvSlot { id: EMERALD, count: 8 };
        inv.slots[1] = InvSlot { id: 154, count: 1 };

        assert!(!inv.trade_offer(&forge), "one iron ingot short");
        assert_eq!(inv.count_of(EMERALD), 8, "the emeralds must survive a refused trade");
        assert_eq!(inv.count_of(154), 1);

        inv.slots[1].count = 2;
        assert!(inv.trade_offer(&forge));
        assert_eq!(inv.count_of(EMERALD), 4);
        assert_eq!(inv.count_of(154), 0);
        assert!(inv.count_of(167) > 0, "the pickaxe arrives with durability");
    }

    #[test]
    fn trading_into_a_full_inventory_is_refused() {
        use crate::villager::{Offer, EMERALD};
        let bulk = Offer { cost: EMERALD, cost_n: 1, cost2: 0, cost2_n: 0, give: Block::Stone as Id, give_n: 8 };
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot { id: Block::Dirt as Id, count: STACK }; }
        inv.slots[0] = InvSlot { id: EMERALD, count: 4 };

        assert!(!inv.trade_offer(&bulk), "nowhere to put the stone");
        assert_eq!(inv.count_of(EMERALD), 4, "the payment must survive a refused trade");
    }
}
