/// `PayloadHeader.PayloadType`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum PayloadType {
    /// Unset.
    Unknown = 0,
    /// An in-band byte blob. Sharing `Frame`s travel as this.
    Bytes = 1,
    /// A file streamed in chunks.
    File = 2,
    /// A stream payload (not implemented).
    Stream = 3,
}

/// `PayloadChunk` — `p000\ivlk.java`: `1 flags:int32`, `2 offset:int64`,
/// `3 body:bytes`, `4 index:int32`.
///
/// Every field has a hasbit, and here presence is **load-bearing**. The info string at
/// `p000\ivlk.java:73` types them `င`/`ဂ`/`ည`/`င` = `0x1004, 0x1002, 0x100A, 0x1004`; the
/// `0x1000` bit is explicit presence. A first chunk carries `flags = 0`, so emitting it as a
/// bare `int32` drops it from the wire and GMS rejects the whole frame:
///
/// ```text
/// iuun: OfflineFrame PAYLOAD_TRANSFER(DATA) missing flags field.
/// PayloadManager failed to retrieve Payload 219401524532613 for chunk at offset 90, discarding.
/// ```
///
/// The data chunk is discarded, so when the `FLAG_LAST` terminator arrives there is no
/// payload to attach it to and it is discarded too — the Sharing layer never sees the frame
/// and the peer eventually times out with `AUTH_FAILURE`. Measured against a Pixel 7 Pro on
/// GMS 26.24.34. `rquickshare` likewise sets `flags: Some(0)` and `offset: Some(0)`
/// (`core_lib/src/hdl/inbound.rs::send_encrypted_frame`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadChunk {
    /// p000\ivlk.java field 1, INT32 with a hasbit. Bit 0 is the last-chunk flag.
    #[prost(int32, optional, tag = "1")]
    pub flags: Option<i32>,
    /// p000\ivlk.java field 2, INT64 with a hasbit.
    #[prost(int64, optional, tag = "2")]
    pub offset: Option<i64>,
    /// p000\ivlk.java field 3, BYTES with a hasbit.
    #[prost(bytes = "vec", optional, tag = "3")]
    pub body: Option<Vec<u8>>,
}

impl PayloadChunk {
    /// True when this chunk closes the payload.
    pub fn is_last(&self) -> bool {
        self.flags() & 1 != 0
    }
}

/// `PayloadTransferFrame.ControlMessage`.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ControlMessage {
    /// Control event kind.
    #[prost(enumeration = "ControlEventType", tag = "1")]
    pub event: i32,
    /// Offset the event refers to.
    #[prost(int64, tag = "2")]
    pub offset: i64,
}

/// `ControlMessage.EventType`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum ControlEventType {
    /// Unset.
    Unknown = 0,
    /// Sender or receiver cancelled the payload.
    PayloadCanceled = 1,
    /// The payload failed.
    PayloadError = 2,
    /// The payload finished.
    PayloadCompleted = 3,
}

