//! ES9+ SM-DP+ orchestration (SGP.22 §5.6).
//!
//! The LPA relays DER blobs between the eUICC and the SM-DP+ over HTTPS. Each
//! call is an HTTP POST of a small JSON object whose binary members are base64
//! DER; the SM-DP+ replies with JSON in the same shape. Networking goes through
//! the `library:network` JNI bridge ([`jni_http`]), so no TLS is linked here.
//!
//! The eUICC performs all SGP.22 cryptography (signature generation/verification,
//! key agreement, and Bound Profile Package decryption); this module never
//! inspects or transforms the blobs it forwards.
//!
//! The HTTP calls require a live SM-DP+ and cannot be exercised in host tests;
//! only the pure URL/JSON helpers are unit-tested.

use serde_json::{json, Value};

use crate::base64;

const ES9P_PATH: &str = "/gsma/rsp2/es9plus/";

/// Result of ES9+.InitiateAuthentication.
pub struct InitiateAuthResult {
    pub transaction_id: String,
    pub server_signed1: Vec<u8>,
    pub server_signature1: Vec<u8>,
    pub euicc_ci_pkid: Vec<u8>,
    pub server_certificate: Vec<u8>,
}

/// Result of ES9+.AuthenticateClient.
pub struct AuthenticateClientResult {
    pub smdp_signed2: Vec<u8>,
    pub smdp_signature2: Vec<u8>,
    pub smdp_certificate: Vec<u8>,
}

/// InitiateAuthentication: sends the eUICC challenge + info; gets the server's
/// signed authentication material.
pub fn initiate_authentication(
    smdp: &str,
    euicc_challenge: &[u8],
    euicc_info1: &[u8],
) -> Result<InitiateAuthResult, String> {
    let body = json!({
        "smdpAddress": host_of(smdp),
        "euiccChallenge": base64::encode(euicc_challenge),
        "euiccInfo1": base64::encode(euicc_info1),
    });
    let v = post("initiateAuthentication", &endpoint(smdp, "initiateAuthentication"), &body, true)?;
    Ok(InitiateAuthResult {
        transaction_id: get_str(&v, "transactionId")?,
        server_signed1: get_b64(&v, "serverSigned1")?,
        server_signature1: get_b64(&v, "serverSignature1")?,
        euicc_ci_pkid: get_b64(&v, "euiccCiPKIdToBeUsed")?,
        server_certificate: get_b64(&v, "serverCertificate")?,
    })
}

/// AuthenticateClient: forwards the eUICC's AuthenticateServer response; gets the
/// SM-DP+'s signed profile-binding material.
pub fn authenticate_client(
    smdp: &str,
    transaction_id: &str,
    authenticate_server_response: &[u8],
) -> Result<AuthenticateClientResult, String> {
    let body = json!({
        "transactionId": transaction_id,
        "authenticateServerResponse": base64::encode(authenticate_server_response),
    });
    let v = post("authenticateClient", &endpoint(smdp, "authenticateClient"), &body, true)?;
    Ok(AuthenticateClientResult {
        smdp_signed2: get_b64(&v, "smdpSigned2")?,
        smdp_signature2: get_b64(&v, "smdpSignature2")?,
        smdp_certificate: get_b64(&v, "smdpCertificate")?,
    })
}

/// GetBoundProfilePackage: forwards the eUICC's PrepareDownload response; gets the
/// encrypted Bound Profile Package.
pub fn get_bound_profile_package(
    smdp: &str,
    transaction_id: &str,
    prepare_download_response: &[u8],
) -> Result<Vec<u8>, String> {
    let body = json!({
        "transactionId": transaction_id,
        "prepareDownloadResponse": base64::encode(prepare_download_response),
    });
    let v = post(
        "getBoundProfilePackage",
        &endpoint(smdp, "getBoundProfilePackage"),
        &body,
        true,
    )?;
    get_b64(&v, "boundProfilePackage")
}

