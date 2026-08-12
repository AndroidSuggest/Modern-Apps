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

/// Builds the ES10c GetEID request (GetEuiccData with a tag list of `{EID}`).
pub fn build_get_eid() -> Vec<u8> {
    // BF3E 03 5C 01 5A
    let tag_list = asn1::tlv(TAG_TAG_LIST, &[TAG_EID as u8]);
    asn1::tlv(TAG_GET_EUICC_DATA, &tag_list)
}

/// Parses a GetEuiccData response and returns the EID as 32 hex digits.
///
/// The 16 EID octets already encode the 32 decimal digits (one per nibble), so
/// a plain hex encoding of the octets is the canonical EID string.
pub fn parse_eid(response: &[u8]) -> Result<String, String> {
    let body = asn1::find(response, TAG_GET_EUICC_DATA)
        .ok_or("GetEuiccData: missing BF3E")?;
    let eid = asn1::find(body, TAG_EID).ok_or("GetEuiccData: missing EID (5A)")?;
    if eid.len() != 16 {
        return Err(format!("EID length {} != 16", eid.len()));
    }
    Ok(hex(eid))
}

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

/// Lowercase hex encoding.
pub fn hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
    }
    s
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
        // BF3E 12 5A 10 <89 04 40 ... 16 octets>
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
        let ver_list = {
            let mut kids = Vec::new();
            kids.extend(asn1::tlv(0x04, &[0xAA; 20]));
            asn1::tlv(TAG_CI_PKID_VERIFICATION, &kids)
        };
        let sign_list = {
            let mut kids = Vec::new();
            kids.extend(asn1::tlv(0x04, &[0xBB; 20]));
            asn1::tlv(TAG_CI_PKID_SIGNING, &kids)
        };
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
}
