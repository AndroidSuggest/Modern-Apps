// Mobs: simple entities with wander/chase AI, gravity + block collision, and skinned box-model
// rendering into the entity atlas (see texture_atlas::load_entity_atlas_bin / build_entity_atlas.py).
use glam::Vec3;
use crate::vulkan::buffers::Vertex;
use crate::texture_atlas::{ENTITY_ATLAS_W, ENTITY_CELL};

const ATLAS: f32 = ENTITY_ATLAS_W as f32;
const CELL: f32 = ENTITY_CELL as f32;
const PX: f32 = 1.0 / 16.0; // one skin pixel in blocks

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum MobKind { Pig, Cow, Sheep, Chicken, Creeper, Zombie }

// A model part: an axis-aligned box textured from the skin.
struct Part {
    size: [f32; 3], // pixels (w, h, d)
    pos: [f32; 3],  // centre in blocks, feet at y=0, y up
    uv: [f32; 2],   // skin-space origin (pixels)
    x_rot: f32,     // static rotation about X (radians) around `pos`
    leg: f32,       // walk-swing phase sign (0 = not animated); rotates about the part's top
}

const fn p(size: [f32;3], pos: [f32;3], uv: [f32;2], x_rot: f32, leg: f32) -> Part { Part{size,pos,uv,x_rot,leg} }
const HP: f32 = std::f32::consts::FRAC_PI_2;

// Quadruped (pig/cow/sheep): head, laid-down body, four legs.
static QUAD: &[Part] = &[
    p([8.,8.,8.],   [0.0, 0.70, -0.44], [0.,0.],   0.0, 0.0),
    p([10.,16.,8.], [0.0, 0.56, 0.06],  [28.,8.],  HP,  0.0),
    p([4.,6.,4.],   [-0.16, 0.1875, -0.22], [0.,16.], 0.0,  1.0),
    p([4.,6.,4.],   [0.16, 0.1875, -0.22],  [0.,16.], 0.0, -1.0),
    p([4.,6.,4.],   [-0.16, 0.1875, 0.28],  [0.,16.], 0.0, -1.0),
    p([4.,6.,4.],   [0.16, 0.1875, 0.28],   [0.,16.], 0.0,  1.0),
];
// Biped (zombie): head, body, two legs, two arms.
static BIPED: &[Part] = &[
    p([8.,8.,8.],   [0.0, 1.50, 0.0], [0.,0.],   0.0, 0.0),
    p([8.,12.,4.],  [0.0, 1.125, 0.0], [16.,16.], 0.0, 0.0),
    p([4.,12.,4.],  [-0.125, 0.375, 0.0], [0.,16.],  0.0,  1.0),
    p([4.,12.,4.],  [0.125, 0.375, 0.0],  [0.,16.],  0.0, -1.0),
    p([4.,12.,4.],  [-0.375, 1.125, 0.0], [40.,16.], 0.0, -1.0),
    p([4.,12.,4.],  [0.375, 1.125, 0.0],  [40.,16.], 0.0,  1.0),
];
// Creeper: head, tall body, four short legs.
static CREEPER: &[Part] = &[
    p([8.,8.,8.],  [0.0, 1.375, 0.0], [0.,0.],   0.0, 0.0),
    p([8.,12.,4.], [0.0, 0.75, 0.0],  [16.,16.], 0.0, 0.0),
    p([4.,6.,4.],  [-0.125, 0.1875, -0.125], [0.,16.], 0.0,  1.0),
    p([4.,6.,4.],  [0.125, 0.1875, -0.125],  [0.,16.], 0.0, -1.0),
    p([4.,6.,4.],  [-0.125, 0.1875, 0.125],  [0.,16.], 0.0, -1.0),
    p([4.,6.,4.],  [0.125, 0.1875, 0.125],   [0.,16.], 0.0,  1.0),
];
// Chicken: small body, head, two thin legs.
static CHICKEN: &[Part] = &[
    p([6.,8.,6.],  [0.0, 0.38, 0.0],  [0.,9.],  0.0, 0.0),
    p([4.,6.,3.],  [0.0, 0.62, -0.18], [0.,0.], 0.0, 0.0),
    p([2.,5.,2.],  [-0.09, 0.15, 0.0], [26.,2.], 0.0,  1.0),
    p([2.,5.,2.],  [0.09, 0.15, 0.0],  [26.,2.], 0.0, -1.0),
];

impl MobKind {
    fn cell(self) -> u32 { match self { MobKind::Pig=>0, MobKind::Cow=>1, MobKind::Sheep=>2, MobKind::Chicken=>3, MobKind::Creeper=>4, MobKind::Zombie=>5 } }
    pub fn hostile(self) -> bool { matches!(self, MobKind::Creeper | MobKind::Zombie) }
    fn speed(self) -> f32 { match self { MobKind::Chicken=>1.6, MobKind::Creeper=>1.5, MobKind::Zombie=>1.7, _=>1.15 } }
    fn parts(self) -> &'static [Part] {
        match self {
            MobKind::Pig | MobKind::Cow | MobKind::Sheep => QUAD,
            MobKind::Zombie => BIPED,
            MobKind::Creeper => CREEPER,
            MobKind::Chicken => CHICKEN,
        }
    }
}

