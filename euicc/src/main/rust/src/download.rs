//! Profile download orchestration (SGP.22 Common Mutual Authentication +
//! Profile Download and Installation).
//!
//! Ties the eUICC-side ES10b commands ([`crate::es10b`]) to the SM-DP+-side ES9+
//! calls ([`crate::es9p`]). The LPA only relays DER blobs: the eUICC verifies the
//! server, agrees keys, decrypts the Bound Profile Package, and installs it; the
//! SM-DP+ produces the signed material. This module sequences the exchange.
//!
//! The full flow requires a live SM-DP+, a real eUICC, and a platform-signed
//! install, so it cannot be exercised in host tests. The pure pieces are:
//! [`parse_activation_code`], [`hash_confirmation_code`], and
//! [`es10b::extract_transaction_id`].

use jni::JNIEnv;

use crate::jni::store_data;
use crate::{asn1, es10, es10b, es9p};

/// A parsed SGP.22 activation code (`LPA:1$smdp$matchingId[$oid][$flag]`).
///
/// Validation mirrors lpac's `download.c:applet_main`: the format field must be
/// `"1"`, the matching id is `alnum|-` only, and a confirmation-required flag
/// without a separately supplied code is a local error — fail fast instead of
/// sending a malformed order to the SM-DP+.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ActivationCode {
    /// SM-DP+ FQDN (no scheme).
    pub smdp: String,
    /// Matching ID for this download.
    pub matching_id: String,
    /// Whether the SM-DP+ requires a confirmation code.
    pub confirmation_code_required: bool,
}

/// Parses `LPA:1$smdp.example.com$MATCHINGID[$OID][$1]` (the `LPA:` prefix and
/// trailing fields are optional).
pub fn parse_activation_code(code: &str) -> Result<ActivationCode, String> {
    let body = code.trim().strip_prefix("LPA:").unwrap_or(code.trim());
    let parts: Vec<&str> = body.split('$').collect();
    if parts.len() < 3 {
        return Err("Activation code must be LPA:1$smdp$matchingId".to_string());
    }
    if parts[0].trim() != "1" {
        return Err(format!(
            "Unsupported activation code format '{}' (expected '1')",
            parts[0].trim()
        ));
    }
    let smdp = parts[1].trim();
    let matching_id = parts[2].trim();
    if smdp.is_empty() {
        return Err("Activation code is missing the SM-DP+ address".to_string());
    }
    if matching_id.is_empty() {
        return Err("Activation code is missing the matching ID".to_string());
    }
    if !matching_id
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || b == b'-')
    {
        return Err(
            "Matching ID has an invalid format (only letters, digits and '-' are allowed)"
                .to_string(),
        );
    }
    if !is_plausible_smdp(smdp) {
        return Err(format!("SM-DP+ address '{smdp}' does not look like a hostname"));
    }
    let confirmation_code_required = parts.get(4).map(|s| s.trim() == "1").unwrap_or(false);
    Ok(ActivationCode {
        smdp: smdp.to_string(),
        matching_id: matching_id.to_string(),
        confirmation_code_required,
    })
}

/// Minimal FQDN sanity check for the SM-DP+ address (lpac leaves a bad address
/// to fail at TLS; we fail fast with the offending value named).
fn is_plausible_smdp(smdp: &str) -> bool {
    let host = smdp
        .trim()
        .trim_start_matches("https://")
        .trim_start_matches("http://")
        .trim_end_matches('/')
        .split(':')
        .next()
        .unwrap_or("");
    !host.is_empty()
        && host.contains('.')
        && host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'.')
        && !host.starts_with('.')
        && !host.starts_with('-')
        && !host.ends_with('.')
}

/// `hashCc = SHA256(SHA256(code) || transactionIdBin)`, where `transactionIdBin`
/// is tag `0x80` inside `smdpSigned2` — mirroring lpac's
/// `es10b_prepare_download_r`.
pub fn hash_confirmation_code(code: &str, transaction_id: &[u8]) -> [u8; 32] {
    use sha2::{Digest, Sha256};
    let inner = Sha256::digest(code.as_bytes());
    let mut outer = Sha256::new();
    outer.update(inner);
    outer.update(transaction_id);
    outer.finalize().into()
}

