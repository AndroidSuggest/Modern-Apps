//! Profile download orchestration (SGP.22 Common Mutual Authentication +
//! Profile Download and Installation).
//!
//! Ties the eUICC-side ES10b commands ([`crate::es10`]) to the SM-DP+-side ES9+
//! calls ([`crate::es9p`]). The LPA only relays DER blobs: the eUICC verifies the
//! server, agrees keys, decrypts the Bound Profile Package, and installs it; the
//! SM-DP+ produces the signed material. This module sequences the exchange.
//!
//! The full flow requires a live SM-DP+, a real eUICC, and a platform-signed
//! install, so it cannot be exercised in host tests. Only
//! [`parse_activation_code`] is unit-tested here.

use jni::JNIEnv;

use crate::jni::store_data;
use crate::{asn1, es10, es9p};

/// A parsed SGP.22 activation code.
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
    // parts[0] is the format version ("1").
    let smdp = parts[1].trim();
    let matching_id = parts[2].trim();
    if smdp.is_empty() {
        return Err("Activation code is missing the SM-DP+ address".to_string());
    }
    let confirmation_code_required = parts.get(4).map(|s| *s == "1").unwrap_or(false);
    Ok(ActivationCode {
        smdp: smdp.to_string(),
        matching_id: matching_id.to_string(),
        confirmation_code_required,
    })
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
/// Confirmation-code-protected profiles are not yet supported; such downloads
/// will be rejected by the eUICC at PrepareDownload.
pub fn download_profile(
    env: &mut JNIEnv,
    activation_code: &str,
) -> Result<es10::InstallResult, String> {
    let ac = parse_activation_code(activation_code)?;

    // 1. eUICC challenge + info for the server's authentication.
    let challenge = es10::parse_euicc_challenge(&store_data(env, &es10::build_get_euicc_challenge())?)?;
    let euicc_info1 = store_data(env, &es10::build_get_euicc_info1())?;

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

    // 3. eUICC authenticates the server and signs its own material.
    let ctx = es10::build_ctx_params1(&ac.matching_id, &[0, 0, 0, 0]);
    let auth_req = es10::build_authenticate_server(
        &r1.server_signed1,
        &r1.server_signature1,
        &r1.euicc_ci_pkid,
        &r1.server_certificate,
        &ctx,
    );
    let auth_resp = store_data(env, &auth_req)?;

    // 4. SM-DP+ binds the profile to this eUICC.
    let r2 = es9p::authenticate_client(&ac.smdp, &r1.transaction_id, &auth_resp)?;

    // 5. eUICC prepares to receive the profile.
    let prep_req =
        es10::build_prepare_download(&r2.smdp_signed2, &r2.smdp_signature2, None, &r2.smdp_certificate);
    let prep_resp = store_data(env, &prep_req)?;

    // 6. Fetch the (encrypted) Bound Profile Package.
    let bpp = es9p::get_bound_profile_package(&ac.smdp, &r1.transaction_id, &prep_resp)?;

    // 7. Stream the BPP into the eUICC segment by segment; the last segment
    //    returns the ProfileInstallationResult.
    let segments = es10::segment_bpp(&bpp)?;
    let mut last = Vec::new();
    for segment in &segments {
        last = store_data(env, segment)?;
    }
    let result = es10::parse_install_result(&last)?;

    // 8. Deliver the install notification (best-effort).
    let _ = es9p::handle_notification(&ac.smdp, &result.notification);

    Ok(result)
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
}