pub struct Mob {
    pub kind: MobKind,
    pub pos: Vec3,
    pub vel: Vec3,
    pub yaw: f32,
    target_yaw: f32,
    wander: f32,
    anim: f32,
    on_ground: bool,
    rng: u32,
}

fn xorshift(s: &mut u32) -> f32 {
    let mut x = *s; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *s = x;
    (x >> 8) as f32 / 16_777_216.0 // [0,1)
}

impl Mob {
    pub fn new(kind: MobKind, pos: Vec3, seed: u32) -> Self {
        Mob { kind, pos, vel: Vec3::ZERO, yaw: 0.0, target_yaw: 0.0, wander: 0.0, anim: 0.0, on_ground: false, rng: seed | 1 }
    }

    pub fn tick(&mut self, dt: f32, player: Vec3, solid: &dyn Fn(i32, i32, i32) -> bool) {
        self.wander -= dt;
        let to_player = player - self.pos;
        let pdist = to_player.length();
        if self.wander <= 0.0 {
            self.wander = 1.5 + xorshift(&mut self.rng) * 3.0;
            if self.kind.hostile() && pdist < 22.0 {
                self.target_yaw = to_player.x.atan2(-to_player.z); // face the player
            } else {
                self.target_yaw = (xorshift(&mut self.rng) - 0.5) * std::f32::consts::TAU;
            }
            // Occasionally idle (stand still).
            if !self.kind.hostile() && xorshift(&mut self.rng) < 0.3 { self.wander = 1.0; self.target_yaw = self.yaw; }
        }
        // Ease yaw toward the target.
        let mut dyaw = self.target_yaw - self.yaw;
        while dyaw > std::f32::consts::PI { dyaw -= std::f32::consts::TAU; }
        while dyaw < -std::f32::consts::PI { dyaw += std::f32::consts::TAU; }
        self.yaw += dyaw.clamp(-3.0 * dt, 3.0 * dt);

        let chasing = self.kind.hostile() && pdist < 22.0;
        let idle = !chasing && (self.target_yaw - self.yaw).abs() < 0.05 && self.wander < 1.05 && !self.kind.hostile();
        let speed = if idle { 0.0 } else { self.kind.speed() * if chasing { 1.25 } else { 1.0 } };
        // Model front is -Z; rotating (0,0,-1) by yaw gives (sin, 0, -cos).
        let fwd = Vec3::new(self.yaw.sin(), 0.0, -self.yaw.cos());

        // Gravity.
        self.vel.y -= 24.0 * dt;
        self.vel.y = self.vel.y.max(-40.0);

        let at = |x: f32, y: f32, z: f32| solid(x.floor() as i32, y.floor() as i32, z.floor() as i32);
        let r = 0.3;
        // Horizontal move per axis, blocked by walls, with a 1-block auto step-up on the ground.
        let dx = fwd.x * speed * dt;
        if dx != 0.0 {
            let nx = self.pos.x + dx + r * dx.signum();
            if !at(nx, self.pos.y + 0.2, self.pos.z) && !at(nx, self.pos.y + 1.0, self.pos.z) {
                self.pos.x += dx;
            } else if self.on_ground && !at(nx, self.pos.y + 1.2, self.pos.z) && !at(nx, self.pos.y + 2.0, self.pos.z) {
                self.pos.x += dx; self.pos.y += 1.0;
            }
        }
        let dz = fwd.z * speed * dt;
        if dz != 0.0 {
            let nz = self.pos.z + dz + r * dz.signum();
            if !at(self.pos.x, self.pos.y + 0.2, nz) && !at(self.pos.x, self.pos.y + 1.0, nz) {
                self.pos.z += dz;
            } else if self.on_ground && !at(self.pos.x, self.pos.y + 1.2, nz) && !at(self.pos.x, self.pos.y + 2.0, nz) {
                self.pos.z += dz; self.pos.y += 1.0;
            }
        }
        // Vertical: fall until a block is underfoot.
        self.pos.y += self.vel.y * dt;
        self.on_ground = false;
        if self.vel.y <= 0.0 && at(self.pos.x, self.pos.y - 0.05, self.pos.z) {
            self.pos.y = self.pos.y.floor() + 1.0;
            self.vel.y = 0.0;
            self.on_ground = true;
        }
        // Hostiles hop toward the player over small ledges.
        if chasing && self.on_ground && speed > 0.0 && at(self.pos.x + fwd.x * 0.4, self.pos.y + 0.1, self.pos.z + fwd.z * 0.4) {
            self.vel.y = 8.0;
        }
        self.anim += speed * dt * 5.5;
    }

