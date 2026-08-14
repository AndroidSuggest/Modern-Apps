//! SecureMessage layer: AES-256-CBC + HMAC-SHA256 with sequence numbers.
//!
//! Wire format (as used by Nearby Sharing after UKEY2, matching
//! NearDrop's `SecureMessage.swift` and grishka/nearby's `SecureChannel`):
//!
//!   SecureMessage (prost):
//!     version          = 1
//!     sequence_number  = monotonically increasing (starts at 0, per direction)
//!     header_and_body  = IV (16 bytes, random) || AES-256-CBC ciphertext
//!                        plaintext = varint(length) || body  (body is the
//!                        inner SharingFrame / PayloadTransferFrame bytes)
//!     signature        = HMAC-SHA256( header_and_body || sequence_number_be32 )
//!                        truncated to 32 bytes (full SHA-256 output). Verified
//!                        with the `mac_key` derived from UKEY2's HKDF.
//!
//! Assumptions / notes:
//! - AES-256-CBC with PKCS#7 padding (via `cbc` + `aes` crates). The Nearby
//!   spec historically uses CBC (not GCM); NearDrop decrypts with CommonCrypto
//!   kCCOptionPKCS7Padding. We match that.
//! - HMAC covers `header_and_body` plus the big-endian 4-byte sequence number.
//!   Some forks include more AAD (e.g. version); we document this simpler form
//!   and note real interop may need an additional 4-byte version prefix in the
//!   HMAC input.
//! - IV is generated fresh with `OsRng` per message.
//! - Sequence numbers are per-direction counters; replay of an old sequence is
//!   treated as an error.
//! - Keys are the 32+32 bytes out of HKDF (enc_key, mac_key). No re-keying.

use aes::Aes256;
use cbc::{Decryptor, Encryptor};
use cipher::{block_padding::Pkcs7, BlockDecryptMut, BlockEncryptMut, KeyIvInit};
use hmac::{Hmac, Mac};
use rand_core::{OsRng, RngCore};
use sha2::Sha256;

use crate::frame::SecureMessage;

type Aes256CbcEnc = Encryptor<Aes256>;
type Aes256CbcDec = Decryptor<Aes256>;

#[derive(Debug)]
pub enum SecureError {
    BadFormat,
    DecryptFailed,
    HmacMismatch,
    BadSequence,
    EncodeError,
}

pub struct SecureChannel {
    enc_key: [u8; 32],
    mac_key: [u8; 32],
    send_seq: i32,
    recv_seq: i32,
}

impl SecureChannel {
    pub fn new(enc_key: [u8; 32], mac_key: [u8; 32]) -> Self {
        Self {
            enc_key,
            mac_key,
            send_seq: 0,
            recv_seq: 0,
        }
    }

    /// Encrypt `plaintext` (inner frame bytes) into a SecureMessage's wire bytes
    /// (length-prefixed prost encoding of SecureMessage). Increments send_seq.
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Vec<u8> {
        // Inner plaintext framing: varint(len) || plaintext
        // This mirrors NearDrop's `SecureMessage.encrypt`: it prefixes the inner
        // message with its length so the decryptor knows where the body ends
        // (plaintext may itself be a prost message without self-delimiting).
        let mut inner = Vec::with_capacity(5 + plaintext.len());
        crate::frame::encode_varint(plaintext.len() as u32, &mut inner);
        inner.extend_from_slice(plaintext);

        // AES-256-CBC encrypt
        let mut iv = [0u8; 16];
        OsRng.fill_bytes(&mut iv);
        let enc = Aes256CbcEnc::new(self.enc_key.as_slice().into(), iv.as_slice().into());
        let mut buf = inner;
        let ciphertext = enc.encrypt_padded_vec_mut::<Pkcs7>(&mut buf);
        let mut header_and_body = Vec::with_capacity(16 + ciphertext.len());
        header_and_body.extend_from_slice(&iv);
        header_and_body.extend_from_slice(&ciphertext);

        let seq = self.send_seq;
        self.send_seq = self.send_seq.wrapping_add(1);

        let sig = self.compute_hmac(&header_and_body, seq);
        let msg = SecureMessage {
            version: 1,
            sequence_number: seq,
            header_and_body,
            signature: sig.to_vec(),
        };
        let mut out = Vec::new();
        prost::Message::encode(&msg, &mut out).expect("prost encode");
        crate::frame::frame_with_length(&out)
    }

