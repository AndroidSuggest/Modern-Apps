use super::block::{Block, GRASS_SIDE_TILE};
use super::chunk::{Chunk, SECTION_SIZE, SECTIONS_PER_CHUNK, CHUNK_HEIGHT};

// Per-chunk lighting: skylight column tops (highest opaque block per column) and a block-light grid
// (BFS flood from emitters, decreasing by 1 per open cell). Cross-chunk bleed is limited to this
// chunk's own data, which is plenty for caves + torch pools.
fn compute_light(chunk: &Chunk) -> ([[i32; 16]; 16], Vec<u8>) {
    let mut col_top = [[-1i32; 16]; 16];
    for x in 0..16 { for z in 0..16 {
        for y in (0..CHUNK_HEIGHT).rev() {
            let id = chunk.get_block(x, y, z);
            if id != 0 && Block::from_id(id).is_opaque() { col_top[x][z] = y as i32; break; }
        }
    }}
    let mut blk = vec![0u8; 16 * CHUNK_HEIGHT * 16];
    let idx = |x: usize, y: usize, z: usize| (y * 16 + z) * 16 + x;
    let mut q: std::collections::VecDeque<(usize, usize, usize, u8)> = std::collections::VecDeque::new();
    for y in 0..CHUNK_HEIGHT { for z in 0..16 { for x in 0..16 {
        let e = Block::from_id(chunk.get_block(x, y, z)).light_emission();
        if e > 0 { let i = idx(x, y, z); if blk[i] < e { blk[i] = e; q.push_back((x, y, z, e)); } }
    }}}
    while let Some((x, y, z, l)) = q.pop_front() {
        if l <= 1 { continue; }
        let nl = l - 1;
        let nbrs = [(x as i32 + 1, y as i32, z as i32), (x as i32 - 1, y as i32, z as i32),
                    (x as i32, y as i32 + 1, z as i32), (x as i32, y as i32 - 1, z as i32),
                    (x as i32, y as i32, z as i32 + 1), (x as i32, y as i32, z as i32 - 1)];
        for (nx, ny, nz) in nbrs {
            if nx < 0 || nx >= 16 || nz < 0 || nz >= 16 || ny < 0 || ny >= CHUNK_HEIGHT as i32 { continue; }
            let (nx, ny, nz) = (nx as usize, ny as usize, nz as usize);
            let bid = chunk.get_block(nx, ny, nz);
            if bid != 0 && Block::from_id(bid).is_opaque() { continue; }
            let i = idx(nx, ny, nz);
            if blk[i] < nl { blk[i] = nl; q.push_back((nx, ny, nz, nl)); }
        }
    }
    (col_top, blk)
}
// Pack skylight (low nibble) + blocklight (high nibble) into a u8; decode to the shader's f32.
fn light_to_f32(packed: u8) -> f32 { (packed & 0x0F) as f32 + ((packed >> 4) & 0x0F) as f32 * 16.0 }

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
    // Packed lighting: skylight + blocklight*16 (each 0..15). Read by block.frag; 0 elsewhere.
    pub light: f32,
}

pub struct MeshData {
    pub vertices: Vec<Vertex>,
    pub indices: Vec<u32>,
    // Water surface, rendered separately as a transparent, wavy pass.
    pub water_vertices: Vec<Vertex>,
    pub water_indices: Vec<u32>,
}

impl MeshData { pub fn is_empty(&self) -> bool { self.vertices.is_empty() && self.water_vertices.is_empty() } }

// Classic voxel corner AO: 0 (darkest) .. 3 (fully open) from the two edge neighbors + the diagonal.
fn vao(s1: bool, s2: bool, c: bool) -> u8 { if s1 && s2 { 0 } else { 3 - (s1 as u8 + s2 as u8 + c as u8) } }
fn aof(level: u8) -> f32 { level as f32 / 3.0 }

