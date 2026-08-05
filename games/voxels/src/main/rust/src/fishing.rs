// Fishing: cast a line into water, wait for a bite, and reel it in before it gets away.
//
// Matcha's fisherman deals in 49 species across five tiers. The game has one fish item, so the depth
// lives in the loot pool instead: mostly fish, sometimes junk, occasionally something worth keeping.

/// Item 254. Cook it in a furnace for the cooked fish the villagers sell.
use crate::world::block::Id;

pub const RAW_FISH: Id = 1150;
/// Item 253.
pub const ROD: Id = 1149;

/// Shortest and longest wait before a bite.
const WAIT_MIN: f32 = 3.0;
const WAIT_MAX: f32 = 14.0;
/// How long the bite stays catchable. Miss it and the fish is gone.
pub const BITE_WINDOW: f32 = 1.6;
/// Wander further than this from the float and the line comes in on its own.
pub const LEASH: f32 = 14.0;

#[derive(Default)]
pub struct Fishing {
    /// Where the float is sitting, if a line is out.
    pub bobber: Option<[f32; 3]>,
    /// Seconds until a bite; once it reaches zero, `bite` starts running.
    pub wait: f32,
    /// Seconds left to strike. Zero means nothing is biting.
    pub bite: f32,
}

impl Fishing {
    pub fn cast(&mut self, at: [f32; 3], roll: f32) {
        self.bobber = Some(at);
        self.wait = WAIT_MIN + roll.clamp(0.0, 1.0) * (WAIT_MAX - WAIT_MIN);
        self.bite = 0.0;
    }
    pub fn reel_in(&mut self) {
        self.bobber = None;
        self.wait = 0.0;
        self.bite = 0.0;
    }
    pub fn is_cast(&self) -> bool { self.bobber.is_some() }
    pub fn biting(&self) -> bool { self.bite > 0.0 }

    /// Advance the line. Returns true on the frame a bite starts, so the caller can splash.
    pub fn tick(&mut self, dt: f32) -> bool {
        if self.bobber.is_none() { return false; }
        if self.bite > 0.0 {
            self.bite -= dt;
            // The fish got away; go back to waiting rather than ending the cast.
            if self.bite <= 0.0 { self.bite = 0.0; self.wait = WAIT_MIN; }
            return false;
        }
        self.wait -= dt;
        if self.wait <= 0.0 {
            self.wait = 0.0;
            self.bite = BITE_WINDOW;
            return true;
        }
        false
    }
}

/// What comes up on the hook. `lucky` is Glaucus (SeaLuck), which trades junk for treasure.
pub fn catch_of_the_day(roll: f32, lucky: bool) -> Id {
    let r = roll.clamp(0.0, 0.999);
    // Treasure first, then junk, then the fish that makes up the bulk of every pool.
    let treasure = if lucky { 0.14 } else { 0.06 };
    let junk = if lucky { 0.06 } else { 0.16 };
    if r < treasure {
        // Four treasures, evenly split within the treasure band.
        match ((r / treasure) * 4.0) as u32 {
            0 => 1087,                                  // ender pearl
            1 => 1052,                                  // emerald
            2 => crate::world::block::Block::SeaLantern as Id,
            _ => 1033,                                  // leather (a waterlogged boot, near enough)
        }
    } else if r < treasure + junk {
        crate::world::block::Block::Kelp as Id
    } else {
        RAW_FISH
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_cast_waits_then_bites_then_lets_go() {
        let mut f = Fishing::default();
        assert!(!f.tick(1.0), "an uncast line never bites");

        f.cast([0.0, 62.0, 0.0], 0.0); // shortest wait
        assert!(f.is_cast());
        assert!(!f.biting());

        // Nothing bites before the wait is up.
        let mut bit = false;
        for _ in 0..(WAIT_MIN as i32 * 60 - 5) { bit |= f.tick(1.0 / 60.0); }
        assert!(!bit, "it bit early");

        // Then it does, exactly once.
        let mut bites = 0;
        for _ in 0..60 { if f.tick(1.0 / 60.0) { bites += 1; } }
        assert_eq!(bites, 1, "a bite must be a single edge, not a held state");
        assert!(f.biting());

        // Miss the window and it goes back to waiting rather than ending the cast.
        for _ in 0..(BITE_WINDOW * 60.0) as i32 + 5 { f.tick(1.0 / 60.0); }
        assert!(!f.biting());
        assert!(f.is_cast(), "missing a fish shouldn't reel the line in");

        f.reel_in();
        assert!(!f.is_cast());
    }

    #[test]
    fn the_wait_is_never_instant_and_never_forever() {
        for r in [0.0f32, 0.5, 1.0, -2.0, 4.0] {
            let mut f = Fishing::default();
            f.cast([0.0; 3], r);
            assert!((WAIT_MIN..=WAIT_MAX).contains(&f.wait), "wait {} out of range for {r}", f.wait);
        }
    }

    // The pool has to be mostly fish, always yield a real item, and reward Glaucus.
    #[test]
    fn the_catch_is_mostly_fish_and_luck_helps() {
        let sample = |lucky: bool| {
            let (mut fish, mut junk, mut treasure) = (0, 0, 0);
            let n = 20_000;
            for i in 0..n {
                let id = catch_of_the_day(i as f32 / n as f32, lucky);
                assert!(id != 0, "the hook came up with nothing");
                assert!(id <= crate::world::block::MAX_BLOCK_ID || crate::item::is_item(id), "{id} is not an id");
                if id == RAW_FISH { fish += 1; }
                else if id == crate::world::block::Block::Kelp as Id { junk += 1; }
                else { treasure += 1; }
            }
            (fish, junk, treasure)
        };
        let (fish, junk, treasure) = sample(false);
        assert!(fish > junk + treasure, "fishing should mostly catch fish");
        assert!(junk > 0 && treasure > 0, "every band has to be reachable");

        let (lucky_fish, lucky_junk, lucky_treasure) = sample(true);
        assert!(lucky_treasure > treasure, "Glaucus has to improve the odds");
        assert!(lucky_junk < junk, "and cut down on the rubbish");
        assert!(lucky_fish > 0);
    }
}
