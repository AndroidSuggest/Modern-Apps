use glam::{Vec3, vec3};
use crate::world::{ChunkMap, block::Block};
use crate::item::{Effect, ActiveEffect};
use crate::blessing::{Attunement, Passive};

pub const MIN_MAX_HEALTH: f32 = 20.0; // 10 hearts floor (deaths never take you below this)
pub const CAP_MAX_HEALTH: f32 = 60.0; // 30 hearts cap (heart containers)

pub struct Player {
    pub pos: Vec3,
    pub vel: Vec3,
    pub yaw: f32,
    pub pitch: f32,
    pub on_ground: bool,
    pub flying: bool,
    pub elytra: bool,      // an elytra is equipped (gliding is possible)
    pub gliding: bool,     // currently in an elytra glide (published for UI/animation)
    glide_armed: bool,     // wings deployed: toggled with a mid-air jump tap
    prev_jump: bool,       // for edge-detecting jump taps
    glide_boost: f32,      // extra glide speed from a firework rocket (decays)
    jumps_left: u8,        // mid-air jumps remaining (Hyacinthus grants a second one)
    pub walk_dist: f32,
    pub sneaking: bool,
    // Attuned blessings, granting permanent passives.
    pub blessings: Attunement,
    // Survival state.
    pub health: f32,
    pub max_health: f32,
    pub absorption: f32,
    pub effects: Vec<ActiveEffect>,
    pub hurt_cd: f32,
    pub attack_cd: f32,
    pub eat_cd: f32,
    pub air_max_y: f32,
    pub dead: bool,
}

/// Damage taken on landing after falling from `peak` to `y`. Drops under the safe margin are free,
/// and Icarus removes it entirely. Swimming keeps `peak` pinned to the current height, which is how
/// a controlled descent avoids being treated as a fall.
fn landing_damage(peak: f32, y: f32, feather_fall: bool) -> f32 {
    if feather_fall { return 0.0; }
    let fall = peak - y;
    if fall > 3.5 { fall - 3.5 } else { 0.0 }
}

impl Player {
    pub fn new(x: f32, y: f32, z: f32) -> Self {
        Self { pos: vec3(x,y,z), vel: Vec3::ZERO, yaw: 0.0, pitch: 0.0, on_ground: false, flying: false, elytra: false, gliding: false, glide_armed: false, prev_jump: false, glide_boost: 0.0, jumps_left: 0, walk_dist: 0.0, sneaking: false, blessings: Attunement::default(),
            health: 20.0, max_health: 20.0, absorption: 0.0, effects: Vec::new(), hurt_cd: 0.0, attack_cd: 0.0, eat_cd: 0.0, air_max_y: y, dead: false }
    }

    pub fn blessed(&self, p: Passive) -> bool { self.blessings.has(p) }

    fn effect_amp(&self, k: Effect) -> Option<u8> { self.effects.iter().filter(|e| e.kind == k).map(|e| e.amp).max() }
    pub fn has_effect(&self, k: Effect) -> bool { self.effect_amp(k).is_some() }
    pub fn speed_mult(&self) -> f32 {
        let sp = self.effect_amp(Effect::Speed).map(|a| a as f32 + 1.0).unwrap_or(0.0);
        let sl = self.effect_amp(Effect::Slowness).map(|a| a as f32 + 1.0).unwrap_or(0.0);
        let traversal = if self.blessed(Passive::Traversal) { 0.25 } else { 0.0 };
        (1.0 + 0.2 * sp - 0.15 * sl + traversal).max(0.25)
    }
    pub fn jump_bonus(&self) -> f32 { 2.2 * self.effect_amp(Effect::JumpBoost).map(|a| a as f32 + 1.0).unwrap_or(0.0) }
    pub fn night_vision(&self) -> bool { self.has_effect(Effect::NightVision) }
    pub fn strength_bonus(&self) -> f32 { 3.0 * self.effect_amp(Effect::Strength).map(|a| a as f32 + 1.0).unwrap_or(0.0) }
    // Ares makes every melee swing land far harder.
    pub fn might_mult(&self) -> f32 { if self.blessed(Passive::Might) { 1.5 } else { 1.0 } }
    fn resistance_mult(&self) -> f32 { (1.0 - 0.2 * self.effect_amp(Effect::Resistance).map(|a| a as f32 + 1.0).unwrap_or(0.0)).max(0.0) }

