#[cfg(test)]
mod tests2 {
    use super::*;

    #[test]
    fn a_truncated_send_does_not_complete() {
        // Fewer bytes than announced means no FLAG_LAST went out, so the peer is still
        // waiting: reporting success here would be a lie the UI cannot walk back.
        let (mut sender, mut receiver) = connected_pair();
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "short.bin".to_string(),
            size_bytes: 4000,
            mime_type: String::new(),
            payload_id: 0,
        }]);
        sender.queue_introduction();
        settle(&mut sender, &mut receiver);
        receiver.accept(true, "");
        settle(&mut sender, &mut receiver);

        assert_eq!(sender.open_file("short.bin", 4000), 0);
        assert_eq!(sender.write_chunk(&[7u8; 100]), 0);
        sender.close_file();
        assert_eq!(sender.state, State::Transferring);
    }

    #[test]
    fn reject_fails_the_sender() {
        let (mut sender, mut receiver) = connected_pair();
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "a.bin".to_string(),
            size_bytes: 1,
            mime_type: String::new(),
            payload_id: 0,
        }]);
        sender.queue_introduction();
        settle(&mut sender, &mut receiver);
        receiver.accept(false, "");
        // Drain by hand: `settle` asserts no side fails, and this one must.
        let out = receiver.outbound_drain().expect("reject frame");
        sender.feed_inbound(&out);
        assert_eq!(sender.state, State::Failed);
        assert_eq!(sender.failed_reason.as_deref(), Some("peer rejected the transfer"));
    }

    #[test]
    fn introduction_queued_before_ready_is_deferred_not_dropped() {
        let mut sender = test_session(Role::Initiator, "Alice", vec![]);
        let mut receiver = test_session(Role::Responder, "Bob", vec![]);
        // Queue while still in Connecting.
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "early.txt".to_string(),
            size_bytes: 3,
            mime_type: "text/plain".to_string(),
            payload_id: 0,
        }]);
        assert_eq!(sender.queue_introduction(), 0);
        settle(&mut sender, &mut receiver);
        assert_eq!(receiver.state, State::AwaitingAccept);
        assert_eq!(receiver.pending_files()[0].name, "early.txt");
    }

    #[test]
    fn chunked_transfer_is_keyed_by_the_announced_payload_id() {
        let (mut sender, mut receiver) = connected_pair();
        let data: Vec<u8> = (0..5000u32).map(|i| (i % 251) as u8).collect();
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "a.bin".to_string(),
            size_bytes: data.len() as u64,
            mime_type: String::new(),
            payload_id: 0,
        }]);
        sender.queue_introduction();
        settle(&mut sender, &mut receiver);
        let announced = receiver.pending_files()[0].payload_id;
        receiver.accept(true, "/tmp");
        settle(&mut sender, &mut receiver);

        assert_eq!(sender.open_file("a.bin", data.len() as i64), 0);
        for chunk in data.chunks(1024) {
            assert_eq!(sender.write_chunk(chunk), 0);
        }
        sender.close_file();
        settle(&mut sender, &mut receiver);

        let records = drain_all_received(&mut receiver);
        assert!(!records.is_empty(), "nothing was queued for Kotlin");
        assert!(
            records.iter().all(|r| r.payload_id == announced),
            "payload must land under the id announced in the INTRODUCTION"
        );
        assert!(records.iter().all(|r| r.name == "a.bin"));
        assert!(records.iter().all(|r| r.total_size == data.len() as i64));
        let mut reassembled = Vec::new();
        for r in &records {
            assert_eq!(r.offset as usize, reassembled.len(), "offsets are contiguous");
            reassembled.extend_from_slice(&r.body);
        }
        assert_eq!(reassembled, data);
        assert_eq!(
            records.iter().filter(|r| r.last).count(),
            1,
            "exactly one record carries the last-chunk flag"
        );
        assert!(records.last().expect("last record").last);
        assert_eq!(receiver.state, State::Completed);
    }

    #[test]
    fn a_streamed_payload_is_never_retained_in_the_session() {
        // Constant memory: the body lives in the drain queue and nowhere else, so a
        // caller that drains as it pumps holds one chunk at a time regardless of the
        // file size. `ActiveRecv` has no body field for it to accumulate into.
        let (mut sender, mut receiver) = connected_pair();
        let data: Vec<u8> = (0..200_000u32).map(|i| (i % 253) as u8).collect();
        sender.set_pending_files_for_send(vec![FileMeta {
            name: "big.bin".to_string(),
            size_bytes: data.len() as u64,
            mime_type: String::new(),
            payload_id: 0,
        }]);
        sender.queue_introduction();
        settle(&mut sender, &mut receiver);
        receiver.accept(true, "");
        settle(&mut sender, &mut receiver);

        assert_eq!(sender.open_file("big.bin", data.len() as i64), 0);
        let mut reassembled = Vec::new();
        for chunk in data.chunks(4096) {
            assert_eq!(sender.write_chunk(chunk), 0);
            let out = sender.outbound_drain().expect("chunk frames");
            assert!(receiver.feed_inbound(&out) >= 0);
            for r in drain_all_received(&mut receiver) {
                reassembled.extend_from_slice(&r.body);
            }
            assert!(
                receiver.received_queue.is_empty(),
                "draining must empty the queue"
            );
        }
        sender.close_file();
        assert_eq!(reassembled, data);
        assert_eq!(receiver.state, State::Completed);
        assert!(receiver.drain_received().is_none());
    }

    #[test]
    fn received_record_layout_is_stable() {
        let record = ReceivedChunk {
            payload_id: 7,
            offset: 2,
            total_size: 5,
            last: true,
            name: "a.txt".to_string(),
            body: b"xyz".to_vec(),
        };
        let raw = encode_received_record(&record);
        assert_eq!(
            raw,
            [
                // version
                0x01,
                // payload_id = 7
                0, 0, 0, 0, 0, 0, 0, 7,
                // offset = 2
                0, 0, 0, 0, 0, 0, 0, 2,
                // total_size = 5
                0, 0, 0, 0, 0, 0, 0, 5,
                // flags = last
                0x01,
                // name_len = 5, "a.txt"
                0x00, 0x05, b'a', b'.', b't', b'x', b't',
                // body_len = 3, "xyz"
                0x00, 0x00, 0x00, 0x03, b'x', b'y', b'z',
            ],
        );
        let decoded = decode_received_record(&raw);
        assert_eq!(decoded.payload_id, 7);
        assert_eq!(decoded.offset, 2);
        assert_eq!(decoded.total_size, 5);
        assert!(decoded.last);
        assert_eq!(decoded.name, "a.txt");
        assert_eq!(decoded.body, b"xyz");
    }

    #[test]
    fn keep_alive_is_acked() {
        let (mut a, mut b) = connected_pair();
        assert_eq!(a.send_keep_alive(), 0);
        let out = a.outbound_drain().expect("keep alive");
        assert_eq!(b.feed_inbound(&out), 0);
        // The peer must answer with an ack rather than ignoring it.
        let ack = b.outbound_drain().expect("keep alive ack");
        assert_eq!(a.feed_inbound(&ack), 0);
        assert_eq!(a.state, State::Handshaking);
    }
}