/// HandleNotification: delivers a pending notification to the SM-DP+. Best-effort;
/// the eUICC keeps the notification until acknowledged, so a failure is not fatal.
pub fn handle_notification(smdp: &str, pending_notification: &[u8]) -> Result<(), String> {
    let body = json!({ "pendingNotification": base64::encode(pending_notification) });
    // A 204/empty body is normal here; ignore the parsed value.
    let _ = post("handleNotification", &endpoint(smdp, "handleNotification"), &body, false)?;
    Ok(())
}

// ---------------------------------------------------------------------------
// HTTP + helpers
// ---------------------------------------------------------------------------

fn post(function: &str, url: &str, body: &Value, expect_body: bool) -> Result<Value, String> {
    let headers = [
        ("Content-Type".to_string(), "application/json".to_string()),
        ("X-Admin-Protocol".to_string(), "gsma/rsp/v2.2.0".to_string()),
        ("User-Agent".to_string(), "gsma-rsp-lpad".to_string()),
        ("Accept".to_string(), "application/json".to_string()),
    ];
    let bytes = serde_json::to_vec(body).map_err(|e| format!("encode body: {e}"))?;
    let resp = jni_http::request(jni_http::Method::Post, url, &headers, Some(&bytes))
        .map_err(|e| format!("{e}"))?;
    if resp.status < 200 || resp.status >= 300 {
        return Err(format!("SM-DP+ HTTP {} at {url}", resp.status));
    }
    if resp.body.is_empty() {
        // HandleNotification answers 204/empty normally; every other function
        // must return a JSON body, so an empty reply names the function and
        // status instead of failing later on a missing field.
        if !expect_body {
            return Ok(Value::Null);
        }
        return Err(format!(
            "SM-DP+ {function} returned HTTP {} with empty body",
            resp.status
        ));
    }
    let v: Value = serde_json::from_slice(&resp.body).map_err(|e| format!("parse response: {e}"))?;
    check_function_execution_status(&v)?;
    Ok(v)
}

/// Surfaces an SM-DP+ ES9+ functionExecutionStatus error, if present.
/// Like lpac, a missing `header`/`functionExecutionStatus` object is itself an
/// error: per SGP.22 every ES9+ reply carries one, so its absence means the
/// server did not answer the function at all (rather than a field-level gap).
fn check_function_execution_status(v: &Value) -> Result<(), String> {
    let status = v
        .get("header")
        .and_then(|h| h.get("functionExecutionStatus"));
    let Some(status) = status else {
        return Err(describe_missing(v, "header.functionExecutionStatus"));
    };
    let state = status.get("status").and_then(Value::as_str).unwrap_or("");
    if state.is_empty() {
        return Err("SM-DP+ reply has functionExecutionStatus without status".to_string());
    }
    if state != "Executed-Success" {
        let reason = status
            .get("statusCodeData")
            .and_then(|d| d.get("reasonCode"))
            .and_then(Value::as_str)
            .unwrap_or("unknown");
        return Err(format!("SM-DP+ {state} ({reason})"));
    }
    Ok(())
}

/// Builds the full ES9+ endpoint URL for a function.
fn endpoint(smdp: &str, function: &str) -> String {
    format!("{}{ES9P_PATH}{function}", base_url(smdp))
}

/// Normalizes an SM-DP+ address into an `https://host[:port]` base URL.
fn base_url(smdp: &str) -> String {
    let s = smdp.trim().trim_end_matches('/');
    if s.starts_with("http://") || s.starts_with("https://") {
        s.to_string()
    } else {
        format!("https://{s}")
    }
}

/// The bare host[:port] the SM-DP+ expects in `smdpAddress`.
fn host_of(smdp: &str) -> String {
    smdp.trim()
        .trim_start_matches("https://")
        .trim_start_matches("http://")
        .trim_end_matches('/')
        .to_string()
}