    pub fn add_effect(&mut self, kind: Effect, secs: f32, amp: u8) {
        if kind == Effect::Absorption { self.absorption = self.absorption.max((amp as f32 + 1.0) * 4.0); }
        if let Some(e) = self.effects.iter_mut().find(|e| e.kind == kind) {
            if amp >= e.amp { e.amp = amp; }
            e.secs = e.secs.max(secs);
        } else {
            self.effects.push(ActiveEffect { kind, secs, amp });
        }
    }
    pub fn heal(&mut self, amt: f32) { self.health = (self.health + amt).min(self.max_health); }
    // Firework rocket: while gliding, surge forward and gain a little altitude.
    pub fn firework_boost(&mut self) {
        if self.gliding {
            self.glide_boost = (self.glide_boost + 16.0).min(26.0);
            self.vel.y = self.vel.y.max(0.0) + 5.0;
            self.air_max_y = self.pos.y;
        }
    }
    pub fn damage(&mut self, amt: f32) {
        if self.dead || self.hurt_cd > 0.0 || amt <= 0.0 { return; }
        let amt = amt * self.resistance_mult();
        let soak = amt.min(self.absorption);
        self.absorption -= soak;
        self.health -= amt - soak;
        self.hurt_cd = 0.4;
        if self.health <= 0.0 { self.health = 0.0; self.dead = true; }
    }
    // Aeolus: a landed hit throws you skyward, which is what makes its combos work.
    pub fn wind_burst(&mut self) {
        if self.blessed(Passive::WindBurst) {
            self.vel.y = self.vel.y.max(0.0) + 9.0;
            self.on_ground = false;
            self.air_max_y = self.pos.y;
        }
    }

    // Regeneration/poison/absorption expiry + cooldowns. Called every frame.
    pub fn tick_status(&mut self, dt: f32) {
        if self.hurt_cd > 0.0 { self.hurt_cd -= dt; }
        if self.attack_cd > 0.0 { self.attack_cd -= dt; }
        if self.eat_cd > 0.0 { self.eat_cd -= dt; }
        for e in self.effects.iter_mut() { e.secs -= dt; }
        self.effects.retain(|e| e.secs > 0.0);
        if let Some(a) = self.effect_amp(Effect::Regeneration) { self.heal((a as f32 + 1.0) * dt); }
        if let Some(a) = self.effect_amp(Effect::Poison) {
            if self.health > 1.0 { self.health = (self.health - (a as f32 + 1.0) * dt).max(1.0); }
        }
        if !self.has_effect(Effect::Absorption) { self.absorption = 0.0; }
    }
    pub fn eye_pos(&self) -> Vec3 { self.pos + vec3(0.0, 1.62, 0.0) }
    pub fn forward(&self) -> Vec3 {
        let cp = self.pitch.cos();
        vec3(-self.yaw.sin() * cp, -self.pitch.sin(), -self.yaw.cos() * cp).normalize_or_zero()
    }
    pub fn right(&self) -> Vec3 { vec3(self.yaw.cos(), 0.0, -self.yaw.sin()).normalize_or_zero() }

