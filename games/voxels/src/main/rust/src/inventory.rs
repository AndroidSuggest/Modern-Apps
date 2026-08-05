use serde::{Deserialize, Serialize};
use crate::world::block::Block;

pub const SLOTS: usize = 36;   // 0..9 = hotbar, 9..36 = main inventory
pub const HOTBAR: usize = 9;
pub const STACK: i32 = 64;

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct InvSlot { pub id: u8, pub count: i32 }
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

// (in1_id, in1_count, in2_id, in2_count, out_id, out_count). in2_id == 0 means a single ingredient.
// Item ids 154+ are materials/tools (see item.rs). Ore -> material conversions live in SMELTING
// instead, since those need a furnace, fuel and time.
pub const RECIPES: [(u8, i32, u8, i32, u8, i32); 91] = [
    (154, 1, 157, 1, 186, 1), // iron + coal -> flint & steel
    (187, 1, Block::Glass as u8, 5, Block::Beacon as u8, 1), // nether star + glass -> beacon
    (138, 2, 0, 0, 189, 3), // gunpowder -> firework rockets
    (Block::Snow as u8, 1, 0, 0, 190, 4), // snow -> snowballs
    (Block::Wood as u8,      1, 0, 0, Block::Planks as u8,      4),
    (Block::BirchLog as u8,  1, 0, 0, Block::BirchPlanks as u8, 4),
    (Block::SpruceLog as u8, 1, 0, 0, Block::SprucePlanks as u8,4),
    (Block::Planks as u8,    4, 0, 0, Block::CraftingTable as u8, 1),
    (Block::Cobble as u8,    8, 0, 0, Block::Furnace as u8,     1),
    (Block::Diorite as u8,   4, 0, 0, Block::PolishedDiorite as u8, 4),
    (Block::Planks as u8,    2, 0, 0, 159 /*Stick*/,            4),
    // Material -> block.
    (154, 9, 0, 0, Block::IronBlock as u8,    1),
    (155, 9, 0, 0, Block::DiamondBlock as u8, 1),
    (156, 9, 0, 0, Block::EmeraldBlock as u8, 1),
    // Tools: material + stick(159).
    (Block::Planks as u8, 3, 159, 2, 163, 1), // wood pickaxe
    (Block::Planks as u8, 2, 159, 1, 164, 1), // wood sword
    (Block::Cobble as u8, 3, 159, 2, 165, 1), // stone pickaxe
    (Block::Cobble as u8, 2, 159, 1, 166, 1), // stone sword
    (154, 3, 159, 2, 167, 1),                 // iron pickaxe
    (154, 2, 159, 1, 168, 1),                 // iron sword
    (155, 3, 159, 2, 169, 1),                 // diamond pickaxe
    (155, 2, 159, 1, 170, 1),                 // diamond sword
    // Armor: material only.
    (154, 5, 0, 0, 171, 1), (154, 8, 0, 0, 172, 1), (154, 7, 0, 0, 173, 1), (154, 4, 0, 0, 174, 1), // iron
    (155, 5, 0, 0, 175, 1), (155, 8, 0, 0, 176, 1), (155, 7, 0, 0, 177, 1), (155, 4, 0, 0, 178, 1), // diamond
    // --- Matcha alloy tier ---
    // Adamant gear: the tier above diamond.
    (196, 3, 159, 2, 197, 1), // adamant pickaxe
    (196, 2, 159, 1, 198, 1), // adamant sword
    (196, 5, 0, 0, 199, 1), (196, 8, 0, 0, 200, 1), (196, 7, 0, 0, 201, 1), (196, 4, 0, 0, 202, 1),
    // Metal storage blocks (silver/steel/adamant also power beacons).
    (193, 9, 0, 0, Block::SilverBlock as u8,  1),
    (195, 9, 0, 0, Block::SteelBlock as u8,   1),
    (196, 9, 0, 0, Block::AdamantBlock as u8, 1),
    // A steel-lined furnace: the only place the alloy recipes will smelt.
    (Block::Furnace as u8, 1, 195, 5, Block::BlastFurnace as u8, 1),
    // Blessings: quicksilver charms bound to a thematic offering. Attuning one grants a permanent
    // passive (see blessing.rs), so the ingredient cost tracks roughly how strong the passive is.
    (194, 2, 193, 1, 160, 1),  // silver          -> Clement, swift of foot
    (194, 2, 154, 4, 161, 1),  // iron            -> Ares, might
    (194, 2, 156, 2, 162, 1),  // emerald         -> Yamm, the deep
    (194, 2, 195, 3, 203, 1),  // steel           -> Daedalus, tools never wear
    (194, 2, 189, 4, 204, 1),  // fireworks       -> Icarus, no fall damage
    (194, 2, 192, 6, 205, 1),  // sulfur          -> Yama, immune to fire
    (194, 2, Block::Obsidian as u8, 4, 206, 1),   // Talos, crushing blows
    (194, 2, 187, 1, 207, 1),  // nether star     -> the God King, smite the undead
    (194, 2, 138, 8, 208, 1),  // gunpowder       -> Arachnae, bane of horrors
    (194, 2, 196, 2, 209, 1),  // adamant         -> Prometheus, armor never wears
    (194, 2, 155, 3, 210, 1),  // diamond         -> Lu Ban, mending
    (194, 2, Block::EmeraldBlock as u8, 1, 211, 1), // Eros, fortune
    (194, 2, 191, 3, 212, 1),  // ender pearls    -> Will, reach
    (194, 2, Block::Glowstone as u8, 4, 213, 1),  // Hyacinthus, second jump
    (194, 2, Block::Purpur as u8, 6, 214, 1),     // Aeolus, wind burst
    (194, 2, Block::Sculk as u8, 8, 215, 1),      // Cronus, swift sneak
    (194, 2, Block::BlueIce as u8, 4, 216, 1),    // Demeter, frost walker
    (194, 2, Block::SeaLantern as u8, 4, 217, 1), // Glaucus, sea luck
    (194, 2, Block::Amethyst as u8, 6, 218, 1),   // Apollo, marksman
    (194, 2, 190, 16, 219, 1), // snowballs       -> Artemis, multishot
    (194, 2, Block::WardingStone as u8, 2, 220, 1), // Warding, thorns
    (194, 2, Block::DiamondBlock as u8, 1, 221, 1), // Paris, infinity
    // The five late additions cost the alloy tier, so they arrive after the Blast Furnace does.
    (194, 2, Block::IronBlock as u8, 2, 245, 1),    // Athena, absorption shield
    (194, 2, Block::Magma as u8, 4, 246, 1),        // Sekhmet, bloodrage
    (194, 2, 222, 12, 247, 1), // raw meat        -> Camazotz, lifesteal
    (194, 2, Block::Prismarine as u8, 8, 248, 1),   // Tangaroa, conduit
    (194, 2, Block::Sculk as u8, 4, 249, 1),        // Anubis, ward undead
    // --- Matcha's kitchen. Cooked meat is the base ingredient; everything else builds on it. ---
    (223, 1, 131, 1, 224, 1),  // cooked meat + bread          -> ramen
    (223, 2, 135, 2, 225, 1),  // cooked meat + carrot         -> japanese curry
    (132, 2, 135, 2, 226, 1),  // cooked fish + carrot         -> green curry
    (146, 2, 131, 1, 227, 1),  // baked potato + bread         -> gnocchi
    (131, 2, 0, 0, 228, 2),    // bread                        -> naan
    (131, 1, 223, 2, 229, 1),  // bread + cooked meat          -> pupusa
    (146, 3, 0, 0, 230, 1),    // baked potato                 -> latke
    (131, 1, 130, 2, 231, 1),  // bread + apple                -> bruschetta
    (131, 1, 149, 1, 232, 1),  // bread + fried egg            -> french toast
    (131, 1, 152, 1, 233, 1),  // bread + glow berry crumble   -> sweet berry danish
    (136, 2, Block::Snow as u8, 2, 234, 1), // melon + snow    -> melon sorbet
    (223, 3, 131, 1, 235, 1),  // cooked meat + bread          -> stroganoff
    (130, 2, 131, 1, 151, 1),  // apple + bread                -> apple empanada
    (130, 1, Block::Glowstone as u8, 1, 152, 1), // apple + glowstone -> glow berry crumble
    // --- Bronze: Matcha alloys copper with gold, landing between iron and diamond. ---
    // 236 copper ingot, 237 gold ingot, 238 bronze ingot.
    (238, 3, 159, 2, 239, 1), // bronze pickaxe
    (238, 2, 159, 1, 240, 1), // bronze sword
    (238, 5, 0, 0, 241, 1), (238, 8, 0, 0, 242, 1), (238, 7, 0, 0, 243, 1), (238, 4, 0, 0, 244, 1),
    (236, 9, 0, 0, Block::CopperBlock as u8, 1),
    (237, 9, 0, 0, Block::GoldBlock as u8,   1),
    (238, 9, 0, 0, Block::BronzeBlock as u8, 1),
    // The stonecutter itself: an iron blade on a stone bed.
    (Block::Stone as u8, 3, 154, 1, Block::Stonecutter as u8, 1),
];

