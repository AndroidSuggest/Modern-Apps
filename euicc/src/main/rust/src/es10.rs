//! ES10 local ISD-R command builders and response parsers (SGP.22).
//!
//! Pure protocol logic: each function either builds a request TLV (to be wrapped
//! in STORE DATA APDUs and transmitted by the transport layer) or parses a
//! response TLV. No JNI or transport here, so it is fully host-testable.
//!
//! Reimplemented for this repo, following the open-source OpenEUICC / lpac
//! (GPL-3.0-only).

use crate::asn1;

// --- Tags (SGP.22) ---
const TAG_GET_EUICC_DATA: u32 = 0xBF3E; // ES10c GetEuiccData request/response
const TAG_TAG_LIST: u32 = 0x5C; // [APPLICATION 28] tag list
const TAG_EID: u32 = 0x5A; // [APPLICATION 26] EID value
const TAG_EUICC_INFO1: u32 = 0xBF20; // ES10c GetEUICCInfo1 request/response
const TAG_SVN: u32 = 0x82; // [2] version (3 octets: major.minor.rev)
const TAG_CI_PKID_VERIFICATION: u32 = 0xA9; // [9] SEQUENCE OF SubjectKeyIdentifier
const TAG_CI_PKID_SIGNING: u32 = 0xAA; // [10] SEQUENCE OF SubjectKeyIdentifier

const TAG_PROFILE_INFO_LIST: u32 = 0xBF2D; // ES10c GetProfilesInfo request/response
const TAG_PROFILE_LIST_OK: u32 = 0xA0; // [0] SEQUENCE OF ProfileInfo
const TAG_PROFILE_INFO: u32 = 0xE3; // [PRIVATE 3] ProfileInfo
const TAG_ICCID: u32 = 0x5A; // [APPLICATION 26] ICCID (same numeric tag as EID)
const TAG_ISDP_AID: u32 = 0x4F; // [APPLICATION 15] ISD-P AID
const TAG_PROFILE_STATE: u32 = 0x9F70; // [112] ProfileState
const TAG_NICKNAME: u32 = 0x90; // [16] profileNickname UTF8String
const TAG_SPN: u32 = 0x91; // [17] serviceProviderName UTF8String
const TAG_PROFILE_NAME: u32 = 0x92; // [18] profileName UTF8String
const TAG_PROFILE_CLASS: u32 = 0x95; // [21] ProfileClass

const TAG_ENABLE: u32 = 0xBF31; // ES10c EnableProfile
const TAG_DISABLE: u32 = 0xBF32; // ES10c DisableProfile
const TAG_DELETE: u32 = 0xBF33; // ES10c DeleteProfile
const TAG_SET_NICKNAME: u32 = 0xBF29; // ES10c SetNickname
const TAG_REFRESH_FLAG: u32 = 0x81; // [1] refreshFlag BOOLEAN
const TAG_RESULT: u32 = 0x80; // [0] result INTEGER (enable/disable/delete/setNickname)

const TAG_LIST_NOTIFICATION: u32 = 0xBF28; // ES10b ListNotification
const TAG_NOTIFICATION_LIST: u32 = 0xA0; // [0] SEQUENCE OF NotificationMetadata
const TAG_NOTIFICATION_METADATA: u32 = 0xBF2F; // [47] NotificationMetadata
const TAG_SEQ_NUMBER: u32 = 0x80; // [0] seqNumber INTEGER
const TAG_NOTIFICATION_EVENT: u32 = 0x81; // [1] profileManagementOperation BIT STRING
const TAG_NOTIFICATION_ADDRESS: u32 = 0x82; // [2] notificationAddress UTF8String
const TAG_REMOVE_NOTIFICATION: u32 = 0xBF30; // ES10b RemoveNotificationFromList