/// The key bytes of a CI public-key identifier field, unwrapping one DER TLV
/// layer when the server sends the full TLV rather than the bare value.
fn pkid_value(field: &[u8]) -> &[u8] {
    asn1::parse(field)
        .map(|(tlv, _)| tlv.value)
        .unwrap_or(field)
}

/// Whether the server's chosen CI key appears in the eUICC's verification list
/// (hex of the raw field or of its unwrapped value).
fn ci_key_known(verification_list: &[String], field: &[u8]) -> bool {
    let raw_hex = es10::hex(field);
    let inner_hex = es10::hex(pkid_value(field));
    verification_list
        .iter()
        .any(|k| *k == raw_hex || *k == inner_hex)
}

/// Runs the full download for an activation code, driving the eUICC over the
/// already-open ISD-R channel (via [`store_data`]) and the SM-DP+ over HTTP.
///
/// Thin wrapper over the split-phase session below for callers that do not
/// need progress, metadata preview, or confirmation-code resume.
pub fn download_profile(
    env: &mut JNIEnv,
    activation_code: &str,
) -> Result<es10b::InstallResult, String> {
    download_profile_with_options(env, activation_code, &DownloadOptions::default())
}

/// Download inputs beyond the activation code itself.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct DownloadOptions {
    /// Confirmation code for `ccRequiredFlag` profiles (`None` when the profile
    /// does not need one or the code is not known yet).
    pub confirmation_code: Option<String>,
    /// Device TAC (4 bytes). Defaults to [`es10b::DEFAULT_TAC`] like lpac.
    pub tac: Option<[u8; 4]>,
    /// Device IMEI digits for `ctxParams1` (user-editable, optional).
    pub imei: Option<String>,
}

/// Progress reporting for the BPP segment loop. Returning `false` aborts the
/// download (the caller is expected to cancel the server session).
///
/// `env` is passed per call (rather than stored in the callback) so JNI
/// progress sinks do not alias the eUICC channel's `&mut JNIEnv` borrow.
pub trait ProgressCallback {
    /// Called with `done`/`total` segments transmitted. Returns whether to continue.
    fn on_progress(&mut self, env: &mut JNIEnv, done: usize, total: usize) -> bool;
}

/// A no-op progress sink for the atomic entry point.
struct NoProgress;
impl ProgressCallback for NoProgress {
    fn on_progress(&mut self, _env: &mut JNIEnv, _done: usize, _total: usize) -> bool {
        true
    }
}

/// One in-flight download session: everything the LPA learns before touching
/// the eUICC's profile store, so the UI can preview the carrier, confirm, and
/// collect a confirmation code before any install happens.
///
/// Mirrors the lpac-jni `downloadProfile` state split
/// (Preparing → Connecting → Authenticating → ConfirmingDownload →
/// Downloading → Finalizing).
pub struct DownloadSession {
    /// Parsed activation code.
    pub activation: ActivationCode,
    /// SM-DP+ transaction id (JSON string form, for ES9+ calls).
    pub transaction_id: String,
    /// Binary transaction id (tag `0x80` inside `serverSigned1`), for `hashCc`
    /// and CancelSession.
    pub transaction_id_bin: Vec<u8>,
    /// Carrier preview parsed from `profileMetadata`.
    pub metadata: es10b::ProfileMetadata,
    /// Whether the SM-DP+ demands a confirmation code (`ccRequiredFlag`).
    pub cc_required: bool,
    /// Options captured at authenticate time (TAC/IMEI/confirmation code).
    options: DownloadOptions,
    server_signed2: Vec<u8>,
    server_signature2: Vec<u8>,
    server_certificate2: Vec<u8>,
}

