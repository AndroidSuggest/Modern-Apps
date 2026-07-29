// Non-block inventory items (food, consumables, materials) and the status-effect model.
// Inventory ids < ITEM_BASE are blocks; ids >= ITEM_BASE are items and are never placeable.
// Design follows the Matcha/Raspberry survival overhaul: no hunger — food applies Regeneration
// (the heal) plus a themed buff; Estus is a fast multi-charge heal; Heart Containers raise max HP.

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Effect { Regeneration, Poison, Resistance, Strength, Speed, Haste, Absorption, FireResistance, NightVision, JumpBoost, Slowness }

impl Effect {
    pub fn key(self) -> &'static str {
        match self {
            Effect::Regeneration => "regen",
            Effect::Poison => "poison",
            Effect::Resistance => "resist",
            Effect::Strength => "strength",
            Effect::Speed => "speed",
            Effect::Haste => "haste",
            Effect::Absorption => "absorb",
            Effect::FireResistance => "fireres",
            Effect::NightVision => "night",
            Effect::JumpBoost => "jump",
            Effect::Slowness => "slow",
        }
    }
}

#[derive(Clone, Copy)]
pub struct ActiveEffect { pub kind: Effect, pub secs: f32, pub amp: u8 }

pub const ITEM_BASE: u8 = 128;
pub fn is_item(id: u8) -> bool { id >= ITEM_BASE }
pub fn is_estus(id: u8) -> bool { id == 128 }
pub fn is_heart_container(id: u8) -> bool { id == 129 }
pub fn is_food(id: u8) -> bool { food_effects(id).is_some() }

// ---- Tools & armor (ids 163..178) ----
// Tools 163..170: {wood,stone,iron,diamond} x {pickaxe,sword}. Armor 171..178: {iron,diamond} x
// {helmet,chestplate,leggings,boots}. Durability is stored in the inventory slot's `count`.
pub fn is_tool(id: u8) -> bool { (163..=170).contains(&id) }
pub fn is_pickaxe(id: u8) -> bool { matches!(id, 163 | 165 | 167 | 169) }
pub fn is_sword(id: u8) -> bool { matches!(id, 164 | 166 | 168 | 170) }
pub fn is_armor(id: u8) -> bool { (171..=178).contains(&id) }
pub fn armor_slot(id: u8) -> usize { ((id - 171) % 4) as usize } // 0 helm, 1 chest, 2 legs, 3 boots
pub fn has_durability(id: u8) -> bool { is_tool(id) || is_armor(id) }

pub fn sword_damage(id: u8) -> f32 { match id { 164 => 5.0, 166 => 6.0, 168 => 7.0, 170 => 9.0, _ => 0.0 } }
pub fn pick_damage(id: u8) -> f32 { match id { 163 => 2.0, 165 => 3.0, 167 => 4.0, 169 => 5.0, _ => 0.0 } }
pub fn armor_defense(id: u8) -> f32 {
    match id {
        171 => 2.0, 172 => 6.0, 173 => 5.0, 174 => 2.0, // iron helm/chest/legs/boots
        175 => 3.0, 176 => 8.0, 177 => 6.0, 178 => 3.0, // diamond
        _ => 0.0,
    }
}
pub fn max_durability(id: u8) -> i32 {
    match id {
        163 | 164 => 60, 165 | 166 => 132, 167 | 168 => 250, 169 | 170 => 1562, // wood/stone/iron/diamond tools
        171..=174 => 240, 175..=178 => 528,                                       // iron/diamond armor
        _ => 0,
    }
}

// Effects applied when a food item is eaten. First entry is the healing Regeneration.
pub fn food_effects(id: u8) -> Option<&'static [(Effect, f32, u8)]> {
    use Effect::*;
    Some(match id {
        130 => &[(Regeneration, 4.0, 0)],                                   // apple
        131 => &[(Regeneration, 6.0, 0)],                                   // bread
        132 => &[(Regeneration, 5.0, 1)],                                   // cooked fish
        133 => &[(Regeneration, 5.0, 1), (Absorption, 120.0, 0), (Resistance, 30.0, 0), (Strength, 60.0, 0)], // golden apple
        134 => &[(Regeneration, 3.6, 2), (Haste, 150.0, 1)],               // brownie
        135 => &[(Regeneration, 4.0, 0), (NightVision, 30.0, 0)],          // carrot (good for your eyes)
        136 => &[(Regeneration, 4.0, 1), (Speed, 30.0, 0)],                // glistering melon slice
        146 => &[(Regeneration, 5.0, 0), (FireResistance, 30.0, 0)],       // baked potato (hot!)
        147 => &[(Regeneration, 2.0, 0)],                                   // cookie
        148 => &[(Regeneration, 5.0, 1)],                                   // cooked salmon
        149 => &[(Regeneration, 4.0, 0)],                                   // fried egg
        150 => &[(Regeneration, 5.0, 1), (JumpBoost, 45.0, 0)],            // cooked rabbit (leaping)
        151 => &[(Regeneration, 4.0, 0), (Speed, 20.0, 0)],                // apple empanada
        152 => &[(Regeneration, 4.0, 0), (NightVision, 90.0, 0)],          // glow berry crumble
        153 => &[(Regeneration, 3.0, 0), (Haste, 60.0, 0)],                // chocolate chip cookie
        // Blessings: charm consumables granting long multi-effect buffs (Matcha's enchant bundles,
        // reimagined for a game without gear).
        160 => &[(Speed, 300.0, 1), (JumpBoost, 300.0, 0)],                // Blessing of Swiftness
        161 => &[(Strength, 300.0, 1), (Resistance, 300.0, 0)],            // Blessing of the Warrior
        162 => &[(NightVision, 300.0, 0), (FireResistance, 300.0, 0)],     // Blessing of the Deep
        _ => return None,
    })
}