pub fn mesh_chunk(chunk: &Chunk, get_neighbor: &dyn Fn(i32,i32,i32)->u8, grass_tint: &dyn Fn(i32,i32)->[f32;3]) -> Vec<Option<MeshData>> {
    let occ = |ax: i32, ay: i32, az: i32| -> bool { let id = get_neighbor(ax, ay, az); id != 0 && Block::from_id(id).is_opaque() };
    let mut result: Vec<Option<MeshData>> = (0..SECTIONS_PER_CHUNK).map(|_| None).collect();
    let ox = chunk.pos.world_origin().0;
    let oz = chunk.pos.world_origin().1;
    // Lighting for the whole chunk column, sampled per exposed face (at the neighbouring air cell).
    let (col_top, blk) = compute_light(chunk);
    let face_light = |wx: i32, wy: i32, wz: i32| -> u8 {
        let lx = (wx - ox).clamp(0, 15) as usize;
        let lz = (wz - oz).clamp(0, 15) as usize;
        let top = col_top[lx][lz];
        let sky = if wy > top { 15 } else { (15 - (top - wy) * 3).max(0) } as u8;
        let by = wy.clamp(0, CHUNK_HEIGHT as i32 - 1) as usize;
        let b = blk[(by * 16 + lz) * 16 + lx];
        sky | (b << 4)
    };
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
            // Mask entry: (block id, tile, per-corner AO in this direction's vertex order).
            match axis {
                0 => {
                    for x in 0..SECTION_SIZE {
                        let mut mask = [[None::<(u8,u32,[u8;4],u8)>; 16]; 16];
                        for y in 0..SECTION_SIZE { for z in 0..SECTION_SIZE {
                            let id = section.get(x,y,z);
                            if id == 0 || id == Block::Water as u8 { continue; }
                            let wx = ox + x as i32; let wy = base_y + y as i32; let wz = oz + z as i32;
                            let nid = get_neighbor(wx+dx, wy+dy, wz+dz);
                            let need = if nid != 0 { !Block::from_id(nid).is_opaque() } else { true };
                            if need {
                                let tile = Block::from_id(id).tile_for_dir(dx,dy,dz);
                                let nb = wx + dx;
                                let ao = |ys: i32, zs: i32| vao(occ(nb, wy+ys, wz), occ(nb, wy, wz+zs), occ(nb, wy+ys, wz+zs));
                                let ao4 = if dx==1 { [ao(-1,-1), ao(1,-1), ao(1,1), ao(-1,1)] }
                                          else       { [ao(-1,1),  ao(1,1),  ao(1,-1), ao(-1,-1)] };
                                mask[y][z] = Some((id, tile, ao4, face_light(wx+dx, wy+dy, wz+dz)));
                            }
                        }}
                        let mut visited = [[false; 16]; 16];
                        for y in 0..SECTION_SIZE { for z in 0..SECTION_SIZE {
                            if visited[y][z] { continue; }
                            let Some((bid, tile_idx, ao4, lpk)) = mask[y][z] else { continue; };
                            let mut w = 1;
                            while z + w < SECTION_SIZE && !visited[y][z+w] && mask[y][z+w] == Some((bid, tile_idx, ao4, lpk)) { w+=1; }
                            let mut h = 1;
                            'outer: while y + h < SECTION_SIZE {
                                for k in 0..w { if visited[y+h][z+k] || mask[y+h][z+k] != Some((bid, tile_idx, ao4, lpk)) { break 'outer; } }
                                h+=1;
                            }
                            for yy in y..y+h { for zz in z..z+w { visited[yy][zz]=true; } }
                            let x0 = x as f32 + if dx==1 { 1.0 } else { 0.0 };
                            let y0 = y as f32; let z0 = z as f32; let ww = w as f32; let hh = h as f32;
                            let (u0,v0,u1,v1) = (0.0f32, 0.0f32, ww, hh);
                            // Grass side: sentinel tile so the shader composites dirt + tinted overlay in one quad.
                            let is_grass = bid == Block::Grass as u8;
                            let color = if is_grass { grass_tint(ox + x as i32, oz + z as i32) } else { Block::from_id(bid).color() };
                            let etile = if is_grass { GRASS_SIDE_TILE as f32 } else { tile_idx as f32 };
                            let quad = if dx==1 {
                                [([x0, y0, z0], [u0, v1]), ([x0, y0+hh, z0], [u0, v0]), ([x0, y0+hh, z0+ww], [u1, v0]), ([x0, y0, z0+ww], [u1, v1])]
                            } else {
                                [([x0, y0, z0+ww], [u0, v1]), ([x0, y0+hh, z0+ww], [u0, v0]), ([x0, y0+hh, z0], [u1, v0]), ([x0, y0, z0], [u1, v1])]
                            };
                            let wx0 = ox as f32; let wz0 = oz as f32; let by = base_y as f32;
                            let start = verts.len() as u32;
                            for (i,(pos, uv)) in quad.iter().enumerate() {
                                verts.push(Vertex{ pos: [wx0 + pos[0], by + pos[1], wz0 + pos[2]], uv: *uv, color, ao: aof(ao4[i]), tile_idx: etile, normal: nrm, light: light_to_f32(lpk) });
                            }
                            indices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
                        }}
                    }
                }
                1 => {
                    for y in 0..SECTION_SIZE {
                        let mut mask = [[None::<(u8,u32,[u8;4],u8)>; 16]; 16];
                        for x in 0..SECTION_SIZE { for z in 0..SECTION_SIZE {
                            let id = section.get(x,y,z);
                            if id==0 || id==Block::Water as u8 { continue; }
                            let wx = ox + x as i32; let wy = base_y + y as i32; let wz = oz + z as i32;
                            let nid = get_neighbor(wx+dx, wy+dy, wz+dz);
                            if nid==0 || !Block::from_id(nid).is_opaque() {
                                if !(nid==id && Block::from_id(nid).is_opaque()) {
                                    let tile = Block::from_id(id).tile_for_dir(dx,dy,dz);
                                    let nb = wy + dy;
                                    let ao = |xs: i32, zs: i32| vao(occ(wx+xs, nb, wz), occ(wx, nb, wz+zs), occ(wx+xs, nb, wz+zs));
                                    let ao4 = if dy==1 { [ao(-1,-1), ao(1,-1), ao(1,1), ao(-1,1)] }
                                              else       { [ao(-1,1),  ao(1,1),  ao(1,-1), ao(-1,-1)] };
                                    mask[x][z]=Some((id, tile, ao4, face_light(wx+dx, wy+dy, wz+dz)));
                                }
                            }
                        }}
                        let mut visited = [[false; 16]; 16];
                        for x in 0..SECTION_SIZE { for z in 0..SECTION_SIZE {
                            if visited[x][z] { continue; }
                            let Some((bid, tile_idx, ao4, lpk)) = mask[x][z] else { continue; };
                            let mut w = 1;
                            while z+w < SECTION_SIZE && !visited[x][z+w] && mask[x][z+w]==Some((bid, tile_idx, ao4, lpk)) { w+=1; }
                            let mut h = 1;
                            'outer2: while x+h < SECTION_SIZE {
                                for k in 0..w { if visited[x+h][z+k] || mask[x+h][z+k]!=Some((bid, tile_idx, ao4, lpk)) { break 'outer2; } }
                                h+=1;
                            }
                            for xx in x..x+h { for zz in z..z+w { visited[xx][zz]=true; } }
                            let y0 = y as f32 + if dy==1 { 1.0 } else { 0.0 };
                            let x0 = x as f32; let z0 = z as f32; let ww = w as f32; let hh = h as f32;
                            let (u0,v0,u1,v1) = (0.0f32, 0.0f32, hh, ww);
                            let color = if bid == Block::Grass as u8 && dy == 1 {
                                grass_tint(ox + x as i32, oz + z as i32)
                            } else { Block::from_id(bid).color() };
                            let wx0 = ox as f32; let wz0 = oz as f32; let by = base_y as f32;
                            let quad = if dy==1 {
                                [([x0, y0, z0], [u0, v0]), ([x0+hh, y0, z0], [u1, v0]), ([x0+hh, y0, z0+ww], [u1, v1]), ([x0, y0, z0+ww], [u0, v1])]
                            } else {
                                [([x0, y0, z0+ww], [u0, v0]), ([x0+hh, y0, z0+ww], [u1, v0]), ([x0+hh, y0, z0], [u1, v1]), ([x0, y0, z0], [u0, v1])]
                            };
                            let start = verts.len() as u32;
                            for (i,(lp, uv)) in quad.iter().enumerate() {
                                verts.push(Vertex{ pos: [wx0 + lp[0], by + lp[1], wz0 + lp[2]], uv: *uv, color, ao: aof(ao4[i]), tile_idx: tile_idx as f32, normal: nrm, light: light_to_f32(lpk) });
                            }
                            indices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
                        }}
                    }
                }
                2 => {
                    for z in 0..SECTION_SIZE {
                        let mut mask = [[None::<(u8,u32,[u8;4],u8)>; 16]; 16];
                        for x in 0..SECTION_SIZE { for y in 0..SECTION_SIZE {
                            let id = section.get(x,y,z);
                            if id==0 || id==Block::Water as u8 { continue; }
                            let wx = ox + x as i32; let wy = base_y + y as i32; let wz = oz + z as i32;
                            let nid = get_neighbor(wx+dx, wy+dy, wz+dz);
                            if nid==0 || !Block::from_id(nid).is_opaque() {
                                let tile = Block::from_id(id).tile_for_dir(dx,dy,dz);
                                let nb = wz + dz;
                                let ao = |xs: i32, ys: i32| vao(occ(wx+xs, wy, nb), occ(wx, wy+ys, nb), occ(wx+xs, wy+ys, nb));
                                let ao4 = if dz==1 { [ao(-1,-1), ao(1,-1), ao(1,1), ao(-1,1)] }
                                          else       { [ao(1,-1),  ao(-1,-1), ao(-1,1), ao(1,1)] };
                                mask[x][y]=Some((id, tile, ao4, face_light(wx+dx, wy+dy, wz+dz)));
                            }
                        }}
                        let mut visited = [[false; 16]; 16];
                        for x in 0..SECTION_SIZE { for y in 0..SECTION_SIZE {
                            if visited[x][y] { continue; }
                            let Some((bid, tile_idx, ao4, lpk)) = mask[x][y] else { continue; };
                            let mut w = 1;
                            while y+w < SECTION_SIZE && !visited[x][y+w] && mask[x][y+w]==Some((bid, tile_idx, ao4, lpk)) { w+=1; }
                            let mut h = 1;
                            'outer3: while x+h < SECTION_SIZE {
                                for k in 0..w { if visited[x+h][y+k] || mask[x+h][y+k]!=Some((bid, tile_idx, ao4, lpk)) { break 'outer3; } }
                                h+=1;
                            }
                            for xx in x..x+h { for yy in y..y+w { visited[xx][yy]=true; } }
                            let z0 = z as f32 + if dz==1 { 1.0 } else { 0.0 };
                            let x0 = x as f32; let y0 = y as f32; let ww = w as f32; let hh = h as f32;
                            let (u0,v0,u1,v1) = (0.0f32, 0.0f32, hh, ww);
                            let is_grass = bid == Block::Grass as u8;
                            let color = if is_grass { grass_tint(ox + x as i32, oz + z as i32) } else { Block::from_id(bid).color() };
                            let etile = if is_grass { GRASS_SIDE_TILE as f32 } else { tile_idx as f32 };
                            let wx0 = ox as f32; let wz0 = oz as f32; let by = base_y as f32;
                            let quad = if dz==1 {
                                [([x0, y0, z0], [u0, v1]), ([x0+hh, y0, z0], [u1, v1]), ([x0+hh, y0+ww, z0], [u1, v0]), ([x0, y0+ww, z0], [u0, v0])]
                            } else {
                                [([x0+hh, y0, z0], [u0, v1]), ([x0, y0, z0], [u1, v1]), ([x0, y0+ww, z0], [u1, v0]), ([x0+hh, y0+ww, z0], [u0, v0])]
                            };
                            let start = verts.len() as u32;
                            for (i,(lp, uv)) in quad.iter().enumerate() {
                                verts.push(Vertex{ pos: [wx0 + lp[0], by + lp[1], wz0 + lp[2]], uv: *uv, color, ao: aof(ao4[i]), tile_idx: etile, normal: nrm, light: light_to_f32(lpk) });
                            }
                            indices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
                        }}
                    }
                }
                _ => {}
            }
        }
        // --- Water surface pass: top faces of water columns exposed to air, greedy-merged. ---
        let mut wverts: Vec<Vertex> = Vec::new();
        let mut windices: Vec<u32> = Vec::new();
        for y in 0..SECTION_SIZE {
            let mut wmask = [[false; 16]; 16];
            for x in 0..SECTION_SIZE { for z in 0..SECTION_SIZE {
                if section.get(x,y,z) != Block::Water as u8 { continue; }
                let wx = ox + x as i32; let wy = base_y + y as i32; let wz = oz + z as i32;
                let above = get_neighbor(wx, wy+1, wz);
                if above != Block::Water as u8 && !(above != 0 && Block::from_id(above).is_opaque()) {
                    wmask[x][z] = true;
                }
            }}
            let mut visited = [[false; 16]; 16];
            for x in 0..SECTION_SIZE { for z in 0..SECTION_SIZE {
                if visited[x][z] || !wmask[x][z] { continue; }
                let mut w = 1; while z+w < SECTION_SIZE && !visited[x][z+w] && wmask[x][z+w] { w+=1; }
                let mut h = 1; 'ow: while x+h < SECTION_SIZE { for k in 0..w { if visited[x+h][z+k] || !wmask[x+h][z+k] { break 'ow; } } h+=1; }
                for xx in x..x+h { for zz in z..z+w { visited[xx][zz]=true; } }
                let y0 = (y+1) as f32;
                let x0 = x as f32; let z0 = z as f32; let ww = w as f32; let hh = h as f32;
                let (u0,v0,u1,v1) = (0.0f32, 0.0f32, hh, ww);
                let wx0 = ox as f32; let wz0 = oz as f32; let by = base_y as f32;
                let quad = [([x0,y0,z0],[u0,v0]), ([x0+hh,y0,z0],[u1,v0]), ([x0+hh,y0,z0+ww],[u1,v1]), ([x0,y0,z0+ww],[u0,v1])];
                let start = wverts.len() as u32;
                for (pos,uv) in quad.iter() {
                    wverts.push(Vertex{ pos: [wx0+pos[0], by+pos[1], wz0+pos[2]], uv: *uv, color: [1.0,1.0,1.0], ao: 1.0, tile_idx: 13.0, normal: [0.0,1.0,0.0], light: 15.0 });
                }
                windices.extend_from_slice(&[start, start+1, start+2, start, start+2, start+3]);
            }}
        }
        if !verts.is_empty() || !wverts.is_empty() { result[sec_idx] = Some(MeshData{ vertices: verts, indices, water_vertices: wverts, water_indices: windices }); }
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