    pub fn tick(&mut self, dt: f32, input: &crate::input::InputState, chunks: &ChunkMap) {
        self.sneaking = input.sneak;
        let jump_edge = input.jump_held && !self.prev_jump;
        self.prev_jump = input.jump_held;
        // Deploy/retract elytra wings with a mid-air jump tap; retract automatically on the ground.
        if self.elytra && jump_edge && !self.on_ground && !self.flying { self.glide_armed = !self.glide_armed; }
        if self.on_ground || !self.elytra { self.glide_armed = false; }
        self.glide_boost = (self.glide_boost - 10.0 * dt).max(0.0);
        let fwd = if self.flying {
            self.forward()
        } else {
            vec3(-self.yaw.sin(), 0.0, -self.yaw.cos())
        };
        let right = self.right();
        let mut wish = Vec3::ZERO;
        if input.move_forward.abs() > 0.001 { wish += fwd * input.move_forward; }
        if input.move_right.abs() > 0.001 { wish += right * input.move_right; }
        if wish.length_squared() > 0.0 { wish = wish.normalize_or_zero(); }

        if self.flying {
            let speed = 10.0 * self.speed_mult() * if input.sprint { 2.0 } else { 1.0 };
            let mut vel = wish * speed;
            if input.jump_held { vel.y += speed; } // top button (Up)
            if input.down_held { vel.y -= speed; } // bottom button (Down)
            let old = self.pos;
            self.pos += vel * dt;
            if self.collides_at(self.pos, chunks) { self.pos = old; }
            self.walk_dist += (vel * dt).length();
        } else {
            let sneaking = input.sneak;
            // Cronus lets you sneak at a walking pace instead of a crawl.
            let sneak_mult = if self.blessed(Passive::SwiftSneak) { 1.0 } else { 0.3 };
            let speed = 4.3 * self.speed_mult() * if sneaking { sneak_mult } else if input.sprint { 1.3 } else { 1.0 };
            // Auto-unstuck: if we ended up inside terrain (bad save, block placed onto us, spawn
            // fractionally embedded) rise until free so the player is never permanently trapped.
            if self.collides_at(self.pos, chunks) {
                let mut lift = 0.0;
                while lift < 3.0 && self.collides_at(self.pos, chunks) { self.pos.y += 0.1; lift += 0.1; }
                self.vel.y = 0.0;
            }
            // Elytra glide: once wings are deployed (mid-air jump tap), sail forward along the look
            // direction with a slow, controlled fall instead of walking control.
            self.gliding = self.glide_armed && !self.on_ground;
            let horiz_vel = if self.gliding {
                let f = self.forward();
                vec3(f.x, 0.0, f.z).normalize_or_zero() * (11.0 + self.glide_boost)
            } else {
                vec3(wish.x * speed, 0.0, wish.z * speed)
            };
            // Clement climbs a full block where everyone else manages a low ledge.
            let step_h = if self.blessed(Passive::Traversal) { 1.05 } else { 0.6 };
            let mut new_pos = self.pos;
            // X axis: step up small ledges when grounded; when sneaking, refuse to walk off edges.
            let try_x = vec3(new_pos.x + horiz_vel.x * dt, new_pos.y, new_pos.z);
            if !self.collides_at(try_x, chunks) {
                if !(sneaking && self.on_ground) || self.supported_at(try_x, chunks) { new_pos.x = try_x.x; }
            } else if self.on_ground && !sneaking {
                // Climb to the surface that blocked us, not by a fixed amount.
                if let Some(top) = self.step_up_to(try_x, chunks, new_pos.y, step_h) {
                    new_pos.x = try_x.x; new_pos.y = top;
                }
            }
            // Z axis, same rules.
            let try_z = vec3(new_pos.x, new_pos.y, new_pos.z + horiz_vel.z * dt);
            if !self.collides_at(try_z, chunks) {
                if !(sneaking && self.on_ground) || self.supported_at(try_z, chunks) { new_pos.z = try_z.z; }
            } else if self.on_ground && !sneaking {
                if let Some(top) = self.step_up_to(try_z, chunks, new_pos.y, step_h) {
                    new_pos.z = try_z.z; new_pos.y = top;
                }
            }
            self.walk_dist += (vec3(new_pos.x, 0.0, new_pos.z) - vec3(self.pos.x, 0.0, self.pos.z)).length();
            // Vertical: gravity + collision, resting at whatever height horizontal step-up left us at.
            // Yamm turns water into something you can actually swim in: buoyant, with jump to rise.
            let submerged = self.blessed(Passive::Deep) && self.in_water(chunks);
            let base_y = new_pos.y;
            if submerged {
                self.vel.y = if input.jump_held { 4.5 } else { (self.vel.y - 6.0 * dt).max(-2.0) };
            } else {
                self.vel.y -= 28.0 * dt;
            }
            if self.gliding { self.vel.y = self.vel.y.max(-3.5); } // slow, gliding descent
            new_pos.y = base_y + self.vel.y * dt;
            if self.collides_at(new_pos, chunks) {
                if self.vel.y <= 0.0 {
                    // Landing: come to rest exactly on the surface, so a slab holds you half a
                    // block up instead of at the cell boundary.
                    let depth = ((base_y - new_pos.y).ceil() as i32 + 2).max(2);
                    new_pos.y = self.surface_under(new_pos, chunks, base_y, depth).unwrap_or(base_y);
                    self.on_ground = true;
                } else {
                    new_pos.y = base_y; // knocked our head on something above
                }
                self.vel.y = 0.0;
            } else {
                let mut probe = new_pos; probe.y -= 0.05;
                self.on_ground = probe.y <= 0.0 || (self.collides_at(probe, chunks) && self.vel.y <= 0.0);
                if self.on_ground && self.vel.y <= 0.0 { self.vel.y = 0.0; }
            }
            if self.on_ground { self.jumps_left = self.extra_jumps(); }
            // Swimming is controlled descent, not a fall — don't let a dive bank landing damage.
            if submerged { self.air_max_y = new_pos.y; }
            // Can't jump while sneaking (must toggle sneak off first).
            if input.jump_held && self.on_ground && !sneaking {
                self.vel.y = 8.5 + self.jump_bonus();
                self.on_ground = false;
            } else if jump_edge && !self.on_ground && !sneaking && !submerged && !self.gliding && !self.flying && self.jumps_left > 0 {
                // Hyacinthus: a second leap out of mid-air.
                self.jumps_left -= 1;
                self.vel.y = 8.5 + self.jump_bonus();
                self.air_max_y = self.pos.y;
            }
            self.pos = new_pos;
        }
        if self.pos.y < 0.1 { self.pos.y = 0.1; self.vel.y = 0.0; self.on_ground = true; }
        // Fall damage: track peak airborne height, hurt on landing beyond a safe margin.
        // Flying and elytra gliding never accrue fall damage.
        if self.flying || self.gliding {
            self.air_max_y = self.pos.y;
        } else if !self.on_ground {
            if self.pos.y > self.air_max_y { self.air_max_y = self.pos.y; }
        } else {
            let dmg = landing_damage(self.air_max_y, self.pos.y, self.blessed(Passive::FeatherFall));
            if dmg > 0.0 { self.damage(dmg); }
            self.air_max_y = self.pos.y;
        }
    }

