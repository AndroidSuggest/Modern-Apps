// Append humanoid avatars for remote players (multiplayer). Each is drawn with the biped model — we
// reuse the mob mesh path rather than a bespoke skeleton, so remote players read as villager-shaped
// figures at the reported position/facing. `poses` is (position, yaw).
pub fn append_remote_players(verts: &mut Vec<Vertex>, indices: &mut Vec<u32>, poses: &[(Vec3, f32)]) {
    for &(pos, yaw) in poses {
        let mut m = Mob::new(MobKind::Villager, pos, 1);
        m.yaw = yaw;
        m.append_mesh(verts, indices);
    }
}

// ---- Projectiles: flying attacks (blaze fireballs, shulker bullets) and firework rockets. ----
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ProjKind { Fireball, ShulkerBullet, Firework, Snowball, EnderPearl }

pub struct Projectile {
    pub pos: Vec3,
    pub vel: Vec3,
    pub life: f32,
    pub kind: ProjKind,
    pub from_player: bool, // fired by the player (doesn't hurt the player)
    pub damage: f32,
    pub explosive: bool,   // fireball detonates terrain on impact (ghast) vs. just bursting (blaze)
}

impl Projectile {
    pub fn color(&self) -> [f32; 3] {
        match self.kind {
            ProjKind::Fireball => [1.0, 0.55, 0.12],
            ProjKind::ShulkerBullet => [0.75, 0.55, 0.85],
            ProjKind::Firework => [1.0, 0.9, 0.5],
            ProjKind::Snowball => [0.95, 0.98, 1.0],
            ProjKind::EnderPearl => [0.25, 0.85, 0.7],
        }
    }
    pub fn size(&self) -> f32 { match self.kind { ProjKind::Fireball => 0.28, ProjKind::ShulkerBullet => 0.18, ProjKind::Firework => 0.16, ProjKind::Snowball => 0.14, ProjKind::EnderPearl => 0.16 } }
    // Snowballs/ender pearls fall under gravity; the rest fly straight.
    pub fn gravity(&self) -> f32 { match self.kind { ProjKind::Snowball | ProjKind::EnderPearl => 14.0, _ => 0.0 } }
}

// Render projectiles as bright, un-fading billboard quads (reuses the particle white-swatch UV).
pub fn append_projectiles(verts: &mut Vec<Vertex>, indices: &mut Vec<u32>, ps: &[Projectile], right: Vec3, up: Vec3) {
    for p in ps {
        let s = p.size();
        let c = p.color();
        let (r, u) = (right * s, up * s);
        let corners = [p.pos - r - u, p.pos + r - u, p.pos + r + u, p.pos - r + u];
        let base = verts.len() as u32;
        for cc in corners {
            verts.push(Vertex { pos: [cc.x, cc.y, cc.z], uv: [PARTICLE_UV, PARTICLE_UV], color: c, ao: 1.0, tile_idx: 0.0, normal: [0.0, 1.0, 0.0], light: 0.0 });
        }
        indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    }
}

// ---- Particles: short-lived billboarded quads (block break, hits, explosions). ----
pub struct Particle {
    pub pos: Vec3,
    pub vel: Vec3,
    pub life: f32,
    pub max_life: f32,
    pub size: f32,
    pub color: [f32; 3],
    /// Downward acceleration. Debris uses `BURST_GRAVITY`; snow drifts at almost nothing.
    pub gravity: f32,
}

pub const BURST_GRAVITY: f32 = 14.0;

pub fn tick_particles(ps: &mut Vec<Particle>, dt: f32) {
    for p in ps.iter_mut() {
        p.vel.y -= p.gravity * dt;
        p.pos += p.vel * dt;
        p.life -= dt;
    }
    ps.retain(|p| p.life > 0.0);
}

