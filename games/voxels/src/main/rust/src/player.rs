use glam::{Vec3, vec3};
use crate::world::{ChunkMap, block::Block};

pub struct Player {
    pub pos: Vec3,
    pub vel: Vec3,
    pub yaw: f32,
    pub pitch: f32,
    pub on_ground: bool,
    pub flying: bool,
    pub walk_dist: f32,
}

impl Player {
    pub fn new(x: f32, y: f32, z: f32) -> Self {
        Self { pos: vec3(x,y,z), vel: Vec3::ZERO, yaw: 0.0, pitch: 0.0, on_ground: false, flying: false, walk_dist: 0.0 }
    }
    pub fn eye_pos(&self) -> Vec3 { self.pos + vec3(0.0, 1.62, 0.0) }
    pub fn forward(&self) -> Vec3 {
        let cp = self.pitch.cos();
        vec3(-self.yaw.sin() * cp, -self.pitch.sin(), -self.yaw.cos() * cp).normalize_or_zero()
    }
    pub fn right(&self) -> Vec3 { vec3(self.yaw.cos(), 0.0, -self.yaw.sin()).normalize_or_zero() }

    pub fn tick(&mut self, dt: f32, input: &crate::input::InputState, chunks: &ChunkMap) {
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

        let speed = if self.flying { 10.0 * if input.sprint { 2.0 } else { 1.0 } } else { 4.3 * if input.sprint { 1.3 } else { 1.0 } };

        if self.flying {
            let mut vel = wish * speed;
            if input.jump { vel.y += speed; }
            if input.sneak { vel.y -= speed; }
            let old = self.pos;
            self.pos += vel * dt;
            if self.collides_at(self.pos, chunks) { self.pos = old; }
            self.walk_dist += (vel * dt).length();
        } else {
            let horiz_vel = vec3(wish.x * speed, 0.0, wish.z * speed);
            let mut new_pos = self.pos;
            new_pos.x += horiz_vel.x * dt;
            if self.collides_at(new_pos, chunks) { new_pos.x = self.pos.x; }
            new_pos.z += horiz_vel.z * dt;
            if self.collides_at(new_pos, chunks) { new_pos.z = self.pos.z; }
            self.walk_dist += (new_pos - self.pos).length();
            self.vel.y -= 28.0 * dt;
            new_pos.y += self.vel.y * dt;
            if self.collides_at(new_pos, chunks) {
                new_pos.y = self.pos.y;
                if self.vel.y <= 0.0 { self.on_ground = true; }
                self.vel.y = 0.0;
            } else {
                let mut probe = new_pos; probe.y -= 0.01;
                self.on_ground = probe.y <= 0.0 || (self.collides_at(probe, chunks) && self.vel.y <= 0.0);
                if self.on_ground && self.vel.y <= 0.0 { self.vel.y = 0.0; }
            }
            if input.jump && self.on_ground {
                self.vel.y = 8.5;
                self.on_ground = false;
            }
            self.pos = new_pos;
        }
        if self.pos.y < 0.1 { self.pos.y = 0.1; self.vel.y = 0.0; self.on_ground = true; }
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
