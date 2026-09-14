impl Session {
    /// Create a session for `role`.
    ///
    /// An initiator queues its `CONNECTION_REQUEST` **and** UKEY2 ClientInit back to
    /// back and enters [`Phase::Ukey2`] immediately: GMS writes the request and then
    /// starts the UKEY2 client without waiting for a response (`p000\dnsi.java:9582`
    /// then `:9633`). Waiting here is what deadlocks against a real device.
    ///
    /// A responder waits for the request.
    ///
    /// `local_endpoint_id` must be the id this device *advertises* — the mDNS
    /// `WifiLanServiceInfo` instance name carries it, so a session that invents its own
    /// dials out under a different identity than the one the peer discovered. An empty
    /// argument falls back to a fresh random id.
    pub fn new(
        role: Role,
        local_name: String,
        local_endpoint_info: Vec<u8>,
        local_endpoint_id: String,
    ) -> Self {
        let local_endpoint_id = if local_endpoint_id.is_empty() {
            random_endpoint_id()
        } else {
            local_endpoint_id
        };
        let mut session = Self {
            local_name,
            local_endpoint_info,
            state: State::Handshaking,
            phase: Phase::Connecting,
            local_endpoint_id,
            outbound: Vec::new(),
            inbound_buf: Vec::new(),
            handshake: HandshakeState::None,
            secure: None,
            pending_files: Vec::new(),
            files_to_send: Vec::new(),
            introduction_pending: false,
            introduction_sent: false,
            next_payload_id: random_payload_id_seed(),
            keep_alive_seq: 0,
            paired_key_encryption_sent: false,
            paired_key_result_sent: false,
            peer_paired_key_result_seen: false,
            active_send: None,
            sent_payloads: HashSet::new(),
            recvs: HashMap::new(),
            bytes_recvs: HashMap::new(),
            trace: VecDeque::new(),
            received_queue: VecDeque::new(),
            last_data_payload_id: None,
            accepted: None,
            peer_name: None,
            failed_reason: None,
        };
        if role == Role::Initiator {
            let nonce = {
                let mut raw = [0u8; 4];
                fill_random(&mut raw);
                i32::from_be_bytes(raw).saturating_abs()
            };
            let request = payload::build_connection_request(
                &session.local_endpoint_id,
                &session.local_name,
                &session.local_endpoint_info,
                nonce,
            );
            session.push_plaintext(&request);
            let hs = new_initiator_handshake();
            match hs.get_next_handshake_message() {
                Some(client_init) => {
                    session.push_plaintext(&client_init);
                    session.handshake = HandshakeState::Initiator(Box::new(hs));
                    session.phase = Phase::Ukey2;
                }
                None => session.fail("initiator produced no UKEY2 ClientInit"),
            }
        }
        session
    }

    /// Files announced by the peer's `INTRODUCTION`.
    pub fn pending_files(&self) -> &[FileMeta] {
        &self.pending_files
    }

    /// Record a protocol event. Bounded ring buffer; the oldest entry is dropped.
    ///
    /// This exists because a peer that simply stops talking gives no other clue about which
    /// frame it disliked — the wire is encrypted, so a packet capture cannot answer it
    /// either.
    fn note(&mut self, event: String) {
        const MAX_TRACE: usize = 64;
        if self.trace.len() >= MAX_TRACE {
            let _ = self.trace.pop_front();
        }
        self.trace.push_back(event);
    }

    /// The recent protocol events, oldest first, one per line.
    pub fn trace_text(&self) -> String {
        self.trace.iter().cloned().collect::<Vec<_>>().join("\n")
    }

    /// Take everything queued for the socket, already length-prefixed.
    pub fn outbound_drain(&mut self) -> Option<Vec<u8>> {
        if self.outbound.is_empty() {
            return None;
        }
        let total: usize = self.outbound.iter().map(Vec::len).sum();
        let mut out = Vec::with_capacity(total);
        for f in self.outbound.drain(..) {
            out.extend_from_slice(&f);
        }
        Some(out)
    }

    /// State ordinal for JNI.
    pub fn query_state(&self) -> i32 {
        self.state as i32
    }

    fn fail(&mut self, reason: &str) {
        self.state = State::Failed;
        self.failed_reason = Some(reason.to_string());
        self.handshake = HandshakeState::Failed;
    }

    fn push_plaintext(&mut self, body: &[u8]) {
        self.outbound.push(frame::frame_with_length(body));
    }

    fn send_encrypted(&mut self, plain: &[u8]) -> i32 {
        let Some(secure) = self.secure.as_mut() else {
            return -2;
        };
        let wire = secure.encode_message_to_peer::<CryptoProviderImpl>(plain, None::<&[u8]>);
        self.outbound.push(frame::frame_with_length(&wire));
        0
    }

    /// Hand out the next payload id.
    ///
    /// The counter is seeded randomly (see [`Session::new`]) so ids look like GMS's, but it
    /// stays monotonic because a multi-file `INTRODUCTION` reserves a consecutive run.
    fn alloc_payload_id(&mut self) -> i64 {
        let id = self.next_payload_id;
        self.next_payload_id = self.next_payload_id.saturating_add(1);
        id
    }

    /// Send a Sharing `Frame` as the body of a BYTES payload.
    fn send_sharing(&mut self, sharing_frame: &[u8]) -> i32 {
        let id = self.alloc_payload_id();
        let frames = payload::bytes_payload(id, sharing_frame);
        self.note(format!(
            "out BYTES id {id} as {} frame(s), {}B body",
            frames.len(),
            sharing_frame.len(),
        ));
        for offline in frames {
            let rc = self.send_encrypted(&offline);
            if rc < 0 {
                return rc;
            }
        }
        0
    }

    // ------------------------------------------------------------------
    // Inbound
    // ------------------------------------------------------------------

    /// Feed raw socket bytes. Returns 0 on success, negative on protocol failure.
    pub fn feed_inbound(&mut self, bytes: &[u8]) -> i32 {
        if self.state == State::Failed || self.state == State::Completed {
            return 0;
        }
        self.inbound_buf.extend_from_slice(bytes);
        loop {
            match frame::try_consume_frame(&mut self.inbound_buf) {
                ConsumeResult::Incomplete => return 0,
                ConsumeResult::Invalid => {
                    self.fail("peer announced a negative or oversized frame length");
                    return -2;
                }
                ConsumeResult::Frame(body) => {
                    let rc = self.handle_one_frame(&body);
                    if rc < 0 {
                        return rc;
                    }
                }
            }
        }
    }

    fn handle_one_frame(&mut self, body: &[u8]) -> i32 {
        match self.phase {
            Phase::Connecting => self.handle_connecting_frame(body),
            Phase::Ukey2 => self.handle_handshake_frame(body),
            Phase::ConnectionAccept => self.handle_connection_accept_frame(body),
            Phase::PairedKey | Phase::Ready => self.handle_encrypted_frame(body),
        }
    }

    /// Responder only: read `CONNECTION_REQUEST` and become the UKEY2 server.
    ///
    /// No `CONNECTION_RESPONSE` is written here. GMS's `onIncomingConnection` goes
    /// straight from the parsed request (`p000\dnsi.java:5106`) to `startServer`
    /// (`:5129`); the response is written later, by `acceptConnection`
    /// (`p000\dncj.java:1204`). Answering early makes a real peer try to parse the
    /// response as UKEY2 message 1 and abort.
    fn handle_connecting_frame(&mut self, body: &[u8]) -> i32 {
        let Ok(offline) = payload::parse_offline_frame(body) else {
            self.fail("first frame is not a parseable OfflineFrame");
            return -2;
        };
        let Some(v1) = offline.v1 else {
            self.fail("OfflineFrame without v1");
            return -2;
        };
        if v1.r#type != OfflineFrameType::ConnectionRequest as i32 {
            // GMS's own wording for this: "In readConnectionRequestFrame, expected a
            // CONNECTION_REQUEST v1 OfflineFrame but got a %s frame instead"
            // (p000\dnsi.java:4067).
            self.fail("expected a CONNECTION_REQUEST OfflineFrame");
            return -2;
        }
        // The request carries the peer's identity. Prefer the name inside `endpoint_info`,
        // which is the human-readable one a Quick Share device advertises (GMS 26.24.34 sends
        // e.g. `[0x22 … "Vayun's Pixel 7 Pro"]`); `endpoint_name` is a bare fallback.
        if let Some(req) = v1.connection_request {
            let from_info = crate::endpoint_info::parse(&req.endpoint_info)
                .and_then(|info| info.device_name)
                .filter(|n| !n.is_empty());
            let name = from_info
                .or_else(|| Some(req.endpoint_name.clone()).filter(|n| !n.is_empty()));
            if let Some(name) = name {
                self.note(format!("peer is \"{name}\""));
                self.peer_name = Some(name);
            }
        }
        self.handshake = HandshakeState::Server(Box::new(new_server_handshake()));
        self.phase = Phase::Ukey2;
        0
    }

    /// The peer's advertised device name, once the handshake has revealed it.
    pub fn peer_name(&self) -> Option<&str> {
        self.peer_name.as_deref()
    }

    /// Read the peer's plaintext `CONNECTION_RESPONSE`.
    ///
    /// Both sides have now accepted, which is exactly the gate
    /// `evaluateConnectionResult` applies before installing the encryptor
    /// (`p000\dnsi.java:4327-4339`, install at `:4366`), so encryption starts here.
    fn handle_connection_accept_frame(&mut self, body: &[u8]) -> i32 {
        let Ok(offline) = payload::parse_offline_frame(body) else {
            self.fail("CONNECTION_RESPONSE is not a parseable OfflineFrame");
            return -2;
        };
        let Some(v1) = offline.v1 else {
            self.fail("OfflineFrame without v1");
            return -2;
        };
        if v1.r#type != OfflineFrameType::ConnectionResponse as i32 {
            self.fail("expected a CONNECTION_RESPONSE OfflineFrame");
            return -2;
        }
        let Some(response) = v1.connection_response else {
            self.fail("CONNECTION_RESPONSE without a connection_response field");
            return -2;
        };
        if !response.accepted() {
            let status = response.status.unwrap_or_default();
            self.fail(&format!(
                "peer rejected the connection (status {status})"
            ));
            return -2;
        }
        // Both sides have accepted, so the channel is encrypted from here. Who speaks
        // first in the paired-key exchange is role-dependent: see `enter_paired_key`.
        self.enter_paired_key()
    }

    fn handle_handshake_frame(&mut self, body: &[u8]) -> i32 {
        // Take the handshake out so its borrow does not collide with `self.fail`
        // and `self.push_plaintext` below.
        let mut hs = std::mem::replace(&mut self.handshake, HandshakeState::None);
        let fed = match &mut hs {
            HandshakeState::Initiator(ctx) => {
                ctx.handle_handshake_message(body).map_err(|e| format!("{e:?}"))
            }
            HandshakeState::Server(ctx) => {
                ctx.handle_handshake_message(body).map_err(|e| format!("{e:?}"))
            }
            _ => Err("handshake frame arrived with no handshake in progress".to_string()),
        };
        if let Err(reason) = fed {
            self.handshake = hs;
            self.fail(&format!("UKEY2 handshake failed: {reason}"));
            return -2;
        }
        // Any message the library wants to send next (ServerInit / ClientFinished).
        let next = match &mut hs {
            HandshakeState::Initiator(ctx) => ctx.get_next_handshake_message(),
            HandshakeState::Server(ctx) => ctx.get_next_handshake_message(),
            _ => None,
        };
        if let Some(msg) = next {
            self.push_plaintext(&msg);
        }
        let complete = match &hs {
            HandshakeState::Initiator(ctx) => ctx.is_handshake_complete(),
            HandshakeState::Server(ctx) => ctx.is_handshake_complete(),
            _ => false,
        };
        if !complete {
            self.handshake = hs;
            return 0;
        }
        let ctx_result = match hs {
            HandshakeState::Initiator(mut ctx) => {
                ctx.to_connection_context().map_err(|e| format!("{e:?}"))
            }
            HandshakeState::Server(mut ctx) => {
                ctx.to_connection_context().map_err(|e| format!("{e:?}"))
            }
            _ => Err("handshake completed without a context".to_string()),
        };
        match ctx_result {
            Ok(ctx) => {
                self.secure = Some(ctx);
                self.handshake = HandshakeState::Done;
                self.enter_connection_accept()
            }
            Err(reason) => {
                self.fail(&format!("UKEY2 context derivation failed: {reason}"));
                -2
            }
        }
    }

    /// Send our plaintext `CONNECTION_RESPONSE{status: 0, response: ACCEPT}`.
    ///
    /// The D2D context exists by now but stays unused: the channel is still
    /// plaintext, because GMS writes this frame before `doeq.mo63639c()`
    /// (`p000\dncj.java:1204-1208`) and installs the encryptor only once both sides
    /// have accepted. Dispatch is keyed on [`Phase`], so holding the context in
    /// `secure` early cannot leak encryption into this phase.
    ///
    /// Accepting unconditionally mirrors Quick Share, which accepts at the Nearby
    /// Connections layer programmatically (`p000\dzuj.java:76`) and asks the user
    /// later, with the Sharing-layer `INTRODUCTION`.
    fn enter_connection_accept(&mut self) -> i32 {
        self.phase = Phase::ConnectionAccept;
        let response = payload::build_offline_connection_response(true);
        self.push_plaintext(&response);
        0
    }

    /// Move to [`Phase::PairedKey`] and send `PAIRED_KEY_ENCRYPTION`, whichever role this is.
    ///
    /// Both sides speak first, immediately after the mutual `CONNECTION_RESPONSE`. That is
    /// what `rquickshare` does — an independent non-GMS implementation that interoperates
    /// with real Quick Share: `core_lib/src/hdl/inbound.rs` (`process_connection_response`)
    /// sends its `CONNECTION_RESPONSE` and then its `PAIRED_KEY_ENCRYPTION` back to back,
    /// with the same 6-byte/72-byte random decoys, from either role.
    ///
    /// An earlier revision restricted this to the responder because an initiator that sent it
    /// proactively appeared to draw a `DISCONNECTION` 250 ms later. That was an artefact of
    /// the `PayloadChunk.flags` bug: GMS discarded the chunk carrying the frame outright
    /// (`OfflineFrame PAYLOAD_TRANSFER(DATA) missing flags field`), so what looked like a
    /// rejected frame was a frame it never received.
    fn enter_paired_key(&mut self) -> i32 {
        self.phase = Phase::PairedKey;
        self.send_paired_key_encryption()
    }

    /// `PAIRED_KEY_ENCRYPTION` with decoy fields, sent at most once.
    ///
    /// `:share` has no contact certificate, so both byte fields are fresh random decoys of
    /// the exact widths GMS uses when signing fails.
    fn send_paired_key_encryption(&mut self) -> i32 {
        if self.paired_key_encryption_sent {
            return 0;
        }
        self.paired_key_encryption_sent = true;
        let frame = payload::build_paired_key_encryption_decoy(fill_random);
        self.send_sharing(&frame)
    }

    fn handle_encrypted_frame(&mut self, body: &[u8]) -> i32 {
        let Some(secure) = self.secure.as_mut() else {
            self.fail("encrypted frame before the secure channel existed");
            return -2;
        };
        let plain = match secure.decode_message_from_peer::<CryptoProviderImpl>(body, None::<&[u8]>) {
            Ok(v) => v,
            Err(e) => {
                self.fail(&format!("D2D decrypt failed: {e:?}"));
                return -2;
            }
        };
        let Ok(offline) = payload::parse_offline_frame(&plain) else {
            // GMS logs exactly this situation as "Read an unencrypted (or garbage)
            // frame when we expected an encrypted frame." (p000\dnhn.java:381).
            self.fail("decrypted body is not a parseable OfflineFrame");
            return -2;
        };
        let Some(v1) = offline.v1 else {
            self.fail("OfflineFrame without v1");
            return -2;
        };
        if v1.r#type == OfflineFrameType::KeepAlive as i32 {
            if let Some(ka) = v1.keep_alive {
                if !ka.ack {
                    let reply = payload::build_keep_alive(true, ka.seq_num);
                    return self.send_encrypted(&reply);
                }
            }
            return 0;
        }
        self.note(format!("in OfflineFrame type {}", v1.r#type));
        // A bandwidth upgrade must be answered even though `:share` never takes one: it is
        // already on WIFI_LAN, the medium a peer upgrades *to*. Measured against a Pixel 7
        // Pro on GMS 26.24.34 — it sends BANDWIDTH_UPGRADE_RETRY, and if that goes
        // unanswered it never sends PAIRED_KEY_RESULT or the INTRODUCTION, keep-alives for
        // ~15 s and then disconnects.
        if v1.r#type == OfflineFrameType::BandwidthUpgradeNegotiation as i32
            || v1.r#type == OfflineFrameType::BandwidthUpgradeRetry as i32
        {
            let event = v1
                .bandwidth_upgrade_negotiation
                .as_ref()
                .map_or(0, |b| b.event_type);
            self.note(format!("declining bandwidth upgrade (event {event})"));
            let decline = payload::build_bandwidth_upgrade_failure();
            return self.send_encrypted(&decline);
        }
        if v1.r#type == OfflineFrameType::Disconnection as i32 {
            if self.state == State::Transferring || self.state == State::Handshaking {
                // Name the phase: a peer that hangs up on us does so for a reason specific
                // to what it just read, and the phase is the only clue we get.
                let reason = format!(
                    "peer disconnected during {:?} (paired-key sent: {}, peer result seen: {})",
                    self.phase, self.paired_key_result_sent, self.peer_paired_key_result_seen,
                );
                self.fail(&reason);
                return -2;
            }
            return 0;
        }
        if v1.r#type != OfflineFrameType::PayloadTransfer as i32 {
            // BANDWIDTH_UPGRADE_NEGOTIATION and the auth frames are not implemented;
            // ignoring them keeps a GMS peer's optional traffic from killing the session.
            return 0;
        }
        let Some(pt) = v1.payload_transfer else {
            return 0;
        };
        self.handle_payload_transfer(pt)
    }
}