const TAG_GET_EUICC_CHALLENGE: u32 = 0xBF2E; // ES10b GetEUICCChallenge
const TAG_EUICC_CHALLENGE: u32 = 0x80; // [0] euiccChallenge Octet16
const TAG_AUTHENTICATE_SERVER: u32 = 0xBF38; // ES10b AuthenticateServer
const TAG_PREPARE_DOWNLOAD: u32 = 0xBF21; // ES10b PrepareDownload
const TAG_HASH_CC: u32 = 0x04; // hashCc Octet32 (confirmation code)
const TAG_BPP: u32 = 0xBF36; // BoundProfilePackage
const TAG_INITIALISE_SECURE_CHANNEL: u32 = 0xBF23; // initialiseSecureChannelRequest
const TAG_BPP_SEQ_87: u32 = 0xA0; // firstSequenceOf87
const TAG_BPP_SEQ_88: u32 = 0xA1; // sequenceOf88
const TAG_BPP_SEQ_87B: u32 = 0xA2; // secondSequenceOf87
const TAG_BPP_SEQ_86: u32 = 0xA3; // sequenceOf86
const TAG_PROFILE_INSTALL_RESULT: u32 = 0xBF37; // ProfileInstallationResult
const TAG_PIR_DATA: u32 = 0xBF27; // profileInstallationResultData
const TAG_FINAL_RESULT: u32 = 0xA2; // [2] finalResult
const TAG_SUCCESS_RESULT: u32 = 0xA0; // [0] successResult
const TAG_ERROR_RESULT: u32 = 0xA1; // [1] errorResult

// ctxParams1 / DeviceInfo construction tags.
const TAG_CTX_PARAMS_COMMON: u32 = 0xA0; // [0] ctxParamsForCommonAuthentication
const TAG_MATCHING_ID: u32 = 0x80; // [0] matchingId UTF8String
const TAG_DEVICE_INFO: u32 = 0x30; // DeviceInfo SEQUENCE
const TAG_TAC: u32 = 0x80; // [0] tac Octet4
const TAG_DEVICE_CAPS: u32 = 0xA1; // [1] deviceCapabilities SEQUENCE

/// Parsed subset of EUICCInfo1 useful for display.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EuiccInfo1 {
    /// SGP.22 spec version, e.g. "2.2.0".
    pub svn: String,
    /// GSMA CI public-key identifiers the eUICC accepts for verification.
    pub ci_pkid_verification: Vec<String>,
    /// GSMA CI public-key identifiers the eUICC can sign under.
    pub ci_pkid_signing: Vec<String>,
}

/// One installed profile, as reported by GetProfilesInfo.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ProfileInfo {
    /// Raw ICCID octets as hex (nibble-swapped form); used as the command key.
    pub iccid: String,
    /// Human-readable ICCID digits (nibble-swapped and trimmed).
    pub iccid_display: String,
    pub isdp_aid: String,
    /// "enabled", "disabled", or "unknown".
    pub state: String,
    /// "operational", "test", "provisioning", or "unknown".
    pub class: String,
    pub nickname: String,
    pub service_provider: String,
    pub name: String,
}

/// One pending notification, as reported by ListNotification.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct NotificationInfo {
    pub seq_number: i64,
    /// "install", "enable", "disable", "delete", or "unknown".
    pub operation: String,
    pub address: String,
    pub iccid_display: String,
}

// ---------------------------------------------------------------------------
// GetEID
// ---------------------------------------------------------------------------

/// Builds the ES10c GetEID request (GetEuiccData with a tag list of `{EID}`).
pub fn build_get_eid() -> Vec<u8> {
    let tag_list = asn1::tlv(TAG_TAG_LIST, &[TAG_EID as u8]);
    asn1::tlv(TAG_GET_EUICC_DATA, &tag_list)
}

/// Parses a GetEuiccData response and returns the EID as 32 hex digits.
pub fn parse_eid(response: &[u8]) -> Result<String, String> {
    let body = asn1::find(response, TAG_GET_EUICC_DATA).ok_or("GetEuiccData: missing BF3E")?;
    let eid = asn1::find(body, TAG_EID).ok_or("GetEuiccData: missing EID (5A)")?;
    if eid.len() != 16 {
        return Err(format!("EID length {} != 16", eid.len()));
    }
    Ok(hex(eid))
}

// ---------------------------------------------------------------------------
// GetEUICCInfo1
// ---------------------------------------------------------------------------

/// Builds the ES10c GetEUICCInfo1 request (empty BF20).
pub fn build_get_euicc_info1() -> Vec<u8> {
    asn1::tlv(TAG_EUICC_INFO1, &[])
}

/// Parses a GetEUICCInfo1 response into the displayable [`EuiccInfo1`] subset.
pub fn parse_euicc_info1(response: &[u8]) -> Result<EuiccInfo1, String> {
    let body = asn1::find(response, TAG_EUICC_INFO1).ok_or("EUICCInfo1: missing BF20")?;

    let svn = match asn1::find(body, TAG_SVN) {
        Some(v) if v.len() == 3 => format!("{}.{}.{}", v[0], v[1], v[2]),
        _ => String::new(),
    };

    let list = |tag: u32| -> Vec<String> {
        asn1::find(body, tag)
            .and_then(asn1::children)
            .map(|kids| kids.iter().map(|k| hex(k.value)).collect())
            .unwrap_or_default()
    };

    Ok(EuiccInfo1 {
        svn,
        ci_pkid_verification: list(TAG_CI_PKID_VERIFICATION),
        ci_pkid_signing: list(TAG_CI_PKID_SIGNING),
    })
}

