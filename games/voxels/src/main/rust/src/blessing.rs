// The Blessing pantheon, Matcha's signature system.
//
// In Matcha a blessing is a crafted enchanted book that permanently upgrades gear. This game has no
// enchanting, so a blessing is instead *attuned* to the player: consuming the charm binds it to one
// of a few permanent slots and grants a passive for the rest of the run. Attunements persist across
// sessions and can be unbound at any time, which returns the charm.

use serde::{Deserialize, Serialize};

/// How many blessings can be attuned at once.
pub const SLOTS: usize = 3;

#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize, Deserialize)]
pub enum Passive {
    Traversal,     // Clement — faster on foot, steps a full block
    Might,         // Ares — heavier melee
    Deep,          // Yamm — swim, and move freely underwater
    ToolWard,      // Daedalus — tools never wear down
    FeatherFall,   // Icarus — no fall damage
    Pyre,          // Yama — immune to fire and lava
    Impact,        // Talos — attacks send mobs flying
    SmiteUndead,   // God King — double damage to the undead
    BaneOfHorrors, // Arachnae — double damage to creepers, shulkers and ghasts
    ArmorWard,     // Prometheus — armor never wears down
    Mending,       // Lu Ban — held gear slowly repairs itself
    Fortune,       // Eros — ore blocks yield twice
    Reach,         // Will — longer reach
    DoubleJump,    // Hyacinthus — a second jump in mid-air
    WindBurst,     // Aeolus — landing a hit launches you skyward
    SwiftSneak,    // Cronus — sneak at walking speed
    FrostWalker,   // Demeter — water freezes underfoot
    SeaLuck,       // Glaucus — richer chests and mob drops
    Marksman,      // Apollo — thrown weapons hit twice as hard
    Multishot,     // Artemis — throw three at once
    Thorns,        // Warding — attackers take a share of the damage back
    Infinity,      // Paris — thrown items are never used up
}

pub struct Blessing {
    pub id: u8,
    pub passive: Passive,
    /// Icon file in assets/block/, and the name shown in the UI.
    pub icon: &'static str,
    pub name: &'static str,
    pub effect: &'static str,
}

const fn b(id: u8, passive: Passive, icon: &'static str, name: &'static str, effect: &'static str) -> Blessing {
    Blessing { id, passive, icon, name, effect }
}

// Ids 160..162 predate the pantheon but are folded into it; every icon is Matcha's own artwork.
pub const PANTHEON: [Blessing; 22] = [
    b(160, Passive::Traversal,     "blessing_clement.png",     "Blessing of Clement",      "Swift of foot; climbs a full block"),
    b(161, Passive::Might,         "blessing_ares.png",        "Blessing of Ares",         "Melee strikes hit far harder"),
    b(162, Passive::Deep,          "blessing_yamm.png",        "Blessing of Yamm",         "Swim freely beneath the waves"),
    b(203, Passive::ToolWard,      "blessing_daedalus.png",    "Blessing of Daedalus",     "Tools never wear down"),
    b(204, Passive::FeatherFall,   "blessing_icarus.png",      "Blessing of Icarus",       "You take no falling damage"),
    b(205, Passive::Pyre,          "blessing_yama.png",        "Blessing of Yama",         "Fire and lava cannot burn you"),
    b(206, Passive::Impact,        "blessing_talos.png",       "Blessing of Talos",        "Your blows send foes flying"),
    b(207, Passive::SmiteUndead,   "blessing_god_king.png",    "Blessing of the God King", "Double damage to the undead"),
    b(208, Passive::BaneOfHorrors, "blessing_arachnae.png",    "Blessing of Arachnae",     "Double damage to creepers and horrors"),
    b(209, Passive::ArmorWard,     "blessing_prometheus.png",  "Blessing of Prometheus",   "Armor never wears down"),
    b(210, Passive::Mending,       "blessing_lu_ban.png",      "Blessing of Lu Ban",       "Your gear slowly mends itself"),
    b(211, Passive::Fortune,       "blessing_eros.png",        "Blessing of Eros",         "Ores yield twice as much"),
    b(212, Passive::Reach,         "blessing_will.png",        "Blessing of Will",         "You can reach much further"),
    b(213, Passive::DoubleJump,    "blessing_hyacinthus.png",  "Blessing of Hyacinthus",   "Leap a second time in mid-air"),
    b(214, Passive::WindBurst,     "blessing_aeolus.png",      "Blessing of Aeolus",       "Landing a hit throws you skyward"),
    b(215, Passive::SwiftSneak,    "blessing_cronus.png",      "Blessing of Cronus",       "Sneak at a walking pace"),
    b(216, Passive::FrostWalker,   "blessing_demeter.png",     "Blessing of Demeter",      "Water freezes beneath your feet"),
    b(217, Passive::SeaLuck,       "blessing_glaucus.png",     "Blessing of Glaucus",      "Chests and foes give up more"),
    b(218, Passive::Marksman,      "blessing_apollo.png",      "Blessing of Apollo",       "Thrown weapons strike twice as hard"),
    b(219, Passive::Multishot,     "blessing_artemis.png",     "Blessing of Artemis",      "Throw three projectiles at once"),
    b(220, Passive::Thorns,        "blessing_warding.png",     "Blessing of Warding",      "Attackers suffer for striking you"),
    b(221, Passive::Infinity,      "blessing_paris.png",       "Blessing of Paris",        "Thrown items are never used up"),
];