    fn append_mesh(&self, verts: &mut Vec<Vertex>, indices: &mut Vec<u32>) {
        let col = (self.kind.cell() % 4) as f32 * CELL;
        let row = (self.kind.cell() / 4) as f32 * CELL;
        let (yc, ys) = (self.yaw.cos(), self.yaw.sin());
        for part in self.kind.parts() {
            let swing = if part.leg != 0.0 { self.anim.sin() * 0.55 * part.leg } else { 0.0 };
            let xr = part.x_rot + swing;
            let (hx, hy, hz) = (part.size[0]*PX*0.5, part.size[1]*PX*0.5, part.size[2]*PX*0.5);
            let center = Vec3::new(part.pos[0], part.pos[1], part.pos[2]);
            // Legs swing about their top; static-rotated parts rotate about their centre.
            let pivot_y = if part.leg != 0.0 { center.y + hy } else { center.y };
            let (xc, xs) = (xr.cos(), xr.sin());
            // Skin-space box unwrap (pixels) for the 6 faces in order +X,-X,+Y,-Y,+Z,-Z.
            let (w, h, d) = (part.size[0], part.size[1], part.size[2]);
            let (u, v) = (part.uv[0], part.uv[1]);
            let rects = [
                [u, v+d, u+d, v+d+h],               // +X east
                [u+d+w, v+d, u+2.0*d+w, v+d+h],      // -X west
                [u+d, v, u+d+w, v+d],               // +Y up
                [u+d+w, v, u+d+2.0*w, v+d],          // -Y down
                [u+2.0*d+w, v+d, u+2.0*d+2.0*w, v+d+h], // +Z south (back)
                [u+d, v+d, u+d+w, v+d+h],           // -Z north (front)
            ];
            // Face corners (CCW) as (x,y,z) multipliers of half-extents.
            let faces: [[[f32;3];4];6] = [
                [[1.,-1.,-1.],[1.,1.,-1.],[1.,1.,1.],[1.,-1.,1.]],   // +X
                [[-1.,-1.,1.],[-1.,1.,1.],[-1.,1.,-1.],[-1.,-1.,-1.]], // -X
                [[-1.,1.,-1.],[-1.,1.,1.],[1.,1.,1.],[1.,1.,-1.]],   // +Y
                [[-1.,-1.,1.],[-1.,-1.,-1.],[1.,-1.,-1.],[1.,-1.,1.]], // -Y
                [[1.,-1.,1.],[1.,1.,1.],[-1.,1.,1.],[-1.,-1.,1.]],   // +Z
                [[-1.,-1.,-1.],[-1.,1.,-1.],[1.,1.,-1.],[1.,-1.,-1.]], // -Z
            ];
            let normals = [[1.,0.,0.],[-1.,0.,0.],[0.,1.,0.],[0.,-1.,0.],[0.,0.,1.],[0.,0.,-1.]];
            for f in 0..6 {
                let base = verts.len() as u32;
                let rect = rects[f];
                let uvc = [[rect[0],rect[3]],[rect[0],rect[1]],[rect[2],rect[1]],[rect[2],rect[3]]];
                // Transform normal (x-rot then yaw).
                let mut nrm = normals[f];
                { let (ny, nz) = (nrm[1]*xc - nrm[2]*xs, nrm[1]*xs + nrm[2]*xc); nrm[1]=ny; nrm[2]=nz;
                  let (nx, nz2) = (nrm[0]*yc - nrm[2]*ys, nrm[0]*ys + nrm[2]*yc); nrm[0]=nx; nrm[2]=nz2; }
                for c in 0..4 {
                    let m = faces[f][c];
                    let mut wp = Vec3::new(center.x + m[0]*hx, center.y + m[1]*hy, center.z + m[2]*hz);
                    // x-rotation about pivot.
                    let (dy, dz) = (wp.y - pivot_y, wp.z - center.z);
                    wp.y = pivot_y + dy*xc - dz*xs;
                    wp.z = center.z + dy*xs + dz*xc;
                    // yaw about Y through the mob origin.
                    let (rx, rz) = (wp.x*yc - wp.z*ys, wp.x*ys + wp.z*yc);
                    wp = Vec3::new(rx, wp.y, rz) + self.pos;
                    let uv = [(col + uvc[c][0]) / ATLAS, (row + uvc[c][1]) / ATLAS];
                    verts.push(Vertex { pos: [wp.x, wp.y, wp.z], uv, color: [1.0,1.0,1.0], ao: 1.0, tile_idx: 0.0, normal: nrm });
                }
                indices.extend_from_slice(&[base, base+1, base+2, base, base+2, base+3]);
            }
        }
    }
}

// Build one vertex/index buffer for all mobs.
pub fn build_entity_mesh(mobs: &[Mob]) -> (Vec<Vertex>, Vec<u32>) {
    let mut verts = Vec::new();
    let mut indices = Vec::new();
    for m in mobs { m.append_mesh(&mut verts, &mut indices); }
    (verts, indices)
}