// ---------------------------------------------------------------------------
// GetProfilesInfo
// ---------------------------------------------------------------------------

/// Builds the ES10c GetProfilesInfo request with a tag list of the fields we
/// display.
pub fn build_get_profiles() -> Vec<u8> {
    // Two-byte tag 9F70 is included as its two octets; the rest are one byte.
    let tags: &[u8] = &[
        TAG_ICCID as u8,
        TAG_ISDP_AID as u8,
        0x9F,
        0x70,
        TAG_NICKNAME as u8,
        TAG_SPN as u8,
        TAG_PROFILE_NAME as u8,
        TAG_PROFILE_CLASS as u8,
    ];
    let tag_list = asn1::tlv(TAG_TAG_LIST, tags);
    asn1::tlv(TAG_PROFILE_INFO_LIST, &tag_list)
}

/// Parses a GetProfilesInfo response into the list of installed profiles.
pub fn parse_profiles(response: &[u8]) -> Result<Vec<ProfileInfo>, String> {
    let body = asn1::find(response, TAG_PROFILE_INFO_LIST).ok_or("ProfileInfoList: missing BF2D")?;
    // profileInfoListOk [0]; absent means an error CHOICE was returned.
    let ok = asn1::find(body, TAG_PROFILE_LIST_OK)
        .ok_or("ProfileInfoList: error result (no A0)")?;

    let mut profiles = Vec::new();
    for child in asn1::children(ok).ok_or("ProfileInfoList: malformed list")? {
        if child.tag != TAG_PROFILE_INFO {
            continue;
        }
        let p = child.value;
        let raw_iccid = asn1::find(p, TAG_ICCID).unwrap_or(&[]);
        profiles.push(ProfileInfo {
            iccid: hex(raw_iccid),
            iccid_display: decode_iccid(raw_iccid),
            isdp_aid: asn1::find(p, TAG_ISDP_AID).map(hex).unwrap_or_default(),
            state: match asn1::find(p, TAG_PROFILE_STATE).and_then(first_byte) {
                Some(0) => "disabled".into(),
                Some(1) => "enabled".into(),
                _ => "unknown".into(),
            },
            class: match asn1::find(p, TAG_PROFILE_CLASS).and_then(first_byte) {
                Some(0) => "test".into(),
                Some(1) => "provisioning".into(),
                Some(2) => "operational".into(),
                _ => "unknown".into(),
            },
            nickname: asn1::find(p, TAG_NICKNAME).map(utf8).unwrap_or_default(),
            service_provider: asn1::find(p, TAG_SPN).map(utf8).unwrap_or_default(),
            name: asn1::find(p, TAG_PROFILE_NAME).map(utf8).unwrap_or_default(),
        });
    }
    Ok(profiles)
}

// ---------------------------------------------------------------------------
// Enable / Disable / Delete / SetNickname
// ---------------------------------------------------------------------------

/// Builds an EnableProfile request keyed by ICCID.
pub fn build_enable(iccid: &[u8], refresh: bool) -> Vec<u8> {
    let mut v = asn1::tlv(TAG_ICCID, iccid);
    v.extend(asn1::tlv(TAG_REFRESH_FLAG, &[if refresh { 0xFF } else { 0x00 }]));
    asn1::tlv(TAG_ENABLE, &v)
}

/// Builds a DisableProfile request keyed by ICCID.
pub fn build_disable(iccid: &[u8], refresh: bool) -> Vec<u8> {
    let mut v = asn1::tlv(TAG_ICCID, iccid);
    v.extend(asn1::tlv(TAG_REFRESH_FLAG, &[if refresh { 0xFF } else { 0x00 }]));
    asn1::tlv(TAG_DISABLE, &v)
}

/// Builds a DeleteProfile request keyed by ICCID.
pub fn build_delete(iccid: &[u8]) -> Vec<u8> {
    let inner = asn1::tlv(TAG_ICCID, iccid);
    asn1::tlv(TAG_DELETE, &inner)
}

