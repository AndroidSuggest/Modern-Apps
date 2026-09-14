#[cfg(test)]
mod tests {
    use super::*;
    use crate::world::chunk::{Chunk, ChunkPos};

    /// Mesh a chunk at the origin, resolving neighbours from the chunk itself so in-chunk occlusion
    /// is exercised. Anything outside the chunk reads as air.
    fn mesh_alone(c: &Chunk) -> Vec<Option<MeshData>> {
        mesh_chunk(c, &|x, y, z| {
            if !(0..16).contains(&x) || !(0..16).contains(&z) || y < 0 || y >= CHUNK_HEIGHT as i32 { return (0, 0); }
            let (x, y, z) = (x as usize, y as usize, z as usize);
            (c.get_block(x, y, z), c.get_meta(x, y, z))
        }, &|_, _| [0.4, 0.7, 0.3])
    }
    fn vert_count(m: &[Option<MeshData>]) -> usize {
        m.iter().filter_map(|o| o.as_ref()).map(|md| md.vertices.len()).sum()
    }

    #[test]
    fn empty_chunk_no_mesh() {
        let c = Chunk::new(ChunkPos(0,0));
        let m = mesh_alone(&c);
        assert!(m.iter().all(|o| o.is_none()));
    }
    #[test]
    fn single_block_has_faces() {
        let mut c = Chunk::new(ChunkPos(0,0));
        c.set_block(1, 10, 1, 1);
        let m = mesh_chunk(&c, &|x,y,z| if x==1 && y==10 && z==1 { (1, 0) } else { (0, 0) }, &|_,_| [0.4,0.7,0.3]);
        assert!(vert_count(&m) >= 24);
    }

    #[test]
    fn a_lone_slab_is_meshed() {
        let mut c = Chunk::new(ChunkPos(0,0));
        c.set_block_meta(2, 10, 2, Block::StoneSlab as Id, 0);
        let m = mesh_alone(&c);
        assert_eq!(vert_count(&m), 24, "a free-standing slab shows all six faces");

        // Its side faces must span only the lower half of the cell.
        let md = m.iter().flatten().next().unwrap();
        let ys: Vec<f32> = md.vertices.iter().map(|v| v.pos[1]).collect();
        assert!(ys.iter().any(|&y| (y - 10.0).abs() < 1e-5), "bottom sits on the cell floor");
        assert!(ys.iter().any(|&y| (y - 10.5).abs() < 1e-5), "top sits at mid-cell");
        assert!(!ys.iter().any(|&y| y > 10.5 + 1e-5), "a bottom slab never reaches the cell ceiling");
    }

    // The regression the directional occlusion test exists for: a cube beside a slab must still draw
    // the half of its face the slab doesn't cover, or the world gets holes.
    #[test]
    fn a_cube_next_to_a_slab_keeps_its_face() {
        let mut with_slab = Chunk::new(ChunkPos(0,0));
        with_slab.set_block(5, 10, 5, Block::Stone as Id);
        with_slab.set_block_meta(6, 10, 5, Block::StoneSlab as Id, 0);

        let mut with_cube = Chunk::new(ChunkPos(0,0));
        with_cube.set_block(5, 10, 5, Block::Stone as Id);
        with_cube.set_block(6, 10, 5, Block::Stone as Id);

        let slab_faces = count_faces_at(&mesh_alone(&with_slab), 6.0, [1.0, 0.0, 0.0]);
        let cube_faces = count_faces_at(&mesh_alone(&with_cube), 6.0, [1.0, 0.0, 0.0]);
        assert_eq!(cube_faces, 0, "two touching cubes hide the shared face");
        assert_eq!(slab_faces, 1, "the slab only covers half, so the cube must still draw its +X face");
    }

    // Count faces on the plane x == `x_plane` with the given normal.
    fn count_faces_at(m: &[Option<MeshData>], x_plane: f32, normal: [f32; 3]) -> usize {
        m.iter().flatten()
            .flat_map(|md| md.indices.chunks(6).map(move |c| &md.vertices[c[0] as usize]))
            .filter(|v| v.normal == normal && (v.pos[0] - x_plane).abs() < 1e-5)
            .count()
    }

    // A slab roof has to darken what is under it, or interiors built from slabs stay daylit.
    #[test]
    fn a_slab_roof_casts_shadow() {
        // The light packed into the floor block's up-facing quad, under an optional roof.
        let floor_light = |roof: Option<Id>| -> f32 {
            let mut c = Chunk::new(ChunkPos(0, 0));
            c.set_block(8, 10, 8, Block::Stone as Id);
            if let Some(r) = roof { c.set_block_meta(8, 14, 8, r, 0); }
            let m = mesh_alone(&c);
            m.iter().flatten()
                .flat_map(|md| md.indices.chunks(6).map(move |ci| md.vertices[ci[0] as usize]))
                .find(|v| v.normal == [0.0, 1.0, 0.0] && (v.pos[1] - 11.0).abs() < 1e-5)
                .expect("the floor block must have a top face")
                .light
        };
        let open_sky = floor_light(None);
        let under_slab = floor_light(Some(Block::StoneSlab as Id));
        let under_stairs = floor_light(Some(Block::StoneStairs as Id));
        let under_glass = floor_light(Some(Block::Glass as Id));

        assert!(under_slab < open_sky, "a slab roof must dim the floor ({under_slab} vs {open_sky})");
        assert!(under_stairs < open_sky, "so must a stair roof ({under_stairs} vs {open_sky})");
        assert_eq!(under_glass, open_sky, "glass has always let daylight through");
    }

    // Two boxes of one stair share a plane. Emitting both sides would z-fight, so the sealed one is
    // dropped — but the visible tread must survive.
    #[test]
    fn stairs_drop_only_the_sealed_internal_face() {
        let mut c = Chunk::new(ChunkPos(0,0));
        c.set_block_meta(3, 10, 3, Block::StoneStairs as Id, crate::world::block::FACE_NORTH);
        let m = mesh_alone(&c);
        let md = m.iter().flatten().next().expect("stairs must mesh");

        let up: Vec<_> = md.indices.chunks(6)
            .map(|ci| &md.vertices[ci[0] as usize])
            .filter(|v| v.normal == [0.0, 1.0, 0.0])
            .collect();
        let down: Vec<_> = md.indices.chunks(6)
            .map(|ci| &md.vertices[ci[0] as usize])
            .filter(|v| v.normal == [0.0, -1.0, 0.0])
            .collect();
        // The tread (base top) and the step top face up; only the base bottom faces down, because
        // the step's underside is sealed against the base.
        assert_eq!(up.len(), 2, "the tread and the step's top both face up");
        assert_eq!(down.len(), 1, "the step's underside is sealed against the base and must be dropped");
    }
}