// Furnace recipes. Unlike crafting these cost fuel and take `secs` of real time, and the ones marked
// `blast` only run in a Blast Furnace — that gate is what makes the steel/adamant line an unlock
// rather than just another recipe.
pub struct Smelt {
    pub in1: u8, pub n1: i32,
    pub in2: u8, pub n2: i32,
    pub out: u8, pub out_n: i32,
    pub secs: f32,
    pub blast: bool,
}
const fn smelt(in1: u8, n1: i32, in2: u8, n2: i32, out: u8, out_n: i32, secs: f32, blast: bool) -> Smelt {
    Smelt { in1, n1, in2, n2, out, out_n, secs, blast }
}
pub const SMELTING: [Smelt; 18] = [
    smelt(Block::CoalOre as u8,     1, 0, 0, 157, 1,  6.0, false), // coal
    smelt(Block::IronOre as u8,     1, 0, 0, 154, 1,  8.0, false), // iron ingot
    smelt(Block::DiamondOre as u8,  1, 0, 0, 155, 1, 10.0, false),
    smelt(Block::EmeraldOre as u8,  1, 0, 0, 156, 1, 10.0, false),
    smelt(Block::RedstoneOre as u8, 1, 0, 0, 158, 1,  6.0, false),
    smelt(Block::SilverOre as u8,   1, 0, 0, 193, 1,  9.0, false), // silver ingot
    smelt(Block::Sand as u8,        2, 0, 0, Block::Glass as u8,  1, 5.0, false),
    smelt(Block::Cobble as u8,      4, 0, 0, Block::Stone as u8,  1, 5.0, false),
    smelt(Block::Clay as u8,        4, 0, 0, Block::Brick as u8,  1, 6.0, false),
    smelt(222, 1, 0, 0, 223, 1, 6.0, false), // raw meat -> cooked meat
    smelt(131, 1, 0, 0, 147, 3, 5.0, false), // bread -> cookies (baking)
    smelt(Block::CopperOre as u8, 1, 0, 0, 236, 1, 6.0, false), // copper ingot
    smelt(Block::GoldOre as u8,   1, 0, 0, 237, 1, 8.0, false), // gold ingot
    smelt(236, 6, 237, 1, 238, 1, 14.0, true),                  // copper + gold -> bronze
    // Blast furnace only: the alloy line.
    smelt(Block::SulfurOre as u8,   1, 0, 0, 192, 2,  5.0, true),  // sulfur
    smelt(Block::CinnabarOre as u8, 1, 0, 0, 194, 1,  7.0, true),  // quicksilver
    smelt(154, 1, 192, 1, 195, 1, 12.0, true),                     // iron + sulfur -> steel
    smelt(195, 4, 194, 2, 196, 1, 20.0, true),                     // steel + quicksilver -> adamant
];

