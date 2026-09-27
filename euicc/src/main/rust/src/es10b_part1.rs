#[cfg(test)]
mod tests {
    use super::*;
    use crate::asn1;
    use crate::es10::{TAG_ICCID, TAG_SEQ_NUMBER, TAG_NOTIFICATION_ADDRESS, TAG_NOTIFICATION_LIST,
        TAG_NOTIFICATION_METADATA};

    #[test]
    fn euicc_challenge_roundtrip() {
        assert_eq!(build_get_euicc_challenge(), vec![0xBF, 0x2E, 0x00]);
        let challenge = [0x11u8; 16];
        let inner = asn1::tlv(TAG_EUICC_CHALLENGE, &challenge);
        let resp = asn1::tlv(TAG_GET_EUICC_CHALLENGE, &inner);
        assert_eq!(parse_euicc_challenge(&resp).unwrap(), challenge.to_vec());
    }

    #[test]
    fn ctx_params1_tags_match_sgp22() {
        // CtxParamsForCommonAuthentication: matchingId [0] IMPLICIT, deviceInfo
        // [1] IMPLICIT (constructed). A bare SEQUENCE tag (0x30) on deviceInfo is
        // malformed — the SM-DP+ cannot verify the AuthenticateServer response
        // built over it (seen as an empty authenticateClient reply).
        let ctx = build_ctx_params1("ABC", &[0x35, 0x29, 0x06, 0x11], None);
        let body = asn1::find(&ctx, TAG_CTX_PARAMS_COMMON).expect("outer A0");
        let kids = asn1::children(body).expect("two children");
        assert_eq!(kids.len(), 2);
        assert_eq!(kids[0].tag, 0x80); // matchingId
        assert_eq!(kids[0].value, b"ABC");
        assert_eq!(kids[1].tag, 0xA1); // deviceInfo [1], NOT 0x30 SEQUENCE
        let dev = asn1::children(kids[1].value).expect("deviceInfo children");
        assert_eq!(dev.len(), 2);
        assert_eq!(dev[0].tag, 0x80); // tac
        assert_eq!(dev[0].value, &[0x35, 0x29, 0x06, 0x11]);
        assert_eq!(dev[1].tag, 0xA1); // deviceCapabilities
    }