/// Builds a SetNickname request for a profile keyed by ICCID.
pub fn build_set_nickname(iccid: &[u8], nickname: &str) -> Vec<u8> {
    let mut v = asn1::tlv(TAG_ICCID, iccid);
    v.extend(asn1::tlv(TAG_NICKNAME, nickname.as_bytes()));
    asn1::tlv(TAG_SET_NICKNAME, &v)
}

/// Parses the INTEGER result of Enable/Disable/Delete/SetNickname. 0 = success.
pub fn parse_result(response: &[u8], tag: u32) -> Result<i64, String> {
    let body = asn1::find(response, tag).ok_or("result: missing response TLV")?;
    let code = asn1::find(body, TAG_RESULT).ok_or("result: missing code (80)")?;
    Ok(parse_int(code))
}

/// Result tag for an EnableProfile response.
pub const RESULT_TAG_ENABLE: u32 = TAG_ENABLE;
pub const RESULT_TAG_DISABLE: u32 = TAG_DISABLE;
pub const RESULT_TAG_DELETE: u32 = TAG_DELETE;
pub const RESULT_TAG_SET_NICKNAME: u32 = TAG_SET_NICKNAME;

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

/// Builds the ES10b ListNotification request (all events).
pub fn build_list_notifications() -> Vec<u8> {
    asn1::tlv(TAG_LIST_NOTIFICATION, &[])
}

/// Parses a ListNotification response into pending notifications.
pub fn parse_notifications(response: &[u8]) -> Result<Vec<NotificationInfo>, String> {
    let body = asn1::find(response, TAG_LIST_NOTIFICATION).ok_or("ListNotification: missing BF28")?;
    let list = asn1::find(body, TAG_NOTIFICATION_LIST)
        .ok_or("ListNotification: error result (no A0)")?;

    let mut out = Vec::new();
    for child in asn1::children(list).ok_or("ListNotification: malformed list")? {
        if child.tag != TAG_NOTIFICATION_METADATA {
            continue;
        }
        let m = child.value;
        out.push(NotificationInfo {
            seq_number: asn1::find(m, TAG_SEQ_NUMBER).map(parse_int).unwrap_or(-1),
            operation: asn1::find(m, TAG_NOTIFICATION_EVENT)
                .map(decode_event)
                .unwrap_or_else(|| "unknown".into()),
            address: asn1::find(m, TAG_NOTIFICATION_ADDRESS).map(utf8).unwrap_or_default(),
            iccid_display: asn1::find(m, TAG_ICCID).map(decode_iccid).unwrap_or_default(),
        });
    }
    Ok(out)
}

/// Builds the ES10b RemoveNotificationFromList request for a sequence number.
pub fn build_remove_notification(seq: u32) -> Vec<u8> {
    let inner = asn1::tlv(TAG_SEQ_NUMBER, &encode_int_minimal(seq));
    asn1::tlv(TAG_REMOVE_NOTIFICATION, &inner)
}

/// Parses the RemoveNotificationFromList result. 0 = success.
pub fn parse_remove_result(response: &[u8]) -> Result<i64, String> {
    let body = asn1::find(response, TAG_REMOVE_NOTIFICATION)
        .ok_or("RemoveNotification: missing BF30")?;
    let code = asn1::find(body, TAG_SEQ_NUMBER).ok_or("RemoveNotification: missing code")?;
    Ok(parse_int(code))
}

// ---------------------------------------------------------------------------
// Download flow (ES10b): challenge, AuthenticateServer, PrepareDownload,
// LoadBoundProfilePackage
// ---------------------------------------------------------------------------

/// Outcome of installing a Bound Profile Package.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstallResult {
    pub success: bool,
    pub message: String,
    /// The raw ProfileInstallationResult (BF37) to deliver via HandleNotification.
    pub notification: Vec<u8>,
}

/// Builds the ES10b GetEUICCChallenge request (empty BF2E).
pub fn build_get_euicc_challenge() -> Vec<u8> {
    asn1::tlv(TAG_GET_EUICC_CHALLENGE, &[])
}

/// Parses a GetEUICCChallenge response into the 16-byte challenge.
pub fn parse_euicc_challenge(response: &[u8]) -> Result<Vec<u8>, String> {
    let body = asn1::find(response, TAG_GET_EUICC_CHALLENGE).ok_or("GetEUICCChallenge: missing BF2E")?;
    let challenge = asn1::find(body, TAG_EUICC_CHALLENGE).ok_or("GetEUICCChallenge: missing challenge")?;
    Ok(challenge.to_vec())
}

