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

/// Lowercase hex encoding.
pub fn hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
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
        // notificationAddress is tag 0x0C per SGP.22 (not context-[2]).
        assert_eq!(TAG_NOTIFICATION_ADDRESS, 0x0C);
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
}
