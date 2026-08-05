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
// Matcha alloy line: 192 Sulfur, 193 Silver Ingot, 194 Quicksilver, 195 Steel Ingot,
// 196 Adamant Ingot, 197/198 adamant pickaxe/sword, 199..202 adamant armor.
pub fn is_item(id: u8) -> bool { id >= ITEM_BASE }
pub fn is_estus(id: u8) -> bool { id == 128 }
pub fn is_heart_container(id: u8) -> bool { id == 129 }
pub fn is_food(id: u8) -> bool { food_effects(id).is_some() }

// ---- Tools & armor ----
// Tools 163..170: {wood,stone,iron,diamond} x {pickaxe,sword}. Armor 171..178: {iron,diamond} x
// {helmet,chestplate,leggings,boots}. Matcha's bronze sits between iron and diamond at 239..244,
// and adamant tops the ladder at 197..202. Durability is stored in the inventory slot's `count`.
pub fn is_tool(id: u8) -> bool { (163..=170).contains(&id) || matches!(id, 197 | 198 | 239 | 240 | 252 | 253) }
/// Shears: used on a sheep rather than on terrain, so they're a tool that mines nothing.
pub fn is_shears(id: u8) -> bool { id == 252 }
pub fn is_pickaxe(id: u8) -> bool { matches!(id, 163 | 165 | 167 | 169 | 197 | 239) }
pub fn is_sword(id: u8) -> bool { matches!(id, 164 | 166 | 168 | 170 | 198 | 240) }
pub fn is_elytra(id: u8) -> bool { id == 188 }
pub fn is_armor(id: u8) -> bool {
    (171..=178).contains(&id) || (199..=202).contains(&id) || (241..=244).contains(&id) || id == 188
} // elytra occupies the chest slot
pub fn armor_slot(id: u8) -> usize {
    if id == 188 { 1 }
    else if id >= 241 { (id - 241) as usize }
    else if id >= 199 { (id - 199) as usize }
    else { ((id - 171) % 4) as usize }
} // 0 helm, 1 chest, 2 legs, 3 boots
pub fn is_flint_steel(id: u8) -> bool { id == 186 }
pub fn is_firework(id: u8) -> bool { id == 189 }
pub fn has_durability(id: u8) -> bool { is_tool(id) || is_armor(id) || id == 186 }

pub fn sword_damage(id: u8) -> f32 { match id { 164 => 5.0, 166 => 6.0, 168 => 7.0, 240 => 8.0, 170 => 9.0, 198 => 11.0, _ => 0.0 } }
pub fn pick_damage(id: u8) -> f32 { match id { 163 => 2.0, 165 => 3.0, 167 => 4.0, 239 => 4.5, 169 => 5.0, 197 => 6.0, _ => 0.0 } }
pub fn armor_defense(id: u8) -> f32 {
    match id {
        171 => 2.0, 172 => 6.0, 173 => 5.0, 174 => 2.0, // iron helm/chest/legs/boots
        241 => 2.0, 242 => 7.0, 243 => 5.0, 244 => 3.0, // bronze
        175 => 3.0, 176 => 8.0, 177 => 6.0, 178 => 3.0, // diamond
        199 => 4.0, 200 => 9.0, 201 => 7.0, 202 => 4.0, // adamant
        188 => 1.0,                                     // elytra (mostly for mobility, minor defense)
        _ => 0.0,
    }
}
pub fn max_durability(id: u8) -> i32 {
    match id {
        163 | 164 => 60, 165 | 166 => 132, 167 | 168 => 250, 169 | 170 => 1562, // wood/stone/iron/diamond tools
        171..=174 => 240, 175..=178 => 528,                                       // iron/diamond armor
        186 => 64,                                                                // flint & steel
        252 => 238,                                                               // shears
        253 => 64,                                                                // fishing rod
        188 => 432,                                                               // elytra
        197 | 198 => 2031,                                                        // adamant tools
        199..=202 => 666,                                                         // adamant armor
        239 | 240 => 700,                                                         // bronze tools
        241..=244 => 380,                                                         // bronze armor
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
        // Matcha's kitchen: cooked meat is the survival staple, and each dish above it trades more
        // ingredients for a longer, more specialised buff.
        223 => &[(Regeneration, 6.0, 0)],                                   // cooked meat
        224 => &[(Regeneration, 5.0, 0), (Speed, 60.0, 0)],                 // ramen
        225 => &[(Regeneration, 6.0, 1), (Strength, 90.0, 0)],              // japanese curry
        226 => &[(Regeneration, 5.0, 0), (Haste, 90.0, 0)],                 // green curry
        227 => &[(Regeneration, 5.0, 0), (Resistance, 60.0, 0)],            // gnocchi
        228 => &[(Regeneration, 6.0, 0)],                                   // naan
        229 => &[(Regeneration, 5.0, 0), (Absorption, 120.0, 0)],           // pupusa
        230 => &[(Regeneration, 5.0, 0), (FireResistance, 60.0, 0)],        // latke
        231 => &[(Regeneration, 4.0, 0), (Speed, 45.0, 0)],                 // bruschetta
        232 => &[(Regeneration, 5.0, 0), (JumpBoost, 60.0, 0)],             // french toast
        233 => &[(Regeneration, 5.0, 0), (NightVision, 120.0, 0)],          // sweet berry danish
        234 => &[(Regeneration, 4.0, 0), (Speed, 60.0, 0), (Haste, 60.0, 0)], // melon sorbet
        235 => &[(Regeneration, 8.0, 2), (Strength, 120.0, 0), (Resistance, 60.0, 0)], // stroganoff
        _ => return None,
    })
}


