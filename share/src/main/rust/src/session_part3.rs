#[cfg(test)]
mod tests {
    use super::*;

    /// Split a drained outbound buffer back into its individual framed messages.
    fn split_frames(raw: &[u8]) -> Vec<Vec<u8>> {
        let mut buf = raw.to_vec();
        let mut out = Vec::new();
        loop {
            match frame::try_consume_frame(&mut buf) {
                ConsumeResult::Frame(body) => out.push(body),
                ConsumeResult::Incomplete => break,
                ConsumeResult::Invalid => panic!("drained buffer is not well framed"),
            }
        }
        assert!(buf.is_empty(), "trailing bytes after the last frame");
        out
    }

    /// The `V1Frame` of `body`, if `body` really is a plaintext `OfflineFrame`.
    ///
    /// Re-encoding and requiring byte equality is what makes this a *proof* rather
    /// than a guess: `prost` will happily decode many byte strings into a mostly
    /// empty message, but an encrypted body will not round-trip.
    fn plaintext_offline(body: &[u8]) -> Option<frame::OfflineV1Frame> {
        let offline = payload::parse_offline_frame(body).ok()?;
        if prost::Message::encode_to_vec(&offline) != body {
            return None;
        }
        offline.v1
    }

    fn frame_types(raw: &[u8]) -> Vec<Option<i32>> {
        split_frames(raw)
            .iter()
            .map(|f| plaintext_offline(f).map(|v1| v1.r#type))
            .collect()
    }

    /// One decoded `drain_received` record.
    struct Drained {
        payload_id: i64,
        offset: i64,
        total_size: i64,
        last: bool,
        name: String,
        body: Vec<u8>,
    }

    fn decode_received_record(raw: &[u8]) -> Drained {
        fn i64_at(raw: &[u8], at: usize) -> i64 {
            i64::from_be_bytes(raw[at..at + 8].try_into().expect("8 bytes"))
        }
        assert_eq!(raw[0], RECEIVED_RECORD_VERSION, "record version");
        let payload_id = i64_at(raw, 1);
        let offset = i64_at(raw, 9);
        let total_size = i64_at(raw, 17);
        let flags = raw[25];
        let name_len = u16::from_be_bytes(raw[26..28].try_into().expect("2 bytes")) as usize;
        let name = String::from_utf8(raw[28..28 + name_len].to_vec()).expect("utf8 name");
        let body_at = 28 + name_len;
        let body_len =
            u32::from_be_bytes(raw[body_at..body_at + 4].try_into().expect("4 bytes")) as usize;
        let body = raw[body_at + 4..body_at + 4 + body_len].to_vec();
        assert_eq!(body_at + 4 + body_len, raw.len(), "record has trailing bytes");
        Drained {
            payload_id,
            offset,
            total_size,
            last: (flags & RECEIVED_FLAG_LAST) != 0,
            name,
            body,
        }
    }

    fn drain_all_received(s: &mut Session) -> Vec<Drained> {
        let mut out = Vec::new();
        while let Some(raw) = s.drain_received() {
            out.push(decode_received_record(&raw));
        }
        out
    }

    /// Pump both sides until neither has anything left to send.
    fn settle(a: &mut Session, b: &mut Session) {
        for _ in 0..32 {
            let mut moved = false;
            if let Some(out) = a.outbound_drain() {
                assert!(b.feed_inbound(&out) >= 0, "b failed: {:?}", b.failed_reason.as_deref());
                moved = true;
            }
            if let Some(out) = b.outbound_drain() {
                assert!(a.feed_inbound(&out) >= 0, "a failed: {:?}", a.failed_reason.as_deref());
                moved = true;
            }
            if !moved {
                return;
            }
        }
        panic!("sessions did not settle");
    }

    /// A session with a random endpoint id, which the empty-id fallback supplies.
    fn test_session(role: Role, local_name: &str, local_endpoint_info: Vec<u8>) -> Session {
        Session::new(
            role,
            local_name.to_string(),
            local_endpoint_info,
            String::new(),
        )
    }

    fn connected_pair() -> (Session, Session) {
        let mut initiator = test_session(Role::Initiator, "Alice", b"alice-info".to_vec());
        let mut responder = test_session(Role::Responder, "Bob", b"bob-info".to_vec());
        settle(&mut initiator, &mut responder);
        (initiator, responder)
    }

    #[test]
    fn both_roles_open_the_paired_key_exchange() {
        // Both sides send PAIRED_KEY_ENCRYPTION as soon as the mutual CONNECTION_RESPONSE
        // lands, which is what rquickshare does from either role. See
        // `Session::enter_paired_key`.
        let (initiator, responder) = connected_pair();
        assert!(
            responder.paired_key_encryption_sent,
            "responder never opened the paired-key exchange",
        );
        assert!(
            initiator.paired_key_encryption_sent,
            "initiator never opened the paired-key exchange",
        );

        // Unprompted, with no peer traffic at all: entering the phase is itself the trigger.
        let mut lone = test_session(Role::Initiator, "Solo", b"solo-info".to_vec());
        let _ = lone.outbound_drain();
        lone.secure = None;
        assert_eq!(
            lone.enter_paired_key(),
            -2,
            "there is no secure channel yet, so the frame cannot be encrypted",
        );
        assert!(lone.paired_key_encryption_sent, "initiator waited to be spoken to");
    }

    #[test]
    fn the_introduction_waits_for_the_peers_paired_key_result() {
        // rquickshare sends the INTRODUCTION only after the peer's PAIRED_KEY_RESULT, and GMS
        // demonstrably does send one (`in Sharing type 4` in the verified receive trace).
        // Introducing earlier risks the peer's Sharing layer dropping it on arrival.
        let mut sender = test_session(Role::Initiator, "Alice", vec![]);
        let mut receiver = test_session(Role::Responder, "Bob", vec![]);
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "photo.jpg".to_string(),
            size_bytes: 1,
            mime_type: String::new(),
            payload_id: 0,
        }]);
        assert_eq!(sender.queue_introduction(), 0);
        settle(&mut sender, &mut receiver);
        assert!(sender.peer_paired_key_result_seen, "peer result never arrived");
        assert!(sender.is_ready(), "sender never became Ready");
        assert_eq!(receiver.state, State::AwaitingAccept);

