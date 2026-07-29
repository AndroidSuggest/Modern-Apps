use glam::{Vec3, vec3};
use crate::world::{ChunkMap, block::Block};
use crate::item::{Effect, ActiveEffect};

pub const MIN_MAX_HEALTH: f32 = 20.0; // 10 hearts floor (deaths never take you below this)
pub const CAP_MAX_HEALTH: f32 = 60.0; // 30 hearts cap (heart containers)

pub struct Player {
    pub pos: Vec3,
    pub vel: Vec3,
    pub yaw: f32,
    pub pitch: f32,
    pub on_ground: bool,
    pub flying: bool,
    pub walk_dist: f32,
    pub sneaking: bool,
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

impl Player {
    pub fn new(x: f32, y: f32, z: f32) -> Self {
        Self { pos: vec3(x,y,z), vel: Vec3::ZERO, yaw: 0.0, pitch: 0.0, on_ground: false, flying: false, walk_dist: 0.0, sneaking: false,
            health: 20.0, max_health: 20.0, absorption: 0.0, effects: Vec::new(), hurt_cd: 0.0, attack_cd: 0.0, eat_cd: 0.0, air_max_y: y, dead: false }
    }

    fn effect_amp(&self, k: Effect) -> Option<u8> { self.effects.iter().filter(|e| e.kind == k).map(|e| e.amp).max() }
    pub fn has_effect(&self, k: Effect) -> bool { self.effect_amp(k).is_some() }
    pub fn speed_mult(&self) -> f32 {
        let sp = self.effect_amp(Effect::Speed).map(|a| a as f32 + 1.0).unwrap_or(0.0);
        let sl = self.effect_amp(Effect::Slowness).map(|a| a as f32 + 1.0).unwrap_or(0.0);
        (1.0 + 0.2 * sp - 0.15 * sl).max(0.25)
    }
    pub fn jump_bonus(&self) -> f32 { 2.2 * self.effect_amp(Effect::JumpBoost).map(|a| a as f32 + 1.0).unwrap_or(0.0) }
    pub fn night_vision(&self) -> bool { self.has_effect(Effect::NightVision) }
    pub fn strength_bonus(&self) -> f32 { 3.0 * self.effect_amp(Effect::Strength).map(|a| a as f32 + 1.0).unwrap_or(0.0) }
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
    pub fn damage(&mut self, amt: f32) {
        if self.dead || self.hurt_cd > 0.0 || amt <= 0.0 { return; }
        let amt = amt * self.resistance_mult();
        let soak = amt.min(self.absorption);
        self.absorption -= soak;
        self.health -= amt - soak;
        self.hurt_cd = 0.4;
        if self.health <= 0.0 { self.health = 0.0; self.dead = true; }
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
            let speed = 4.3 * self.speed_mult() * if sneaking { 0.3 } else if input.sprint { 1.3 } else { 1.0 };
            // Auto-unstuck: if we ended up inside terrain (bad save, block placed onto us, spawn
            // fractionally embedded) rise until free so the player is never permanently trapped.
            if self.collides_at(self.pos, chunks) {
                let mut lift = 0.0;
                while lift < 3.0 && self.collides_at(self.pos, chunks) { self.pos.y += 0.1; lift += 0.1; }
                self.vel.y = 0.0;
            }
            let horiz_vel = vec3(wish.x * speed, 0.0, wish.z * speed);
            let mut new_pos = self.pos;
            // X axis: step up small ledges when grounded; when sneaking, refuse to walk off edges.
            let try_x = vec3(new_pos.x + horiz_vel.x * dt, new_pos.y, new_pos.z);
            if !self.collides_at(try_x, chunks) {
                if !(sneaking && self.on_ground) || self.supported_at(try_x, chunks) { new_pos.x = try_x.x; }
            } else if self.on_ground && !sneaking {
                let step = vec3(try_x.x, new_pos.y + 0.6, new_pos.z);
                if !self.collides_at(step, chunks) { new_pos.x = step.x; new_pos.y = step.y; }
            }
            // Z axis, same rules.
            let try_z = vec3(new_pos.x, new_pos.y, new_pos.z + horiz_vel.z * dt);
            if !self.collides_at(try_z, chunks) {
                if !(sneaking && self.on_ground) || self.supported_at(try_z, chunks) { new_pos.z = try_z.z; }
            } else if self.on_ground && !sneaking {
                let step = vec3(new_pos.x, new_pos.y + 0.6, try_z.z);
                if !self.collides_at(step, chunks) { new_pos.z = step.z; new_pos.y = step.y; }
            }
            self.walk_dist += (vec3(new_pos.x, 0.0, new_pos.z) - vec3(self.pos.x, 0.0, self.pos.z)).length();
            // Vertical: gravity + collision, resting at whatever height horizontal step-up left us at.
            let base_y = new_pos.y;
            self.vel.y -= 28.0 * dt;
            new_pos.y = base_y + self.vel.y * dt;
            if self.collides_at(new_pos, chunks) {
                new_pos.y = base_y;
                if self.vel.y <= 0.0 { self.on_ground = true; }
                self.vel.y = 0.0;
            } else {
                let mut probe = new_pos; probe.y -= 0.05;
                self.on_ground = probe.y <= 0.0 || (self.collides_at(probe, chunks) && self.vel.y <= 0.0);
                if self.on_ground && self.vel.y <= 0.0 { self.vel.y = 0.0; }
            }
            // Can't jump while sneaking (must toggle sneak off first).
            if input.jump_held && self.on_ground && !sneaking {
                self.vel.y = 8.5 + self.jump_bonus();
                self.on_ground = false;
            }
            self.pos = new_pos;
        }
        if self.pos.y < 0.1 { self.pos.y = 0.1; self.vel.y = 0.0; self.on_ground = true; }
        // Fall damage: track peak airborne height, hurt on landing beyond a safe margin.
        if self.flying {
            self.air_max_y = self.pos.y;
        } else if !self.on_ground {
            if self.pos.y > self.air_max_y { self.air_max_y = self.pos.y; }
        } else {
            let fall = self.air_max_y - self.pos.y;
            if fall > 3.5 { self.damage(fall - 3.5); }
            self.air_max_y = self.pos.y;
        }
    }

