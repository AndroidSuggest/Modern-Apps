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
// Item ids 154+ are materials/tools (see item.rs). Crafting and smelting both flow through this table
// (the Furnace menu opens the same crafting UI).
pub const RECIPES: [(u8, i32, u8, i32, u8, i32); 38] = [
    (154, 1, 157, 1, 186, 1), // iron + coal -> flint & steel
    (187, 1, Block::Glass as u8, 5, Block::Beacon as u8, 1), // nether star + glass -> beacon
    (138, 2, 0, 0, 189, 3), // gunpowder -> firework rockets
    (Block::Snow as u8, 1, 0, 0, 190, 4), // snow -> snowballs
    (Block::Wood as u8,      1, 0, 0, Block::Planks as u8,      4),
    (Block::BirchLog as u8,  1, 0, 0, Block::BirchPlanks as u8, 4),
    (Block::SpruceLog as u8, 1, 0, 0, Block::SprucePlanks as u8,4),
    (Block::Planks as u8,    4, 0, 0, Block::CraftingTable as u8, 1),
    (Block::Cobble as u8,    8, 0, 0, Block::Furnace as u8,     1),
    (Block::Sand as u8,      2, 0, 0, Block::Glass as u8,       1),
    (Block::Cobble as u8,    4, 0, 0, Block::Stone as u8,       1),
    (Block::Diorite as u8,   4, 0, 0, Block::PolishedDiorite as u8, 4),
    (Block::Planks as u8,    2, 0, 0, 159 /*Stick*/,            4),
    (Block::Clay as u8,      4, 0, 0, Block::Brick as u8,       1),
    // Smelting: ore -> material.
    (Block::CoalOre as u8,   1, 0, 0, 157 /*Coal*/,             1),
    (Block::IronOre as u8,   1, 0, 0, 154 /*Iron Ingot*/,       1),
    (Block::DiamondOre as u8,1, 0, 0, 155 /*Diamond*/,          1),
    (Block::EmeraldOre as u8,1, 0, 0, 156 /*Emerald*/,          1),
    (Block::RedstoneOre as u8,1,0, 0, 158 /*Redstone*/,         1),
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
];

// Villager trades: (cost_id, cost_count, give_id, give_count). Emerald = item 156.
pub const TRADES: [(u8, i32, u8, i32); 7] = [
    (156, 3, 168, 1),  // 3 Emerald -> Iron Sword
    (156, 6, 172, 1),  // 6 Emerald -> Iron Chestplate
    (156, 2, 133, 1),  // 2 Emerald -> Golden Apple
    (156, 1, 131, 6),  // 1 Emerald -> 6 Bread
    (156, 4, 128, 1),  // 4 Emerald -> Estus Flask
    (157, 12, 156, 1), // 12 Coal -> 1 Emerald
    (137, 6, 156, 2),  // 6 Leather -> 2 Emerald
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
    pub fn add_block(&mut self, id: u8) {
        if id == 0 { return; }
        for slot in self.slots.iter_mut() { if slot.id == id && slot.count < STACK { slot.count += 1; return; } }
        for slot in self.slots.iter_mut() { if slot.id == 0 { slot.id = id; slot.count = 1; return; } }
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
    pub fn add_item_with_count(&mut self, id: u8, count: i32) {
        for slot in self.slots.iter_mut() { if slot.id == 0 { slot.id = id; slot.count = count; return; } }
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
    // Execute a villager trade if the player can afford it.
    pub fn trade(&mut self, idx: usize) -> bool {
        let Some(&(c, cn, g, gn)) = TRADES.get(idx) else { return false; };
        if self.count_of(c) < cn { return false; }
        self.remove_count(c, cn);
        if crate::item::has_durability(g) { self.add_item_with_count(g, crate::item::max_durability(g)); }
        else { for _ in 0..gn { self.add_block(g); } }
        true
    }
    pub fn armor_defense(&self) -> f32 { self.armor.iter().map(|s| if s.id != 0 { crate::item::armor_defense(s.id) } else { 0.0 }).sum() }
    // Wear down each equipped piece by 1 when the player is hurt.
    pub fn damage_armor(&mut self) {
        for s in self.armor.iter_mut() { if s.id != 0 { s.count -= 1; if s.count <= 0 { *s = InvSlot::default(); } } }
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