#[cfg(test)]
mod tests {
    use super::*;
    use prost::Message;

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02X}")).collect()
    }

    #[test]
    fn length_prefix_is_four_byte_big_endian() {
        // 13 bytes of payload must be announced as 00 00 00 0D, not as a varint 0x0D.
        let payload = b"hello sharing";
        let framed = frame_with_length(payload);
        assert_eq!(&framed[..4], &[0x00, 0x00, 0x00, 0x0D]);
        assert_eq!(framed.len(), 4 + payload.len());

        // 300 bytes: 00 00 01 2C. A varint prefix would have been AC 02.
        let big = vec![0u8; 300];
        assert_eq!(&frame_with_length(&big)[..4], &[0x00, 0x00, 0x01, 0x2C]);
    }

    #[test]
    fn length_prefix_round_trip() {
        let payload = b"hello sharing";
        let mut buf = frame_with_length(payload);
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Frame(payload.to_vec()));
        assert!(buf.is_empty());
    }

    #[test]
    fn partial_read_split_mid_prefix() {
        let payload = vec![0xABu8; 200];
        let framed = frame_with_length(&payload);

        // Two of the four prefix bytes: still Incomplete, buffer untouched.
        let mut buf = framed[..2].to_vec();
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Incomplete);
        assert_eq!(buf.len(), 2);

        // Whole prefix but only part of the body.
        buf.extend_from_slice(&framed[2..10]);
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Incomplete);
        assert_eq!(buf.len(), 10);

        buf.extend_from_slice(&framed[10..]);
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Frame(payload));
        assert!(buf.is_empty());
    }

    #[test]
    fn two_frames_back_to_back() {
        let mut buf = frame_with_length(b"one");
        buf.extend_from_slice(&frame_with_length(b"two"));
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Frame(b"one".to_vec()));
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Frame(b"two".to_vec()));
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Incomplete);
    }

    #[test]
    fn negative_and_oversized_lengths_are_rejected() {
        // High bit set = negative int32, which p000\dnhn.java:348 refuses.
        let mut buf = vec![0xFF, 0xFF, 0xFF, 0xFF];
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Invalid);
        assert_eq!(buf.len(), 4, "buffer must not be drained on Invalid");

        let mut too_big = ((MAX_FRAME_LEN + 1) as u32).to_be_bytes().to_vec();
        assert_eq!(try_consume_frame(&mut too_big), ConsumeResult::Invalid);
    }

    #[test]
    fn zero_length_frame_is_valid() {
        let mut buf = frame_with_length(&[]);
        assert_eq!(try_consume_frame(&mut buf), ConsumeResult::Frame(Vec::new()));
    }

    #[test]
    fn file_metadata_golden_bytes() {
        // Golden encoding pins the tag numbers. A round-trip test cannot: it passes
        // against any self-consistent (including wrong) tag assignment.
        let meta = SharingFileMetadata {
            name: "a.txt".to_string(),
            r#type: SharingFileType::Document as i32,
            payload_id: 7,
            size: 300,
            mime_type: "text/plain".to_string(),
            ..Default::default()
        };
        // field 1 (name)      : tag 0x0A, len 5, "a.txt"
        // field 2 (type)      : tag 0x10, varint 5   (DOCUMENT)
        // field 3 (payload_id): tag 0x18, varint 7
        // field 4 (size)      : tag 0x20, varint 300 = AC 02
        // field 5 (mime_type) : tag 0x2A, len 10, "text/plain"
        let want = "0A05612E7478741005180720AC022A0A746578742F706C61696E";
        assert_eq!(hex(&meta.encode_to_vec()), want);
    }

    #[test]
    fn connection_response_accept_encodes_status_one() {
        let accept = SharingConnectionResponseFrame {
            status: SharingResponseStatus::Accept as i32,
        };
        // field 1 varint 1 => 08 01. The old code emitted 00 bytes (status 0 is the
        // proto3 default and is not serialised at all), i.e. nothing.
        assert_eq!(hex(&accept.encode_to_vec()), "0801");

        let reject = SharingConnectionResponseFrame {
            status: SharingResponseStatus::Reject as i32,
        };
        assert_eq!(hex(&reject.encode_to_vec()), "0802");
    }

    #[test]
    fn sharing_frame_nesting_golden_bytes() {
        let frame = SharingFrame {
            version: SharingVersion::V1 as i32,
            v1: Some(SharingV1Frame {
                r#type: SharingFrameType::Introduction as i32,
                introduction: Some(IntroductionFrame {
                    required_package: "x".to_string(),
                    start_transfer: true,
                    use_case: ShareUseCase::NearbyShare as i32,
                    ..Default::default()
                }),
                ..Default::default()
            }),
        };
        // Frame.version = 1                         -> 08 01
        // Frame.v1 (len 11)                         -> 12 0B
        //   V1Frame.type = INTRODUCTION(1)          -> 08 01
        //   V1Frame.introduction (len 7)            -> 12 07
        //     required_package = "x"                -> 1A 01 78
        //     start_transfer = true                 -> 30 01
        //     use_case = NEARBY_SHARE(1)            -> 40 01
        assert_eq!(hex(&frame.encode_to_vec()), "0801120B080112071A017830014001");

        let back = SharingFrame::decode(frame.encode_to_vec().as_slice()).expect("decode");
        assert_eq!(back, frame);
    }

    #[test]
    fn paired_key_encryption_field_order() {
        // secret_id_hash is field 2 (tag 0x12) and optional_signed_data is field 3
        // (tag 0x1A), per p000\dzrr.java:12-21. Getting these two the wrong way round
        // is invisible to a round-trip test.
        let frame = PairedKeyEncryptionFrame {
            signed_data: vec![0x01],
            secret_id_hash: vec![0x02],
            optional_signed_data: vec![0x03],
            qr_code_handshake_data: vec![0x04],
        };
        assert_eq!(hex(&frame.encode_to_vec()), "0A01011201021A0103220104");
    }

    #[test]
    fn offline_frame_wraps_payload_transfer() {
        let offline = OfflineFrame {
            version: OfflineVersion::V1 as i32,
            v1: Some(OfflineV1Frame {
                r#type: OfflineFrameType::PayloadTransfer as i32,
                payload_transfer: Some(PayloadTransferFrame {
                    packet_type: PayloadPacketType::Data as i32,
                    payload_header: Some(PayloadHeader {
                        id: 9,
                        r#type: PayloadType::Bytes as i32,
                        total_size: 5,
                        ..Default::default()
                    }),
                    payload_chunk: Some(PayloadChunk {
                        flags: Some(1),
                        offset: Some(0),
                        body: Some(b"hello".to_vec()),
                    }),
                    ..Default::default()
                }),
                ..Default::default()
            }),
        };
        let back = OfflineFrame::decode(offline.encode_to_vec().as_slice()).expect("decode");
        assert_eq!(back, offline);
        // payload_transfer is field 4 of V1Frame -> tag 0x22.
        assert!(hex(&offline.encode_to_vec()).contains("22"));
    }

    #[test]
    fn connection_request_mediums_are_unpacked() {
        let req = ConnectionRequestFrame {
            endpoint_id: "ABCD".to_string(),
            endpoint_name: "n".to_string(),
            mediums: vec![ConnectionsMedium::WifiLan as i32],
            ..Default::default()
        };
        // field 1 "ABCD"  -> 0A 04 41 42 43 44
        // field 2 "n"     -> 12 01 6E
        // field 5 unpacked varint 5 -> 28 05  (packed would be 2A 01 05)
        assert_eq!(hex(&req.encode_to_vec()), "0A044142434412016E2805");
    }

    #[test]
    fn text_and_wifi_metadata_start_at_field_two() {
        let text = TextMetadata {
            text_title: "t".to_string(),
            ..Default::default()
        };
        // text_title is field 2 -> tag 0x12, not 0x0A.
        assert_eq!(hex(&text.encode_to_vec()), "120174");

        let wifi = WifiCredentialsMetadata {
            ssid: "s".to_string(),
            security_type: WifiSecurityType::WpaPsk as i32,
            ..Default::default()
        };
        // ssid field 2 -> 12 01 73; security_type field 3 -> 18 02
        assert_eq!(hex(&wifi.encode_to_vec()), "1201731802");
    }

    #[test]
    fn keep_alive_round_trip() {
        let ka = KeepAliveFrame { ack: true, seq_num: 3 };
        assert_eq!(hex(&ka.encode_to_vec()), "08011003");
        assert_eq!(
            KeepAliveFrame::decode(ka.encode_to_vec().as_slice()).expect("decode"),
            ka
        );
    }
}