pub fn is_blessing(id: u8) -> bool { PANTHEON.iter().any(|b| b.id == id) }
pub fn find(id: u8) -> Option<&'static Blessing> { PANTHEON.iter().find(|b| b.id == id) }

/// The blessings currently bound to the player. Empty slots hold 0.
#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize)]
pub struct Attunement {
    pub slots: [u8; SLOTS],
}

impl Attunement {
    pub fn has(&self, passive: Passive) -> bool {
        self.slots.iter().any(|&id| find(id).is_some_and(|b| b.passive == passive))
    }
    pub fn contains(&self, id: u8) -> bool { self.slots.contains(&id) }
    /// Bind a charm to the first free slot. Fails if it's already bound or there's no room.
    pub fn attune(&mut self, id: u8) -> bool {
        if !is_blessing(id) || self.contains(id) { return false; }
        match self.slots.iter_mut().find(|s| **s == 0) {
            Some(slot) => { *slot = id; true }
            None => false,
        }
    }
    /// Unbind the charm in `slot`, returning it so the caller can hand it back to the player.
    pub fn release(&mut self, slot: usize) -> Option<u8> {
        let id = *self.slots.get(slot)?;
        if id == 0 { return None; }
        self.slots[slot] = 0;
        Some(id)
    }
    pub fn to_json(&self) -> String {
        let list: Vec<_> = self.slots.iter().map(|&id| {
            match find(id) {
                Some(b) => serde_json::json!({"id": b.id, "name": b.name, "effect": b.effect}),
                None => serde_json::json!({"id": 0, "name": "", "effect": ""}),
            }
        }).collect();
        serde_json::json!({ "slots": list }).to_string()
    }
}

/// Catalog for the UI: every blessing and what it does.
pub fn catalog_json() -> String {
    let list: Vec<_> = PANTHEON.iter()
        .map(|b| serde_json::json!({"id": b.id, "name": b.name, "effect": b.effect}))
        .collect();
    serde_json::json!(list).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_pantheon_is_consistent() {
        for b in PANTHEON.iter() {
            assert!(b.id >= crate::item::ITEM_BASE, "{} must be an item id", b.name);
            assert!(!b.name.is_empty() && !b.effect.is_empty());
            assert!(b.icon.ends_with(".png"));
        }
        // Ids, passives and icons must all be unique or two blessings would collide.
        for (i, a) in PANTHEON.iter().enumerate() {
            for other in PANTHEON.iter().skip(i + 1) {
                assert_ne!(a.id, other.id, "duplicate id {}", a.id);
                assert_ne!(a.passive, other.passive, "duplicate passive on {}", a.name);
                assert_ne!(a.icon, other.icon, "duplicate icon on {}", a.name);
            }
        }
    }

    #[test]
    fn attuning_fills_slots_and_rejects_duplicates() {
        let mut a = Attunement::default();
        assert!(a.attune(160));
        assert!(!a.attune(160), "the same blessing must not stack");
        assert!(a.attune(204));
        assert!(a.attune(211));
        assert!(!a.attune(212), "there are only {SLOTS} slots");
        assert!(a.has(Passive::Traversal));
        assert!(a.has(Passive::Fortune));
        assert!(!a.has(Passive::Reach));
    }

    #[test]
    fn releasing_returns_the_charm() {
        let mut a = Attunement::default();
        a.attune(205);
        assert!(a.has(Passive::Pyre));
        assert_eq!(a.release(0), Some(205));
        assert!(!a.has(Passive::Pyre));
        assert_eq!(a.release(0), None, "an empty slot gives nothing back");
        assert!(a.attune(212), "the slot is free again");
    }

    #[test]
    fn non_blessings_cannot_be_attuned() {
        let mut a = Attunement::default();
        assert!(!a.attune(0));
        assert!(!a.attune(155)); // a diamond
        assert!(!a.attune(169)); // a pickaxe
    }
}
