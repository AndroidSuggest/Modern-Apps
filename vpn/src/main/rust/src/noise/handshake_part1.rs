impl Handshake {
    pub(crate) fn new(
        static_private: x25519::StaticSecret,
        static_public: x25519::PublicKey,
        peer_static_public: x25519::PublicKey,
        index_table: IndexTable,
        preshared_key: Option<[u8; 32]>,
    ) -> Handshake {
        let params = NoiseParams::new(
            static_private,
            static_public,
            peer_static_public,
            preshared_key,
        );

        Handshake {
            params,
            index_table,
            previous: HandshakeState::None,
            state: HandshakeState::None,
            last_handshake_timestamp: Tai64N::zero(),
            stamper: TimeStamper::new(),
            cookies: Default::default(),
            last_rtt: None,
        }
    }

    pub(crate) fn is_in_progress(&self) -> bool {
        !matches!(self.state, HandshakeState::None | HandshakeState::Expired)
    }

    pub(crate) fn timer(&self) -> Option<Instant> {
        match self.state {
            HandshakeState::InitSent(HandshakeInitSentState { time_sent, .. }) => Some(time_sent),
            _ => None,
        }
    }

    pub(crate) fn set_expired(&mut self) {
        self.previous = HandshakeState::Expired;
        self.state = HandshakeState::Expired;
    }

    /// Reset the handshake to its initial state, so a fresh handshake can be initiated.
    pub(crate) fn reset(&mut self) {
        self.previous = HandshakeState::None;
        self.state = HandshakeState::None;
    }

    pub(crate) fn is_expired(&self) -> bool {
        matches!(self.state, HandshakeState::Expired)
    }

    pub(crate) fn has_cookie(&self) -> bool {
        self.cookies.write_cookie.is_some()
    }

    pub(crate) fn clear_cookie(&mut self) {
        self.cookies.write_cookie = None;
    }

    pub(crate) fn set_static_private(
        &mut self,
        private_key: x25519::StaticSecret,
        public_key: x25519::PublicKey,
    ) {
        self.params.set_static_private(private_key, public_key);
        // Invalidate in-flight handshakes
        self.state = HandshakeState::None;
        self.previous = HandshakeState::None;
    }

    /// Store the preshared key used by future handshakes.
    ///
    /// Any in-flight handshake and established sessions are deliberately left untouched.
    pub(crate) fn set_preshared_key(&mut self, preshared_key: Option<[u8; 32]>) {
        self.params.set_preshared_key(preshared_key);
    }

    /// Get the preshared key
    pub(crate) fn preshared_key(&self) -> Option<[u8; 32]> {
        self.params.preshared_key
    }

    pub(super) fn receive_handshake_initialization(
        &mut self,
        packet: crate::packet::Packet<WgHandshakeInit>,
    ) -> Result<(crate::packet::Packet<WgHandshakeResp>, Session), WireGuardError> {
        // initiator.chaining_key = HASH(CONSTRUCTION)
        let mut chaining_key = INITIAL_CHAIN_KEY;
        // initiator.hash = HASH(HASH(initiator.chaining_key || IDENTIFIER) || responder.static_public)
        let mut hash = INITIAL_CHAIN_HASH;
        hash = b2s_hash(&hash, self.params.static_public.as_bytes());
        // msg.sender_index = little_endian(initiator.sender_index)
        let peer_index = packet.sender_idx;
        // msg.unencrypted_ephemeral = DH_PUBKEY(initiator.ephemeral_private)
        let peer_ephemeral_public = x25519::PublicKey::from(packet.unencrypted_ephemeral);
        // initiator.hash = HASH(initiator.hash || msg.unencrypted_ephemeral)
        hash = b2s_hash(&hash, peer_ephemeral_public.as_bytes());
        // temp = HMAC(initiator.chaining_key, msg.unencrypted_ephemeral)
        // initiator.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(
            &b2s_hmac(&chaining_key, peer_ephemeral_public.as_bytes()),
            &[0x01],
        );
        // temp = HMAC(initiator.chaining_key, DH(initiator.ephemeral_private, responder.static_public))
        let ephemeral_shared = self
            .params
            .static_private
            .diffie_hellman(&peer_ephemeral_public);
        let temp = b2s_hmac(&chaining_key, &ephemeral_shared.to_bytes());
        // initiator.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(&temp, &[0x01]);
        // key = HMAC(temp, initiator.chaining_key || 0x2)
        let key = b2s_hmac2(&temp, &chaining_key, &[0x02]);

        let mut peer_static_public_decrypted = [0u8; KEY_LEN];
        // msg.encrypted_static = AEAD(key, 0, initiator.static_public, initiator.hash)
        aead_chacha20_open(
            &mut peer_static_public_decrypted,
            &key,
            0,
            packet.encrypted_static.as_bytes(),
            &hash,
        )?;

        if !constant_time_eq_n(
            self.params.peer_static_public.as_bytes(),
            &peer_static_public_decrypted,
        ) {
            return Err(WireGuardError::WrongKey);
        }

        // initiator.hash = HASH(initiator.hash || msg.encrypted_static)
        hash = b2s_hash(&hash, packet.encrypted_static.as_bytes());
        // temp = HMAC(initiator.chaining_key, DH(initiator.static_private, responder.static_public))
        let temp = b2s_hmac(&chaining_key, self.params.static_shared.as_bytes());
        // initiator.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(&temp, &[0x01]);
        // key = HMAC(temp, initiator.chaining_key || 0x2)
        let key = b2s_hmac2(&temp, &chaining_key, &[0x02]);
        // msg.encrypted_timestamp = AEAD(key, 0, TAI64N(), initiator.hash)
        let mut timestamp = [0u8; TIMESTAMP_LEN];
        aead_chacha20_open(&mut timestamp, &key, 0, packet.timestamp.as_bytes(), &hash)?;

        let timestamp = Tai64N::parse(&timestamp)?;
        if !timestamp.after(&self.last_handshake_timestamp) {
            // Possibly a replay
            return Err(WireGuardError::WrongTai64nTimestamp);
        }
        self.last_handshake_timestamp = timestamp;

        // initiator.hash = HASH(initiator.hash || msg.encrypted_timestamp)
        hash = b2s_hash(&hash, packet.timestamp.as_bytes());

        self.previous = std::mem::replace(
            &mut self.state,
            HandshakeState::InitReceived {
                chaining_key,
                hash,
                peer_ephemeral_public,
                peer_index: peer_index.get(),
            },
        );

        Ok(self.format_handshake_response(packet.into_bytes()))
    }

    pub(super) fn receive_handshake_response(
        &mut self,
        packet: &WgHandshakeResp,
    ) -> Result<Session, WireGuardError> {
        // Check if there is a handshake awaiting a response and take the correct one
        let expected =
            |s: &HandshakeInitSentState| s.local_index.value() == packet.receiver_idx.get();
        let state = match (&self.state, &self.previous) {
            (HandshakeState::InitSent(s), _) | (_, HandshakeState::InitSent(s)) if expected(s) => s,
            _ => return Err(WireGuardError::UnexpectedPacket),
        };

        let sender_index = packet.sender_idx;

        let unencrypted_ephemeral = x25519::PublicKey::from(packet.unencrypted_ephemeral);
        // msg.unencrypted_ephemeral = DH_PUBKEY(responder.ephemeral_private)
        // responder.hash = HASH(responder.hash || msg.unencrypted_ephemeral)
        let mut hash = b2s_hash(&state.hash, unencrypted_ephemeral.as_bytes());
        // temp = HMAC(responder.chaining_key, msg.unencrypted_ephemeral)
        let temp = b2s_hmac(&state.chaining_key, unencrypted_ephemeral.as_bytes());
        // responder.chaining_key = HMAC(temp, 0x1)
        let mut chaining_key = b2s_hmac(&temp, &[0x01]);
        // temp = HMAC(responder.chaining_key, DH(responder.ephemeral_private, initiator.ephemeral_public))
        let ephemeral_shared = state
            .ephemeral_private
            .diffie_hellman(&unencrypted_ephemeral);
        let temp = b2s_hmac(&chaining_key, &ephemeral_shared.to_bytes());
        // responder.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(&temp, &[0x01]);
        // temp = HMAC(responder.chaining_key, DH(responder.ephemeral_private, initiator.static_public))
        let temp = b2s_hmac(
            &chaining_key,
            &self
                .params
                .static_private
                .diffie_hellman(&unencrypted_ephemeral)
                .to_bytes(),
        );
        // responder.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(&temp, &[0x01]);
        // temp = HMAC(responder.chaining_key, preshared_key)
        let temp = b2s_hmac(
            &chaining_key,
            &self.params.preshared_key.unwrap_or([0u8; 32])[..],
        );
        // responder.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(&temp, &[0x01]);
        // temp2 = HMAC(temp, responder.chaining_key || 0x2)
        let temp2 = b2s_hmac2(&temp, &chaining_key, &[0x02]);
        // key = HMAC(temp, temp2 || 0x3)
        let key = b2s_hmac2(&temp, &temp2, &[0x03]);
        // responder.hash = HASH(responder.hash || temp2)
        hash = b2s_hash(&hash, &temp2);
        // msg.encrypted_nothing = AEAD(key, 0, [empty], responder.hash)
        aead_chacha20_open(&mut [], &key, 0, packet.encrypted_nothing.as_bytes(), &hash)?;

        // responder.hash = HASH(responder.hash || msg.encrypted_nothing)
        // hash = b2s_hash(hash, buf[ENC_NOTHING_OFF..ENC_NOTHING_OFF + ENC_NOTHING_SZ]);

        // Derive keys
        // temp1 = HMAC(initiator.chaining_key, [empty])
        // temp2 = HMAC(temp1, 0x1)
        // temp3 = HMAC(temp1, temp2 || 0x2)
        // initiator.sending_key = temp2
        // initiator.receiving_key = temp3
        // initiator.sending_key_counter = 0
        // initiator.receiving_key_counter = 0
        let temp1 = b2s_hmac(&chaining_key, &[]);
        let temp2 = b2s_hmac(&temp1, &[0x01]);
        let temp3 = b2s_hmac2(&temp1, &temp2, &[0x02]);

        let rtt_time = Instant::now().duration_since(state.time_sent);
        self.last_rtt = Some(rtt_time.as_millis() as u32);

        // Remove handshake state at the very end so it's kept in case of an error
        let is_current = matches!(&self.state, HandshakeState::InitSent(state) if expected(state));
        let state = if is_current {
            let HandshakeState::InitSent(state) =
                std::mem::replace(&mut self.state, HandshakeState::None)
            else {
                unreachable!("self.state was HandshakeState::InitSent")
            };
            state
        } else {
            let HandshakeState::InitSent(state) =
                std::mem::replace(&mut self.previous, HandshakeState::None)
            else {
                unreachable!("self.previous was HandshakeState::InitSent")
            };
            state
        };

        Ok(Session::new(
            state.local_index,
            sender_index.get(),
            temp3,
            temp2,
        ))
    }

    pub(super) fn receive_cookie_reply(
        &mut self,
        packet: &WgCookieReply,
    ) -> Result<(), WireGuardError> {
        let mac1 = match self.cookies.last_mac1 {
            Some(mac) => mac,
            None => {
                return Err(WireGuardError::UnexpectedPacket);
            }
        };

        let local_index = self.cookies.index;
        if packet.receiver_idx != local_index {
            return Err(WireGuardError::WrongIndex);
        }
        // msg.encrypted_cookie = XAEAD(HASH(LABEL_COOKIE || responder.static_public), msg.nonce, cookie, last_received_msg.mac1)
        let key = b2s_hash(LABEL_COOKIE, self.params.peer_static_public.as_bytes()); // TODO: pre-compute

        let payload = Payload {
            aad: &mac1,
            msg: packet.encrypted_cookie.as_bytes(),
        };
        let plaintext = XChaCha20Poly1305::new_from_slice(&key)
            .unwrap()
            .decrypt(&packet.nonce.into(), payload)
            .map_err(|_| WireGuardError::InvalidAeadTag)?;

        let cookie = plaintext
            .try_into()
            .map_err(|_| WireGuardError::InvalidPacket)?;
        self.cookies.write_cookie = Some(cookie);
        Ok(())
    }

    // Compute and append mac1 and mac2 to a handshake message
    fn init_mac1_and_mac2<T: WgHandshakeBase>(&mut self, packet: &mut T, local_index: u32) {
        // msg.mac1 = MAC(HASH(LABEL_MAC1 || responder.static_public), msg[0:offsetof(msg.mac1)])
        *packet.mac1_mut() = b2s_keyed_mac_16(&self.params.sending_mac1_key, packet.until_mac1());

        //msg.mac2 = MAC(initiator.last_received_cookie, msg[0:offsetof(msg.mac2)])
        *packet.mac2_mut() = if let Some(cookie) = &self.cookies.write_cookie {
            b2s_keyed_mac_16(cookie, packet.until_mac2())
        } else {
            [0u8; 16]
        };

        self.cookies.index = local_index;
        self.cookies.last_mac1 = Some(*packet.mac1());
    }

    pub(super) fn format_handshake_initiation(&mut self) -> crate::packet::Packet<WgHandshakeInit> {
        let mut handshake = WgHandshakeInit::new();

        let local_index = self.index_table.new_index();
        let local_index_val = local_index.value();

        // initiator.chaining_key = HASH(CONSTRUCTION)
        let mut chaining_key = INITIAL_CHAIN_KEY;
        // initiator.hash = HASH(HASH(initiator.chaining_key || IDENTIFIER) || responder.static_public)
        let mut hash = INITIAL_CHAIN_HASH;
        hash = b2s_hash(&hash, self.params.peer_static_public.as_bytes());
        // initiator.ephemeral_private = DH_GENERATE()
        let ephemeral_private = x25519::ReusableSecret::random_from_rng(rand_core::OsRng);
        // msg.message_type = 1
        // msg.reserved_zero = { 0, 0, 0 }
        // msg.sender_index = little_endian(initiator.sender_index)
        handshake.sender_idx.set(local_index_val);
        // msg.unencrypted_ephemeral = DH_PUBKEY(initiator.ephemeral_private)
        handshake
            .unencrypted_ephemeral
            .copy_from_slice(x25519::PublicKey::from(&ephemeral_private).as_bytes());
        // initiator.hash = HASH(initiator.hash || msg.unencrypted_ephemeral)
        hash = b2s_hash(&hash, &handshake.unencrypted_ephemeral);
        // temp = HMAC(initiator.chaining_key, msg.unencrypted_ephemeral)
        // initiator.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(
            &b2s_hmac(&chaining_key, &handshake.unencrypted_ephemeral),
            &[0x01],
        );
        // temp = HMAC(initiator.chaining_key, DH(initiator.ephemeral_private, responder.static_public))
        let ephemeral_shared = ephemeral_private.diffie_hellman(&self.params.peer_static_public);
        let temp = b2s_hmac(&chaining_key, &ephemeral_shared.to_bytes());
        // initiator.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(&temp, &[0x01]);
        // key = HMAC(temp, initiator.chaining_key || 0x2)
        let key = b2s_hmac2(&temp, &chaining_key, &[0x02]);
        // msg.encrypted_static = AEAD(key, 0, initiator.static_public, initiator.hash)
        aead_chacha20_seal(
            handshake.encrypted_static.as_mut_bytes(),
            &key,
            0,
            self.params.static_public.as_bytes(),
            &hash,
        );
        // initiator.hash = HASH(initiator.hash || msg.encrypted_static)
        hash = b2s_hash(&hash, handshake.encrypted_static.as_bytes());
        // temp = HMAC(initiator.chaining_key, DH(initiator.static_private, responder.static_public))
        let temp = b2s_hmac(&chaining_key, self.params.static_shared.as_bytes());
        // initiator.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(&temp, &[0x01]);
        // key = HMAC(temp, initiator.chaining_key || 0x2)
        let key = b2s_hmac2(&temp, &chaining_key, &[0x02]);
        // msg.encrypted_timestamp = AEAD(key, 0, TAI64N(), initiator.hash)
        let timestamp = self.stamper.stamp();
        aead_chacha20_seal(
            handshake.timestamp.as_mut_bytes(),
            &key,
            0,
            &timestamp,
            &hash,
        );
        // initiator.hash = HASH(initiator.hash || msg.encrypted_timestamp)
        hash = b2s_hash(&hash, handshake.timestamp.as_bytes());

        let time_now = Instant::now();
        self.previous = std::mem::replace(
            &mut self.state,
            HandshakeState::InitSent(HandshakeInitSentState {
                local_index,
                chaining_key,
                hash,
                ephemeral_private,
                time_sent: time_now,
            }),
        );

        self.init_mac1_and_mac2(&mut handshake, local_index_val);

        Packet::copy_from(&handshake)
    }

    fn format_handshake_response(
        &mut self,
        buf: crate::packet::Packet,
    ) -> (crate::packet::Packet<WgHandshakeResp>, Session) {
        let state = std::mem::replace(&mut self.state, HandshakeState::None);
        let (mut chaining_key, mut hash, peer_ephemeral_public, peer_index) = match state {
            HandshakeState::InitReceived {
                chaining_key,
                hash,
                peer_ephemeral_public,
                peer_index,
            } => (chaining_key, hash, peer_ephemeral_public, peer_index),
            _ => {
                panic!("Unexpected attempt to call send_handshake_response");
            }
        };

        // responder.ephemeral_private = DH_GENERATE()
        let ephemeral_private = x25519::ReusableSecret::random_from_rng(rand_core::OsRng);
        let local_index = self.index_table.new_index();
        let local_index_val = local_index.value();
        // msg.message_type = 2
        // msg.reserved_zero = { 0, 0, 0 }
        let mut resp = WgHandshakeResp::new(
            local_index_val,
            peer_index,
            *x25519::PublicKey::from(&ephemeral_private).as_bytes(),
        );
        // msg.sender_index = little_endian(responder.sender_index)
        // msg.receiver_index = little_endian(initiator.sender_index)
        // msg.unencrypted_ephemeral = DH_PUBKEY(initiator.ephemeral_private)
        // responder.hash = HASH(responder.hash || msg.unencrypted_ephemeral)
        hash = b2s_hash(&hash, &resp.unencrypted_ephemeral);
        // temp = HMAC(responder.chaining_key, msg.unencrypted_ephemeral)
        let temp = b2s_hmac(&chaining_key, &resp.unencrypted_ephemeral);
        // responder.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(&temp, &[0x01]);
        // temp = HMAC(responder.chaining_key, DH(responder.ephemeral_private, initiator.ephemeral_public))
        let ephemeral_shared = ephemeral_private.diffie_hellman(&peer_ephemeral_public);
        let temp = b2s_hmac(&chaining_key, &ephemeral_shared.to_bytes());
        // responder.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(&temp, &[0x01]);
        // temp = HMAC(responder.chaining_key, DH(responder.ephemeral_private, initiator.static_public))
        let temp = b2s_hmac(
            &chaining_key,
            &ephemeral_private
                .diffie_hellman(&self.params.peer_static_public)
                .to_bytes(),
        );
        // responder.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(&temp, &[0x01]);