fn get_str(v: &Value, key: &str) -> Result<String, String> {
    v.get(key)
        .and_then(Value::as_str)
        .map(str::to_string)
        .ok_or_else(|| describe_missing(v, key))
}

fn get_b64(v: &Value, key: &str) -> Result<Vec<u8>, String> {
    let s = get_str(v, key)?;
    base64::decode(&s).ok_or_else(|| format!("SM-DP+ '{key}' is not valid base64"))
}

/// Names what the SM-DP+ actually returned when an expected field is absent:
/// the top-level keys present (or the scalar it replied with), so the failure
/// screen shows the server's real answer instead of just the missing key.
fn describe_missing(v: &Value, key: &str) -> String {
    match v {
        Value::Object(map) => {
            let mut keys: Vec<&str> = map.keys().map(String::as_str).collect();
            keys.sort_unstable();
            format!("SM-DP+ response missing '{key}' (has: {})", keys.join(", "))
        }
        Value::Null => format!("SM-DP+ response missing '{key}' (empty reply)"),
        other => format!(
            "SM-DP+ response missing '{key}' (non-object reply: {})",
            summarize_scalar(other)
        ),
    }
}

fn summarize_scalar(v: &Value) -> String {
    match v {
        Value::String(s) => format!("string[{}]", s.len()),
        Value::Array(a) => format!("array[{}]", a.len()),
        Value::Bool(b) => format!("bool({b})"),
        Value::Number(n) => format!("number({n})"),
        _ => "null".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoint_urls() {
        assert_eq!(
            endpoint("smdp.example.com", "initiateAuthentication"),
            "https://smdp.example.com/gsma/rsp2/es9plus/initiateAuthentication",
        );
        assert_eq!(
            endpoint("https://rsp.truphone.com/", "authenticateClient"),
            "https://rsp.truphone.com/gsma/rsp2/es9plus/authenticateClient",
        );
    }

    #[test]
    fn host_normalization() {
        assert_eq!(host_of("https://smdp.example.com/"), "smdp.example.com");
        assert_eq!(host_of("smdp.example.com"), "smdp.example.com");
    }

    #[test]
    fn extract_b64_field() {
        let v = json!({ "serverSigned1": base64::encode(&[1u8, 2, 3]) });
        assert_eq!(get_b64(&v, "serverSigned1").unwrap(), vec![1, 2, 3]);
        assert!(get_b64(&v, "missing").is_err());
    }

    #[test]
    fn missing_field_names_present_keys() {
        let v = json!({ "header": {}, "other": 1 });
        let err = get_str(&v, "smdpSigned2").expect_err("must be missing");
        assert!(err.contains("smdpSigned2"), "names the missing key: {err}");
        assert!(err.contains("header"), "lists present keys: {err}");
        assert!(err.contains("other"), "lists present keys: {err}");
    }

    #[test]
    fn missing_field_on_empty_reply() {
        let err = get_str(&Value::Null, "smdpSigned2").expect_err("must be missing");
        assert!(err.contains("empty reply"), "says the reply was empty: {err}");
    }

    #[test]
    fn function_status_error_is_surfaced() {
        let v = json!({
            "header": { "functionExecutionStatus": {
                "status": "Failed",
                "statusCodeData": { "reasonCode": "8.1.1" }
            }}
        });
        assert!(check_function_execution_status(&v).is_err());
    }

    #[test]
    fn missing_status_block_is_an_error() {
        // lpac parity: a reply without header.functionExecutionStatus is not a
        // function answer at all, even if data fields happen to be present.
        let v = json!({ "smdpSigned2": "aGVsbG8=" });
        let err = check_function_execution_status(&v).expect_err("must be missing");
        assert!(err.contains("functionExecutionStatus"), "names it: {err}");
        let empty = json!({});
        assert!(check_function_execution_status(&empty).is_err());
    }
}
