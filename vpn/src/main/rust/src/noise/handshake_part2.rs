        // temp = HMAC(responder.chaining_key, preshared_key)
        let temp = b2s_hmac(
            &chaining_key,
            &self.params.preshared_key.unwrap_or([0u8; 32])[..],
        );
        // responder.chaining_key = HMAC(temp, 0x1)
        chaining_key = b2s_hmac(&temp, &[0x01]);
        // temp2 = HMAC(temp, responder.chaining_key || 0x2)
        let temp2 = b2s_hmac2(&temp, &chaining_key, &[0x02]);
        // key = HMAC(temp, temp2 || 0x3)
        let key = b2s_hmac2(&temp, &temp2, &[0x03]);
        // responder.hash = HASH(responder.hash || temp2)
        hash = b2s_hash(&hash, &temp2);
        // msg.encrypted_nothing = AEAD(key, 0, [empty], responder.hash)
        aead_chacha20_seal(resp.encrypted_nothing.as_mut_bytes(), &key, 0, &[], &hash);

        // Derive keys
        // temp1 = HMAC(initiator.chaining_key, [empty])
        // temp2 = HMAC(temp1, 0x1)
        // temp3 = HMAC(temp1, temp2 || 0x2)
        // initiator.sending_key = temp2
        // initiator.receiving_key = temp3
        // initiator.sending_key_counter = 0
        // initiator.receiving_key_counter = 0
        let temp1 = b2s_hmac(&chaining_key, &[]);
        let temp2 = b2s_hmac(&temp1, &[0x01]);
        let temp3 = b2s_hmac2(&temp1, &temp2, &[0x02]);

        self.init_mac1_and_mac2(&mut resp, local_index_val);

        let packet = buf.overwrite_with(&resp);

        (packet, Session::new(local_index, peer_index, temp2, temp3))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chacha20_seal_rfc7530_test_vector() {
        let plaintext = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
        let aad: [u8; 12] = [
            0x50, 0x51, 0x52, 0x53, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7,
        ];
        let key: [u8; 32] = [
            0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d,
            0x8e, 0x8f, 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b,
            0x9c, 0x9d, 0x9e, 0x9f,
        ];
        let nonce: [u8; 12] = [
            0x07, 0x00, 0x00, 0x00, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47,
        ];
        let mut buffer = vec![0; plaintext.len() + 16];

        aead_chacha20_seal_inner(&mut buffer, &key, nonce, plaintext, &aad);

        const EXPECTED_CIPHERTEXT: [u8; 114] = [
            0xd3, 0x1a, 0x8d, 0x34, 0x64, 0x8e, 0x60, 0xdb, 0x7b, 0x86, 0xaf, 0xbc, 0x53, 0xef,
            0x7e, 0xc2, 0xa4, 0xad, 0xed, 0x51, 0x29, 0x6e, 0x08, 0xfe, 0xa9, 0xe2, 0xb5, 0xa7,
            0x36, 0xee, 0x62, 0xd6, 0x3d, 0xbe, 0xa4, 0x5e, 0x8c, 0xa9, 0x67, 0x12, 0x82, 0xfa,
            0xfb, 0x69, 0xda, 0x92, 0x72, 0x8b, 0x1a, 0x71, 0xde, 0x0a, 0x9e, 0x06, 0x0b, 0x29,
            0x05, 0xd6, 0xa5, 0xb6, 0x7e, 0xcd, 0x3b, 0x36, 0x92, 0xdd, 0xbd, 0x7f, 0x2d, 0x77,
            0x8b, 0x8c, 0x98, 0x03, 0xae, 0xe3, 0x28, 0x09, 0x1b, 0x58, 0xfa, 0xb3, 0x24, 0xe4,
            0xfa, 0xd6, 0x75, 0x94, 0x55, 0x85, 0x80, 0x8b, 0x48, 0x31, 0xd7, 0xbc, 0x3f, 0xf4,
            0xde, 0xf0, 0x8e, 0x4b, 0x7a, 0x9d, 0xe5, 0x76, 0xd2, 0x65, 0x86, 0xce, 0xc6, 0x4b,
            0x61, 0x16,
        ];
        const EXPECTED_TAG: [u8; 16] = [
            0x1a, 0xe1, 0x0b, 0x59, 0x4f, 0x09, 0xe2, 0x6a, 0x7e, 0x90, 0x2e, 0xcb, 0xd0, 0x60,
            0x06, 0x91,
        ];

        assert_eq!(buffer[..plaintext.len()], EXPECTED_CIPHERTEXT);
        assert_eq!(buffer[plaintext.len()..], EXPECTED_TAG);
    }

    #[test]
    fn symmetric_chacha20_seal_open() {
        let aad: [u8; 32] = Default::default();
        let key: [u8; 32] = Default::default();
        let counter = 0;

        let mut encrypted_nothing: [u8; 16] = Default::default();

        aead_chacha20_seal(&mut encrypted_nothing, &key, counter, &[], &aad);

        eprintln!("encrypted_nothing: {encrypted_nothing:?}");

        aead_chacha20_open(&mut [], &key, counter, &encrypted_nothing, &aad)
            .expect("Should open what we just sealed");
    }
}