// Seconds of furnace burn a stack item is worth. Anything not listed can't be used as fuel.
pub fn fuel_secs(id: u8) -> f32 {
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
const DEDICATED_FUELS: [u8; 3] = [157, 84, 192];

// Villager trades live in `villager.rs`, tiered per profession. Emerald = item 156.

// Which shelf of the crafting menu a recipe belongs on. Purely for the UI — the recipe table itself
// stays a flat list so indices remain stable.
pub fn recipe_category(out: u8) -> &'static str {
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
pub struct Cut { pub input: u8, pub output: u8, pub count: i32 }

/// Every stonecutter conversion, derived from the block table so a new slab family is picked up for
/// free. A cube yields two slabs or one stair, and either shape converts back to the other.
pub fn cut_variants() -> Vec<Cut> {
    let mut out = Vec::new();
    for material in CUTTABLE {
        let (Some(slab), Some(stairs)) = (material.slab_of(), material.stairs_of()) else { continue; };
        let (m, s, st) = (material as u8, slab as u8, stairs as u8);
        out.push(Cut { input: m, output: s, count: 2 });
        out.push(Cut { input: m, output: st, count: 1 });
        out.push(Cut { input: s, output: st, count: 1 });
        out.push(Cut { input: st, output: s, count: 1 });
    }
    // Decorative conversions between whole blocks of the same family.
    for &(input, output) in DECOR_CUTS {
        out.push(Cut { input: input as u8, output: output as u8, count: 1 });
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
        for (i, (b, c)) in start.iter().enumerate() { slots[i] = InvSlot { id: *b as u8, count: *c }; }
        Self { selected: 0, slots, placed: 0, broken: 0, armor: [InvSlot::default(); 4] }
    }
}

impl Inventory {
    pub fn selected_block(&self) -> u8 {
        if self.selected < HOTBAR { let s = &self.slots[self.selected]; if s.count > 0 { s.id } else { 0 } } else { 0 }
    }
    pub fn consume_selected(&mut self) -> Option<u8> {
        if self.selected >= HOTBAR { return None; }
        let slot = &mut self.slots[self.selected];
        if slot.count <= 0 || slot.id == 0 { return None; }
        let id = slot.id;
        slot.count -= 1;
        if slot.count <= 0 { *slot = InvSlot::default(); }
        self.placed += 1;
        Some(id)
    }
    pub fn add_block(&mut self, id: u8) { self.try_add_block(id); }
    // Add one unit, reporting whether it actually fit anywhere.
    pub fn try_add_block(&mut self, id: u8) -> bool {
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
    pub fn give(&mut self, id: u8) {
        if id == 0 { return; }
        for slot in self.slots.iter_mut() { if slot.id == 0 { slot.id = id; slot.count = STACK; return; } }
        for slot in self.slots.iter_mut() { if slot.id == id { slot.count = STACK; return; } }
    }

    fn count_of(&self, id: u8) -> i32 { self.slots.iter().filter(|s| s.id == id).map(|s| s.count).sum() }
    fn remove_count(&mut self, id: u8, mut n: i32) {
        for slot in self.slots.iter_mut() {
            if slot.id == id && n > 0 {
                let take = slot.count.min(n);
                slot.count -= take; n -= take;
                if slot.count <= 0 { *slot = InvSlot::default(); }
            }
        }
    }
    pub fn craft(&mut self, recipe: usize) -> bool {
        let Some(&(i1, c1, i2, c2, oid, oc)) = RECIPES.get(recipe) else { return false; };
        if self.count_of(i1) < c1 { return false; }
        if i2 != 0 && self.count_of(i2) < c2 { return false; }
        self.remove_count(i1, c1);
        if i2 != 0 { self.remove_count(i2, c2); }
        if crate::item::has_durability(oid) { self.add_item_with_count(oid, crate::item::max_durability(oid)); }
        else { for _ in 0..oc { self.add_block(oid); } }
        true
    }
    // Place a non-stacking item (tool/armor) into the first empty slot with a given count (durability).
    pub fn add_item_with_count(&mut self, id: u8, count: i32) -> bool {
        for slot in self.slots.iter_mut() { if slot.id == 0 { slot.id = id; slot.count = count; return true; } }
        false
    }
    pub fn selected_count(&self) -> i32 { if self.selected < HOTBAR { self.slots[self.selected].count } else { 0 } }
    // Remove the whole selected stack, returning (id, count). Used to equip armor without losing durability.
    pub fn take_selected(&mut self) -> Option<(u8, i32)> {
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
    pub fn equip_armor(&mut self, id: u8, dur: i32) -> Option<InvSlot> {
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
        if !crate::item::has_durability(o.give) && !self.has_room_for(o.give, give_n) { return false; }
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
    pub fn has_room_for(&self, id: u8, n: i32) -> bool {
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
    pub fn consume_fuel(&mut self, spare: &[u8]) -> Option<f32> {
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
        for &(_, _, _, _, out, _) in RECIPES.iter() {
            let cat = recipe_category(out);
            assert!(SHELVES.contains(&cat), "recipe for {out} has unknown category {cat}");
        }
        // Each shelf should actually have something on it.
        for shelf in SHELVES {
            assert!(RECIPES.iter().any(|r| recipe_category(r.4) == shelf), "shelf {shelf} is empty");
        }
    }

    #[test]
    fn recipe_tables_are_well_formed() {
        for (i, &(in1, n1, in2, n2, out, out_n)) in RECIPES.iter().enumerate() {
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
            assert!(RECIPES.iter().any(|r| r.4 == b.id), "{} has no recipe", b.name);
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
            let slab = m.slab_of().unwrap() as u8;
            let stairs = m.stairs_of().unwrap() as u8;
            assert!(cuts.iter().any(|c| c.input == m as u8 && c.output == slab), "{m:?} -> slab missing");
            assert!(cuts.iter().any(|c| c.input == m as u8 && c.output == stairs), "{m:?} -> stairs missing");
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
        let idx = cuts.iter().position(|c| c.input == Block::Stone as u8 && c.output == Block::StoneSlab as u8).unwrap();
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot::default(); }
        inv.slots[0] = InvSlot { id: Block::Stone as u8, count: 3 };

        assert!(inv.cut(idx));
        assert_eq!(inv.count_of(Block::Stone as u8), 2, "one stone consumed");
        assert_eq!(inv.count_of(Block::StoneSlab as u8), 2, "a stone block yields two slabs");

        // With no input left the cut must refuse rather than conjure slabs.
        inv.remove_count(Block::Stone as u8, 99);
        assert!(!inv.cut(idx));
        assert_eq!(inv.count_of(Block::StoneSlab as u8), 2);
    }

    // A full inventory must not let the stonecutter eat the input and drop the result.
    #[test]
    fn cutting_into_a_full_inventory_is_refused() {
        let cuts = cut_variants();
        let idx = cuts.iter().position(|c| c.input == Block::Stone as u8 && c.output == Block::StoneSlab as u8).unwrap();
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot { id: Block::Dirt as u8, count: STACK }; }
        inv.slots[0] = InvSlot { id: Block::Stone as u8, count: STACK };

        assert!(!inv.cut(idx), "nowhere to put the slabs");
        assert_eq!(inv.count_of(Block::Stone as u8), STACK, "the input must survive a refused cut");
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
        let bulk = Offer { cost: EMERALD, cost_n: 1, cost2: 0, cost2_n: 0, give: Block::Stone as u8, give_n: 8 };
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot { id: Block::Dirt as u8, count: STACK }; }
        inv.slots[0] = InvSlot { id: EMERALD, count: 4 };

        assert!(!inv.trade_offer(&bulk), "nowhere to put the stone");
        assert_eq!(inv.count_of(EMERALD), 4, "the payment must survive a refused trade");
    }
}
