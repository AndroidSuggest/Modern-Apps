// Atmosphere: footsteps, cave ambience, and Matcha's eerie score.
//
// Matcha's signature mood system is `set_eerie_score` / `village_eerie_sound` / `initial_stalking`: a
// scalar that builds while the player is somewhere dark and lonely and drives occasional stalking
// sounds. This is that idea, plus the footstep and cave-ambience sounds the pack also adds.
//
// Nothing here plays audio. The engine advances the state and publishes counters; the Kotlin side
// edge-detects them and synthesises the sound (see `util/SoundFx.kt` — this game has no audio assets
// and doesn't need any).

/// Surface classes a footstep can land on. The Kotlin synthesiser picks a timbre per class.
pub const STEP_STONE: u8 = 0;
pub const STEP_WOOD: u8 = 1;
pub const STEP_GRASS: u8 = 2;
pub const STEP_SAND: u8 = 3;
pub const STEP_SNOW: u8 = 4;
pub const STEP_WATER: u8 = 5;

/// One-shot cues, distinct from the per-step sound.
pub const CUE_NONE: u8 = 0;
pub const CUE_CAVE: u8 = 1;
pub const CUE_STALK: u8 = 2;

/// Blocks travelled between footfalls.
const STRIDE: f32 = 2.2;
/// The eerie score has to reach this before anything starts stalking you.
pub const STALK_AT: f32 = 0.55;
const EERIE_DARK_RISE: f32 = 0.045;
const EERIE_DEEP_RISE: f32 = 0.055;
const EERIE_DECAY: f32 = 0.11;
/// Gap between one-shots at minimum and maximum dread.
const CUE_GAP_CALM: f32 = 46.0;
const CUE_GAP_TENSE: f32 = 13.0;

/// What a footstep on this block should sound like.
pub fn step_material(id: u8) -> u8 {
    match id {
        12 => STEP_WATER,
        11 | 42 | 43 | 44 => STEP_SNOW,
        6 | 14 | 36 | 37 | 38 | 79 => STEP_SAND,
        4 | 10 | 26 | 27 | 29 | 30 | 33 | 34 | 47 | 49 | 50 | 51 | 52 | 82 => STEP_WOOD,
        2 | 3 | 5 | 28 | 31 | 39 | 40 | 41 | 45 | 46 | 48 | 58 | 59 | 60 | 66 | 71 | 72 | 80 => STEP_GRASS,
        _ => STEP_STONE,
    }
}

/// Advance the eerie score. It climbs in the dark, climbs faster underground, and burns off in
/// daylight — so the dread is something the player can walk out of.
pub fn eerie_step(score: f32, dark: bool, deep: bool, dt: f32) -> f32 {
    let rate = if dark || deep {
        (if dark { EERIE_DARK_RISE } else { 0.0 }) + (if deep { EERIE_DEEP_RISE } else { 0.0 })
    } else {
        -EERIE_DECAY
    };
    (score + rate * dt).clamp(0.0, 1.0)
}

/// How long to wait before the next one-shot. The more dread, the closer together they come.
pub fn cue_gap(score: f32) -> f32 {
    let t = score.clamp(0.0, 1.0);
    CUE_GAP_CALM + (CUE_GAP_TENSE - CUE_GAP_CALM) * t
}

#[derive(Default)]
pub struct Ambience {
    pub eerie: f32,
    /// Distance walked since the last footfall.
    step_dist: f32,
    /// Monotonic counters. The UI plays a sound whenever one of these changes.
    pub step_n: u32,
    pub step_mat: u8,
    pub cue_n: u32,
    pub cue_kind: u8,
    cue_cd: f32,
}