/// Builds ctxParams1 for common authentication: matchingId + a minimal DeviceInfo.
///
/// `tac` is the 4-byte Type Allocation Code; deviceCapabilities is sent empty.
pub fn build_ctx_params1(matching_id: &str, tac: &[u8; 4]) -> Vec<u8> {
    let mut device_info_val = asn1::tlv(TAG_TAC, tac);
    device_info_val.extend(asn1::tlv(TAG_DEVICE_CAPS, &[])); // empty DeviceCapabilities
    let device_info = asn1::tlv(TAG_DEVICE_INFO, &device_info_val);

    let mut common = asn1::tlv(TAG_MATCHING_ID, matching_id.as_bytes());
    common.extend(device_info);
    asn1::tlv(TAG_CTX_PARAMS_COMMON, &common)
}

/// Builds the ES10b AuthenticateServer request by concatenating the server blobs
/// (each already a complete DER TLV) with ctxParams1, wrapped in BF38.
pub fn build_authenticate_server(
    server_signed1: &[u8],
    server_signature1: &[u8],
    euicc_ci_pkid: &[u8],
    server_certificate: &[u8],
    ctx_params1: &[u8],
) -> Vec<u8> {
    let mut v = Vec::new();
    v.extend_from_slice(server_signed1);
    v.extend_from_slice(server_signature1);
    v.extend_from_slice(euicc_ci_pkid);
    v.extend_from_slice(server_certificate);
    v.extend_from_slice(ctx_params1);
    asn1::tlv(TAG_AUTHENTICATE_SERVER, &v)
}

/// Builds the ES10b PrepareDownload request from the SM-DP+ blobs (each a
/// complete DER TLV), wrapped in BF21.
pub fn build_prepare_download(
    smdp_signed2: &[u8],
    smdp_signature2: &[u8],
    hash_cc: Option<&[u8]>,
    smdp_certificate: &[u8],
) -> Vec<u8> {
    let mut v = Vec::new();
    v.extend_from_slice(smdp_signed2);
    v.extend_from_slice(smdp_signature2);
    if let Some(cc) = hash_cc {
        v.extend(asn1::tlv(TAG_HASH_CC, cc));
    }
    v.extend_from_slice(smdp_certificate);
    asn1::tlv(TAG_PREPARE_DOWNLOAD, &v)
}

/// Segments a Bound Profile Package (BF36) into the ordered list of TLVs the LPA
/// must transmit to the eUICC via STORE DATA: the initialiseSecureChannelRequest
/// followed by each element of the 87/88/87/86 sequences.
pub fn segment_bpp(bpp: &[u8]) -> Result<Vec<Vec<u8>>, String> {
    let body = asn1::find(bpp, TAG_BPP).ok_or("BoundProfilePackage: missing BF36")?;
    let mut segments = Vec::new();
    for child in asn1::children(body).ok_or("BoundProfilePackage: malformed")? {
        match child.tag {
            TAG_INITIALISE_SECURE_CHANNEL => {
                segments.push(asn1::tlv(child.tag, child.value));
            }
            TAG_BPP_SEQ_87 | TAG_BPP_SEQ_88 | TAG_BPP_SEQ_87B | TAG_BPP_SEQ_86 => {
                for element in asn1::children(child.value).ok_or("BPP: malformed sequence")? {
                    segments.push(asn1::tlv(element.tag, element.value));
                }
            }
            other => {
                // Unknown top-level element: forward it verbatim to be safe.
                segments.push(asn1::tlv(other, child.value));
            }
        }
    }
    Ok(segments)
}