#[cfg(test)]
mod tests {
    use super::*;

    // Blocks and items share one u8 id space split at ITEM_BASE. If a new block ever crosses that
    // line it would silently become unplaceable, so pin the invariant down.
    #[test]
    fn block_ids_stay_below_the_item_base() {
        assert!(crate::world::block::MAX_BLOCK_ID < ITEM_BASE);
        assert!(!is_item(crate::world::block::MAX_BLOCK_ID));
    }

    // armor_slot() does range arithmetic over three disjoint id blocks; a wrong mapping would let
    // a helmet occupy the boots slot.
    #[test]
    fn armor_maps_to_the_right_slot() {
        for (id, slot) in [(171u8, 0usize), (172, 1), (173, 2), (174, 3),   // iron
                           (175, 0), (176, 1), (177, 2), (178, 3),          // diamond
                           (199, 0), (200, 1), (201, 2), (202, 3),          // adamant
                           (188, 1)] {                                      // elytra -> chest
            assert!(is_armor(id), "{id} should be armor");
            assert_eq!(armor_slot(id), slot, "{id} mapped to the wrong slot");
        }
    }

    #[test]
    fn tools_and_armor_do_not_overlap() {
        for id in 0..=255u8 {
            assert!(!(is_tool(id) && is_armor(id)), "{id} is both a tool and armor");
            // Shears and the fishing rod are the exceptions: tools that neither mine nor fight.
            if is_tool(id) && !is_shears(id) && id != crate::fishing::ROD {
                assert!(is_pickaxe(id) ^ is_sword(id), "{id} must be exactly one tool kind");
            }
            if is_shears(id) { assert!(!is_pickaxe(id) && !is_sword(id), "shears are not a weapon"); }
            if is_tool(id) || is_armor(id) { assert!(max_durability(id) > 0, "{id} has no durability"); }
        }
    }

    // Adamant is the top tier: it must beat diamond everywhere it competes.
    // Every dish must be reachable: craftable, smeltable, dropped by a mob or found in a chest.
    // A food with no source is dead content.
    #[test]
    fn every_food_is_obtainable() {
        use crate::inventory::{RECIPES, SMELTING};
        let mut missing = Vec::new();
        for id in 0..=255u8 {
            if food_effects(id).is_none() { continue; }
            let crafted = RECIPES.iter().any(|r| r.4 == id);
            let smelted = SMELTING.iter().any(|s| s.out == id);
            let dropped = crate::entity::MobKind::ALL.iter().any(|k| k.loot().contains(&id));
            let traded = crate::villager::ALL.iter()
                .any(|&p| crate::villager::offers(p, crate::villager::MAX_LEVEL).iter().any(|o| o.give == id));
            let foraged = id == 130; // apples fall out of leaves
            let looted = (0..40).any(|i| {
                crate::container::roll_loot(i * 7, 40, i * 13, i as u8 % 3, false)
                    .iter().any(|s| s.id == id)
            });
            if !(crafted || smelted || dropped || looted || traded || foraged) { missing.push(id); }
        }
        assert!(missing.is_empty(), "these foods have no source: {missing:?}");
    }

    // Raw meat is a cooking input, not a snack.
    #[test]
    fn raw_meat_is_not_edible() {
        assert!(food_effects(222).is_none(), "raw meat must be cooked first");
        assert!(food_effects(223).is_some(), "cooked meat must feed you");
    }

    #[test]
    fn adamant_outclasses_diamond() {
        assert!(sword_damage(198) > sword_damage(170));
        assert!(pick_damage(197) > pick_damage(169));
        for (adamant, diamond) in [(199u8, 175u8), (200, 176), (201, 177), (202, 178)] {
            assert!(armor_defense(adamant) > armor_defense(diamond), "{adamant} must beat {diamond}");
        }
        assert!(max_durability(197) > max_durability(169));
    }
}