impl Ambience {
    /// `walked` is the distance covered this tick, `under` the block being stood on.
    #[allow(clippy::too_many_arguments)]
    pub fn tick(&mut self, dt: f32, walked: f32, on_ground: bool, under: u8, dark: bool, deep: bool, roll: f32) {
        if on_ground && under != 0 {
            self.step_dist += walked;
            if self.step_dist >= STRIDE {
                self.step_dist = 0.0;
                self.step_mat = step_material(under);
                self.step_n = self.step_n.wrapping_add(1);
            }
        } else {
            // Falling or flying: land on the next surface with a fresh stride rather than mid-cycle.
            self.step_dist = 0.0;
        }

        self.eerie = eerie_step(self.eerie, dark, deep, dt);
        self.cue_cd -= dt;
        if self.cue_cd <= 0.0 {
            self.cue_cd = cue_gap(self.eerie) * (0.6 + 0.8 * roll);
            // Deep and dark gets cave ambience; genuine dread gets something following you.
            self.cue_kind = if self.eerie >= STALK_AT { CUE_STALK } else if deep { CUE_CAVE } else { CUE_NONE };
            if self.cue_kind != CUE_NONE { self.cue_n = self.cue_n.wrapping_add(1); }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dread_builds_in_the_dark_and_burns_off_in_daylight() {
        // A night above ground fills the meter, but not instantly.
        let mut s = 0.0;
        for _ in 0..10 { s = eerie_step(s, true, false, 1.0); }
        assert!(s > 0.0 && s < 0.9, "ten seconds of dark shouldn't max it out, got {s}");

        // Underground in the dark is worse than either alone.
        let dark_only = eerie_step(0.0, true, false, 1.0);
        let both = eerie_step(0.0, true, true, 1.0);
        assert!(both > dark_only);

        // It saturates rather than running away.
        let mut s = 0.0;
        for _ in 0..10_000 { s = eerie_step(s, true, true, 1.0); }
        assert_eq!(s, 1.0);

        // And daylight clears it, down to zero and no further.
        let mut s = 1.0;
        for _ in 0..10_000 { s = eerie_step(s, false, false, 1.0); }
        assert_eq!(s, 0.0);
    }

    #[test]
    fn cues_come_faster_the_more_frightened_you_are() {
        assert!(cue_gap(0.0) > cue_gap(1.0));
        for t in [0.0f32, 0.3, 0.7, 1.0, -5.0, 9.0] {
            let g = cue_gap(t);
            assert!((CUE_GAP_TENSE..=CUE_GAP_CALM).contains(&g), "gap {g} out of range for {t}");
        }
    }

    #[test]
    fn footsteps_land_one_stride_apart() {
        let mut a = Ambience::default();
        // Walking on grass.
        for _ in 0..100 { a.tick(1.0 / 60.0, 0.05, true, 3, false, false, 0.5); }
        assert_eq!(a.step_mat, STEP_GRASS);
        let walked = 100.0 * 0.05;
        let expected = (walked / STRIDE) as u32;
        assert_eq!(a.step_n, expected, "{walked} blocks should be {expected} steps");

        // Standing still makes no sound.
        let before = a.step_n;
        for _ in 0..600 { a.tick(1.0 / 60.0, 0.0, true, 3, false, false, 0.5); }
        assert_eq!(a.step_n, before);

        // Neither does being airborne, however far you travel.
        for _ in 0..600 { a.tick(1.0 / 60.0, 0.2, false, 3, false, false, 0.5); }
        assert_eq!(a.step_n, before);
    }

    #[test]
    fn the_surface_underfoot_picks_the_sound() {
        assert_eq!(step_material(1), STEP_STONE);   // stone
        assert_eq!(step_material(8), STEP_STONE);   // cobble
        assert_eq!(step_material(10), STEP_WOOD);   // planks
        assert_eq!(step_material(3), STEP_GRASS);   // grass
        assert_eq!(step_material(6), STEP_SAND);    // sand
        assert_eq!(step_material(11), STEP_SNOW);   // snow
        assert_eq!(step_material(12), STEP_WATER);  // water
        // Anything unrecognised still makes a noise rather than silence.
        assert_eq!(step_material(200), STEP_STONE);
    }

    // A calm player above ground should never be stalked, and a frightened one always should be.
    #[test]
    fn stalking_only_starts_once_the_dread_is_real() {
        let mut calm = Ambience::default();
        for _ in 0..60 * 300 { calm.tick(1.0 / 60.0, 0.0, true, 3, false, false, 0.5); }
        assert_eq!(calm.cue_n, 0, "nothing should stalk a player standing in daylight");

        let mut spooked = Ambience::default();
        for _ in 0..60 * 300 { spooked.tick(1.0 / 60.0, 0.0, true, 1, true, true, 0.5); }
        assert!(spooked.eerie >= STALK_AT);
        assert!(spooked.cue_n > 0, "five minutes alone in the dark should produce something");
        assert_eq!(spooked.cue_kind, CUE_STALK);
    }
}
