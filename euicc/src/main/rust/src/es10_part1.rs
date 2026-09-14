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