/// Authenticates both sides up to `AuthenticateClient` and returns the session
/// plus carrier preview — no eUICC profile-store write has happened yet, so
/// cancelling here costs nothing (the server session should still be freed via
/// [`cancel_session`]).
pub fn authenticate(
    env: &mut JNIEnv,
    activation_code: &str,
    options: &DownloadOptions,
) -> Result<DownloadSession, String> {
    let ac = parse_activation_code(activation_code)?;

    // 1. eUICC challenge + info for the server's authentication.
    let challenge = es10b::parse_euicc_challenge(
        &store_data(env, &es10b::build_get_euicc_challenge())
            .map_err(|e| format!("GetEUICCChallenge: {e}"))?,
    )?;
    let euicc_info1 = store_data(env, &es10::build_get_euicc_info1())
        .map_err(|e| format!("GetEUICCInfo1: {e}"))?;

    // 2. Server authentication material.
    let r1 = es9p::initiate_authentication(&ac.smdp, &challenge, &euicc_info1)?;

    // The eUICC can only verify the server against a CI public key it holds;
    // otherwise AuthenticateServer fails with SW 6A88. Compare the server's
    // chosen key against the eUICC's own verification list first so the failure
    // names the key instead of surfacing as a bare status word. PKIds are
    // public key identifiers, safe to report. Skip the check when the eUICC
    // gave no list (never block a download on a parser gap).
    if let Ok(info) = es10::parse_euicc_info1(&euicc_info1) {
        if !info.ci_pkid_verification.is_empty()
            && !ci_key_known(&info.ci_pkid_verification, &r1.euicc_ci_pkid)
        {
            return Err(format!(
                "server uses CI key {} unknown to this eUICC (eUICC trusts: {})",
                es10::hex(&pkid_value(&r1.euicc_ci_pkid)),
                info.ci_pkid_verification.join(", "),
            ));
        }
    }

    // 3. eUICC authenticates the server and signs its own material. TAC
    // defaults to lpac's constant; IMEI is user-supplied and optional.
    let tac = options.tac.unwrap_or(es10b::DEFAULT_TAC);
    let ctx = es10b::build_ctx_params1(&ac.matching_id, &tac, options.imei.as_deref());
    let auth_req = es10b::build_authenticate_server(
        &r1.server_signed1,
        &r1.server_signature1,
        &r1.euicc_ci_pkid,
        &r1.server_certificate,
        &ctx,
    );
    let auth_resp = store_data(env, &auth_req).map_err(|e| format!("AuthenticateServer: {e}"))?;

    // Binary transaction id for hashCc / CancelSession (the JSON string form
    // stays the ES9+ correlator).
    let transaction_id_bin = es10b::extract_transaction_id(&r1.server_signed1)?;

    // 4. SM-DP+ binds the profile to this eUICC.
    let r2 = es9p::authenticate_client(&ac.smdp, &r1.transaction_id, &auth_resp)?;

    let metadata = es10b::parse_profile_metadata(&r2.profile_metadata)?;
    let cc_required = ac.confirmation_code_required || es10b::cc_required(&r2.smdp_signed2);

    Ok(DownloadSession {
        activation: ac,
        transaction_id: r1.transaction_id,
        transaction_id_bin,
        metadata,
        cc_required,
        options: options.clone(),
        server_signed2: r2.smdp_signed2,
        server_signature2: r2.smdp_signature2,
        server_certificate2: r2.smdp_certificate,
    })
}