    // True if there is a solid block just under the player's footprint at `pos` (used so a sneaking
    // player won't walk off ledges).
    fn supported_at(&self, pos: Vec3, chunks: &ChunkMap) -> bool {
        let hw = 0.3;
        let y = (pos.y - 0.05).floor() as i32;
        for &dx in &[-hw, hw] {
            for &dz in &[-hw, hw] {
                let x = (pos.x + dx).floor() as i32;
                let z = (pos.z + dz).floor() as i32;
                let id = chunks.get_block_world(x, y, z);
                if id != 0 && Block::from_id(id).is_solid() { return true; }
            }
        }
        false
    }

    fn collides_at(&self, pos: Vec3, chunks: &ChunkMap) -> bool {
        let hw = 0.3;
        let min = vec3(pos.x - hw, pos.y, pos.z - hw);
        let max = vec3(pos.x + hw, pos.y + 1.8, pos.z + hw);
        let x0 = min.x.floor() as i32; let y0 = min.y.floor() as i32; let z0 = min.z.floor() as i32;
        let x1 = max.x.ceil() as i32; let y1 = max.y.ceil() as i32; let z1 = max.z.ceil() as i32;
        for x in x0..=x1 { for y in y0..=y1 { for z in z0..=z1 {
            let id = chunks.get_block_world(x, y, z);
            if id != 0 && Block::from_id(id).is_solid() {
                if (x as f32 + 1.0) > min.x && (x as f32) < max.x && (y as f32 + 1.0) > min.y && (y as f32) < max.y && (z as f32 + 1.0) > min.z && (z as f32) < max.z {
                    return true;
                }
            }
        }}}
        false
    }
}
