use serde::{Deserialize, Serialize};
use crate::world::block::Block;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InvSlot { pub id: u8, pub count: i32 }
impl Default for InvSlot { fn default() -> Self { Self { id: 0, count: 0 } } }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Inventory { pub selected: usize, pub slots: [InvSlot; 9], pub placed: i32, pub broken: i32 }

impl Default for Inventory {
    fn default() -> Self {
        Self {
            selected: 0,
            slots: std::array::from_fn(|i| match i {
                0 => InvSlot{ id: Block::Grass as u8, count: 64 },
                1 => InvSlot{ id: Block::Dirt as u8, count: 64 },
                2 => InvSlot{ id: Block::Stone as u8, count: 64 },
                3 => InvSlot{ id: Block::Wood as u8, count: 16 },
                4 => InvSlot{ id: Block::Planks as u8, count: 32 },
                5 => InvSlot{ id: Block::Sand as u8, count: 32 },
                6 => InvSlot{ id: Block::Glass as u8, count: 16 },
                7 => InvSlot{ id: Block::Cobble as u8, count: 16 },
                8 => InvSlot{ id: Block::Brick as u8, count: 16 },
                _ => InvSlot::default(),
            }),
            placed: 0, broken: 0,
        }
    }
}

impl Inventory {
    pub fn selected_block(&self) -> u8 {
        if self.selected < 9 { let s = &self.slots[self.selected]; if s.count > 0 { s.id } else { 0 } } else { 0 }
    }
    pub fn consume_selected(&mut self) -> Option<u8> {
        if self.selected >=9 { return None; }
        let slot = &mut self.slots[self.selected];
        if slot.count <=0 || slot.id==0 { return None; }
        let id = slot.id;
        slot.count -=1;
        if slot.count <=0 { slot.id = 0; slot.count = 0; }
        self.placed +=1;
        Some(id)
    }
    pub fn add_block(&mut self, id: u8) {
        if id==0 { return; }
        for slot in self.slots.iter_mut() { if slot.id == id && slot.count < 64 { slot.count +=1; return; } }
        for slot in self.slots.iter_mut() { if slot.id==0 { slot.id = id; slot.count = 1; return; } }
    }
    pub fn select(&mut self, idx: usize) { if idx < 9 { self.selected = idx; } }
    pub fn to_json(&self) -> String { serde_json::to_string(self).unwrap_or_else(|_| r#"{"selected":0,"slots":[]}"#.to_string()) }
}
