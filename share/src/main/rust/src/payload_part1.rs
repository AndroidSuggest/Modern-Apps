/// Split `data` into `PayloadTransferFrame`s for the FILE payload `id`, starting at
/// `start_offset` within the payload.
///
/// Every frame repeats the header and carries a chunk of at most [`MAX_CHUNK`]
/// bytes; the frame that reaches `total_size` sets [`FLAG_LAST`]. Empty `data`
/// still produces one frame so a zero-length file terminates.
pub fn chunk_payload(
    id: i64,
    data: &[u8],
    file_name: &str,
    total_size: i64,
    start_offset: i64,
) -> Vec<PayloadTransferFrame> {
    let header = payload_header(id, PayloadType::File, total_size, file_name);
    let frame_for = |offset: i64, body: &[u8]| {
        let is_last = offset.saturating_add(body.len() as i64) >= total_size;
        PayloadTransferFrame {
            packet_type: PayloadPacketType::Data as i32,
            payload_header: Some(header.clone()),
            payload_chunk: Some(PayloadChunk {
                flags: Some(if is_last { FLAG_LAST } else { 0 }),
                offset: Some(offset),
                body: Some(body.to_vec()),
            }),
            control_message: None,
        }
    };
    if data.is_empty() {
        return vec![frame_for(start_offset, &[])];
    }
    let mut out = Vec::new();
    let mut offset = start_offset;
    for chunk in data.chunks(MAX_CHUNK) {
        out.push(frame_for(offset, chunk));
        offset = offset.saturating_add(chunk.len() as i64);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn zeros(buf: &mut [u8]) {
        buf.fill(0);
    }

    #[test]
    fn introduction_carries_payload_ids_and_document_type() {
        let files = vec![
            FileMeta {
                name: "a.jpg".to_string(),
                size_bytes: 100,
                mime_type: "image/jpeg".to_string(),
                payload_id: 0,
            },
            FileMeta {
                name: "notes".to_string(),
                size_bytes: 2000,
                mime_type: String::new(),
                payload_id: 0,
            },
        ];
        let bytes = build_introduction_frame(&files, 5);
        let frame = parse_sharing_frame(&bytes).expect("decode");
        assert_eq!(frame.version, SharingVersion::V1 as i32);
        let v1 = frame.v1.expect("v1");
        assert_eq!(v1.r#type, SharingFrameType::Introduction as i32);
        let intro = v1.introduction.expect("introduction");
        assert!(intro.start_transfer);
        assert_eq!(intro.use_case, ShareUseCase::NearbyShare as i32);

        let got = introduction_files(&intro);
        assert_eq!(got.len(), 2);
        assert_eq!(got[0].payload_id, 5);
        assert_eq!(got[1].payload_id, 6);
        // An absent mime_type must read back as the proto2 default, not "".
        assert_eq!(got[1].mime_type, DEFAULT_MIME_TYPE);
        assert_eq!(
            intro.file_metadata[0].r#type,
            SharingFileType::Image as i32
        );
        assert_eq!(
            intro.file_metadata[1].r#type,
            SharingFileType::Document as i32,
            "the catch-all type is DOCUMENT(5)"
        );
    }

    #[test]
    fn accept_is_status_one_reject_is_two() {
        for (accept, want) in [(true, SharingResponseStatus::Accept), (false, SharingResponseStatus::Reject)] {
            let frame = parse_sharing_frame(&build_connection_response(accept)).expect("decode");
            let v1 = frame.v1.expect("v1");
            assert_eq!(v1.r#type, SharingFrameType::Response as i32);
            assert_eq!(
                v1.connection_response.expect("response").status,
                want as i32
            );
        }
    }

    #[test]
    fn paired_key_decoy_has_the_right_shape() {
        let frame = parse_sharing_frame(&build_paired_key_encryption_decoy(zeros)).expect("decode");
        let v1 = frame.v1.expect("v1");
        assert_eq!(v1.r#type, SharingFrameType::PairedKeyEncryption as i32);
        let pk = v1.paired_key_encryption.expect("paired_key_encryption");
        assert_eq!(pk.signed_data.len(), SIGNED_DATA_DECOY_LEN);
        assert_eq!(pk.secret_id_hash.len(), SECRET_ID_HASH_LEN);
        assert!(pk.optional_signed_data.is_empty());
        assert!(pk.qr_code_handshake_data.is_empty());
    }

    #[test]
    fn paired_key_result_is_unable() {
        let frame =
            parse_sharing_frame(&build_paired_key_result(PairedKeyResultStatus::Unable)).expect("decode");
        let v1 = frame.v1.expect("v1");
        assert_eq!(v1.r#type, SharingFrameType::PairedKeyResult as i32);
        assert_eq!(
            v1.paired_key_result.expect("result").status,
            PairedKeyResultStatus::Unable as i32
        );
    }

    #[test]
    fn sharing_frame_travels_as_a_bytes_payload() {
        let inner = build_connection_response(true);
        let frames = bytes_payload(3, &inner);
        assert_eq!(frames.len(), 2, "a data chunk and its terminator");

        let offline = parse_offline_frame(&frames[0]).expect("decode");
        let v1 = offline.v1.expect("v1");
        assert_eq!(v1.r#type, OfflineFrameType::PayloadTransfer as i32);
        let pt = v1.payload_transfer.expect("payload_transfer");
        let header = pt.payload_header.expect("header");
        assert_eq!(header.r#type, PayloadType::Bytes as i32);
        assert_eq!(header.id, 3);
        assert_eq!(header.total_size, inner.len() as i64);
        let chunk = pt.payload_chunk.expect("chunk");
        // The body travels on a chunk that is NOT flagged last: a real device ignores a
        // flagged chunk's body, so carrying both drops the frame silently.
        assert_eq!(chunk.flags() & FLAG_LAST, 0);
        // Present, not merely zero: GMS rejects a DATA frame whose chunk omits `flags`
        // outright — see `frame::PayloadChunk`.
        assert_eq!(chunk.flags, Some(0), "flags must be on the wire even when zero");
        assert_eq!(chunk.offset, Some(0), "offset must be on the wire even when zero");
        assert_eq!(chunk.body(), inner);

        let term = parse_offline_frame(&frames[1]).expect("decode terminator");
        let term_pt = term.v1.expect("v1").payload_transfer.expect("payload_transfer");
        assert_eq!(term_pt.payload_header.expect("header").id, 3);
        let term_chunk = term_pt.payload_chunk.expect("chunk");
        assert_eq!(term_chunk.flags() & FLAG_LAST, FLAG_LAST);
        assert_eq!(term_chunk.offset(), inner.len() as i64);
        assert!(term_chunk.body().is_empty());
    }

    #[test]
    fn connection_request_offers_only_wifi_lan() {
        let offline = parse_offline_frame(&build_connection_request("AB12", "Pixel", b"info", 42))
            .expect("decode");
        let v1 = offline.v1.expect("v1");
        assert_eq!(v1.r#type, OfflineFrameType::ConnectionRequest as i32);
        let req = v1.connection_request.expect("request");
        assert_eq!(req.endpoint_id, "AB12");
        assert_eq!(req.endpoint_name, "Pixel");
        assert_eq!(req.endpoint_info, b"info");
        assert_eq!(req.nonce, 42);
        assert_eq!(req.mediums, vec![ConnectionsMedium::WifiLan as i32]);
        // GMS's builder always populates both keep-alive fields (p000\dnlw.java:305).
        assert_eq!(req.keep_alive_interval_millis, KEEP_ALIVE_INTERVAL_MILLIS);
        assert_eq!(req.keep_alive_timeout_millis, KEEP_ALIVE_TIMEOUT_MILLIS);
    }

    #[test]
    fn offline_connection_response_golden_bytes() {
        // Both status fields, as p000\dnlx.java:1039-1051 writes them. `status = 0`
        // must be present on the wire, not merely defaulted: with field 3 absent
        // p000\dnsi.java:6911 reads an absent status as a rejection.
        let accept = build_offline_connection_response(true);
        assert_eq!(
            accept,
            [
                0x08, 0x01, // OfflineFrame.version = V1
                0x12, 0x08, // OfflineFrame.v1, 8 bytes
                0x08, 0x02, // V1Frame.type = CONNECTION_RESPONSE
                0x1A, 0x04, // V1Frame.connection_response, 4 bytes
                0x08, 0x00, // status = 0, written explicitly
                0x18, 0x01, // response = ACCEPT
            ],
            "accept: status 0 and response 1",
        );
        let reject = build_offline_connection_response(false);
        assert_eq!(
            reject,
            [
                0x08, 0x01, // OfflineFrame.version = V1
                0x12, 0x09, // OfflineFrame.v1, 9 bytes
                0x08, 0x02, // V1Frame.type = CONNECTION_RESPONSE
                0x1A, 0x05, // V1Frame.connection_response, 5 bytes
                0x08, 0xC4, 0x3E, // status = 8004
                0x18, 0x02, // response = REJECT
            ],
            "reject: status 8004 and response 2",
        );

        let parsed = parse_offline_frame(&accept).expect("decode");
        let response = parsed
            .v1
            .expect("v1")
            .connection_response
            .expect("connection_response");
        assert!(response.accepted());
        assert!(
            !parse_offline_frame(&reject)
                .expect("decode")
                .v1
                .expect("v1")
                .connection_response
                .expect("connection_response")
                .accepted()
        );
    }

    #[test]
    fn a_response_with_neither_status_nor_field_three_is_a_rejection() {
        // GMS's rule, verbatim (p000\dnsi.java:6911): with field 3 absent, acceptance
        // needs field 1 explicitly written. An empty frame is not an accept.
        assert!(!crate::frame::OfflineConnectionResponseFrame::default().accepted());
    }

    #[test]
    fn keep_alive_round_trips_through_offline_frame() {
        let offline = parse_offline_frame(&build_keep_alive(false, 7)).expect("decode");
        let v1 = offline.v1.expect("v1");
        assert_eq!(v1.r#type, OfflineFrameType::KeepAlive as i32);
        let ka = v1.keep_alive.expect("keep_alive");
        assert!(!ka.ack);
        assert_eq!(ka.seq_num, 7);
    }

    #[test]
    fn chunk_and_reassemble() {
        let data: Vec<u8> = (0..40000u32).map(|i| (i % 256) as u8).collect();
        let frames = chunk_payload(1, &data, "big.bin", data.len() as i64, 0);
        assert!(frames.len() >= 3);
        let mut reassembled = Vec::new();
        for f in frames.iter() {
            assert_eq!(f.packet_type, PayloadPacketType::Data as i32);
            let ch = f.payload_chunk.as_ref().expect("chunk");
            assert!(f.payload_header.is_some(), "every DATA frame repeats the header");
            assert_eq!(ch.offset() as usize, reassembled.len());
            reassembled.extend_from_slice(ch.body());
        }
        assert_eq!(reassembled, data);
        let last = frames.last().expect("last").payload_chunk.as_ref().expect("chunk");
        assert_eq!(last.flags() & FLAG_LAST, FLAG_LAST);
    }

    #[test]
    fn empty_payload_single_last_chunk() {
        let frames = chunk_payload(7, &[], "empty.bin", 0, 0);
        assert_eq!(frames.len(), 1);
        let chunk = frames[0].payload_chunk.as_ref().expect("chunk");
        assert_eq!(chunk.flags, Some(FLAG_LAST));
        assert_eq!(
            frames[0].payload_header.as_ref().expect("header").r#type,
            PayloadType::File as i32
        );
    }

    #[test]
    fn vcard_is_a_contact_card() {
        let files = vec![FileMeta {
            name: "me.vcf".to_string(),
            size_bytes: 10,
            mime_type: "text/vcard".to_string(),
            payload_id: 0,
        }];
        let frame = parse_sharing_frame(&build_introduction_frame(&files, 1)).expect("decode");
        let intro = frame.v1.expect("v1").introduction.expect("intro");
        assert_eq!(
            intro.file_metadata[0].r#type,
            SharingFileType::ContactCard as i32
        );
    }
}