/// Finishes an authenticated session: PrepareDownload (with `hashCc` when a
/// confirmation code is present), BPP fetch, and segment install with progress.
///
/// Aborts on the first failing segment (like lpac's per-`tx` BF37 check) and
/// reports the typed `bppCommandId/errorReason` instead of cascading one
/// failure into every later segment.
pub fn finish_download(
    env: &mut JNIEnv,
    session: &DownloadSession,
    confirmation_code: Option<&str>,
    progress: &mut dyn ProgressCallback,
) -> Result<es10b::InstallResult, String> {
    // 5. eUICC prepares to receive the profile.
    let code = confirmation_code
        .filter(|c| !c.is_empty())
        .or(session.options.confirmation_code.as_deref());
    if session.cc_required && code.is_none() {
        return Err(
            "This profile needs a confirmation code (ccRequiredFlag is set) — supply it and retry"
                .to_string(),
        );
    }
    let tx_bin = es10b::extract_transaction_id(&session.server_signed2)?;
    let hash = code.map(|c| hash_confirmation_code(c, &tx_bin));
    let hash_ref = hash.as_ref().map(|h| &h[..]);
    let prep_req = es10b::build_prepare_download(
        &session.server_signed2,
        &session.server_signature2,
        hash_ref,
        &session.server_certificate2,
    );
    let prep_resp = store_data(env, &prep_req).map_err(|e| format!("PrepareDownload: {e}"))?;

    // 6. Fetch the (encrypted) Bound Profile Package.
    let bpp = es9p::get_bound_profile_package(
        &session.activation.smdp,
        &session.transaction_id,
        &prep_resp,
    )?;

    // 7. Stream the BPP into the eUICC segment by segment, aborting on the
    // first error response. The last segment returns the
    // ProfileInstallationResult — but a mid-stream BF37 error must stop the
    // loop immediately so one failure does not cascade.
    let segments = es10b::segment_bpp(&bpp)?;
    let total = segments.len();
    let mut last = Vec::new();
    for (i, segment) in segments.iter().enumerate() {
        last = store_data(env, segment)
            .map_err(|e| format!("LoadBoundProfilePackage segment {}/{total}: {e}", i + 1))?;
        if !progress.on_progress(env, i + 1, total) {
            return Err("Download cancelled".to_string());
        }
        // Intermediate BF37 error results abort the stream (lpac parity): a
        // typed mid-stream failure must stop the loop, not cascade.
        if i + 1 < total {
            if let Ok(probe) = es10b::parse_install_result(&last) {
                if !probe.success
                    && (probe.bpp_command_id.is_some() || probe.error_reason.is_some())
                {
                    return Err(probe.message);
                }
            }
        }
    }
    let result = es10b::parse_install_result(&last)?;

    // 8. Deliver the install notification (best-effort): retrieve the pending
    // notification to learn its delivery address, post it there, then remove
    // it from the eUICC list — the lpac `notification process` sequence.
    deliver_notification(env, &session.activation.smdp, result.seq_number, &result.notification);

    Ok(result)
}

/// Retrieve → handle → remove for one install notification. Best-effort: the
/// eUICC keeps the notification until acknowledged, so delivery failure must
/// not fail the install itself.
fn deliver_notification(env: &mut JNIEnv, smdp: &str, seq_number: i64, fallback: &[u8]) {
    let Ok(seq) = u32::try_from(seq_number.max(0)) else {
        return;
    };
    let Ok(retrieved) = store_data(env, &es10b::build_retrieve_notification(seq))
        .and_then(|resp| es10b::parse_retrieved_notification(&resp))
    else {
        // Fall back to posting the raw result to the download server.
        let _ = es9p::handle_notification(smdp, fallback);
        return;
    };
    let (address, pending) = retrieved;
    let target = if address.is_empty() { smdp } else { address.as_str() };
    if es9p::handle_notification_at(target, &pending).is_ok() {
        let _ = store_data(env, &es10::build_remove_notification(seq))
            .and_then(|resp| es10::parse_remove_result(&resp));
    }
}

/// Frees an authenticated-but-unfinished server session (user cancel,
/// metadata mismatch, confirmation timeout). ES10 CancelSession first, then
/// ES9+ cancelSession — mirroring lpac-download's error path
/// (`es10b_cancel_session` + `es9p_cancel_session`). Best-effort.
pub fn cancel_session(env: &mut JNIEnv, session: &DownloadSession, reason: es10b::CancelReason) {
    let es10_req = es10b::build_cancel_session(&session.transaction_id_bin, reason);
    let cancel_resp = store_data(env, &es10_req).unwrap_or_default();
    let _ = es9p::cancel_session(&session.activation.smdp, &session.transaction_id, &cancel_resp);
}