    /// Deterministic encrypt for tests (fixed IV).
    #[cfg(test)]
    pub fn encrypt_with_iv(&mut self, plaintext: &[u8], iv: [u8; 16]) -> Vec<u8> {
        let mut inner = Vec::with_capacity(5 + plaintext.len());
        crate::frame::encode_varint(plaintext.len() as u32, &mut inner);
        inner.extend_from_slice(plaintext);
        let enc = Aes256CbcEnc::new(self.enc_key.as_slice().into(), iv.as_slice().into());
        let mut buf = inner;
        let ciphertext = enc.encrypt_padded_vec_mut::<Pkcs7>(&mut buf);
        let mut header_and_body = Vec::with_capacity(16 + ciphertext.len());
        header_and_body.extend_from_slice(&iv);
        header_and_body.extend_from_slice(&ciphertext);
        let seq = self.send_seq;
        self.send_seq = self.send_seq.wrapping_add(1);
        let sig = self.compute_hmac(&header_and_body, seq);
        let msg = SecureMessage {
            version: 1,
            sequence_number: seq,
            header_and_body,
            signature: sig.to_vec(),
        };
        let mut out = Vec::new();
        prost::Message::encode(&msg, &mut out).expect("prost encode");
        crate::frame::frame_with_length(&out)
    }

    /// Decrypt a *single* length-prefixed SecureMessage frame's payload bytes
    /// (i.e. the prost encoding after stripping the varint length). Verifies HMAC
    /// and sequence ordering, then AES-decrypts and strips the inner varint prefix.
    /// Returns the inner plaintext (the body that was passed to `encrypt`).
    pub fn decrypt_one(&mut self, frame_payload: &[u8]) -> Result<Vec<u8>, SecureError> {
        let msg = SecureMessage::decode(frame_payload).map_err(|_| SecureError::BadFormat)?;
        // HMAC verify
        let expected = self.compute_hmac(&msg.header_and_body, msg.sequence_number);
        if expected.to_vec() != msg.signature {
            return Err(SecureError::HmacMismatch);
        }
        if msg.sequence_number != self.recv_seq {
            return Err(SecureError::BadSequence);
        }
        self.recv_seq = msg.sequence_number.wrapping_add(1);

        if msg.header_and_body.len() < 16 {
            return Err(SecureError::BadFormat);
        }
        let (iv, ciphertext) = msg.header_and_body.split_at(16);
        let dec = Aes256CbcDec::new(self.enc_key.as_slice().into(), iv.into());
        let mut buf = ciphertext.to_vec();
        let decrypted = dec
            .decrypt_padded_vec_mut::<Pkcs7>(&mut buf)
            .map_err(|_| SecureError::DecryptFailed)?;
        // Strip inner varint length prefix
        let (inner_len, prefix_len) =
            crate::frame::decode_varint(&decrypted).ok_or(SecureError::BadFormat)?;
        if prefix_len + inner_len as usize > decrypted.len() {
            return Err(SecureError::BadFormat);
        }
        Ok(decrypted[prefix_len..prefix_len + inner_len as usize].to_vec())
    }

    fn compute_hmac(&self, header_and_body: &[u8], seq: i32) -> [u8; 32] {
        let mut mac = Hmac::<Sha256>::new_from_slice(&self.mac_key).expect("hmac key");
        mac.update(header_and_body);
        mac.update(&seq.to_be_bytes());
        mac.finalize().into_bytes().into()
    }