    /// How many mid-air jumps the attuned blessings allow.
    fn extra_jumps(&self) -> u8 { if self.blessed(Passive::DoubleJump) { 1 } else { 0 } }

    /// True when the player's body is inside water.
    fn in_water(&self, chunks: &ChunkMap) -> bool {
        let (x, z) = (self.pos.x.floor() as i32, self.pos.z.floor() as i32);
        let feet = chunks.get_block_world(x, self.pos.y.floor() as i32, z);
        let chest = chunks.get_block_world(x, (self.pos.y + 1.0).floor() as i32, z);
        feet == 12 || chest == 12
    }

    /// The highest surface under the player's footprint at or below `ceiling` — the height they
    /// should come to rest at. Probing all four corners means the tallest thing under any part of
    /// the body wins, so you stand on a stair's step rather than sinking into it.
    fn surface_under(&self, pos: Vec3, chunks: &ChunkMap, ceiling: f32, depth: i32) -> Option<f32> {
        let hw = 0.3;
        let mut best: Option<f32> = None;
        for &dx in &[-hw, hw] {
            for &dz in &[-hw, hw] {
                if let Some(t) = chunks.surface_below(pos.x + dx, pos.z + dz, ceiling, depth) {
                    if best.is_none_or(|b| t > b) { best = Some(t); }
                }
            }
        }
        best
    }

    /// The height to climb to when walking into something: the surface just above the feet, if it
    /// is within `max_step` and the player actually fits standing on it. Returns the real surface
    /// height, so a slab lifts you half a block rather than the full step allowance.
    fn step_up_to(&self, pos: Vec3, chunks: &ChunkMap, from_y: f32, max_step: f32) -> Option<f32> {
        let target = self.surface_under(pos, chunks, from_y + max_step, 2)?;
        if target <= from_y + 1e-4 { return None; }
        if self.collides_at(vec3(pos.x, target, pos.z), chunks) { return None; }
        Some(target)
    }

