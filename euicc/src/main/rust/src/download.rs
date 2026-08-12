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
use crate::{es10, es9p};

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
}
