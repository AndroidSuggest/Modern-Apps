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
}

// (id, in_count, out_id, out_count) — kept in sync with getRecipesJson for the UI.
pub const RECIPES: [(u8, i32, u8, i32); 10] = [
    (Block::Wood as u8,      1, Block::Planks as u8,      4),
    (Block::BirchLog as u8,  1, Block::BirchPlanks as u8, 4),
    (Block::SpruceLog as u8, 1, Block::SprucePlanks as u8,4),
    (Block::Planks as u8,    4, Block::CraftingTable as u8, 1),
    (Block::Cobble as u8,    8, Block::Furnace as u8,     1),
    (Block::Sand as u8,      2, Block::Glass as u8,       1),
    (Block::Cobble as u8,    4, Block::Stone as u8,       1),
    (Block::Diorite as u8,   4, Block::PolishedDiorite as u8, 4),
    (Block::IronOre as u8,   1, Block::IronBlock as u8,   1),
    (Block::DiamondOre as u8,1, Block::DiamondBlock as u8,1),
];

impl Default for Inventory {
    fn default() -> Self {
        let mut slots = [InvSlot::default(); SLOTS];
        let start = [
            (Block::Grass, 64), (Block::Dirt, 64), (Block::Stone, 64), (Block::Wood, 16),
            (Block::Planks, 32), (Block::Sand, 32), (Block::Glass, 16), (Block::Cobble, 16), (Block::Brick, 16),
        ];
        for (i, (b, c)) in start.iter().enumerate() { slots[i] = InvSlot { id: *b as u8, count: *c }; }
        Self { selected: 0, slots, placed: 0, broken: 0 }
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
        let Some(&(iid, ic, oid, oc)) = RECIPES.get(recipe) else { return false; };
        if self.count_of(iid) < ic { return false; }
        self.remove_count(iid, ic);
        for _ in 0..oc { self.add_block(oid); }
        true
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
