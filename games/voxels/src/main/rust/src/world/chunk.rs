pub const CHUNK_SIZE: usize = 16;
pub const SECTION_SIZE: usize = 16;
pub const SECTIONS_PER_CHUNK: usize = 16;
pub const CHUNK_HEIGHT: usize = SECTIONS_PER_CHUNK * SECTION_SIZE;
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct ChunkPos(pub i32, pub i32);
impl ChunkPos {
    pub fn from_world(x: i32, z: i32) -> Self { Self(x.div_euclid(CHUNK_SIZE as i32), z.div_euclid(CHUNK_SIZE as i32)) }
    pub fn world_origin(self) -> (i32, i32) { (self.0 * CHUNK_SIZE as i32, self.1 * CHUNK_SIZE as i32) }
}
#[derive(Clone)]
pub struct BlockSection { pub blocks: [u8; 4096], pub non_air: usize, }
impl BlockSection {
    pub fn empty() -> Self { Self { blocks: [0;4096], non_air:0 } }
    fn idx(x: usize, y: usize, z: usize) -> usize { y*256+z*16+x }
    pub fn get(&self, x: usize, y: usize, z: usize) -> u8 { self.blocks[Self::idx(x,y,z)] }
    pub fn set(&mut self, x: usize, y: usize, z: usize, id: u8) {
        let i=Self::idx(x,y,z); let prev=self.blocks[i];
        if prev!=0 && id==0 { self.non_air=self.non_air.saturating_sub(1); } else if prev==0 && id!=0 { self.non_air+=1; }
        self.blocks[i]=id;
    }
    pub fn is_empty(&self) -> bool { self.non_air==0 }
}
pub struct Chunk {
    pub pos: ChunkPos,
    pub sections: [Option<BlockSection>; SECTIONS_PER_CHUNK],
    pub generated: bool, pub dirty: bool, pub mesh_dirty: bool,
}
impl Chunk {
    pub fn new(pos: ChunkPos) -> Self { Self { pos, sections: std::array::from_fn(|_| None), generated:false, dirty:false, mesh_dirty:true } }
    pub fn get_block(&self, x: usize, y: usize, z: usize) -> u8 {
        if y>=CHUNK_HEIGHT { return 0; } let sec=y/SECTION_SIZE; let ly=y%SECTION_SIZE;
        if let Some(s)=&self.sections[sec] { s.get(x,ly,z) } else { 0 }
    }
    pub fn set_block(&mut self, x: usize, y: usize, z: usize, id: u8) {
        if y>=CHUNK_HEIGHT { return; } let sec=y/SECTION_SIZE; let ly=y%SECTION_SIZE;
        if self.sections[sec].is_none() { if id==0 { return; } self.sections[sec]=Some(BlockSection::empty()); }
        if let Some(s)=self.sections[sec].as_mut() { s.set(x,ly,z,id); if s.is_empty() { self.sections[sec]=None; } }
        self.dirty=true; self.mesh_dirty=true;
    }
}
