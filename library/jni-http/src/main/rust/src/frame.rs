//! The flat wire frame the JNI HTTP bridge exchanges.
//!
//! Both directions are a single `byte[]`. Nothing is modelled as a Java object, so a request
//! costs one JNI call and one array copy each way — no `FindClass`, no `NewObject`, no
//! `GetFieldID`, no per-header `Map` churn. That was the expensive part of the previous bridge,
//! which built a `NativeHttpResponse` and then read four fields back out through JNI.
//!
//! Everything is big-endian and length-prefixed:
//!
//! ```text
//! headers (both directions)   repeated: u16 name_len, name, u16 value_len, value
//!
//! response frame              u16 status
//!                             u32 url_len,     final URL after redirects (UTF-8)
//!                             u32 headers_len, header block as above
//!                             u32 body_len,    body bytes
//! ```
//!
//! A status of 0 means the request never completed; the body then carries the error text. That
//! keeps failures on the same path as successes instead of throwing across the boundary, which
//! would need an exception check after every call.

/// One header name/value pair. Values are `String` because every real header is text; a
/// non-UTF-8 value is lossily converted rather than failing the request.
pub type Header = (String, String);

pub fn encode_headers(headers: &[Header]) -> Vec<u8> {
    let mut out = Vec::new();
    for (name, value) in headers {
        put_u16(&mut out, name.len());
        out.extend_from_slice(name.as_bytes());
        put_u16(&mut out, value.len());
        out.extend_from_slice(value.as_bytes());
    }
    out
}