// UV of the white swatch baked into the entity atlas (cell 15); particles tint it via vertex color.
const PARTICLE_UV: f32 = 0.766;
pub fn append_particles(verts: &mut Vec<Vertex>, indices: &mut Vec<u32>, ps: &[Particle], right: Vec3, up: Vec3) {
    for p in ps {
        let f = (p.life / p.max_life).clamp(0.0, 1.0);
        let s = p.size * (0.35 + 0.65 * f);
        let c = [p.color[0] * (0.4 + 0.6 * f), p.color[1] * (0.4 + 0.6 * f), p.color[2] * (0.4 + 0.6 * f)];
        let (r, u) = (right * s, up * s);
        let corners = [p.pos - r - u, p.pos + r - u, p.pos + r + u, p.pos - r + u];
        let base = verts.len() as u32;
        for cc in corners {
            verts.push(Vertex { pos: [cc.x, cc.y, cc.z], uv: [PARTICLE_UV, PARTICLE_UV], color: c, ao: 1.0, tile_idx: 0.0, normal: [0.0, 1.0, 0.0], light: 0.0 });
        }
        indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::world::block::Block;
    use crate::world::ChunkMap;

    // Terrain never reaches this high, so the test platform sits in clear air.
    const SKY: i32 = 200;

    fn world() -> ChunkMap {
        let dir = std::env::temp_dir().join("voxels_mob_test").to_string_lossy().into_owned();
        ChunkMap::new(3, dir)
    }

    /// A wide platform so a wandering mob can't stroll off it during the test.
    fn platform(w: &mut ChunkMap, id: Id, meta: u8) {
        for x in -6..=6 { for z in -6..=6 { w.set_block_meta_world(x, SKY, z, id, meta); } }
    }

    fn settle(w: &ChunkMap, kind: MobKind, from_y: f32) -> Mob {
        let terrain = Terrain {
            solid: &|x, y, z| w.solid_at(x, y, z),
            surface: &|x, z, ceiling| w.surface_below(x, z, ceiling, 2),
        };
        let mut m = Mob::new(kind, Vec3::new(0.5, from_y, 0.5), 7);
        // A player far away, so passive mobs just wander and hostiles don't charge off the platform.
        let player = Vec3::new(0.5, from_y, 400.0);
        for _ in 0..180 { m.tick(1.0 / 60.0, player, &terrain); }
        m
    }

    // A mob standing on a slab must rest on its surface, not float at the cell boundary.
    #[test]
    fn a_mob_rests_on_a_slabs_surface() {
        let mut w = world();
        platform(&mut w, Block::StoneSlab as Id, 0);
        let m = settle(&w, MobKind::Pig, SKY as f32 + 3.0);
        assert!(m.on_ground, "the pig should have landed");
        assert!((m.pos.y - (SKY as f32 + 0.5)).abs() < 1e-3,
            "expected to stand on the slab top at {}, got {}", SKY as f32 + 0.5, m.pos.y);
    }

    // The same fall onto full blocks rests a whole block up, so the slab case really follows shape.
    #[test]
    fn a_mob_rests_a_whole_block_up_on_cubes() {
        let mut w = world();
        platform(&mut w, Block::Stone as Id, 0);
        let m = settle(&w, MobKind::Pig, SKY as f32 + 3.0);
        assert!((m.pos.y - (SKY as f32 + 1.0)).abs() < 1e-3, "got {}", m.pos.y);
    }

    // A top slab's solid half is its upper half, so a mob stands at the cell ceiling.
    #[test]
    fn a_mob_stands_on_top_of_a_top_slab() {
        let mut w = world();
        platform(&mut w, Block::StoneSlab as Id, crate::world::block::META_TOP);
        let m = settle(&w, MobKind::Pig, SKY as f32 + 3.0);
        assert!((m.pos.y - (SKY as f32 + 1.0)).abs() < 1e-3, "got {}", m.pos.y);
    }

    // Mobs step up half a block onto a slab rather than the full block they used to.
    #[test]
    fn a_mob_steps_up_onto_a_slab() {
        let mut w = world();
        // A long floor so the chase never runs off the end, with a slab ledge across the path.
        for x in -3..=3 { for z in -20..=3 { w.set_block_world(x, SKY, z, Block::Stone as Id); } }
        for x in -3..=3 { w.set_block_meta_world(x, SKY + 1, -3, Block::StoneSlab as Id, 0); }

        let terrain = Terrain {
            solid: &|x, y, z| w.solid_at(x, y, z),
            surface: &|x, z, ceiling| w.surface_below(x, z, ceiling, 2),
        };
        let mut m = Mob::new(MobKind::Zombie, Vec3::new(0.5, SKY as f32 + 1.0, 0.5), 11);
        // A hostile chases the player, who stands beyond the ledge.
        let player = Vec3::new(0.5, SKY as f32 + 1.5, -8.0);
        for _ in 0..240 { m.tick(1.0 / 60.0, player, &terrain); }

        assert!(m.pos.z < -2.0, "the zombie should have reached the ledge, z = {}", m.pos.z);
        assert!(m.pos.y >= SKY as f32 + 1.0, "and never sunk below the floor, y = {}", m.pos.y);
        assert!(m.pos.y <= SKY as f32 + 2.0, "nor been launched onto a phantom full block, y = {}", m.pos.y);
    }

    // Matcha retunes hostiles per type rather than leaving them all on vanilla's flat 20 HP. This is a
    // sanity net, not a spec: it catches a zero, a negative, and a stat typo an order of magnitude out.
    #[test]
    fn every_mob_has_plausible_stats() {
        for &k in MobKind::ALL.iter() {
            let (hp, sp) = (k.max_health(), k.speed());
            assert!(hp > 0.0, "{k:?} spawns dead");
            assert!(sp >= 0.0, "{k:?} walks backwards");
            assert!(k.height() > 0.0 && k.hit_radius() > 0.0, "{k:?} can't be hit");
            if k.is_boss() {
                assert!(hp >= 100.0, "{k:?} is a boss with only {hp} HP");
            } else {
                assert!(hp <= 40.0, "{k:?} has boss-tier health at {hp}");
                assert!(sp <= 3.0, "{k:?} moves at {sp}, faster than the player can react");
            }
            // Creepers do all their damage by detonating, and Ghasts by fireball, so neither needs a
            // contact hit. Everything else hostile has to hurt you for walking into it.
            if k.hostile() && k != MobKind::Creeper && !k.flies() {
                assert!(k.contact_damage() > 0.0, "{k:?} is hostile but harmless");
            }
            if !k.hostile() { assert_eq!(k.contact_damage(), 0.0, "{k:?} is passive but hurts on touch"); }
        }
        // Zombies are the pressure mob and must out-pace the wandering livestock.
        assert!(MobKind::Zombie.speed() > MobKind::Cow.speed());
    }

    // Variation has to be real but bounded, and deterministic for a given spawn seed.
    #[test]
    fn spawn_variation_stays_inside_its_bounds() {
        for &k in MobKind::ALL.iter() {
            let base = k.max_health();
            let mut elites = 0;
            let mut distinct = std::collections::HashSet::new();
            let mut s = 0xDEAD_BEEFu32;
            let rolls = 3000;
            for _ in 0..rolls {
                s ^= s << 13; s ^= s >> 17; s ^= s << 5;
                let v = roll_variation(k, s);
                assert_eq!(v.health, roll_variation(k, s).health, "{k:?} rolls differently for the same seed");
                let cap = base * (1.0 + HEALTH_JITTER) * if v.elite { ELITE_HEALTH } else { 1.0 };
                let floor = base * (1.0 - HEALTH_JITTER);
                assert!(v.health >= floor - 1e-3 && v.health <= cap + 1e-3, "{k:?} rolled {} HP against a {base} base", v.health);
                assert!(v.speed >= 0.0);
                if v.elite { elites += 1; }
                distinct.insert(v.health.to_bits());
            }
            if k.is_boss() {
                assert_eq!(elites, 0, "{k:?} is a boss and must never roll elite");
                assert_eq!(distinct.len(), 1, "{k:?} is a set-piece fight and must not vary");
            } else {
                assert!(distinct.len() > rolls / 2, "{k:?} barely varies: {} distinct rolls", distinct.len());
                if k.hostile() {
                    let rate = elites as f32 / rolls as f32;
                    assert!((rate - ELITE_CHANCE).abs() < 0.03, "{k:?} elite rate {rate} is off target");
                } else {
                    assert_eq!(elites, 0, "{k:?} is passive; an elite cow is just confusing");
                }
            }
        }
    }

    // Anubis has to actually push the undead away, not merely stop them closing in.
    #[test]
    fn a_warded_mob_backs_off() {
        let mut w = world();
        for x in -6..=6 { for z in -6..=20 { w.set_block_world(x, SKY, z, Block::Stone as Id); } }
        let terrain = Terrain {
            solid: &|x, y, z| w.solid_at(x, y, z),
            surface: &|x, z, ceiling| w.surface_below(x, z, ceiling, 2),
        };
        let player = Vec3::new(0.5, SKY as f32 + 1.5, 0.5);
        let start = Vec3::new(0.5, SKY as f32 + 1.0, 5.5);

        let mut chaser = Mob::new(MobKind::Zombie, start, 21);
        for _ in 0..180 { chaser.tick(1.0 / 60.0, player, &terrain); }
        let chased = (chaser.pos - player).length();

        let mut warded = Mob::new(MobKind::Zombie, start, 21);
        warded.repelled = 6.0;
        for _ in 0..180 { warded.tick(1.0 / 60.0, player, &terrain); }
        let kept = (warded.pos - player).length();

        assert!(chased < 4.0, "an unwarded zombie should have closed in, dist = {chased}");
        assert!(kept > 5.0, "a warded zombie should have backed off, dist = {kept}");
    }

    #[test]
    fn an_elite_is_visibly_different() {
        let mut elite = Mob::new(MobKind::Zombie, Vec3::ZERO, 7);
        elite.elite = true;
        let plain = Mob::new(MobKind::Zombie, Vec3::ZERO, 7);
        assert_ne!(elite.tint(), plain.tint(), "an elite has to be readable before it reaches you");
        // A villager's profession colour wins over the elite tint; villagers are never hostile.
        let v = Mob::new(MobKind::Villager, Vec3::ZERO, 7);
        assert_eq!(v.tint(), crate::villager::Profession::from_index(v.profession as usize).tint());
    }
}
