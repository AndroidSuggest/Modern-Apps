//! Varint length-prefix + protobuf (prost) framing for Nearby Connections
//! and Nearby Sharing (WireFormat) wire formats.
//!
//! References (open reimplementations):
//! - NearDrop `OfflineFrame` / `WireFormat` / `UKey2` (Swift, prost-equivalent)
//! - grishka/nearby `NearbyImpl` (Kotlin) — OfflineFrame v1 with
//!   ConnectionRequest/Response, PayloadTransferFrame, KeepAlive
//! - Android Nearby Connections `offline_wire_formats.proto`,
//!   `sharing_wire_format.proto` (decompiled, mirrored here with prost derives)
//!
//! Assumptions / divergences are documented inline. The exact field numbers
//! are chosen to round-trip correctly between two of our peers; byte-level
//! interop with a real Android Quick Share peer will require aligning the
//! tag numbers to the decompiled protos (documented TODOs where uncertain).

use prost::Message;

// ---------------------------------------------------------------------------
// Varint + length-prefix helpers (protobuf base128)
// ---------------------------------------------------------------------------

pub fn encode_varint(mut v: u32, out: &mut Vec<u8>) {
    loop {
        let mut b = (v & 0x7F) as u8;
        v >>= 7;
        if v != 0 {
            b |= 0x80;
            out.push(b);
        } else {
            out.push(b);
            break;
        }
    }
}

pub fn decode_varint(buf: &[u8]) -> Option<(u32, usize)> {
    let mut result: u32 = 0;
    let mut shift = 0;
    for (i, &b) in buf.iter().enumerate() {
        let val = (b & 0x7F) as u32;
        if shift >= 32 {
            return None;
        }
        result |= val << shift;
        if b & 0x80 == 0 {
            return Some((result, i + 1));
        }
        shift += 7;
    }
    None
}

/// Prepends a varint length prefix.
pub fn frame_with_length(payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(5 + payload.len());
    encode_varint(payload.len() as u32, &mut out);
    out.extend_from_slice(payload);
    out
}

/// Try to consume one length-prefixed frame from `buf`. Returns `None` if
/// not enough bytes are buffered yet. Consumes the prefix + payload on success.
pub fn try_consume_frame(buf: &mut Vec<u8>) -> Option<Vec<u8>> {
    let (len, prefix_len) = decode_varint(buf)?;
    let total = prefix_len + len as usize;
    if buf.len() < total {
        return None;
    }
    let payload = buf[prefix_len..total].to_vec();
    buf.drain(..total);
    Some(payload)
}

