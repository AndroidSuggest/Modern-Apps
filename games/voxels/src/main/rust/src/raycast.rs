use glam::Vec3;
use crate::world::{ChunkMap, block::Block};

#[derive(Debug, Clone, Copy)]
pub struct HitResult { pub pos: (i32,i32,i32), pub prev: (i32,i32,i32), pub normal: (i32,i32,i32), pub dist: f32 }

pub fn raycast(chunks: &ChunkMap, origin: Vec3, dir: Vec3, max_dist: f32) -> Option<HitResult> {
    let dir = dir.normalize_or_zero();
    if dir.length_squared() < 1e-6 { return None; }
    let mut x = origin.x.floor() as i32;
    let mut y = origin.y.floor() as i32;
    let mut z = origin.z.floor() as i32;
    let step_x = if dir.x > 0.0 { 1 } else if dir.x < 0.0 { -1 } else { 0 };
    let step_y = if dir.y > 0.0 { 1 } else if dir.y < 0.0 { -1 } else { 0 };
    let step_z = if dir.z > 0.0 { 1 } else if dir.z < 0.0 { -1 } else { 0 };
    let t_delta_x = if dir.x != 0.0 { 1.0 / dir.x.abs() } else { f32::INFINITY };
    let t_delta_y = if dir.y != 0.0 { 1.0 / dir.y.abs() } else { f32::INFINITY };
    let t_delta_z = if dir.z != 0.0 { 1.0 / dir.z.abs() } else { f32::INFINITY };
    let mut t_max_x = if step_x != 0 {
        let border = if step_x > 0 { x as f32 + 1.0 } else { x as f32 };
        (border - origin.x) / dir.x
    } else { f32::INFINITY };
    let mut t_max_y = if step_y != 0 {
        let border = if step_y > 0 { y as f32 + 1.0 } else { y as f32 };
        (border - origin.y) / dir.y
    } else { f32::INFINITY };
    let mut t_max_z = if step_z != 0 {
        let border = if step_z > 0 { z as f32 + 1.0 } else { z as f32 };
        (border - origin.z) / dir.z
    } else { f32::INFINITY };
    let mut prev = (x,y,z);
    let mut dist = 0.0;
    let id_start = chunks.get_block_world(x,y,z);
    if id_start != 0 && Block::from_id(id_start).is_solid() {
        return Some(HitResult{ pos: (x,y,z), prev: (x - step_x, y - step_y, z - step_z), normal: (-step_x, -step_y, -step_z), dist: 0.0 });
    }
    loop {
        if dist > max_dist { return None; }
        if t_max_x < t_max_y && t_max_x < t_max_z {
            prev = (x,y,z); x += step_x; dist = t_max_x; t_max_x += t_delta_x;
            let nid = chunks.get_block_world(x,y,z);
            if nid !=0 && Block::from_id(nid).is_solid() { return Some(HitResult{ pos: (x,y,z), prev, normal: (-step_x,0,0), dist }); }
        } else if t_max_y < t_max_z {
            prev = (x,y,z); y += step_y; dist = t_max_y; t_max_y += t_delta_y;
            let nid = chunks.get_block_world(x,y,z);
            if nid !=0 && Block::from_id(nid).is_solid() { return Some(HitResult{ pos: (x,y,z), prev, normal: (0,-step_y,0), dist }); }
        } else {
            prev = (x,y,z); z += step_z; dist = t_max_z; t_max_z += t_delta_z;
            let nid = chunks.get_block_world(x,y,z);
            if nid !=0 && Block::from_id(nid).is_solid() { return Some(HitResult{ pos: (x,y,z), prev, normal: (0,0,-step_z), dist }); }
        }
        if x.abs() > 10000 || y.abs() > 10000 || z.abs() > 10000 { return None; }
    }
}
