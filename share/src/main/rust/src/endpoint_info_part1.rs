#[cfg(test)]
mod tests {
    use super::*;
    use crate::ble_adv::{BleAdvertisement, MAX_FAST_ADV_LEN};

    /// Deterministic stand-in for the CSPRNG so golden bytes are stable.
    fn counted(buf: &mut [u8]) {
        for (i, b) in buf.iter_mut().enumerate() {
            *b = i as u8;
        }
    }

    /// The `BleAdvertisement.data` a Pixel 7 Pro really advertised in "Everyone" mode,
    /// captured from GMS 26.24.34 via `:share`'s own scan.
    const REAL_PIXEL_BLE_PAYLOAD: &[u8] = &[
        0x23, 0xFC, 0x9F, 0x5E, 0x58, 0x30, 0x48, 0x54, 0x25, 0x22, 0x7E, 0x50, 0x66, 0x27,
        0x37, 0x59, 0xB3, 0xEB, 0x13, 0x73, 0xE4, 0xD6, 0x85, 0x13, 0x32, 0x4B, 0x13, 0x56,
        0x61, 0x79, 0x75, 0x6E, 0x27, 0x73, 0x20, 0x50, 0x69, 0x78, 0x65, 0x6C, 0x20, 0x37,
        0x20, 0x50, 0x72, 0x6F, 0x0C, 0xC4, 0x13, 0x44, 0xC6, 0xD0, 0x00, 0x00,
    ];

    #[test]
    fn a_real_pixel_ble_payload_yields_its_endpoint_id_and_name() {
        // This is the measurement the layout was recovered from: if our parser stops
        // agreeing with it, we have stopped agreeing with a real device.
        let payload = parse_ble_endpoint_payload(REAL_PIXEL_BLE_PAYLOAD).expect("payload");
        assert_eq!(payload.endpoint_id, "X0HT");
        assert_eq!(payload.endpoint_info.len(), 37);
        let info = parse(&payload.endpoint_info).expect("endpoint info");
        assert_eq!(info.device_name.as_deref(), Some("Vayun's Pixel 7 Pro"));
        assert_eq!(info.device_type, DeviceType::Phone);
        assert_eq!(info.version, ENDPOINT_INFO_VERSION);
    }

    #[test]
    fn ble_endpoint_payload_round_trip() {
        let info = build("Pixel 9", DeviceType::Phone, counted).expect("info");
        let payload = build_ble_endpoint_payload("3Q5V", &info).expect("payload");
        // Same header byte and hash as the real sample, then our id and length.
        assert_eq!(payload.first().copied(), Some(0x23));
        assert_eq!(payload.get(1..4), Some([0xFC, 0x9F, 0x5E].as_slice()));
        assert_eq!(payload.get(4..8), Some(b"3Q5V".as_slice()));
        assert_eq!(payload.get(8).copied(), Some(info.len() as u8));
        assert_eq!(
            parse_ble_endpoint_payload(&payload),
            Some(BleEndpointPayload {
                endpoint_id: "3Q5V".to_string(),
                endpoint_info: info,
            })
        );
    }

    #[test]
    fn the_bare_endpoint_info_is_not_a_valid_ble_payload() {
        // The bug this fixed: `:share` advertised the Sharing blob with no Nearby
        // Connections envelope, so a peer found no endpoint id and dropped us silently.
        let info = build("Pixel 9", DeviceType::Phone, counted).expect("info");
        assert!(parse_ble_endpoint_payload(&info).is_none());
    }

    #[test]
    fn ble_endpoint_payload_rejects_a_foreign_service_and_bad_lengths() {
        let mut payload = build_ble_endpoint_payload("3Q5V", b"info").expect("payload");
        // A different service id hash is a different service.
        payload[1] = 0x00;
        assert!(parse_ble_endpoint_payload(&payload).is_none());
        // A declared length past the buffer is refused, not clamped.
        let mut short = build_ble_endpoint_payload("3Q5V", b"info").expect("payload");
        short[8] = 99;
        assert!(parse_ble_endpoint_payload(&short).is_none());
        assert!(build_ble_endpoint_payload("3Q5", b"info").is_none());
    }