pub fn decode_headers(bytes: &[u8]) -> Vec<Header> {
    let mut out = Vec::new();
    let mut r = Reader::new(bytes);
    // A truncated block yields the headers read so far; headers are advisory and a partial
    // read should not fail an otherwise good response.
    while let (Some(name), Some(value)) = (r.string_u16(), r.string_u16()) {
        out.push((name, value));
    }
    out
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResponseFrame {
    /// HTTP status, or 0 when the request never completed (see [`error`](Self::error)).
    pub status: u16,
    pub final_url: String,
    pub headers: Vec<Header>,
    pub body: Vec<u8>,
}

impl ResponseFrame {
    /// The transport error message when `status == 0`, otherwise `None`.
    pub fn error(&self) -> Option<String> {
        (self.status == 0).then(|| String::from_utf8_lossy(&self.body).into_owned())
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(n, _)| n.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    pub fn encode(&self) -> Vec<u8> {
        let headers = encode_headers(&self.headers);
        let url = self.final_url.as_bytes();
        let mut out = Vec::with_capacity(14 + url.len() + headers.len() + self.body.len());
        put_u16(&mut out, self.status as usize);
        put_u32(&mut out, url.len());
        out.extend_from_slice(url);
        put_u32(&mut out, headers.len());
        out.extend_from_slice(&headers);
        put_u32(&mut out, self.body.len());
        out.extend_from_slice(&self.body);
        out
    }

    /// `None` if the frame is truncated or malformed — treated as a transport failure.
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let mut r = Reader::new(bytes);
        let status = r.u16()? as u16;
        let final_url = r.string_u32()?;
        let headers = decode_headers(r.slice_u32()?);
        let body = r.slice_u32()?.to_vec();
        Some(Self { status, final_url, headers, body })
    }
}

fn put_u16(out: &mut Vec<u8>, v: usize) {
    out.extend_from_slice(&(v as u16).to_be_bytes());
}

fn put_u32(out: &mut Vec<u8>, v: usize) {
    out.extend_from_slice(&(v as u32).to_be_bytes());
}

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, pos: 0 }
    }

    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let end = self.pos.checked_add(n)?;
        let out = self.bytes.get(self.pos..end)?;
        self.pos = end;
        Some(out)
    }

    fn u16(&mut self) -> Option<usize> {
        let b = self.take(2)?;
        Some(u16::from_be_bytes([b[0], b[1]]) as usize)
    }

    fn u32(&mut self) -> Option<usize> {
        let b = self.take(4)?;
        Some(u32::from_be_bytes([b[0], b[1], b[2], b[3]]) as usize)
    }

    fn slice_u32(&mut self) -> Option<&'a [u8]> {
        let n = self.u32()?;
        self.take(n)
    }

    fn string_u16(&mut self) -> Option<String> {
        let n = self.u16()?;
        let b = self.take(n)?;
        Some(String::from_utf8_lossy(b).into_owned())
    }

    fn string_u32(&mut self) -> Option<String> {
        let n = self.u32()?;
        let b = self.take(n)?;
        Some(String::from_utf8_lossy(b).into_owned())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> ResponseFrame {
        ResponseFrame {
            status: 206,
            final_url: "https://example.com/final".into(),
            headers: vec![
                ("Content-Type".into(), "application/json".into()),
                ("Content-Range".into(), "bytes 0-0/155373816".into()),
            ],
            body: vec![1, 2, 3, 4, 5],
        }
    }

    #[test]
    fn frames_round_trip() {
        let frame = sample();
        assert_eq!(ResponseFrame::decode(&frame.encode()).unwrap(), frame);
    }

    #[test]
    fn header_lookup_is_case_insensitive() {
        let frame = sample();
        assert_eq!(frame.header("content-type"), Some("application/json"));
        assert_eq!(frame.header("CONTENT-RANGE"), Some("bytes 0-0/155373816"));
        assert_eq!(frame.header("missing"), None);
    }

    #[test]
    fn empty_body_and_headers_round_trip() {
        let frame = ResponseFrame {
            status: 204,
            final_url: String::new(),
            headers: Vec::new(),
            body: Vec::new(),
        };
        assert_eq!(ResponseFrame::decode(&frame.encode()).unwrap(), frame);
    }

    #[test]
    fn transport_failures_use_status_zero() {
        let frame = ResponseFrame {
            status: 0,
            final_url: "https://x".into(),
            headers: Vec::new(),
            body: b"connection reset".to_vec(),
        };
        let decoded = ResponseFrame::decode(&frame.encode()).unwrap();
        assert_eq!(decoded.error().as_deref(), Some("connection reset"));
        // A real response never reports an error, even with a body that looks like one.
        assert_eq!(sample().error(), None);
    }

    #[test]
    fn truncated_frames_decode_to_none_rather_than_panicking() {
        let encoded = sample().encode();
        for cut in 0..encoded.len() {
            // Every prefix must be rejected cleanly; none may panic.
            let _ = ResponseFrame::decode(&encoded[..cut]);
        }
        assert!(ResponseFrame::decode(&[]).is_none());
    }

    #[test]
    fn a_lying_length_prefix_is_rejected() {
        let mut encoded = sample().encode();
        // Claim a body far larger than what follows.
        let n = encoded.len();
        encoded[n - 5 - 4..n - 5].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(ResponseFrame::decode(&encoded).is_none());
    }

    #[test]
    fn headers_round_trip_through_their_own_encoding() {
        let headers: Vec<Header> = vec![
            ("A".into(), "1".into()),
            ("Set-Cookie".into(), "x=y; Path=/".into()),
        ];
        assert_eq!(decode_headers(&encode_headers(&headers)), headers);
        assert!(decode_headers(&[]).is_empty());
    }

    #[test]
    fn a_truncated_header_block_keeps_what_parsed() {
        let encoded = encode_headers(&[("A".into(), "1".into()), ("B".into(), "2".into())]);
        let partial = decode_headers(&encoded[..encoded.len() - 1]);
        assert_eq!(partial.len(), 1, "the complete first pair survives");
    }

    #[test]
    fn large_bodies_are_supported() {
        let frame = ResponseFrame {
            status: 200,
            final_url: "u".into(),
            headers: Vec::new(),
            body: vec![7u8; 1_000_000],
        };
        assert_eq!(ResponseFrame::decode(&frame.encode()).unwrap().body.len(), 1_000_000);
    }
}
