//! Payload transfer: Introduction / FileMetadata / chunked PayloadTransferFrame.
//!
//! Two layers:
//! 1) Sharing introduction (grishka/NearDrop `IntroductionFrame`): the sender
//!    announces `FileMetadata[]` + `TextMetadata[]` inside a `SharingFrame`.
//!    The receiver surfaces `pendingFiles` and answers with `ConnectionResponse`
//!    (accept/reject). Both are carried inside `SecureMessage` after the
//!    UKEY2 handshake.
//! 2) Chunked payload transfer (Nearby Connections `PayloadTransferFrame`):
//!    each file/bytes payload is split into `PayloadChunk`s with offsets and
//!    a final-chunk flag. Kotlin owns the actual file I/O (SAF/MediaStore);
//!    Rust owns the sequence / offset validation and ACK generation.
//!
//! Assumptions:
//! - Max chunk body = 16 KiB (matches Nearby Connections default 16K).
//! - Payload IDs are i64 (prost uses int64). We assign 1..N for N files.
//! - Text payloads are not yet streaming-oriented; they arrive whole as a
//!   single DATA packet. File payloads are chunked.

use crate::frame::{
    IntroductionFrame, PayloadChunk, PayloadHeader, PayloadPacketType, PayloadTransferFrame, PayloadType,
    SharingConnectionResponse, SharingFileMetadata, SharingFileType, SharingFrame, SharingFrameType,
    TextMetadata,
};

pub const MAX_CHUNK: usize = 16 * 1024;
pub const FLAG_LAST: i32 = 1;

/// Metadata surfaced to Kotlin's `queryPendingFiles` JSON.
#[derive(Debug, Clone)]
pub struct FileMeta {
    pub name: String,
    pub size_bytes: u64,
    pub mime_type: String,
}

impl FileMeta {
    pub fn from_sharing(m: &SharingFileMetadata) -> Self {
        Self {
            name: m.name.clone(),
            size_bytes: m.size as u64,
            mime_type: m.mime_type.clone(),
        }
    }
}

// ---------------------------------------------------------------------------
// Build Introduction / ConnectionResponse frames (SharingFrame)
// ---------------------------------------------------------------------------

pub fn build_introduction_frame(files: &[FileMeta], seq_start_id: i64) -> Vec<u8> {
    let file_metadata = files
        .iter()
        .enumerate()
        .map(|(i, f)| SharingFileMetadata {
            name: f.name.clone(),
            size: f.size_bytes as i64,
            mime_type: f.mime_type.clone(),
            r#type: guess_file_type(&f.name, &f.mime_type) as i32,
            id: seq_start_id + i as i64,
            payload_id: (seq_start_id + i as i64).to_string(),
        })
        .collect();
    let intro = IntroductionFrame {
        file_metadata,
        text_metadata: Vec::new(),
        required_package: String::new(),
    };
    let frame = SharingFrame {
        frame_type: SharingFrameType::Introduction as i32,
        introduction: Some(intro),
        connection_response: None,
        paired_key_encryption: None,
        paired_key_result: None,
        certificate: None,
    };
    let mut buf = Vec::new();
    prost::Message::encode(&frame, &mut buf).expect("prost encode");
    buf
}

pub fn build_connection_response(accept: bool) -> Vec<u8> {
    let frame = SharingFrame {
        frame_type: SharingFrameType::ConnectionResponse as i32,
        introduction: None,
        connection_response: Some(SharingConnectionResponse {
            status: if accept { 0 } else { 1 },
        }),
        paired_key_encryption: None,
        paired_key_result: None,
        certificate: None,
    };
    let mut buf = Vec::new();
    prost::Message::encode(&frame, &mut buf).expect("prost encode");
    buf
}

pub fn parse_sharing_frame(bytes: &[u8]) -> Result<SharingFrame, prost::DecodeError> {
    prost::Message::decode(bytes)
}

pub fn parse_introduction_files(frame: &SharingFrame) -> Vec<FileMeta> {
    frame
        .introduction
        .as_ref()
        .map(|intro| intro.file_metadata.iter().map(FileMeta::from_sharing).collect())
        .unwrap_or_default()
}

fn guess_file_type(name: &str, mime: &str) -> SharingFileType {
    let lower_mime = mime.to_ascii_lowercase();
    let lower_name = name.to_ascii_lowercase();
    if lower_mime.starts_with("image/") {
        return SharingFileType::Image;
    }
    if lower_mime.starts_with("video/") {
        return SharingFileType::Video;
    }
    if lower_mime.starts_with("audio/") {
        return SharingFileType::Audio;
    }
    if lower_mime == "application/vnd.android.package-archive" || lower_name.ends_with(".apk") {
        return SharingFileType::App;
    }
    SharingFileType::File
}

// ---------------------------------------------------------------------------
// Chunked PayloadTransferFrame encode/decode helpers
// ---------------------------------------------------------------------------