    #[test]
    fn endpoint_info_golden_bytes() {
        let blob = build("Pixel 7", DeviceType::Phone, counted).expect("build");
        // version 1 -> bits 7..5 = 001 (0x20); deviceType PHONE -> bits 3..1 = 001
        // (0x02); visibility bit 4 clear because a plaintext name follows.
        assert_eq!(blob.first().copied(), Some(0x22));
        // 2-byte salt then 14-byte cipher text, from the deterministic source.
        assert_eq!(blob.get(1..17), Some((0u8..16).collect::<Vec<u8>>().as_slice()));
        assert_eq!(blob.get(17).copied(), Some(7));
        assert_eq!(blob.get(18..), Some(b"Pixel 7".as_slice()));
        assert_eq!(blob.len(), 25);
    }

    #[test]
    fn endpoint_info_round_trip() {
        let blob = build("Pixel 7", DeviceType::Phone, counted).expect("build");
        assert_eq!(
            parse(&blob),
            Some(EndpointInfo {
                version: ENDPOINT_INFO_VERSION,
                device_type: DeviceType::Phone,
                device_name: Some("Pixel 7".to_string()),
                vendor_id: 0,
            })
        );
    }

    #[test]
    fn a_name_of_exactly_the_maximum_length_round_trips() {
        let name = "A".repeat(MAX_NAME_LEN);
        let blob = build(&name, DeviceType::Laptop, counted).expect("build");
        let parsed = parse(&blob).expect("parse");
        assert_eq!(parsed.device_name.as_deref(), Some(name.as_str()));
        assert_eq!(parsed.device_type, DeviceType::Laptop);
    }

    #[test]
    fn a_multibyte_name_round_trips_and_truncates_on_a_char_boundary() {
        let blob = build("Téléphone d'Amélie", DeviceType::Phone, counted).expect("build");
        assert_eq!(
            parse(&blob).expect("parse").device_name.as_deref(),
            Some("Téléphone d'Amélie")
        );
        // 12 four-byte characters is 48 bytes; the 32-byte cap must land on character 8,
        // never mid-character, or dzqj.java:51-54 rejects the U+FFFD it decodes to.
        let long = "😀".repeat(12);
        let truncated = build(&long, DeviceType::Phone, counted).expect("build");
        assert_eq!(
            parse(&truncated).expect("parse").device_name.as_deref(),
            Some("😀".repeat(8).as_str())
        );
    }

    #[test]
    fn parse_rejects_a_blob_shorter_than_the_minimum() {
        // dzqj.java:18-21 — 16 bytes is one short of the floor.
        assert!(parse(&[0x22; MIN_ENDPOINT_INFO_LEN - 1]).is_none());
    }

    #[test]
    fn parse_rejects_a_bad_name_length() {
        let mut blob = build("Pixel 7", DeviceType::Phone, counted).expect("build");
        // dzqj.java:47, :56 — a zero length is "wrong".
        blob[17] = 0;
        assert!(parse(&blob).is_none());
        // dzqj.java:47 — a length past the end of the buffer is refused, not clamped.
        blob[17] = 31;
        assert!(parse(&blob).is_none());
        // dzqk.java:43-45 / dzqj.java:47 — above 32 is refused outright.
        blob[17] = 33;
        assert!(parse(&blob).is_none());
    }

    #[test]
    fn parse_rejects_an_invalid_utf8_name() {
        let mut blob = build("Pixel 7", DeviceType::Phone, counted).expect("build");
        // dzqj.java:50-54 — a lone continuation byte decodes to U+FFFD.
        let last = blob.len() - 1;
        blob[last] = 0x80;
        assert!(parse(&blob).is_none());
    }

    #[test]
    fn parse_rejects_an_unknown_version() {
        let mut blob = build("Pixel 7", DeviceType::Phone, counted).expect("build");
        // dzqj.java:26-29 — version 2 in bits 7..5.
        blob[0] = set_bits(blob[0], 2, 5, 3);
        assert!(parse(&blob).is_none());
    }