/// Full download with options: authenticate → prepare (with `hashCc`) →
/// fetch → install with progress → notify.
pub fn download_profile_with_options(
    env: &mut JNIEnv,
    activation_code: &str,
    options: &DownloadOptions,
) -> Result<es10b::InstallResult, String> {
    let session = authenticate(env, activation_code, options)?;
    let mut sink = NoProgress;
    finish_download(env, &session, None, &mut sink)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_full_activation_code() {
        let ac = parse_activation_code("LPA:1$smdp.example.com$MATCH-123").unwrap();
        assert_eq!(ac.smdp, "smdp.example.com");
        assert_eq!(ac.matching_id, "MATCH-123");
        assert!(!ac.confirmation_code_required);
    }

    #[test]
    fn parses_confirmation_code_flag() {
        let ac = parse_activation_code("1$rsp.truphone.com$QR-ABC$$1").unwrap();
        assert_eq!(ac.smdp, "rsp.truphone.com");
        assert_eq!(ac.matching_id, "QR-ABC");
        assert!(ac.confirmation_code_required);
    }

    #[test]
    fn rejects_short_codes() {
        assert!(parse_activation_code("LPA:1$smdp.example.com").is_err());
        assert!(parse_activation_code("garbage").is_err());
        assert!(parse_activation_code("LPA:1$smdp.example.com$").is_err());
    }

    #[test]
    fn rejects_bad_format_version() {
        assert!(parse_activation_code("LPA:2$smdp.example.com$ABC").is_err());
    }

    #[test]
    fn rejects_bad_matching_id_charset() {
        // lpac parity: alnum and '-' only.
        assert!(parse_activation_code("LPA:1$smdp.example.com$ABC_DEF").is_err());
        assert!(parse_activation_code("LPA:1$smdp.example.com$ABC DEF").is_err());
        assert!(parse_activation_code("LPA:1$smdp.example.com$ABC-123").is_ok());
    }

    #[test]
    fn rejects_implausible_smdp() {
        assert!(parse_activation_code("LPA:1$notahost$ABC-123").is_err());
        assert!(parse_activation_code("LPA:1$$ABC-123").is_err());
    }

    #[test]
    fn ci_key_match_accepts_bare_and_wrapped_forms() {
        let list = vec!["aabbcc".to_string()];
        assert!(ci_key_known(&list, &[0xAA, 0xBB, 0xCC]));
        // Full DER TLV around the same value also matches.
        let wrapped = crate::asn1::tlv(0x04, &[0xAA, 0xBB, 0xCC]);
        assert!(ci_key_known(&list, &wrapped));
        assert!(!ci_key_known(&list, &[0xDD]));
        assert!(!ci_key_known(&[], &[0xAA, 0xBB, 0xCC]));
    }

    #[test]
    fn hash_cc_vector() {
        // hashCc = SHA256(SHA256(code) || transactionId), like lpac's
        // es10b_prepare_download_r. Independent re-computation with sha2 must
        // agree with the helper.
        use sha2::{Digest, Sha256};
        let tx = [0x01u8, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08];
        let inner = Sha256::digest(b"1234");
        let mut outer = Sha256::new();
        outer.update(inner);
        outer.update(tx);
        let expected: [u8; 32] = outer.finalize().into();
        assert_eq!(hash_confirmation_code("1234", &tx), expected);
        // Different codes / transactions differ.
        assert_ne!(
            hash_confirmation_code("1234", &tx),
            hash_confirmation_code("4321", &tx)
        );
        assert_ne!(
            hash_confirmation_code("1234", &tx),
            hash_confirmation_code("1234", &[0x09])
        );
    }

    #[test]
    fn transaction_id_extraction() {
        let mut seq = crate::asn1::tlv(0x80, &[0xDE, 0xAD, 0xBE, 0xEF]);
        seq.extend(crate::asn1::tlv(0x01, &[0x00]));
        let signed = crate::asn1::tlv(0x30, &seq);
        assert_eq!(
            es10b::extract_transaction_id(&signed).unwrap(),
            vec![0xDE, 0xAD, 0xBE, 0xEF]
        );
        assert!(es10b::extract_transaction_id(&[0x30, 0x00]).is_err());
    }
}
