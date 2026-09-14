impl WgHandshakeBase for WgHandshakeResp {
    const LEN: usize = Self::LEN;
    const MAC1_OFF: usize = offset_of!(Self, mac1);
    const MAC2_OFF: usize = offset_of!(Self, mac2);

    fn sender_idx(&self) -> u32 {
        self.sender_idx.get()
    }

    fn mac1_mut(&mut self) -> &mut [u8; 16] {
        &mut self.mac1
    }

    fn mac2_mut(&mut self) -> &mut [u8; 16] {
        &mut self.mac2
    }

    fn mac1(&self) -> &[u8; 16] {
        &self.mac1
    }

    fn mac2(&self) -> &[u8; 16] {
        &self.mac2
    }
}

/// WireGuard cookie reply packet.
/// See section 5.4.7 of the [whitepaper](https://www.wireguard.com/papers/wireguard.pdf).
#[derive(FromBytes, IntoBytes, KnownLayout, Unaligned, Immutable)]
#[repr(C, packed)]
pub struct WgCookieReply {
    // INVARIANT: Must be WgPacketType::CookieReply
    packet_type: WgPacketType,
    _reserved_zeros: [u8; 4 - size_of::<WgPacketType>()],

    /// An integer that identifies the WireGuard session for the handshake-initiating peer.
    pub receiver_idx: little_endian::U32,

    /// Number only used once.
    pub nonce: [u8; 24],

    /// An encrypted 16-byte value that identifies the [`WgHandshakeInit`] that this packet is in
    /// response to.
    pub encrypted_cookie: EncryptedWithTag<[u8; 16]>,
}

impl WgCookieReply {
    /// Length of the packet, in bytes.
    pub const LEN: usize = size_must_be::<Self>(64);

    /// Construct a [`WgCookieReply`] where all fields except `packet_type` are zeroed.
    pub fn new() -> Self {
        Self {
            packet_type: WgPacketType::CookieReply,
            ..Self::new_zeroed()
        }
    }
}

impl Default for WgCookieReply {
    fn default() -> Self {
        Self::new()
    }
}

impl Packet {
    /// Try to cast to a WireGuard packet while sanity-checking packet type and size.
    pub fn try_into_wg(self) -> eyre::Result<WgKind> {
        let wg = Wg::ref_from_bytes(self.as_bytes())
            .map_err(|_| eyre!("Not a wireguard packet, too small."))?;

        let len = wg.as_bytes().len();
        match (wg.packet_type, len) {
            (WgPacketType::HandshakeInit, WgHandshakeInit::LEN) => {
                Ok(WgKind::HandshakeInit(self.cast()))
            }
            (WgPacketType::HandshakeResp, WgHandshakeResp::LEN) => {
                Ok(WgKind::HandshakeResp(self.cast()))
            }
            (WgPacketType::CookieReply, WgCookieReply::LEN) => Ok(WgKind::CookieReply(self.cast())),
            (WgPacketType::Data, WgData::OVERHEAD..) => Ok(WgKind::Data(self.cast())),
            _ => bail!("Not a wireguard packet, bad type/size."),
        }
    }
}

impl Debug for WgPacketType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let name = match self {
            &WgPacketType::HandshakeInit => "HandshakeInit",
            &WgPacketType::HandshakeResp => "HandshakeResp",
            &WgPacketType::CookieReply => "CookieReply",
            &WgPacketType::Data => "Data",

            WgPacketType(t) => return Debug::fmt(t, f),
        };

        f.debug_tuple(name).finish()
    }
}