    #[test]
    fn parse_rejects_a_truncated_tlv() {
        let mut blob = build("Pixel 7", DeviceType::Phone, counted).expect("build");
        // dzqj.java:66-68 — a type byte with no length byte is "wrong TLV format".
        blob.push(TLV_VENDOR_ID);
        assert!(parse(&blob).is_none());
    }

    #[test]
    fn parse_reads_the_vendor_id_tlv() {
        let mut blob = build("Pixel 7", DeviceType::Phone, counted).expect("build");
        blob.extend_from_slice(&[TLV_VENDOR_ID, 1, 0x42]);
        assert_eq!(parse(&blob).expect("parse").vendor_id, 0x42);
    }

    #[test]
    fn parse_accepts_contact_only_mode_without_a_name() {
        let mut blob = vec![0u8; MIN_ENDPOINT_INFO_LEN];
        blob[0] = set_bits(set_bits(0, ENDPOINT_INFO_VERSION, 5, 3), 1, 4, 1);
        assert_eq!(parse(&blob).expect("parse").device_name, None);
    }

    #[test]
    fn the_bare_device_name_is_not_a_valid_endpoint_info() {
        // This is the bug being fixed: `:share` advertised the UTF-8 device name
        // verbatim, which fails dzqj.java's very first length check, so a Google device
        // discarded us before the handshake. If this ever passes again, discovery is
        // silently broken.
        assert!(parse(b"Pixel 7").is_none());
    }

    #[test]
    fn build_rejects_an_empty_name() {
        // dzqk.java:46-48 — "Device name is empty".
        assert!(build("", DeviceType::Phone, counted).is_none());
    }

    #[test]
    fn wifi_lan_service_info_golden_bytes() {
        let raw = build_wifi_lan_service_info("ABCD").expect("build");
        // version 1 -> 0x20, pcp 3 -> 0x03.
        assert_eq!(raw.first().copied(), Some(0x23));
        assert_eq!(raw.get(1..5), Some(b"ABCD".as_slice()));
        assert_eq!(raw.get(5..8), Some([0xFC, 0x9F, 0x5E].as_slice()));
        assert_eq!(raw.len(), MIN_WIFI_LAN_SERVICE_INFO_LEN);
        assert_eq!(
            parse_wifi_lan_service_info(&raw),
            Some(WifiLanServiceInfo {
                pcp: WIFI_LAN_PCP,
                endpoint_id: "ABCD".to_string(),
                service_id_hash: [0xFC, 0x9F, 0x5E],
            })
        );
    }

    #[test]
    fn wifi_lan_service_info_rejects_a_wrong_endpoint_id_length() {
        assert!(build_wifi_lan_service_info("ABC").is_none());
        assert!(build_wifi_lan_service_info("ABCDE").is_none());
    }

    #[test]
    fn parse_wifi_lan_service_info_applies_the_gms_checks() {
        let mut raw = build_wifi_lan_service_info("ABCD").expect("build");
        // dnux.java:87-90 — under 8 bytes.
        assert!(parse_wifi_lan_service_info(&raw[..7]).is_none());
        // dnux.java:91-95 — version 0.
        raw[0] = WIFI_LAN_PCP;
        assert!(parse_wifi_lan_service_info(&raw).is_none());
        // dnux.java:110-113 — pcp 0.
        raw[0] = WIFI_LAN_VERSION << 5;
        assert!(parse_wifi_lan_service_info(&raw).is_none());
    }

    #[test]
    fn fast_mode_only_fits_a_short_endpoint_info() {
        // Fast mode's budget is 27 bytes (dscb.java:118) and its framing costs 2, so a
        // real endpoint info only fits for a very short name — and the platform's legacy
        // 31-byte advertisement is tighter still, since the service-data AD wrapper and
        // the flags AD cost another 7. This is why BleDiscoveryManager prefers extended
        // advertising and only falls back to fast mode.
        let short = build("Pix", DeviceType::Phone, counted).expect("build");
        let fits = BleAdvertisement::fast(short, None).serialize().expect("fits");
        assert!(fits.len() <= MAX_FAST_ADV_LEN);

        let long = build(&"A".repeat(MAX_NAME_LEN), DeviceType::Phone, counted).expect("build");
        assert!(BleAdvertisement::fast(long, None).serialize().is_none());
    }
}