/// Parses a ProfileInstallationResult (BF37) into an [`InstallResult`].
pub fn parse_install_result(response: &[u8]) -> Result<InstallResult, String> {
    let body = asn1::find(response, TAG_PROFILE_INSTALL_RESULT)
        .ok_or("ProfileInstallationResult: missing BF37")?;
    let data = asn1::find(body, TAG_PIR_DATA).ok_or("ProfileInstallationResult: missing BF27")?;
    let final_result = asn1::find(data, TAG_FINAL_RESULT)
        .ok_or("ProfileInstallationResult: missing finalResult")?;

    let (success, message) = if asn1::find(final_result, TAG_SUCCESS_RESULT).is_some() {
        (true, "Profile installed".to_string())
    } else if let Some(err) = asn1::find(final_result, TAG_ERROR_RESULT) {
        // errorResult ::= SEQUENCE { bppCommandId INTEGER, errorReason ENUMERATED, ... }
        let reason = asn1::children(err)
            .and_then(|kids| kids.get(1).map(|k| parse_int(k.value)))
            .unwrap_or(-1);
        (false, format!("Install failed (errorReason {reason})"))
    } else {
        (false, "Install failed (unknown result)".to_string())
    };

    Ok(InstallResult {
        success,
        message,
        notification: response.to_vec(),
    })
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Lowercase hex encoding.
pub fn hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

/// Decodes hex, returning empty on malformed input.
pub fn hex_decode(s: &str) -> Vec<u8> {
    if s.len() % 2 != 0 {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(s.len() / 2);
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let hi = (bytes[i] as char).to_digit(16);
        let lo = (bytes[i + 1] as char).to_digit(16);
        match (hi, lo) {
            (Some(h), Some(l)) => out.push((h * 16 + l) as u8),
            _ => return Vec::new(),
        }
        i += 2;
    }
    out
}

fn first_byte(v: &[u8]) -> Option<u8> {
    v.first().copied()
}

fn utf8(v: &[u8]) -> String {
    String::from_utf8_lossy(v).into_owned()
}

/// Parses a (short) two's-complement BER INTEGER into i64.
fn parse_int(v: &[u8]) -> i64 {
    if v.is_empty() {
        return 0;
    }
    let mut acc: i64 = if v[0] & 0x80 != 0 { -1 } else { 0 };
    for &b in v {
        acc = (acc << 8) | b as i64;
    }
    acc
}

/// Minimal unsigned big-endian INTEGER encoding (always at least one byte, with
/// a leading zero when the top bit would otherwise make it negative).
fn encode_int_minimal(mut v: u32) -> Vec<u8> {
    if v == 0 {
        return vec![0];
    }
    let mut bytes = Vec::new();
    while v > 0 {
        bytes.insert(0, (v & 0xFF) as u8);
        v >>= 8;
    }
    if bytes[0] & 0x80 != 0 {
        bytes.insert(0, 0x00);
    }
    bytes
}

/// Decodes an SGP.22 ICCID (BCD, nibble-swapped, F-padded) into decimal digits.
fn decode_iccid(raw: &[u8]) -> String {
    let mut s = String::with_capacity(raw.len() * 2);
    for &b in raw {
        let lo = b & 0x0F;
        let hi = b >> 4;
        for nib in [lo, hi] {
            if nib == 0x0F {
                continue;
            }
            if nib < 10 {
                s.push((b'0' + nib) as char);
            }
        }
    }
    s
}

/// Decodes a NotificationEvent BIT STRING into the first set operation label.
fn decode_event(v: &[u8]) -> String {
    // BIT STRING: first byte is the count of unused bits, then the bit octets.
    let data = v.get(1).copied().unwrap_or(0);
    // Bit 0 is the MSB of the first data octet.
    if data & 0x80 != 0 {
        "install".into()
    } else if data & 0x40 != 0 {
        "enable".into()
    } else if data & 0x20 != 0 {
        "disable".into()
    } else if data & 0x10 != 0 {
        "delete".into()
    } else {
        "unknown".into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn get_eid_request_bytes() {
        assert_eq!(build_get_eid(), vec![0xBF, 0x3E, 0x03, 0x5C, 0x01, 0x5A]);
    }

    #[test]
    fn parse_eid_from_response() {
        let mut inner = vec![0x5A, 0x10];
        let octets: [u8; 16] = [
            0x89, 0x04, 0x40, 0x00, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99,
            0x00, 0x01,
        ];
        inner.extend_from_slice(&octets);
        let resp = asn1::tlv(TAG_GET_EUICC_DATA, &inner);
        assert_eq!(parse_eid(&resp).unwrap(), "89044000001122334455667788990001");
    }

    #[test]
    fn get_euicc_info1_request_bytes() {
        assert_eq!(build_get_euicc_info1(), vec![0xBF, 0x20, 0x00]);
    }

    #[test]
    fn parse_euicc_info1_svn_and_lists() {
        let svn = asn1::tlv(TAG_SVN, &[0x02, 0x02, 0x00]);
        let ver_list = asn1::tlv(TAG_CI_PKID_VERIFICATION, &asn1::tlv(0x04, &[0xAA; 20]));
        let sign_list = asn1::tlv(TAG_CI_PKID_SIGNING, &asn1::tlv(0x04, &[0xBB; 20]));
        let mut body = Vec::new();
        body.extend(svn);
        body.extend(ver_list);
        body.extend(sign_list);
        let resp = asn1::tlv(TAG_EUICC_INFO1, &body);

        let info = parse_euicc_info1(&resp).unwrap();
        assert_eq!(info.svn, "2.2.0");
        assert_eq!(info.ci_pkid_verification, vec!["aa".repeat(20)]);
        assert_eq!(info.ci_pkid_signing, vec!["bb".repeat(20)]);
    }

    #[test]
    fn iccid_decoding() {
        // 98 10 32 -> swap each byte -> "890123"
        assert_eq!(decode_iccid(&[0x98, 0x10, 0x32]), "890123");
        // Trailing F is padding and dropped.
        assert_eq!(decode_iccid(&[0x21, 0xF3]), "123");
    }

    #[test]
    fn parse_profiles_list() {
        let iccid = [0x98, 0x10, 0x32, 0x54, 0x76];
        let mut p = Vec::new();
        p.extend(asn1::tlv(TAG_ICCID, &iccid));
        p.extend(asn1::tlv(TAG_PROFILE_STATE, &[0x01])); // enabled
        p.extend(asn1::tlv(TAG_PROFILE_CLASS, &[0x02])); // operational
        p.extend(asn1::tlv(TAG_NICKNAME, "Work".as_bytes()));
        p.extend(asn1::tlv(TAG_SPN, "Carrier".as_bytes()));
        let profile = asn1::tlv(TAG_PROFILE_INFO, &p);
        let ok = asn1::tlv(TAG_PROFILE_LIST_OK, &profile);
        let resp = asn1::tlv(TAG_PROFILE_INFO_LIST, &ok);

        let profiles = parse_profiles(&resp).unwrap();
        assert_eq!(profiles.len(), 1);
        let pi = &profiles[0];
        assert_eq!(pi.iccid, "9810325476");
        assert_eq!(pi.iccid_display, "8901234567");
        assert_eq!(pi.state, "enabled");
        assert_eq!(pi.class, "operational");
        assert_eq!(pi.nickname, "Work");
        assert_eq!(pi.service_provider, "Carrier");
    }

    #[test]
    fn enable_disable_delete_requests() {
        let iccid = [0x11, 0x22, 0x33];
        assert_eq!(
            build_enable(&iccid, true),
            vec![0xBF, 0x31, 0x08, 0x5A, 0x03, 0x11, 0x22, 0x33, 0x81, 0x01, 0xFF],
        );
        assert_eq!(
            build_disable(&iccid, false),
            vec![0xBF, 0x32, 0x08, 0x5A, 0x03, 0x11, 0x22, 0x33, 0x81, 0x01, 0x00],
        );
        assert_eq!(
            build_delete(&iccid),
            vec![0xBF, 0x33, 0x05, 0x5A, 0x03, 0x11, 0x22, 0x33],
        );
    }

    #[test]
    fn set_nickname_request() {
        let iccid = [0xAA, 0xBB];
        assert_eq!(
            build_set_nickname(&iccid, "Hi"),
            vec![0xBF, 0x29, 0x08, 0x5A, 0x02, 0xAA, 0xBB, 0x90, 0x02, b'H', b'i'],
        );
    }

    #[test]
    fn parse_result_code() {
        let body = asn1::tlv(TAG_RESULT, &[0x00]);
        let resp = asn1::tlv(TAG_ENABLE, &body);
        assert_eq!(parse_result(&resp, RESULT_TAG_ENABLE).unwrap(), 0);
    }

    #[test]
    fn remove_notification_request() {
        assert_eq!(
            build_remove_notification(5),
            vec![0xBF, 0x30, 0x03, 0x80, 0x01, 0x05],
        );
    }

    #[test]
    fn parse_notifications_list() {
        let mut m = Vec::new();
        m.extend(asn1::tlv(TAG_SEQ_NUMBER, &[0x03]));
        m.extend(asn1::tlv(TAG_NOTIFICATION_EVENT, &[0x00, 0x40])); // enable
        m.extend(asn1::tlv(TAG_NOTIFICATION_ADDRESS, "smdp.example.com".as_bytes()));
        let meta = asn1::tlv(TAG_NOTIFICATION_METADATA, &m);
        let list = asn1::tlv(TAG_NOTIFICATION_LIST, &meta);
        let resp = asn1::tlv(TAG_LIST_NOTIFICATION, &list);

        let notes = parse_notifications(&resp).unwrap();
        assert_eq!(notes.len(), 1);
        assert_eq!(notes[0].seq_number, 3);
        assert_eq!(notes[0].operation, "enable");
        assert_eq!(notes[0].address, "smdp.example.com");
    }

    #[test]
    fn euicc_challenge_roundtrip() {
        assert_eq!(build_get_euicc_challenge(), vec![0xBF, 0x2E, 0x00]);
        let challenge = [0x11u8; 16];
        let inner = asn1::tlv(TAG_EUICC_CHALLENGE, &challenge);
        let resp = asn1::tlv(TAG_GET_EUICC_CHALLENGE, &inner);
        assert_eq!(parse_euicc_challenge(&resp).unwrap(), challenge.to_vec());
    }

    #[test]
    fn ctx_params1_shape() {
        let ctx = build_ctx_params1("MID-123", &[0, 0, 0, 0]);
        // A0 { 80 <mid> 30 { 80 04 <tac> A1 00 } }
        let common = asn1::find(&ctx, TAG_CTX_PARAMS_COMMON).unwrap();
        assert_eq!(asn1::find(common, TAG_MATCHING_ID).unwrap(), b"MID-123");
        let di = asn1::find(common, TAG_DEVICE_INFO).unwrap();
        assert_eq!(asn1::find(di, TAG_TAC).unwrap(), &[0, 0, 0, 0]);
        assert_eq!(asn1::find(di, TAG_DEVICE_CAPS).unwrap(), &[] as &[u8]);
    }

    #[test]
    fn authenticate_server_concatenates_blobs() {
        let req = build_authenticate_server(&[0x30, 0x01, 0xAA], &[0x5F, 0x37, 0x01, 0xBB], &[0x04, 0x01, 0xCC], &[0x30, 0x01, 0xDD], &[0xA0, 0x00]);
        let body = asn1::find(&req, TAG_AUTHENTICATE_SERVER).unwrap();
        assert_eq!(body, &[0x30, 0x01, 0xAA, 0x5F, 0x37, 0x01, 0xBB, 0x04, 0x01, 0xCC, 0x30, 0x01, 0xDD, 0xA0, 0x00]);
    }

    #[test]
    fn segment_bpp_orders_elements() {
        let isc = asn1::tlv(TAG_INITIALISE_SECURE_CHANNEL, &[0x01]);
        let seq87 = asn1::tlv(TAG_BPP_SEQ_87, &asn1::tlv(0x87, &[0x11, 0x22]));
        let mut seq88_inner = asn1::tlv(0x88, &[0x33]);
        seq88_inner.extend(asn1::tlv(0x88, &[0x44]));
        let seq88 = asn1::tlv(TAG_BPP_SEQ_88, &seq88_inner);
        let seq86 = asn1::tlv(TAG_BPP_SEQ_86, &asn1::tlv(0x86, &[0x55]));
        let mut body = Vec::new();
        body.extend(isc);
        body.extend(seq87);
        body.extend(seq88);
        body.extend(seq86);
        let bpp = asn1::tlv(TAG_BPP, &body);

        let segments = segment_bpp(&bpp).unwrap();
        assert_eq!(segments.len(), 5); // BF23, 87, 88, 88, 86
        assert_eq!(segments[0], vec![0xBF, 0x23, 0x01, 0x01]);
        assert_eq!(segments[1], vec![0x87, 0x02, 0x11, 0x22]);
        assert_eq!(segments[2], vec![0x88, 0x01, 0x33]);
        assert_eq!(segments[3], vec![0x88, 0x01, 0x44]);
        assert_eq!(segments[4], vec![0x86, 0x01, 0x55]);
    }

    #[test]
    fn install_result_success_and_error() {
        let ok_final = asn1::tlv(TAG_FINAL_RESULT, &asn1::tlv(TAG_SUCCESS_RESULT, &[]));
        let ok_data = asn1::tlv(TAG_PIR_DATA, &ok_final);
        let ok = asn1::tlv(TAG_PROFILE_INSTALL_RESULT, &ok_data);
        assert!(parse_install_result(&ok).unwrap().success);

        let mut err_inner = asn1::tlv(TAG_RESULT, &[0x00]); // bppCommandId
        err_inner.extend(asn1::tlv(0x81, &[0x05])); // errorReason = 5
        let err_final = asn1::tlv(TAG_FINAL_RESULT, &asn1::tlv(TAG_ERROR_RESULT, &err_inner));
        let err_data = asn1::tlv(TAG_PIR_DATA, &err_final);
        let err = asn1::tlv(TAG_PROFILE_INSTALL_RESULT, &err_data);
        let r = parse_install_result(&err).unwrap();
        assert!(!r.success);
        assert!(r.message.contains('5'));
    }
}
