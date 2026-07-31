use bytes::BytesMut;
use crate::packet::Packet;
#[derive(Clone)]
pub struct PacketBufPool<const N: usize = 4096>;
impl<const N: usize> PacketBufPool<N> {
    pub fn new(_cap: usize) -> Self { Self }
    pub fn new_lazy(_cap: usize) -> Self { Self }
    pub fn get(&self) -> Packet<[u8]> { Packet::from_bytes(BytesMut::zeroed(N)) }
    pub fn capacity(&self) -> usize { 0 }
}
pub struct ReturnToPool;
