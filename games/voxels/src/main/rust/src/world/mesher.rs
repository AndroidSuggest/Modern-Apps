use super::block::{Block, GRASS_SIDE_OVERLAY};
use super::chunk::{Chunk, SECTION_SIZE, SECTIONS_PER_CHUNK};

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct Vertex {
    pub pos: [f32; 3],
    // uv carries per-block tile counts (0..w, 0..h); the shader wraps with fract into the tile so
    // greedy-merged faces tile the texture instead of stretching one tile across the whole quad.
    pub uv: [f32; 2],
    pub color: [f32; 3],
    pub ao: f32,
    pub tile_idx: f32,
    pub normal: [f32; 3],
}

pub struct MeshData {
    pub vertices: Vec<Vertex>,
    pub indices: Vec<u32>,
}

impl MeshData { pub fn is_empty(&self) -> bool { self.vertices.is_empty() } }

pub fn mesh_chunk(chunk: &Chunk, get_neighbor: &dyn Fn(i32,i32,i32)->u8, grass_tint: &dyn Fn(i32,i32)->[f32;3]) -> Vec<Option<MeshData>> {
    let mut result: Vec<Option<MeshData>> = (0..SECTIONS_PER_CHUNK).map(|_| None).collect();
    for sec_idx in 0..SECTIONS_PER_CHUNK {
        let section = match &chunk.sections[sec_idx] {
            Some(s) if !s.is_empty() => s,
            _ => continue,
        };
        let base_y = (sec_idx * SECTION_SIZE) as i32;
        let mut verts = Vec::with_capacity(1024);
        let mut indices = Vec::with_capacity(1536);
        let dirs = [(1,0,0,0), (-1,0,0,0), (0,1,0,1), (0,-1,0,1), (0,0,1,2), (0,0,-1,2)];
        for &(dx,dy,dz, axis) in &dirs {
            let nrm = [dx as f32, dy as f32, dz as f32];
            match axis {
                0 => {
                    for x in 0..SECTION_SIZE {
                        let mut mask = [[None::<(u8,u32)>; 16]; 16];
                        for y in 0..SECTION_SIZE { for z in 0..SECTION_SIZE {
                            let id = section.get(x,y,z);
                            if id == 0 { continue; }
                            let wx = chunk.pos.world_origin().0 + x as i32;
                            let wy = base_y + y as i32;
                            let wz = chunk.pos.world_origin().1 + z as i32;
                            let nid = get_neighbor(wx+dx, wy+dy, wz+dz);
                            let need = if nid != 0 { !Block::from_id(nid).is_opaque() } else { true };
                            if need {
                                let tile = Block::from_id(id).tile_for_dir(dx,dy,dz);
                                mask[y][z] = Some((id, tile));
                            }
                        }}
                        let mut visited = [[false; 16]; 16];
                        for y in 0..SECTION_SIZE { for z in 0..SECTION_SIZE {
                            if visited[y][z] { continue; }
                            let Some((bid, tile_idx)) = mask[y][z] else { continue; };
                            let mut w = 1;
                            while z + w < SECTION_SIZE && !visited[y][z+w] && mask[y][z+w] == Some((bid, tile_idx)) { w+=1; }
                            let mut h = 1;
                            'outer: while y + h < SECTION_SIZE {
                                for k in 0..w { if visited[y+h][z+k] || mask[y+h][z+k] != Some((bid, tile_idx)) { break 'outer; } }
                                h+=1;
                            }
                            for yy in y..y+h { for zz in z..z+w { visited[yy][zz]=true; } }
                            let x0 = x as f32 + if dx==1 { 1.0 } else { 0.0 };
                            let y0 = y as f32; let z0 = z as f32;
                            let ww = w as f32; let hh = h as f32;
                            let (u0,v0,u1,v1) = (0.0f32, 0.0f32, ww, hh); // u: z (ww), v: y (hh)
                            let color = Block::from_id(bid).color();
                            let quad = if dx==1 {
                                [([x0, y0, z0], [u0, v1]), ([x0, y0+hh, z0], [u0, v0]), ([x0, y0+hh, z0+ww], [u1, v0]), ([x0, y0, z0+ww], [u1, v1])]
                            } else {
                                [([x0, y0, z0+ww], [u0, v1]), ([x0, y0+hh, z0+ww], [u0, v0]), ([x0, y0+hh, z0], [u1, v0]), ([x0, y0, z0], [u1, v1])]
                            };
                            let wx0 = chunk.pos.world_origin().0 as f32;
                            let wz0 = chunk.pos.world_origin().1 as f32;
                            let by = base_y as f32;
                            let start = verts.len() as u32;
                            for (pos, uv) in quad.iter() {
                                verts.push(Vertex{ pos: [wx0 + pos[0], by + pos[1], wz0 + pos[2]], uv: *uv, color, ao: 1.0, tile_idx: tile_idx as f32, normal: nrm });
                            }
                            indices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
                            // Grass side: tinted grass_block_side_overlay just outside the dirt face.
                            if bid == Block::Grass as u8 {
                                let oc = grass_tint(chunk.pos.world_origin().0 + x as i32, chunk.pos.world_origin().1 + z as i32);
                                let ex = 0.02 * dx as f32;
                                let oquad = if dx==1 {
                                    [([x0, y0, z0], [u0, v1]), ([x0, y0+hh, z0], [u0, v0]), ([x0, y0+hh, z0+ww], [u1, v0]), ([x0, y0, z0+ww], [u1, v1])]
                                } else {
                                    [([x0, y0, z0+ww], [u0, v1]), ([x0, y0+hh, z0+ww], [u0, v0]), ([x0, y0+hh, z0], [u1, v0]), ([x0, y0, z0], [u1, v1])]
                                };
                                let ostart = verts.len() as u32;
                                for (pos, uv) in oquad.iter() {
                                    verts.push(Vertex{ pos: [wx0 + pos[0] + ex, by + pos[1], wz0 + pos[2]], uv: *uv, color: oc, ao: 1.0, tile_idx: GRASS_SIDE_OVERLAY as f32, normal: nrm });
                                }
                                indices.extend_from_slice(&[ostart, ostart+1, ostart+2, ostart, ostart+2, ostart+3]);
                            }
                        }}
                    }
                }
                1 => {
                    for y in 0..SECTION_SIZE {
                        let mut mask = [[None::<(u8,u32)>; 16]; 16];
                        for x in 0..SECTION_SIZE { for z in 0..SECTION_SIZE {
                            let id = section.get(x,y,z);
                            if id==0 { continue; }
                            let wx = chunk.pos.world_origin().0 + x as i32;
                            let wy = base_y + y as i32;
                            let wz = chunk.pos.world_origin().1 + z as i32;
                            let nid = get_neighbor(wx+dx, wy+dy, wz+dz);
                            if nid==0 || !Block::from_id(nid).is_opaque() {
                                if !(nid==id && Block::from_id(nid).is_opaque()) {
                                    let tile = Block::from_id(id).tile_for_dir(dx,dy,dz);
                                    mask[x][z]=Some((id, tile));
                                }
                            }
                        }}
                        let mut visited = [[false; 16]; 16];
                        for x in 0..SECTION_SIZE { for z in 0..SECTION_SIZE {
                            if visited[x][z] { continue; }
                            let Some((bid, tile_idx)) = mask[x][z] else { continue; };
                            let mut w = 1;
                            while z+w < SECTION_SIZE && !visited[x][z+w] && mask[x][z+w]==Some((bid, tile_idx)) { w+=1; }
                            let mut h = 1;
                            'outer2: while x+h < SECTION_SIZE {
                                for k in 0..w { if visited[x+h][z+k] || mask[x+h][z+k]!=Some((bid, tile_idx)) { break 'outer2; } }
                                h+=1;
                            }
                            for xx in x..x+h { for zz in z..z+w { visited[xx][zz]=true; } }
                            let y0 = y as f32 + if dy==1 { 1.0 } else { 0.0 };
                            let x0 = x as f32; let z0 = z as f32;
                            let ww = w as f32; let hh = h as f32;
                            let (u0,v0,u1,v1) = (0.0f32, 0.0f32, hh, ww); // u: x (hh), v: z (ww)
                            // Grass tops use the grayscale grass_top tile tinted per biome; other faces
                            // keep the block's base colour (dirt bottom stays brown).
                            let color = if bid == Block::Grass as u8 && dy == 1 {
                                grass_tint(chunk.pos.world_origin().0 + x as i32, chunk.pos.world_origin().1 + z as i32)
                            } else { Block::from_id(bid).color() };
                            let wx0 = chunk.pos.world_origin().0 as f32;
                            let wz0 = chunk.pos.world_origin().1 as f32;
                            let by = base_y as f32;
                            let quad = if dy==1 {
                                [([x0, y0, z0], [u0, v0]), ([x0+hh, y0, z0], [u1, v0]), ([x0+hh, y0, z0+ww], [u1, v1]), ([x0, y0, z0+ww], [u0, v1])]
                            } else {
                                [([x0, y0, z0+ww], [u0, v0]), ([x0+hh, y0, z0+ww], [u1, v0]), ([x0+hh, y0, z0], [u1, v1]), ([x0, y0, z0], [u0, v1])]
                            };
                            let start = verts.len() as u32;
                            for (lp, uv) in quad.iter() {
                                verts.push(Vertex{ pos: [wx0 + lp[0], by + lp[1], wz0 + lp[2]], uv: *uv, color, ao: if dy==1 { 1.0 } else { 0.75 }, tile_idx: tile_idx as f32, normal: nrm });
                            }
                            indices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
                        }}
                    }
                }
                2 => {
                    for z in 0..SECTION_SIZE {
                        let mut mask = [[None::<(u8,u32)>; 16]; 16];
                        for x in 0..SECTION_SIZE { for y in 0..SECTION_SIZE {
                            let id = section.get(x,y,z);
                            if id==0 { continue; }
                            let wx = chunk.pos.world_origin().0 + x as i32;
                            let wy = base_y + y as i32;
                            let wz = chunk.pos.world_origin().1 + z as i32;
                            let nid = get_neighbor(wx+dx, wy+dy, wz+dz);
                            if nid==0 || !Block::from_id(nid).is_opaque() {
                                let tile = Block::from_id(id).tile_for_dir(dx,dy,dz);
                                mask[x][y]=Some((id, tile));
                            }
                        }}
                        let mut visited = [[false; 16]; 16];
                        for x in 0..SECTION_SIZE { for y in 0..SECTION_SIZE {
                            if visited[x][y] { continue; }
                            let Some((bid, tile_idx)) = mask[x][y] else { continue; };
                            let mut w = 1;
                            while y+w < SECTION_SIZE && !visited[x][y+w] && mask[x][y+w]==Some((bid, tile_idx)) { w+=1; }
                            let mut h = 1;
                            'outer3: while x+h < SECTION_SIZE {
                                for k in 0..w { if visited[x+h][y+k] || mask[x+h][y+k]!=Some((bid, tile_idx)) { break 'outer3; } }
                                h+=1;
                            }
                            for xx in x..x+h { for yy in y..y+w { visited[xx][yy]=true; } }
                            let z0 = z as f32 + if dz==1 { 1.0 } else { 0.0 };
                            let x0 = x as f32; let y0 = y as f32;
                            let ww = w as f32; let hh = h as f32;
                            let (u0,v0,u1,v1) = (0.0f32, 0.0f32, hh, ww); // u: x (hh), v: y (ww)
                            let color = Block::from_id(bid).color();
                            let wx0 = chunk.pos.world_origin().0 as f32;
                            let wz0 = chunk.pos.world_origin().1 as f32;
                            let by = base_y as f32;
                            let quad = if dz==1 {
                                [([x0, y0, z0], [u0, v1]), ([x0+hh, y0, z0], [u1, v1]), ([x0+hh, y0+ww, z0], [u1, v0]), ([x0, y0+ww, z0], [u0, v0])]
                            } else {
                                [([x0+hh, y0, z0], [u0, v1]), ([x0, y0, z0], [u1, v1]), ([x0, y0+ww, z0], [u1, v0]), ([x0+hh, y0+ww, z0], [u0, v0])]
                            };
                            let start = verts.len() as u32;
                            for (lp, uv) in quad.iter() {
                                verts.push(Vertex{ pos: [wx0 + lp[0], by + lp[1], wz0 + lp[2]], uv: *uv, color, ao: 0.92, tile_idx: tile_idx as f32, normal: nrm });
                            }
                            indices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
                            // Grass side: tinted grass_block_side_overlay just outside the dirt face.
                            if bid == Block::Grass as u8 {
                                let oc = grass_tint(chunk.pos.world_origin().0 + x as i32, chunk.pos.world_origin().1 + z as i32);
                                let ez = 0.02 * dz as f32;
                                let oquad = if dz==1 {
                                    [([x0, y0, z0], [u0, v1]), ([x0+hh, y0, z0], [u1, v1]), ([x0+hh, y0+ww, z0], [u1, v0]), ([x0, y0+ww, z0], [u0, v0])]
                                } else {
                                    [([x0+hh, y0, z0], [u0, v1]), ([x0, y0, z0], [u1, v1]), ([x0, y0+ww, z0], [u1, v0]), ([x0+hh, y0+ww, z0], [u0, v0])]
                                };
                                let ostart = verts.len() as u32;
                                for (lp, uv) in oquad.iter() {
                                    verts.push(Vertex{ pos: [wx0 + lp[0], by + lp[1], wz0 + lp[2] + ez], uv: *uv, color: oc, ao: 0.92, tile_idx: GRASS_SIDE_OVERLAY as f32, normal: nrm });
                                }
                                indices.extend_from_slice(&[ostart, ostart+1, ostart+2, ostart, ostart+2, ostart+3]);
                            }
                        }}
                    }
                }
                _ => {}
            }
        }
        if !verts.is_empty() { result[sec_idx] = Some(MeshData{ vertices: verts, indices }); }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::world::chunk::{Chunk, ChunkPos};
    #[test]
    fn empty_chunk_no_mesh() {
        let c = Chunk::new(ChunkPos(0,0));
        let m = mesh_chunk(&c, &|_,_,_| 0, &|_,_| [0.4,0.7,0.3]);
        assert!(m.iter().all(|o| o.is_none()));
    }
    #[test]
    fn single_block_has_faces() {
        let mut c = Chunk::new(ChunkPos(0,0));
        c.set_block(1, 10, 1, 1);
        let m = mesh_chunk(&c, &|x,y,z| if x==1 && y==10 && z==1 { 1 } else { 0 }, &|_,_| [0.4,0.7,0.3]);
        let total: usize = m.iter().filter_map(|o| o.as_ref()).map(|md| md.vertices.len()).sum();
        assert!(total >= 24);
    }
}