/// Split `data` into chunk frames for payload `id`.
pub fn chunk_payload(id: i64, data: &[u8], file_name: &str, total_size: i64) -> Vec<PayloadTransferFrame> {
    if data.is_empty() {
        return vec![PayloadTransferFrame {
            packet_type: PayloadPacketType::Data as i32,
            payload_header: Some(PayloadHeader {
                id,
                r#type: PayloadType::File as i32,
                total_size,
                is_sensitive: false,
                file_name: file_name.to_string(),
                parent_folder: String::new(),
            }),
            payload_chunk: Some(PayloadChunk {
                offset: 0,
                body: Vec::new(),
                flags: FLAG_LAST,
            }),
            control_message: None,
        }];
    }
    let mut out = Vec::new();
    let mut offset: i64 = 0;
    // First chunk carries the header.
    let mut first = true;
    for chunk in data.chunks(MAX_CHUNK) {
        let is_last = offset as usize + chunk.len() >= data.len();
        let flags = if is_last { FLAG_LAST } else { 0 };
        out.push(PayloadTransferFrame {
            packet_type: PayloadPacketType::Data as i32,
            payload_header: if first {
                Some(PayloadHeader {
                    id,
                    r#type: PayloadType::File as i32,
                    total_size,
                    is_sensitive: false,
                    file_name: file_name.to_string(),
                    parent_folder: String::new(),
                })
            } else {
                None
            },
            payload_chunk: Some(PayloadChunk {
                offset,
                body: chunk.to_vec(),
                flags,
            }),
            control_message: None,
        });
        offset += chunk.len() as i64;
        first = false;
    }
    out
}

/// Encode a single PayloadTransferFrame to bytes.
pub fn encode_payload_frame(frame: &PayloadTransferFrame) -> Vec<u8> {
    let mut buf = Vec::new();
    prost::Message::encode(frame, &mut buf).expect("prost encode");
    buf
}

pub fn decode_payload_frame(bytes: &[u8]) -> Result<PayloadTransferFrame, prost::DecodeError> {
    prost::Message::decode(bytes)
}

#[allow(dead_code)]
fn _touch_text_metadata() {
    // Keep TextMetadata import used (for future text payload support).
    let _ = TextMetadata {
        text_title: String::new(),
        r#type: 0,
        size: 0,
        id: 0,
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn introduction_round_trip() {
        let files = vec![
            FileMeta {
                name: "a.jpg".to_string(),
                size_bytes: 100,
                mime_type: "image/jpeg".to_string(),
            },
            FileMeta {
                name: "b.mp4".to_string(),
                size_bytes: 2000,
                mime_type: "video/mp4".to_string(),
            },
        ];
        let bytes = build_introduction_frame(&files, 1);
        let frame = parse_sharing_frame(&bytes).unwrap();
        let got = parse_introduction_files(&frame);
        assert_eq!(got.len(), 2);
        assert_eq!(got[0].name, "a.jpg");
        assert_eq!(got[1].size_bytes, 2000);
    }

    #[test]
    fn chunk_and_reassemble() {
        let data: Vec<u8> = (0..40000u32).map(|i| (i % 256) as u8).collect();
        let frames = chunk_payload(1, &data, "big.bin", data.len() as i64);
        assert!(frames.len() >= 3);
        // Reassemble: headers only on first, offsets must be contiguous, last has FLAG_LAST
        let mut reassembled = Vec::new();
        for (idx, f) in frames.iter().enumerate() {
            assert_eq!(f.packet_type, PayloadPacketType::Data as i32);
            let ch = f.payload_chunk.as_ref().unwrap();
            if idx == 0 {
                assert!(f.payload_header.is_some());
            } else {
                assert!(f.payload_header.is_none());
            }
            assert_eq!(ch.offset as usize, reassembled.len());
            reassembled.extend_from_slice(&ch.body);
        }
        assert_eq!(reassembled, data);
        assert_eq!(
            frames.last().unwrap().payload_chunk.as_ref().unwrap().flags & FLAG_LAST,
            FLAG_LAST
        );
    }

    #[test]
    fn payload_frame_prost_round_trip() {
        let f = PayloadTransferFrame {
            packet_type: PayloadPacketType::Data as i32,
            payload_header: Some(PayloadHeader {
                id: 42,
                r#type: PayloadType::Bytes as i32,
                total_size: 5,
                is_sensitive: false,
                file_name: "x".to_string(),
                parent_folder: String::new(),
            }),
            payload_chunk: Some(PayloadChunk {
                offset: 0,
                body: b"hello".to_vec(),
                flags: FLAG_LAST,
            }),
            control_message: None,
        };
        let enc = encode_payload_frame(&f);
        let dec = decode_payload_frame(&enc).unwrap();
        assert_eq!(f, dec);
    }

    #[test]
    fn empty_payload_single_last_chunk() {
        let frames = chunk_payload(7, &[], "empty.bin", 0);
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].payload_chunk.as_ref().unwrap().flags, FLAG_LAST);
    }
}
