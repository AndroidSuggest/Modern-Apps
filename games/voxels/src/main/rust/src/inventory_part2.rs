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
        let idx = RECIPES.iter().position(|r| r.out == 1072).expect("diamond chestplate");
        let need = RECIPES[idx].n1;
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot::default(); }
        inv.slots[0] = InvSlot { id: 1051, count: need };

        let mut crafted = vec![false; RECIPES.len()];
        assert!(!recipe_unlocked(idx, &crafted), "it should start locked");
        assert!(!inv.craft(idx, &crafted), "a locked recipe must refuse");
        assert_eq!(inv.count_of(1051), need, "and must not take the diamonds");
        assert_eq!(inv.count_of(1072), 0);

        // Crafting the prerequisite opens it.
        let pick = RECIPES.iter().position(|r| r.out == RECIPES[idx].unlocked_by).unwrap();
        crafted[pick] = true;
        assert!(recipe_unlocked(idx, &crafted));
        assert!(inv.craft(idx, &crafted), "now it should go through");
        assert_eq!(inv.count_of(1051), 0);
        assert!(inv.count_of(1072) > 0);
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
        let idx = RECIPES.iter().position(|r| r.out == 1059).expect("wood pickaxe");
        let none = vec![false; RECIPES.len()];
        let mut inv = Inventory::default();
        // Every slot occupied, but the two ingredients are present in the stacks that fill it.
        for s in inv.slots.iter_mut() { *s = InvSlot { id: Block::Dirt as Id, count: STACK }; }
        inv.slots[0] = InvSlot { id: Block::Planks as Id, count: STACK };
        inv.slots[1] = InvSlot { id: 1055, count: STACK };

        assert!(!inv.craft(idx, &none), "there is nowhere to put a pickaxe");
        assert_eq!(inv.count_of(Block::Planks as Id), STACK, "the planks must survive");
        assert_eq!(inv.count_of(1055), STACK, "the sticks must survive");
        assert_eq!(inv.count_of(1059), 0);

        // Free one slot and it goes through.
        inv.slots[5] = InvSlot::default();
        assert!(inv.craft(idx, &none));
        assert!(inv.count_of(1059) > 0);
    }

    // Same hazard on the trade path: a forged tool with nowhere to go must not eat the payment.
    #[test]
    fn trading_for_a_tool_with_no_empty_slot_is_refused() {
        use crate::villager::{Offer, EMERALD};
        let forge = Offer { cost: EMERALD, cost_n: 4, cost2: 1050, cost2_n: 2, give: 1063, give_n: 1 };
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot { id: Block::Dirt as Id, count: STACK }; }
        inv.slots[0] = InvSlot { id: EMERALD, count: STACK };
        inv.slots[1] = InvSlot { id: 1050, count: STACK };

        assert!(!inv.trade_offer(&forge), "nowhere to put the pickaxe");
        assert_eq!(inv.count_of(EMERALD), STACK, "the emeralds must survive");
        assert_eq!(inv.count_of(1050), STACK);
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
        let steel = SMELTING.iter().find(|s| s.out == 1091).expect("steel must be smeltable");
        let adamant = SMELTING.iter().find(|s| s.out == 1092).expect("adamant must be smeltable");
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
        inv.slots[1] = InvSlot { id: 1053, count: 1 }; // 1 coal

        let recipe = SMELTING.iter().find(|s| s.out == 1050).unwrap();
        assert!(inv.can_smelt(recipe));
        assert_eq!(inv.consume_fuel(&[recipe.in1, recipe.in2]), Some(fuel_secs(1053)));
        assert_eq!(inv.count_of(1053), 0, "the coal should be burnt");

        inv.take_smelt_inputs(recipe);
        inv.give_smelt_output(recipe);
        assert_eq!(inv.count_of(19), 2, "one ore should be consumed");
        assert_eq!(inv.count_of(1050), 1, "one ingot should be produced");

        // With no fuel left the furnace can't run again.
        assert_eq!(inv.consume_fuel(&[recipe.in1, recipe.in2]), None);
    }

    // Sulfur is both a fuel and the second half of the steel recipe. Burning it would consume the
    // input and leave the player with nothing.
    #[test]
    fn a_recipe_never_burns_its_own_ingredients() {
        let steel = SMELTING.iter().find(|s| s.out == 1091).unwrap();
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot::default(); }
        inv.slots[0] = InvSlot { id: 1050, count: 1 };  // iron ingot
        inv.slots[1] = InvSlot { id: 1088, count: 1 };  // the only sulfur, also a valid fuel

        assert!(inv.can_smelt(steel));
        assert_eq!(inv.consume_fuel(&[steel.in1, steel.in2]), None, "the sulfur input must be spared");
        assert_eq!(inv.count_of(1088), 1);
        assert!(inv.can_smelt(steel), "the recipe must still be runnable");
    }

    // Coal exists to be burnt; planks and logs are building material, so reach for coal first.
    #[test]
    fn dedicated_fuel_is_burnt_before_building_material() {
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot::default(); }
        inv.slots[0] = InvSlot { id: 10, count: 4 };   // planks, earlier in the inventory
        inv.slots[5] = InvSlot { id: 1053, count: 2 };  // coal

        assert_eq!(inv.consume_fuel(&[]), Some(fuel_secs(1053)));
        assert_eq!(inv.count_of(10), 4, "the planks should be untouched");
        assert_eq!(inv.count_of(1053), 1);
    }

    // A full inventory must not let a furnace eat its inputs and throw the result away.
    #[test]
    fn a_full_inventory_has_no_room() {
        let mut inv = Inventory::default();
        for s in inv.slots.iter_mut() { *s = InvSlot { id: 2, count: STACK }; }
        assert!(!inv.has_room_for(1050, 1), "no free slot and no matching stack");
        assert!(!inv.has_room_for(2, 1), "every dirt stack is already full");

        inv.slots[3] = InvSlot { id: 1050, count: STACK - 2 };
        assert!(inv.has_room_for(1050, 2));
        assert!(!inv.has_room_for(1050, 3), "only 2 of 3 would fit");

        inv.slots[4] = InvSlot::default();
        assert!(inv.has_room_for(1050, 3));
        assert!(inv.has_room_for(1065, 1), "an empty slot can hold a tool");
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
        assert!(fuel_secs(1053) > 0.0);   // coal
        assert!(fuel_secs(10) > 0.0);    // planks
        assert_eq!(fuel_secs(1051), 0.0); // diamonds are not firewood
        assert_eq!(fuel_secs(0), 0.0);
    }

    // A two-ingredient trade must be all-or-nothing: the emeralds can't disappear when the player is
    // short on the material half.
    #[test]
    fn a_half_affordable_trade_takes_nothing() {
        use crate::villager::{Offer, EMERALD};
        let forge = Offer { cost: EMERALD, cost_n: 4, cost2: 1050, cost2_n: 2, give: 1063, give_n: 1 };
        let mut inv = Inventory::default();
        inv.slots[0] = InvSlot { id: EMERALD, count: 8 };
        inv.slots[1] = InvSlot { id: 1050, count: 1 };

        assert!(!inv.trade_offer(&forge), "one iron ingot short");
        assert_eq!(inv.count_of(EMERALD), 8, "the emeralds must survive a refused trade");
        assert_eq!(inv.count_of(1050), 1);

        inv.slots[1].count = 2;
        assert!(inv.trade_offer(&forge));
        assert_eq!(inv.count_of(EMERALD), 4);
        assert_eq!(inv.count_of(1050), 0);
        assert!(inv.count_of(1063) > 0, "the pickaxe arrives with durability");
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