    /// Current send / recv sequence numbers (for diagnostics).
    pub fn seqs(&self) -> (i32, i32) {
        (self.send_seq, self.recv_seq)
    }
    pub fn send_seq_debug(&self) -> i32 { self.send_seq }
    pub fn recv_seq_debug(&self) -> i32 { self.recv_seq }
}

// Need prost::Message decode for SecureMessage in decrypt_one
use prost::Message;

#[cfg(test)]
mod tests {
    use super::*;

    fn keys() -> ([u8; 32], [u8; 32]) {
        ([0x11u8; 32], [0x22u8; 32])
    }

    #[test]
    fn encrypt_decrypt_round_trip() {
        let (enc, mac) = keys();
        let mut a = SecureChannel::new(enc, mac);
        let mut b = SecureChannel::new(enc, mac);
        let plaintext = b"hello secure world";
        let iv = [0xAAu8; 16];
        let wire = a.encrypt_with_iv(plaintext, iv);
        // unwrap outer varint length
        let mut buf = wire;
        let inner = crate::frame::try_consume_frame(&mut buf).unwrap();
        let got = b.decrypt_one(&inner).unwrap();
        assert_eq!(got, plaintext);
    }

    #[test]
    fn hmac_tamper_rejected() {
        let (enc, mac) = keys();
        let mut a = SecureChannel::new(enc, mac);
        let mut b = SecureChannel::new(enc, mac);
        let wire = a.encrypt_with_iv(b"tamper me", [0x01; 16]);
        let mut buf = wire;
        let mut inner = crate::frame::try_consume_frame(&mut buf).unwrap();
        // flip a byte in header_and_body (inside the prost encoding, hard to target precisely;
        // easier: decode, tamper header_and_body, re-encode.
        let mut msg = SecureMessage::decode(inner.as_slice()).unwrap();
        if let Some(b) = msg.header_and_body.last_mut() {
            *b ^= 0xFF;
        }
        let mut reenc = Vec::new();
        msg.encode(&mut reenc).unwrap();
        // b should reject HMAC
        assert!(matches!(b.decrypt_one(&reenc), Err(SecureError::HmacMismatch)));
    }

    #[test]
    fn sequence_enforced() {
        let (enc, mac) = keys();
        let mut a = SecureChannel::new(enc, mac);
        let mut b = SecureChannel::new(enc, mac);
        let w1 = a.encrypt_with_iv(b"one", [0x01; 16]);
        let w2 = a.encrypt_with_iv(b"two", [0x02; 16]);
        let mut buf1 = w1;
        let mut buf2 = w2;
        let p1 = crate::frame::try_consume_frame(&mut buf1).unwrap();
        let p2 = crate::frame::try_consume_frame(&mut buf2).unwrap();
        // Deliver out of order: p2 first should not match expected seq 0.
        // Our implementation advances recv_seq only on success, so the second decrypt
        // should fail BadSequence if we deliver 2 before 1.
        // Actually p2 has seq=1, b expects 0 -> BadSequence.
        assert!(matches!(b.decrypt_one(&p2), Err(SecureError::HmacMismatch) | Err(SecureError::BadSequence)));
        // But p1 should still succeed.
        assert!(b.decrypt_one(&p1).is_ok());
    }

    #[test]
    fn multiple_messages_sequential() {
        let (enc, mac) = keys();
        let mut a = SecureChannel::new(enc, mac);
        let mut b = SecureChannel::new(enc, mac);
        for i in 0..5 {
            let pt = format!("msg {i}").into_bytes();
            let wire = a.encrypt(&pt);
            let mut buf = wire;
            let inner = crate::frame::try_consume_frame(&mut buf).unwrap();
            let got = b.decrypt_one(&inner).unwrap();
            assert_eq!(got, pt);
        }
    }
}