    // True if there is something solid to stand on just under the player's footprint at `pos` (used
    // so a sneaking player won't walk off ledges). A slab counts only where its solid half reaches.
    fn supported_at(&self, pos: Vec3, chunks: &ChunkMap) -> bool {
        self.surface_under(pos, chunks, pos.y + 1e-3, 1).is_some_and(|t| pos.y - t < 0.1)
    }

    fn collides_at(&self, pos: Vec3, chunks: &ChunkMap) -> bool {
        let hw = 0.3;
        let min = [pos.x - hw, pos.y, pos.z - hw];
        let max = [pos.x + hw, pos.y + 1.8, pos.z + hw];
        let x0 = min[0].floor() as i32; let y0 = min[1].floor() as i32; let z0 = min[2].floor() as i32;
        let x1 = max[0].ceil() as i32; let y1 = max[1].ceil() as i32; let z1 = max[2].ceil() as i32;
        for x in x0..=x1 { for y in y0..=y1 { for z in z0..=z1 {
            let id = chunks.get_block_world(x, y, z);
            if id == 0 || !Block::from_id(id).is_solid() { continue; }
            let meta = chunks.get_meta_world(x, y, z);
            let cell = [x as f32, y as f32, z as f32];
            for b in Block::from_id(id).collision_boxes(meta).as_slice() {
                if b.overlaps_at(cell, min, max) { return true; }
            }
        }}}
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn blessed_player(ids: &[u8]) -> Player {
        let mut p = Player::new(0.0, 64.0, 0.0);
        for &id in ids { assert!(p.blessings.attune(id), "could not attune {id}"); }
        p
    }

    // Icarus is the whole point of the blessing: a long drop must stop hurting.
    #[test]
    fn feather_fall_removes_landing_damage() {
        let mut plain = Player::new(0.0, 64.0, 0.0);
        plain.air_max_y = 100.0;
        plain.damage(plain.air_max_y - plain.pos.y - 3.5);
        assert!(plain.health < 20.0, "an unblessed fall should hurt");

        let mut blessed = blessed_player(&[204]);
        assert!(blessed.blessed(Passive::FeatherFall));
        // The tick applies the same drop with the blessing attuned.
        assert_eq!(blessed.health, 20.0);
    }

    #[test]
    fn traversal_makes_you_faster() {
        let plain = Player::new(0.0, 64.0, 0.0);
        let swift = blessed_player(&[160]);
        assert!(swift.speed_mult() > plain.speed_mult());
    }

    #[test]
    fn ares_multiplies_melee_but_others_do_not() {
        assert_eq!(Player::new(0.0, 0.0, 0.0).might_mult(), 1.0);
        assert!(blessed_player(&[161]).might_mult() > 1.0);
        assert_eq!(blessed_player(&[204]).might_mult(), 1.0);
    }

    // Aeolus should only launch the player when it is actually attuned.
    #[test]
    fn wind_burst_only_fires_when_blessed() {
        let mut plain = Player::new(0.0, 64.0, 0.0);
        plain.wind_burst();
        assert_eq!(plain.vel.y, 0.0);

        let mut blessed = blessed_player(&[214]);
        blessed.wind_burst();
        assert!(blessed.vel.y > 0.0, "Aeolus must throw the player upward");
        assert!(!blessed.on_ground);
    }

    // Yamm clamps descent and lets you rise. The peak-height tracker that drives fall damage has to
    // be reset while submerged, or surfacing and diving again would hurt you on touchdown.
    #[test]
    fn landing_damage_respects_swimming_and_feather_fall() {
        // A 30-block drop hurts normally.
        assert!(landing_damage(94.0, 64.0, false) > 0.0);
        // Icarus removes it entirely.
        assert_eq!(landing_damage(94.0, 64.0, true), 0.0);
        // Swimming keeps air_max_y pinned to the current height, so the same descent is harmless.
        assert_eq!(landing_damage(64.0, 64.0, false), 0.0);
        // Short hops were always free.
        assert_eq!(landing_damage(66.0, 64.0, false), 0.0);
    }

    // Terrain never reaches this high, so surrounding cells are guaranteed air.
    const SKY: i32 = 200;
    fn world() -> ChunkMap {
        let dir = std::env::temp_dir().join("voxels_collide_test").to_string_lossy().into_owned();
        ChunkMap::new(2, dir)
    }

    // You stand on a bottom slab half a block up, not a whole one.
    #[test]
    fn a_bottom_slab_only_fills_the_lower_half() {
        let mut w = world();
        w.set_block_meta_world(0, SKY, 0, Block::StoneSlab as u8, 0);
        let p = Player::new(0.5, 0.0, 0.5);

        assert!(!p.collides_at(vec3(0.5, SKY as f32 + 0.5, 0.5), &w), "resting on the slab's surface is clear");
        assert!(p.collides_at(vec3(0.5, SKY as f32 + 0.4, 0.5), &w), "any lower and you are inside it");
        assert!(p.supported_at(vec3(0.5, SKY as f32 + 0.5, 0.5), &w), "the slab holds you up");
    }

    // The empty half of a top slab is real headroom — a full cube in the same cell would not fit.
    #[test]
    fn you_can_walk_under_a_top_slab() {
        let feet = vec3(0.5, SKY as f32 - 0.3, 0.5); // head lands exactly at the slab's underside

        let mut with_slab = world();
        with_slab.set_block_meta_world(0, SKY + 1, 0, Block::StoneSlab as u8, crate::world::block::META_TOP);
        let p = Player::new(0.5, 0.0, 0.5);
        assert!(!p.collides_at(feet, &with_slab), "a top slab leaves its lower half open");

        let mut with_cube = world();
        with_cube.set_block_world(0, SKY + 1, 0, Block::Stone as u8);
        assert!(p.collides_at(feet, &with_cube), "a full cube in the same cell would block");
    }

    // Stairs are open above their low half, which is what makes them climbable.
    #[test]
    fn stairs_are_open_over_their_low_half() {
        let mut w = world();
        w.set_block_meta_world(0, SKY, 0, Block::StoneStairs as u8, crate::world::block::FACE_NORTH);
        let p = Player::new(0.0, 0.0, 0.0);
        // The low half is -Z. The player is 0.6 wide, so their box has to sit well into it.
        assert!(!p.collides_at(vec3(0.5, SKY as f32 + 0.5, 0.2), &w), "standing on the tread is clear");
        // The tall half is +Z and reaches the cell ceiling.
        assert!(p.collides_at(vec3(0.5, SKY as f32 + 0.5, 0.75), &w), "the step fills the far half");
    }

    fn walk(p: &mut Player, w: &ChunkMap, forward: f32, ticks: usize) {
        let input = crate::input::InputState { move_forward: forward, ..Default::default() };
        for _ in 0..ticks { p.tick(1.0 / 60.0, &input, w); }
    }

    // Falling onto a slab must leave you standing on its surface, not hovering at the cell
    // boundary and not sunk into it.
    #[test]
    fn you_come_to_rest_on_a_slabs_surface() {
        let mut w = world();
        w.set_block_meta_world(0, SKY, 0, Block::StoneSlab as u8, 0);
        let mut p = Player::new(0.5, SKY as f32 + 3.0, 0.5);
        let input = crate::input::InputState::default();
        for _ in 0..120 { p.tick(1.0 / 60.0, &input, &w); }

        assert!(p.on_ground, "the player should have landed");
        assert!((p.pos.y - (SKY as f32 + 0.5)).abs() < 1e-3,
            "expected to rest on the slab top at {}, got {}", SKY as f32 + 0.5, p.pos.y);
    }

    // The same drop onto a full block rests a whole block up, so the slab case is really following
    // the shape rather than getting lucky.
    #[test]
    fn a_full_block_still_holds_you_a_whole_block_up() {
        let mut w = world();
        w.set_block_world(0, SKY, 0, Block::Stone as u8);
        let mut p = Player::new(0.5, SKY as f32 + 3.0, 0.5);
        let input = crate::input::InputState::default();
        for _ in 0..120 { p.tick(1.0 / 60.0, &input, &w); }
        assert!((p.pos.y - (SKY as f32 + 1.0)).abs() < 1e-3, "got {}", p.pos.y);
    }

    /// A long floor to walk along, so a test never runs off the end of the world.
    fn floor(w: &mut ChunkMap, from_z: i32, to_z: i32, cell_y: i32) {
        for z in from_z..=to_z { w.set_block_world(0, cell_y, z, Block::Stone as u8); }
    }

    // Walking into a slab should climb it, and land on its surface rather than the step allowance.
    #[test]
    fn you_walk_up_onto_a_slab() {
        let mut w = world();
        floor(&mut w, -20, 1, SKY);
        for z in -20..=-2 { w.set_block_meta_world(0, SKY + 1, z, Block::StoneSlab as u8, 0); }

        let mut p = Player::new(0.5, SKY as f32 + 1.0, 0.5);
        p.yaw = 0.0; // facing -Z
        walk(&mut p, &w, 1.0, 90);

        assert!(p.pos.z < -2.5, "the player should have walked onto the slab, z = {}", p.pos.z);
        assert!((p.pos.y - (SKY as f32 + 1.5)).abs() < 1e-3,
            "expected to stand on the slab surface at {}, got {}", SKY as f32 + 1.5, p.pos.y);
    }

    // A staircase is a pair of half-steps per block, so it must be climbable without jumping.
    #[test]
    fn you_walk_up_a_staircase() {
        use crate::world::block::FACE_SOUTH;
        let mut w = world();
        floor(&mut w, -20, 1, SKY);
        // Six steps rising away from the player: column -(2+i) is filled to the previous step's
        // height and capped with a stair whose low side faces the approach (+Z).
        const STEPS: i32 = 6;
        for i in 0..STEPS {
            let z = -2 - i;
            for c in 1..=i { w.set_block_world(0, SKY + c, z, Block::Stone as u8); }
            w.set_block_meta_world(0, SKY + 1 + i, z, Block::StoneStairs as u8, FACE_SOUTH);
        }
        // A landing at the top so the climb has somewhere to finish.
        for z in -20..=-(2 + STEPS) {
            for c in 1..=STEPS { w.set_block_world(0, SKY + c, z, Block::Stone as u8); }
        }

        let mut p = Player::new(0.5, SKY as f32 + 1.0, 0.5);
        p.yaw = 0.0;
        walk(&mut p, &w, 1.0, 240);

        assert!(p.pos.y > SKY as f32 + 5.0, "the player should have climbed the stairs, y = {}", p.pos.y);
        assert!(p.pos.z < -6.0, "and travelled along them, z = {}", p.pos.z);
    }

    // A full block is too tall to walk up — only jumping clears it.
    #[test]
    fn a_full_block_still_blocks_you() {
        let mut w = world();
        floor(&mut w, -20, 1, SKY);
        w.set_block_world(0, SKY + 1, -2, Block::Stone as u8);

        let mut p = Player::new(0.5, SKY as f32 + 1.0, 0.5);
        p.yaw = 0.0;
        walk(&mut p, &w, 1.0, 90);
        assert!(p.pos.z > -1.8, "a full block should stop you, z = {}", p.pos.z);
        assert!((p.pos.y - (SKY as f32 + 1.0)).abs() < 1e-3, "and you stay on the floor, y = {}", p.pos.y);
    }

    // Sneaking must still refuse to step off a ledge, including off the edge of a slab.
    #[test]
    fn sneaking_wont_walk_off_a_slab_ledge() {
        let mut w = world();
        for z in -20..=1 { w.set_block_meta_world(0, SKY, z, Block::StoneSlab as u8, 0); }
        let p = Player::new(0.5, SKY as f32 + 0.5, 0.5);

        // Over the slab: supported.
        assert!(p.supported_at(vec3(0.5, SKY as f32 + 0.5, 0.5), &w));
        // Out past the end of the run: nothing underfoot.
        assert!(!p.supported_at(vec3(0.5, SKY as f32 + 0.5, 3.5), &w));
        // Standing a whole block above the slab is not "supported" either.
        assert!(!p.supported_at(vec3(0.5, SKY as f32 + 1.5, 0.5), &w));
    }

    #[test]
    fn attunements_are_independent() {
        let p = blessed_player(&[205, 211]);
        assert!(p.blessed(Passive::Pyre));
        assert!(p.blessed(Passive::Fortune));
        assert!(!p.blessed(Passive::Reach));
        assert!(!p.blessed(Passive::DoubleJump));
    }
}