        // Without the peer's result the introduction stays queued rather than going out.
        let (mut lone, _) = connected_pair();
        lone.phase = Phase::PairedKey;
        lone.peer_paired_key_result_seen = false;
        lone.introduction_sent = false;
        lone.set_pending_files_for_send(vec![FileMeta {
            name: "early.txt".to_string(),
            size_bytes: 1,
            mime_type: String::new(),
            payload_id: 0,
        }]);
        assert_eq!(lone.queue_introduction(), 0);
        let _ = lone.outbound_drain();
        lone.maybe_enter_ready();
        assert!(!lone.introduction_sent, "introduced before the peer's result");
    }

    #[test]
    fn connection_request_then_ukey2_then_paired_key_reaches_ready() {
        let (initiator, responder) = connected_pair();
        assert!(initiator.secure.is_some(), "initiator has no secure channel");
        assert!(responder.secure.is_some(), "responder has no secure channel");
        assert!(initiator.is_ready(), "initiator not Ready: {:?}", initiator.failed_reason.as_deref());
        assert!(responder.is_ready(), "responder not Ready: {:?}", responder.failed_reason.as_deref());
        assert_eq!(initiator.state, State::Handshaking);
    }

    #[test]
    fn initiators_first_batch_is_the_request_and_ukey2_together() {
        // The interop fix: GMS writes CONNECTION_REQUEST and then starts the UKEY2
        // client without waiting (p000\dnsi.java:9582 then :9633). An initiator that
        // blocks for a CONNECTION_RESPONSE here deadlocks against a real device.
        let mut initiator = test_session(Role::Initiator, "Alice", vec![]);
        let raw = initiator.outbound_drain().expect("initiator sends immediately");
        let frames = split_frames(&raw);
        assert_eq!(frames.len(), 2, "expected CONNECTION_REQUEST + UKEY2 ClientInit");

        let first = plaintext_offline(&frames[0]).expect("first frame is an OfflineFrame");
        assert_eq!(first.r#type, OfflineFrameType::ConnectionRequest as i32);

        // The second frame is UKEY2 message 1 and nothing else: a real UKEY2 server
        // accepts it.
        assert!(
            plaintext_offline(&frames[1]).is_none(),
            "the second frame must not be an OfflineFrame"
        );
        let mut server = new_server_handshake();
        server
            .handle_handshake_message(&frames[1])
            .expect("second frame is a UKEY2 ClientInit");

        assert!(
            !frame_types(&raw).contains(&Some(OfflineFrameType::ConnectionResponse as i32)),
            "no CONNECTION_RESPONSE may be sent before UKEY2"
        );
        assert!(initiator.outbound_drain().is_none(), "nothing else is queued");
    }

    #[test]
    fn the_connection_request_carries_the_advertised_identity() {
        // The mDNS WifiLanServiceInfo publishes this endpoint id and the `n` TXT
        // attribute publishes this endpoint info, so dialling out under a different
        // identity is what makes a peer log "Failed to parse incoming connection from
        // endpoint %s. Disconnecting." (p000\each.java:2092-2097).
        let endpoint_info =
            crate::endpoint_info::build("Alice", crate::endpoint_info::DeviceType::Phone, |b| {
                b.fill(7)
            })
            .expect("endpoint info");
        let mut initiator = Session::new(
            Role::Initiator,
            "Alice".to_string(),
            endpoint_info.clone(),
            "WXYZ".to_string(),
        );
        let raw = initiator.outbound_drain().expect("initiator sends immediately");
        let frames = split_frames(&raw);
        let offline = plaintext_offline(&frames[0]).expect("CONNECTION_REQUEST");
        let request = offline.connection_request.expect("connection_request");
        assert_eq!(request.endpoint_id, "WXYZ");
        assert_eq!(request.endpoint_info, endpoint_info);
    }

    #[test]
    fn responder_sends_no_connection_response_until_ukey2_completes() {
        let mut initiator = test_session(Role::Initiator, "Alice", vec![]);
        let mut responder = test_session(Role::Responder, "Bob", vec![]);
        let first = initiator.outbound_drain().expect("request + ClientInit");
        let frames = split_frames(&first);

        // Only the CONNECTION_REQUEST: a real peer is waiting for UKEY2 message 1 at
        // this point, so answering here is what it misparses as a Ukey2Message.
        assert_eq!(responder.feed_inbound(&frame::frame_with_length(&frames[0])), 0);
        assert!(
            responder.outbound_drain().is_none(),
            "responder must stay silent until UKEY2 message 1 arrives"
        );

        assert_eq!(responder.feed_inbound(&frame::frame_with_length(&frames[1])), 0);
        let server_init = responder.outbound_drain().expect("ServerInit");
        assert!(
            !frame_types(&server_init)
                .contains(&Some(OfflineFrameType::ConnectionResponse as i32)),
            "the response comes after UKEY2, not during it"
        );

        // ClientFinished completes the handshake, and only now is the response due.
        assert_eq!(initiator.feed_inbound(&server_init), 0);
        let client_finished = initiator.outbound_drain().expect("ClientFinished");
        assert_eq!(responder.feed_inbound(&client_finished), 0);
        assert_eq!(
            frame_types(&responder.outbound_drain().expect("CONNECTION_RESPONSE"))
                .first()
                .copied()
                .flatten(),
            Some(OfflineFrameType::ConnectionResponse as i32),
        );
    }

    #[test]
    fn connection_response_is_plaintext_and_encryption_starts_after_both() {
        let mut initiator = test_session(Role::Initiator, "Alice", vec![]);
        let mut responder = test_session(Role::Responder, "Bob", vec![]);

        // Collect, in order, what each side put on the wire across the whole handshake.
        let mut initiator_sent: Vec<Option<i32>> = Vec::new();
        let mut responder_sent: Vec<Option<i32>> = Vec::new();
        for _ in 0..32 {
            let mut moved = false;
            if let Some(out) = initiator.outbound_drain() {
                initiator_sent.extend(frame_types(&out));
                assert!(responder.feed_inbound(&out) >= 0);
                moved = true;
            }
            if let Some(out) = responder.outbound_drain() {
                responder_sent.extend(frame_types(&out));
                assert!(initiator.feed_inbound(&out) >= 0);
                moved = true;
            }
            if !moved {
                break;
            }
        }

        for (who, sent) in [("initiator", &initiator_sent), ("responder", &responder_sent)] {
            let response_at = sent
                .iter()
                .position(|t| *t == Some(OfflineFrameType::ConnectionResponse as i32))
                .unwrap_or_else(|| panic!("{who} never sent a plaintext CONNECTION_RESPONSE"));
            // Everything up to and including the response is plaintext-parseable only
            // if it is an OfflineFrame; the UKEY2 messages are not, and neither is
            // anything after the response.
            assert!(
                sent[response_at + 1..].iter().all(Option::is_none),
                "{who} sent a plaintext OfflineFrame after CONNECTION_RESPONSE"
            );
            assert!(
                sent.len() > response_at + 1,
                "{who} sent no encrypted frame after the response"
            );
        }
        assert!(initiator.is_ready() && responder.is_ready());
    }

    #[test]
    fn a_rejecting_connection_response_fails_the_session_with_the_peers_status() {
        let mut initiator = test_session(Role::Initiator, "Alice", vec![]);
        let mut responder = test_session(Role::Responder, "Bob", vec![]);
        // Run UKEY2 to completion, then answer with a rejection instead.
        let first = initiator.outbound_drain().expect("request + ClientInit");
        assert_eq!(responder.feed_inbound(&first), 0);
        let server_init = responder.outbound_drain().expect("ServerInit");
        assert_eq!(initiator.feed_inbound(&server_init), 0);
        let client_finished = initiator.outbound_drain().expect("ClientFinished");
        assert_eq!(responder.feed_inbound(&client_finished), 0);
        let _ = responder.outbound_drain();

        let reject = frame::frame_with_length(&payload::build_offline_connection_response(false));
        assert!(initiator.feed_inbound(&reject) < 0);
        assert_eq!(initiator.state, State::Failed);
        assert_eq!(
            initiator.failure_reason(),
            Some("peer rejected the connection (status 8004)"),
        );
    }

    #[test]
    fn responder_rejects_a_ukey2_message_before_the_connection_request() {
        let mut responder = test_session(Role::Responder, "Bob", vec![]);
        let junk = frame::frame_with_length(b"not an offline frame");
        assert!(responder.feed_inbound(&junk) < 0);
        assert_eq!(responder.state, State::Failed);
    }

    #[test]
    fn oversized_length_prefix_fails_the_session() {
        let mut responder = test_session(Role::Responder, "Bob", vec![]);
        assert!(responder.feed_inbound(&[0xFF, 0xFF, 0xFF, 0xFF]) < 0);
        assert_eq!(responder.state, State::Failed);
        assert!(responder.failed_reason.as_deref().unwrap_or_default().contains("oversized"));
    }

    #[test]
    fn a_senders_own_acceptance_does_not_accept_for_the_receiver() {
        // GMS emits a Sharing RESPONSE(ACCEPT) right after its INTRODUCTION to report that
        // *it* skipped local confirmation. Acting on it skips the user's prompt entirely and
        // GMS then hangs up after 60 s waiting for an accept that never comes.
        // See `Session::handle_response_status`.
        let (mut sender, mut receiver) = connected_pair();
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "photo.jpg".to_string(),
            size_bytes: 1234,
            mime_type: "image/jpeg".to_string(),
            payload_id: 0,
        }]);
        assert_eq!(sender.queue_introduction(), 0);
        settle(&mut sender, &mut receiver);
        assert_eq!(receiver.state, State::AwaitingAccept);

        // The sender's own RESPONSE(ACCEPT), replayed at the receiver.
        let response = payload::build_connection_response(true);
        assert_eq!(receiver.handle_sharing_frame(&response), 0);
        assert_eq!(
            receiver.state,
            State::AwaitingAccept,
            "receiver accepted on the sender's behalf",
        );

        // The user's own accept still works and is what moves us on.
        assert_eq!(receiver.accept(true, ""), 0);
        assert_eq!(receiver.state, State::Transferring);
    }

    #[test]
    fn introduction_and_accept_flow() {
        let (mut sender, mut receiver) = connected_pair();
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "photo.jpg".to_string(),
            size_bytes: 1234,
            mime_type: "image/jpeg".to_string(),
            payload_id: 0,
        }]);
        assert_eq!(sender.queue_introduction(), 0);
        settle(&mut sender, &mut receiver);

        assert_eq!(receiver.state, State::AwaitingAccept);
        assert_eq!(receiver.pending_files().len(), 1);
        assert_eq!(receiver.pending_files()[0].name, "photo.jpg");
        assert!(receiver.pending_files()[0].payload_id > 0);

        assert_eq!(receiver.accept(true, "/tmp"), 0);
        assert_eq!(receiver.state, State::Transferring);
        settle(&mut sender, &mut receiver);
        assert_eq!(sender.state, State::Transferring);
    }

    #[test]
    fn the_sender_completes_once_every_announced_payload_is_written() {
        // The send-side P0: Completed used to be assigned only on the receive path, so a
        // finished send span until the peer hung up and the DISCONNECTION handler then
        // reported the successful transfer as a failure.
        let (mut sender, mut receiver) = connected_pair();
        let a: Vec<u8> = (0..3000u32).map(|i| (i % 251) as u8).collect();
        let b: Vec<u8> = (0..1500u32).map(|i| (i % 249) as u8).collect();
        sender.set_pending_files_for_send(vec![
            FileMeta {
                name: "a.bin".to_string(),
                size_bytes: a.len() as u64,
                mime_type: String::new(),
                payload_id: 0,
            },
            FileMeta {
                name: "b.bin".to_string(),
                size_bytes: b.len() as u64,
                mime_type: String::new(),
                payload_id: 0,
            },
        ]);
        sender.queue_introduction();
        settle(&mut sender, &mut receiver);
        receiver.accept(true, "");
        settle(&mut sender, &mut receiver);
        assert_eq!(sender.state, State::Transferring);

        assert_eq!(sender.open_file("a.bin", a.len() as i64), 0);