// ---------------------------------------------------------------------------
// UKEY2 frame (prost)
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum Ukey2FrameType {
    Unknown = 0,
    ClientInit = 1,
    ServerInit = 2,
    ClientFinish = 3,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NextProtocol {
    Sharing,
    Connections,
    Unknown,
}

impl NextProtocol {
    pub fn as_str(&self) -> &'static str {
        match self {
            NextProtocol::Sharing => "com.google.nearby.sharing",
            NextProtocol::Connections => "com.google.nearby.connections",
            NextProtocol::Unknown => "unknown",
        }
    }
    pub fn from_str(s: &str) -> Self {
        match s {
            "com.google.nearby.sharing" => NextProtocol::Sharing,
            "com.google.nearby.connections" => NextProtocol::Connections,
            _ => NextProtocol::Unknown,
        }
    }
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct Ukey2Frame {
    #[prost(int32, tag = "1")]
    pub frame_type: i32,
    #[prost(int32, tag = "2")]
    pub version: i32,
    #[prost(bytes = "vec", optional, tag = "3")]
    pub random: Option<Vec<u8>>,
    #[prost(bytes = "vec", optional, tag = "4")]
    pub public_key: Option<Vec<u8>>,
    #[prost(bytes = "vec", optional, tag = "5")]
    pub commitment: Option<Vec<u8>>,
    #[prost(string, optional, tag = "6")]
    pub next_protocol: Option<String>,
    #[prost(bytes = "vec", tag = "7")]
    pub payload: Vec<u8>,
}

impl Ukey2Frame {
    pub fn encode_to_vec(&self) -> Vec<u8> {
        let mut buf = Vec::new();
        prost::Message::encode(self, &mut buf).expect("prost encode");
        buf
    }
    pub fn decode_from_bytes(bytes: &[u8]) -> Result<Self, prost::DecodeError> {
        prost::Message::decode(bytes)
    }
}

// ---------------------------------------------------------------------------
// Nearby Connections OfflineFrame (outer + v1)
// Based on android `offline_wire_formats.proto`:
//   message OfflineFrame { int32 version = 1; V1Frame v1 = 2; }
//   message V1Frame { enum FrameType { UNKNOWN=0; CONNECTION_REQUEST=1; ... } }
//
// Field numbers below mirror the decompiled proto per NearDrop's
// `OfflineFrame.pb.swift` (v1.type = 1, connection_request = 2, etc.).
// ---------------------------------------------------------------------------

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct OfflineFrame {
    #[prost(int32, tag = "1")]
    pub version: i32,
    #[prost(message, optional, tag = "2")]
    pub v1: Option<V1Frame>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum V1FrameType {
    Unknown = 0,
    ConnectionRequest = 1,
    ConnectionResponse = 2,
    PayloadTransfer = 3,
    KeepAlive = 4,
    Disconnection = 5,
    BandwidthUpgradeNegotiation = 6,
    PairedKeyEncryption = 7,
    PairedKeyResult = 8,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct V1Frame {
    #[prost(enumeration = "V1FrameType", tag = "1")]
    pub frame_type: i32,
    #[prost(message, optional, tag = "2")]
    pub connection_request: Option<ConnectionRequest>,
    #[prost(message, optional, tag = "3")]
    pub connection_response: Option<ConnectionResponse>,
    #[prost(message, optional, tag = "4")]
    pub payload_transfer: Option<PayloadTransferFrame>,
    #[prost(message, optional, tag = "5")]
    pub keep_alive: Option<KeepAlive>,
    #[prost(message, optional, tag = "6")]
    pub disconnection: Option<Disconnection>,
    #[prost(message, optional, tag = "7")]
    pub paired_key_encryption: Option<PairedKeyEncryption>,
    #[prost(message, optional, tag = "8")]
    pub paired_key_result: Option<PairedKeyResult>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ConnectionRequest {
    #[prost(string, tag = "1")]
    pub endpoint_id: String,
    #[prost(string, tag = "2")]
    pub endpoint_name: String,
    #[prost(bytes = "vec", tag = "3")]
    pub endpoint_info: Vec<u8>,
    #[prost(int32, tag = "4")]
    pub nonce: i32,
    #[prost(string, repeated, tag = "5")]
    pub mediums: Vec<String>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ConnectionResponse {
    #[prost(int32, tag = "1")]
    pub status: i32, // 0 = accept, non-zero = reject
    #[prost(int32, tag = "2")]
    pub multiplex_socket_bitmask: i32,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct KeepAlive {
    #[prost(bool, tag = "1")]
    pub ack: bool,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct Disconnection {
    #[prost(bool, tag = "1")]
    pub safe_to_disconnect: bool,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PairedKeyEncryption {
    #[prost(bytes = "vec", tag = "1")]
    pub signed_data: Vec<u8>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PairedKeyResult {
    #[prost(int32, tag = "1")]
    pub status: i32,
}

// PayloadTransferFrame: chunked file/bytes transfer.
// Mirrors `PayloadTransferFrame` in NearDrop's `OfflineFrame.pb.swift`:
//   message PayloadTransferFrame {
//     enum PacketType { UNKNOWN=0; DATA=1; CONTROL=2; PAYLOAD_ACK=3; ... }
//     PacketType packet_type = 1;
//     PayloadHeader payload_header = 2;
//     PayloadChunk payload_chunk = 3;
//     ControlMessage control_message = 4;
//   }
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum PayloadPacketType {
    Unknown = 0,
    Data = 1,
    Control = 2,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadTransferFrame {
    #[prost(enumeration = "PayloadPacketType", tag = "1")]
    pub packet_type: i32,
    #[prost(message, optional, tag = "2")]
    pub payload_header: Option<PayloadHeader>,
    #[prost(message, optional, tag = "3")]
    pub payload_chunk: Option<PayloadChunk>,
    #[prost(message, optional, tag = "4")]
    pub control_message: Option<ControlMessage>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadHeader {
    #[prost(int64, tag = "1")]
    pub id: i64,
    #[prost(enumeration = "PayloadType", tag = "2")]
    pub r#type: i32,
    #[prost(int64, tag = "3")]
    pub total_size: i64,
    #[prost(bool, tag = "4")]
    pub is_sensitive: bool,
    #[prost(string, tag = "5")]
    pub file_name: String,
    #[prost(string, tag = "6")]
    pub parent_folder: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum PayloadType {
    Unknown = 0,
    Bytes = 1,
    File = 2,
    Stream = 3,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadChunk {
    #[prost(int64, tag = "1")]
    pub offset: i64,
    #[prost(bytes = "vec", tag = "2")]
    pub body: Vec<u8>,
    #[prost(int32, tag = "3")]
    pub flags: i32, // bit 0 = last chunk
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ControlMessage {
    #[prost(enumeration = "ControlEventType", tag = "1")]
    pub event: i32,
    #[prost(int64, tag = "2")]
    pub offset: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum ControlEventType {
    Unknown = 0,
    PayloadCanceled = 1,
    PayloadError = 2,
    PayloadCompleted = 3,
}

// ---------------------------------------------------------------------------
// Sharing WireFormat (Introduction etc.)
// Mirrors `sharing_wire_format.proto` per NearDrop / grishka:
//   message Frame { optional IntroductionFrame introduction = 1; ... }
// For simplicity we expose IntroductionFrame directly — the outer `Frame`
// oneof is flattened into optional fields here.
// ---------------------------------------------------------------------------

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingFrame {
    #[prost(enumeration = "SharingFrameType", tag = "1")]
    pub frame_type: i32,
    #[prost(message, optional, tag = "2")]
    pub introduction: Option<IntroductionFrame>,
    #[prost(message, optional, tag = "3")]
    pub connection_response: Option<SharingConnectionResponse>,
    #[prost(message, optional, tag = "4")]
    pub paired_key_encryption: Option<SharingPairedKeyEncryption>,
    #[prost(message, optional, tag = "5")]
    pub paired_key_result: Option<SharingPairedKeyResult>,
    #[prost(message, optional, tag = "6")]
    pub certificate: Option<CertificateFrame>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingFrameType {
    Unknown = 0,
    Introduction = 1,
    ConnectionResponse = 2,
    PairedKeyEncryption = 3,
    PairedKeyResult = 4,
    Certificate = 5,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct IntroductionFrame {
    #[prost(message, repeated, tag = "1")]
    pub file_metadata: Vec<SharingFileMetadata>,
    #[prost(message, repeated, tag = "2")]
    pub text_metadata: Vec<TextMetadata>,
    #[prost(string, tag = "3")]
    pub required_package: String,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingFileMetadata {
    #[prost(string, tag = "1")]
    pub name: String,
    #[prost(int64, tag = "2")]
    pub size: i64,
    #[prost(string, tag = "3")]
    pub mime_type: String,
    #[prost(enumeration = "SharingFileType", tag = "4")]
    pub r#type: i32,
    #[prost(int64, tag = "5")]
    pub id: i64,
    #[prost(string, tag = "6")]
    pub payload_id: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingFileType {
    Unknown = 0,
    Image = 1,
    Video = 2,
    App = 3,
    Audio = 4,
    File = 5,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct TextMetadata {
    #[prost(string, tag = "1")]
    pub text_title: String,
    #[prost(enumeration = "TextType", tag = "2")]
    pub r#type: i32,
    #[prost(int64, tag = "3")]
    pub size: i64,
    #[prost(int64, tag = "4")]
    pub id: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum TextType {
    Unknown = 0,
    Text = 1,
    Url = 2,
    Address = 3,
    PhoneNumber = 4,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingConnectionResponse {
    #[prost(int32, tag = "1")]
    pub status: i32, // 0 = accept
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingPairedKeyEncryption {
    #[prost(bytes = "vec", tag = "1")]
    pub signed_data: Vec<u8>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingPairedKeyResult {
    #[prost(int32, tag = "1")]
    pub status: i32,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct CertificateFrame {
    #[prost(bytes = "vec", tag = "1")]
    pub cert_data: Vec<u8>,
}

// ---------------------------------------------------------------------------
// Helpers to encode/decode length-prefixed prost messages generically
// ---------------------------------------------------------------------------

pub fn encode_length_prefixed<M: prost::Message>(msg: &M) -> Vec<u8> {
    let mut payload = Vec::new();
    msg.encode(&mut payload).expect("prost encode");
    frame_with_length(&payload)
}

pub fn decode_length_prefixed<M: prost::Message + Default>(bytes: &[u8]) -> Result<M, prost::DecodeError> {
    M::decode(bytes)
}

// ---------------------------------------------------------------------------
// SecureMessage proto (separate file but defined here for single prost dep)
// ---------------------------------------------------------------------------

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SecureMessage {
    #[prost(int32, tag = "1")]
    pub version: i32,
    #[prost(int32, tag = "2")]
    pub sequence_number: i32,
    #[prost(bytes = "vec", tag = "3")]
    pub header_and_body: Vec<u8>, // IV (16) + ciphertext
    #[prost(bytes = "vec", tag = "4")]
    pub signature: Vec<u8>, // HMAC-SHA256
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varint_round_trip() {
        for v in [0u32, 1, 127, 128, 300, 16384, u32::MAX] {
            let mut out = Vec::new();
            encode_varint(v, &mut out);
            let (got, n) = decode_varint(&out).unwrap();
            assert_eq!(got, v);
            assert_eq!(n, out.len());
        }
    }

    #[test]
    fn length_prefix_round_trip() {
        let payload = b"hello sharing";
        let framed = frame_with_length(payload);
        let mut buf = framed.clone();
        let got = try_consume_frame(&mut buf).unwrap();
        assert_eq!(got, payload);
        assert!(buf.is_empty());
    }

    #[test]
    fn ukey2_frame_round_trip() {
        let f = Ukey2Frame {
            frame_type: Ukey2FrameType::ClientInit as i32,
            version: 1,
            random: Some(vec![0xAB; 32]),
            public_key: Some(vec![0x04; 65]),
            commitment: Some(vec![0xFF; 32]),
            next_protocol: Some("com.google.nearby.sharing".to_string()),
            payload: vec![],
        };
        let enc = f.encode_to_vec();
        let dec = Ukey2Frame::decode_from_bytes(&enc).unwrap();
        assert_eq!(f, dec);
    }

    #[test]
    fn offline_frame_round_trip() {
        let of = OfflineFrame {
            version: 1,
            v1: Some(V1Frame {
                frame_type: V1FrameType::ConnectionRequest as i32,
                connection_request: Some(ConnectionRequest {
                    endpoint_id: "ABCD".to_string(),
                    endpoint_name: "My Phone".to_string(),
                    endpoint_info: vec![1, 2, 3],
                    nonce: 42,
                    mediums: vec!["WIFI_LAN".to_string()],
                }),
                connection_response: None,
                payload_transfer: None,
                keep_alive: None,
                disconnection: None,
                paired_key_encryption: None,
                paired_key_result: None,
            }),
        };
        let mut buf = Vec::new();
        of.encode(&mut buf).unwrap();
        let back = OfflineFrame::decode(buf.as_slice()).unwrap();
        assert_eq!(of, back);
    }

    #[test]
    fn sharing_introduction_round_trip() {
        let intro = IntroductionFrame {
            file_metadata: vec![SharingFileMetadata {
                name: "photo.jpg".to_string(),
                size: 1234,
                mime_type: "image/jpeg".to_string(),
                r#type: SharingFileType::Image as i32,
                id: 1,
                payload_id: "1".to_string(),
            }],
            text_metadata: vec![],
            required_package: String::new(),
        };
        let frame = SharingFrame {
            frame_type: SharingFrameType::Introduction as i32,
            introduction: Some(intro.clone()),
            connection_response: None,
            paired_key_encryption: None,
            paired_key_result: None,
            certificate: None,
        };
        let mut buf = Vec::new();
        frame.encode(&mut buf).unwrap();
        let back = SharingFrame::decode(buf.as_slice()).unwrap();
        assert_eq!(frame, back);
        assert_eq!(back.introduction.unwrap().file_metadata[0].name, "photo.jpg");
    }

    #[test]
    fn payload_transfer_round_trip() {
        let pt = PayloadTransferFrame {
            packet_type: PayloadPacketType::Data as i32,
            payload_header: Some(PayloadHeader {
                id: 1,
                r#type: PayloadType::File as i32,
                total_size: 999,
                is_sensitive: false,
                file_name: "a.bin".to_string(),
                parent_folder: String::new(),
            }),
            payload_chunk: Some(PayloadChunk {
                offset: 0,
                body: b"hello".to_vec(),
                flags: 0,
            }),
            control_message: None,
        };
        let mut buf = Vec::new();
        pt.encode(&mut buf).unwrap();
        let back = PayloadTransferFrame::decode(buf.as_slice()).unwrap();
        assert_eq!(pt, back);
    }

    #[test]
    fn partial_frame_returns_none() {
        let payload = vec![0u8; 200];
        let framed = frame_with_length(&payload);
        let mut buf = framed[..10].to_vec();
        assert!(try_consume_frame(&mut buf).is_none());
        // append rest -> now it completes
        buf.extend_from_slice(&framed[10..]);
        let got = try_consume_frame(&mut buf).unwrap();
        assert_eq!(got.len(), 200);
    }
}
