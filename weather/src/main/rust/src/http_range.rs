//! HTTP Range fetching for the `.om` decoder, over the shared JNI bridge.
//!
//! Networking stays on the platform stack (`library:network`) so this `.so` links no TLS —
//! embedding rustls here cost ~1.3 MB, a second static copy alongside the youpipe extractor.
//!
//! The bridge is flat-framed: one JNI call and one `byte[]` each way, with the class and method
//! id cached, so a per-block fetch does not pay `FindClass` or walk a result object.

use jni_http::{Header, Method};

const RANGE: &str = "Range";

/// Total size of the remote file, via a one-byte range probe.
///
/// Prefers the total from `Content-Range` (`bytes 0-0/155373816`); a server that ignores `Range`
/// answers 200, and `Content-Length` is then the whole file.
pub fn file_size(url: &str) -> Result<usize, String> {
    let headers: Vec<Header> = vec![(RANGE.into(), "bytes=0-0".into())];
    let frame = jni_http::request(Method::Get, url, &headers, None).map_err(|e| e.to_string())?;

    let from_range = frame
        .header("Content-Range")
        .and_then(|v| v.rsplit('/').next().and_then(|t| t.parse::<usize>().ok()));
    let from_length = frame.header("Content-Length").and_then(|v| v.parse::<usize>().ok());

    from_range
        .or(from_length)
        .filter(|n| *n > 0)
        .ok_or_else(|| "could not determine file size".to_string())
}

/// `len` bytes starting at `offset`.
///
/// A server that ignores `Range` returns 200 with the whole file; the requested window is sliced
/// out so callers are never handed the wrong bytes (or a 148 MB buffer).
pub fn fetch_range(url: &str, offset: u64, len: u64) -> Result<Vec<u8>, String> {
    if len == 0 {
        return Ok(Vec::new());
    }
    let end = offset + len - 1;
    let headers: Vec<Header> = vec![(RANGE.into(), format!("bytes={offset}-{end}"))];
    let frame = jni_http::request(Method::Get, url, &headers, None).map_err(|e| e.to_string())?;

    if frame.status != 206 && frame.status != 200 {
        return Err(format!("unexpected status {} for range request", frame.status));
    }

    let bytes = frame.body;
    if frame.status == 200 && bytes.len() as u64 > len {
        let from = offset as usize;
        if from >= bytes.len() {
            return Ok(Vec::new());
        }
        let to = ((offset + len) as usize).min(bytes.len());
        return Ok(bytes[from..to].to_vec());
    }
    Ok(bytes)
}