    #[test]
    fn ctx_params1_shape() {
        let ctx = build_ctx_params1("MID-123", &[0, 0, 0, 0], None);
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
    fn segment_bpp_wraps_and_splits_like_lpac() {
        // lpac framing: [BF23][A0 whole][A1 header + 88s][A2 whole][A3 header + 86s].
        let isc = asn1::tlv(TAG_INITIALISE_SECURE_CHANNEL, &[0x01]);
        let seq87 = asn1::tlv(TAG_BPP_SEQ_87, &asn1::tlv(0x87, &[0x11]));
        let mut seq88_inner = asn1::tlv(0x88, &[0x33]);
        seq88_inner.extend(asn1::tlv(0x88, &[0x44]));
        let seq88 = asn1::tlv(TAG_BPP_SEQ_88, &seq88_inner);
        let seq87b = asn1::tlv(TAG_BPP_SEQ_87B, &asn1::tlv(0x87, &[0x66]));
        let seq86 = asn1::tlv(TAG_BPP_SEQ_86, &asn1::tlv(0x86, &[0x55]));
        let mut body = Vec::new();
        body.extend(isc);
        body.extend(seq87);
        body.extend(seq88);
        body.extend(seq87b);
        body.extend(seq86);
        let bpp = asn1::tlv(TAG_BPP, &body);

        let segments = segment_bpp(&bpp).unwrap();
        // BF23, A0 whole, A1 header, 88, 88, A2 whole, A3 header, 86.
        assert_eq!(segments.len(), 8);
        assert_eq!(segments[0], vec![0xBF, 0x23, 0x01, 0x01]);
        // A1 header: tag + length only, no value bytes.
        assert_eq!(segments[2], asn1::header(TAG_BPP_SEQ_88, seq88_inner.len()));
        assert_eq!(segments[3], vec![0x88, 0x01, 0x33]);
        assert_eq!(segments[4], vec![0x88, 0x01, 0x44]);
        assert_eq!(segments[7], vec![0x86, 0x01, 0x55]);
    }

    #[test]
    fn install_result_success_and_error() {
        let ok_final = asn1::tlv(TAG_FINAL_RESULT, &asn1::tlv(TAG_SUCCESS_RESULT, &[]));
        let ok_data = asn1::tlv(TAG_PIR_DATA, &ok_final);
        let ok = asn1::tlv(TAG_PROFILE_INSTALL_RESULT, &ok_data);
        assert!(parse_install_result(&ok).unwrap().success);

        // Extra fields before the reason must not shift the parse: tags win.
        // bppCommandId is tag 0x80 (same numeric value as es10c's result tag).
        let mut err_inner = asn1::tlv(TAG_BPP_COMMAND_ID, &[0x00]);
        err_inner.extend(asn1::tlv(0x82, &[0xAA])); // unrelated extra field
        err_inner.extend(asn1::tlv(TAG_ERROR_REASON, &[0x05])); // errorReason = 5
        let err_final = asn1::tlv(TAG_FINAL_RESULT, &asn1::tlv(TAG_ERROR_RESULT, &err_inner));
        let err_data = asn1::tlv(TAG_PIR_DATA, &err_final);
        let err = asn1::tlv(TAG_PROFILE_INSTALL_RESULT, &err_data);
        let r = parse_install_result(&err).unwrap();
        assert!(!r.success);
        assert_eq!(r.bpp_command_id, Some(0));
        assert_eq!(r.error_reason, Some(5));
        assert!(r.message.contains("unsupported_remote_operation_type"), "{}", r.message);
    }

    #[test]
    fn ctx_params1_embeds_gsmbcd_imei() {
        // 490154203237518 (classic test IMEI): pair-swapped nibbles, F-padded.
        let ctx = build_ctx_params1("MID", &DEFAULT_TAC, Some("490154203237518"));
        let common = asn1::find(&ctx, TAG_CTX_PARAMS_COMMON).unwrap();
        let di = asn1::find(common, TAG_DEVICE_INFO).unwrap();
        let imei = asn1::find(di, TAG_IMEI).expect("imei tag 0x82");
        assert_eq!(imei, &[0x94, 0x10, 0x45, 0x02, 0x23, 0x73, 0x15, 0xF8]);
        // Default TAC lands when no explicit one is supplied.
        let di_tac = asn1::find(di, TAG_TAC).unwrap();
        assert_eq!(di_tac, DEFAULT_TAC);
        // Garbage IMEIs are dropped, not fatal.
        let ctx2 = build_ctx_params1("MID", &DEFAULT_TAC, Some("not-an-imei!!"));
        let di2 = asn1::find(asn1::find(&ctx2, TAG_CTX_PARAMS_COMMON).unwrap(), TAG_DEVICE_INFO).unwrap();
        assert!(asn1::find(di2, TAG_IMEI).is_none());
    }

    #[test]
    fn profile_metadata_parse_subset() {
        let mut body = Vec::new();
        body.extend(asn1::tlv(TAG_ICCID, &[0x98, 0x10, 0x32]));
        body.extend(asn1::tlv(TAG_METADATA_SPN, "Carrier".as_bytes()));
        body.extend(asn1::tlv(TAG_METADATA_NAME, "Plan 5G".as_bytes()));
        body.extend(asn1::tlv(TAG_METADATA_CLASS, &[0x02]));
        body.extend(asn1::tlv(0x93, &[0x01])); // iconType: skipped
        let meta = asn1::tlv(TAG_PROFILE_METADATA, &body);
        let parsed = parse_profile_metadata(&meta).unwrap();
        assert_eq!(parsed.iccid_display, "890123");
        assert_eq!(parsed.service_provider, "Carrier");
        assert_eq!(parsed.profile_name, "Plan 5G");
        assert_eq!(parsed.class, "operational");
        assert!(parse_profile_metadata(&[0x30, 0x00]).is_err());
    }

    #[test]
    fn retrieve_notification_request_shape() {
        assert_eq!(
            build_retrieve_notification(7),
            vec![0xBF, 0x2B, 0x05, 0xA0, 0x03, 0x80, 0x01, 0x07],
        );
    }

    #[test]
    fn retrieved_notification_address_and_blob() {
        // BF2B { A0 { BF37 { BF27 { BF2F { 0C addr, 80 seq } A2 { A0 {} } } } } }
        // NotificationMetadata and finalResult are siblings inside BF27.
        let mut meta = asn1::tlv(TAG_NOTIFICATION_ADDRESS, "smdp.example.com".as_bytes());
        meta.extend(asn1::tlv(TAG_SEQ_NUMBER, &[0x03]));
        let mut inner = asn1::tlv(TAG_NOTIFICATION_METADATA, &meta);
        inner.extend(asn1::tlv(
            TAG_FINAL_RESULT,
            &asn1::tlv(TAG_SUCCESS_RESULT, &[]),
        ));
        let notif = asn1::tlv(
            TAG_PROFILE_INSTALL_RESULT,
            &asn1::tlv(TAG_PIR_DATA, &inner),
        );
        let resp = asn1::tlv(
            TAG_RETRIEVE_NOTIFICATIONS,
            &asn1::tlv(TAG_NOTIFICATION_LIST, &notif),
        );
        let (address, blob) = parse_retrieved_notification(&resp).unwrap();
        assert_eq!(address, "smdp.example.com");
        assert_eq!(blob, notif);
    }

    #[test]
    fn cancel_session_request_shape() {
        assert_eq!(
            build_cancel_session(&[0xDE, 0xAD], CancelReason::EndUserRejection),
            vec![0xBF, 0x41, 0x07, 0x80, 0x02, 0xDE, 0xAD, 0x81, 0x01, 0x00],
        );
        assert_eq!(
            build_cancel_session(&[0x01], CancelReason::MetadataMismatch),
            vec![0xBF, 0x41, 0x06, 0x80, 0x01, 0x01, 0x81, 0x01, 0x04],
        );
    }
}
