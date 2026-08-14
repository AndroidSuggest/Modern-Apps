//! UKEY2 handshake (P-256 ECDH + HKDF-SHA256) for Nearby Share / Quick Share.
//!
//! Assumptions / byte-format notes (documented because Google's spec is not
//! public; derived from open reimplementations NearDrop, grishka/nearby and
//! Android's Nearby Connections `ukey2` sources):
//!
//! - Curve: NIST P-256 (prime256v1) via the `p256` crate. Keys are exchanged
//!   as 65-byte uncompressed points (0x04 || X || Y). This matches UKEY2's
//!   `p256_ecdh_public_key` encoding in NearDrop's `UKey2.swift`.
//! - Commitment: SHA-256 over (client_random || client_public_key || next_protocol).
//!   The real UKEY2 commitment is `SHA256(random || commitment_data)` where the
//!   commitment data is the serialized ClientInit; we simplify to the above
//!   but keep it verifiable end-to-end between two of our peers.
//! - HKDF: `HKDF-SHA256( IKM=ECDH_x, salt=client_random||server_random, info="UKEY2 D2D v1:" + next_protocol )`
//!   yielding 64 bytes split into `enc_key[32] | mac_key[32]`. Grishka's
//!   `NearbyImpl` uses HKDF with concatenated randoms as salt and next_protocol
//!   as info; exact ordering is not published so this is a documented divergent
//!   choice that remains deterministic and interoperable with ourselves.
//! - Roles are auto-detected in `session.rs`: `Session::new` queues a
//!   ClientInit (initiator). If a peer's ClientInit arrives while still in
//!   `WaitServerInit`, we switch to responder and reply with ServerInit.
//! - All handshake frames are length-prefixed (varint) protobuf-like byte
//!   blobs produced by `frame.rs` helpers (see `crate::frame::Ukey2Frame`).
//!   They travel *outside* the SecureMessage layer; SecureMessage is only
//!   established after `ClientFinished` verification.

use hkdf::Hkdf;
use p256::ecdh;
use p256::elliptic_curve::sec1::{FromEncodedPoint, ToEncodedPoint};
use p256::{EncodedPoint, PublicKey, SecretKey};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};

use crate::frame::{NextProtocol, Ukey2Frame, Ukey2FrameType};

/// How many bytes of random / commitment etc.
pub const RANDOM_LEN: usize = 32;
pub const COMMITMENT_LEN: usize = 32; // SHA-256

#[derive(Debug)]
pub enum HandshakeError {
    BadFrame,
    BadState,
    BadPublicKey,
    CommitmentMismatch,
    HmacVerifyFailed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HandshakeState {
    Init,
    SentClientInit,
    ReceivedClientInit, // responder: got ClientInit, sent ServerInit, awaiting ClientFinished
    SentServerInit,     // same as above (alias clarity)
    AwaitingClientFinish,
    Done,
    Failed,
}

/// Holds one side of the handshake.
pub struct Ukey2Handshake {
    pub is_initiator: bool,
    pub state: HandshakeState,
    pub next_protocol: String,
    local_secret: Option<SecretKey>,
    local_public_bytes: Vec<u8>, // 65 bytes uncompressed
    local_random: [u8; RANDOM_LEN],
    remote_public_bytes: Option<Vec<u8>>,
    remote_random: Option<[u8; RANDOM_LEN]>,
    commitment: Option<[u8; COMMITMENT_LEN]>,
    shared_secret: Option<[u8; 32]>, // x-coordinate
    derived_enc: Option<[u8; 32]>,
    derived_mac: Option<[u8; 32]>,
}

impl Ukey2Handshake {
    /// Create an initiating handshake. Generates an ephemeral P-256 keypair and 32-byte random.
    pub fn new_initiator(next_protocol: &str) -> Self {
        let mut rng = OsRng;
        let secret = SecretKey::random(&mut rng);
        let public = secret.public_key();
        let encoded = public.to_encoded_point(false);
        let mut random = [0u8; RANDOM_LEN];
        rng.fill_bytes(&mut random);
        let pub_bytes = encoded.as_bytes().to_vec();
        let commitment = Self::compute_commitment(&random, &pub_bytes, next_protocol);
        Self {
            is_initiator: true,
            state: HandshakeState::Init,
            next_protocol: next_protocol.to_string(),
            local_secret: Some(secret),
            local_public_bytes: pub_bytes,
            local_random: random,
            remote_public_bytes: None,
            remote_random: None,
            commitment: Some(commitment),
            shared_secret: None,
            derived_enc: None,
            derived_mac: None,
        }
    }

    /// Create a responder-side handshake (no ClientInit sent yet, but own keypair is ready).
    pub fn new_responder(next_protocol: &str) -> Self {
        let mut rng = OsRng;
        let secret = SecretKey::random(&mut rng);
        let public = secret.public_key();
        let encoded = public.to_encoded_point(false);
        let mut random = [0u8; RANDOM_LEN];
        rng.fill_bytes(&mut random);
        Self {
            is_initiator: false,
            state: HandshakeState::Init,
            next_protocol: next_protocol.to_string(),
            local_secret: Some(secret),
            local_public_bytes: encoded.as_bytes().to_vec(),
            local_random: random,
            remote_public_bytes: None,
            remote_random: None,
            commitment: None,
            shared_secret: None,
            derived_enc: None,
            derived_mac: None,
        }
    }

    /// Deterministic constructor for tests (fixed private key + random).
    #[cfg(test)]
    pub fn new_initiator_with_secret(secret_bytes: [u8; 32], random: [u8; 32], next_protocol: &str) -> Self {
        let secret = SecretKey::from_bytes(&secret_bytes.into()).expect("valid p256 scalar");
        let public = secret.public_key();
        let encoded = public.to_encoded_point(false);
        let pub_bytes = encoded.as_bytes().to_vec();
        let commitment = Self::compute_commitment(&random, &pub_bytes, next_protocol);
        Self {
            is_initiator: true,
            state: HandshakeState::Init,
            next_protocol: next_protocol.to_string(),
            local_secret: Some(secret),
            local_public_bytes: pub_bytes,
            local_random: random,
            remote_public_bytes: None,
            remote_random: None,
            commitment: Some(commitment),
            shared_secret: None,
            derived_enc: None,
            derived_mac: None,
        }
    }

    #[cfg(test)]
    pub fn new_responder_with_secret(secret_bytes: [u8; 32], random: [u8; 32], next_protocol: &str) -> Self {
        let secret = SecretKey::from_bytes(&secret_bytes.into()).expect("valid p256 scalar");
        let public = secret.public_key();
        let encoded = public.to_encoded_point(false);
        Self {
            is_initiator: false,
            state: HandshakeState::Init,
            next_protocol: next_protocol.to_string(),
            local_secret: Some(secret),
            local_public_bytes: encoded.as_bytes().to_vec(),
            local_random: random,
            remote_public_bytes: None,
            remote_random: None,
            commitment: None,
            shared_secret: None,
            derived_enc: None,
            derived_mac: None,
        }
    }

    fn compute_commitment(random: &[u8; 32], pub_bytes: &[u8], next_protocol: &str) -> [u8; 32] {
        let mut h = Sha256::new();
        h.update(random);
        h.update(pub_bytes);
        h.update(next_protocol.as_bytes());
        h.finalize().into()
    }

    /// Build the ClientInit frame bytes (without length prefix; caller frames it).
    pub fn build_client_init(&mut self) -> Vec<u8> {
        let f = Ukey2Frame {
            frame_type: Ukey2FrameType::ClientInit as i32,
            version: 1,
            random: Some(self.local_random.to_vec()),
            public_key: Some(self.local_public_bytes.clone()),
            commitment: self.commitment.clone().map(|c| c.to_vec()),
            next_protocol: Some(self.next_protocol.clone()),
            payload: Vec::new(),
        };
        self.state = HandshakeState::SentClientInit;
        f.encode_to_vec()
    }

    /// Build ServerInit (responder side, after seeing ClientInit).
    fn build_server_init(&self) -> Vec<u8> {
        let f = Ukey2Frame {
            frame_type: Ukey2FrameType::ServerInit as i32,
            version: 1,
            random: Some(self.local_random.to_vec()),
            public_key: Some(self.local_public_bytes.clone()),
            commitment: None,
            next_protocol: Some(self.next_protocol.clone()),
            payload: Vec::new(),
        };
        f.encode_to_vec()
    }

    /// Build ClientFinished — an HMAC over the transcript using the derived MAC key's prefix.
    fn build_client_finished(&self) -> Vec<u8> {
        // Transcript = client_random || server_random || shared_secret || next_protocol
        let transcript = self.transcript_bytes();
        let mac_key = self.derived_mac.expect("derived before finish");
        let tag = Self::hmac_tag(&mac_key, &transcript);
        let f = Ukey2Frame {
            frame_type: Ukey2FrameType::ClientFinish as i32,
            version: 1,
            random: None,
            public_key: None,
            commitment: None,
            next_protocol: None,
            payload: tag.to_vec(),
        };
        f.encode_to_vec()
    }

    fn transcript_bytes(&self) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&self.local_random);
        if let Some(rr) = &self.remote_random {
            out.extend_from_slice(rr);
        }
        if let Some(ss) = &self.shared_secret {
            out.extend_from_slice(ss);
        }
        out.extend_from_slice(self.next_protocol.as_bytes());
        out
    }

    fn hmac_tag(key: &[u8; 32], data: &[u8]) -> [u8; 32] {
        use hmac::{Hmac, Mac};
        let mut mac = Hmac::<Sha256>::new_from_slice(key).expect("hmac key len");
        mac.update(data);
        mac.finalize().into_bytes().into()
    }

    /// Process an inbound ClientInit (responder role). Returns ServerInit bytes to send.
    pub fn handle_client_init(&mut self, frame_bytes: &[u8]) -> Result<Vec<u8>, HandshakeError> {
        let frame = Ukey2Frame::decode_from_bytes(frame_bytes).map_err(|_| HandshakeError::BadFrame)?;
        if frame.frame_type != Ukey2FrameType::ClientInit as i32 {
            return Err(HandshakeError::BadFrame);
        }
        let remote_random = frame
            .random
            .ok_or(HandshakeError::BadFrame)?
            .try_into()
            .map_err(|_| HandshakeError::BadFrame)?;
        let remote_pub = frame.public_key.ok_or(HandshakeError::BadFrame)?;
        if remote_pub.len() != 65 || remote_pub[0] != 0x04 {
            return Err(HandshakeError::BadPublicKey);
        }
        // Verify commitment if present.
        if let Some(commitment) = frame.commitment {
            let expected = Self::compute_commitment(&remote_random, &remote_pub, &self.next_protocol);
            if commitment != expected.to_vec() {
                return Err(HandshakeError::CommitmentMismatch);
            }
        }
        self.remote_random = Some(remote_random);
        self.remote_public_bytes = Some(remote_pub.clone());
        self.derive_shared_and_keys(&remote_pub)?;
        // Now build ServerInit
        self.state = HandshakeState::AwaitingClientFinish;
        Ok(self.build_server_init())
    }

    /// Process an inbound ServerInit (initiator role). Returns ClientFinished bytes to send and derived keys.
    pub fn handle_server_init(&mut self, frame_bytes: &[u8]) -> Result<Vec<u8>, HandshakeError> {
        let frame = Ukey2Frame::decode_from_bytes(frame_bytes).map_err(|_| HandshakeError::BadFrame)?;
        if frame.frame_type != Ukey2FrameType::ServerInit as i32 {
            return Err(HandshakeError::BadFrame);
        }
        let remote_random = frame
            .random
            .ok_or(HandshakeError::BadFrame)?
            .try_into()
            .map_err(|_| HandshakeError::BadFrame)?;
        let remote_pub = frame.public_key.ok_or(HandshakeError::BadFrame)?;
        if remote_pub.len() != 65 || remote_pub[0] != 0x04 {
            return Err(HandshakeError::BadPublicKey);
        }
        self.remote_random = Some(remote_random);
        self.remote_public_bytes = Some(remote_pub.clone());
        self.derive_shared_and_keys(&remote_pub)?;
        let finished = self.build_client_finished();
        self.state = HandshakeState::Done;
        Ok(finished)
    }

    /// Process inbound ClientFinished (responder verifies).
    pub fn handle_client_finish(&mut self, frame_bytes: &[u8]) -> Result<(), HandshakeError> {
        let frame = Ukey2Frame::decode_from_bytes(frame_bytes).map_err(|_| HandshakeError::BadFrame)?;
        if frame.frame_type != Ukey2FrameType::ClientFinish as i32 {
            return Err(HandshakeError::BadFrame);
        }
        let tag = frame.payload;
        if tag.len() != 32 {
            return Err(HandshakeError::BadFrame);
        }
        // Recompute transcript from responder perspective:
        // transcript = client_random (remote) || server_random (local) || shared || next_protocol
        // But our transcript_bytes uses local_random first; for responder we need remote_first ordering.
        let mut transcript = Vec::new();
        let client_random = self.remote_random.ok_or(HandshakeError::BadState)?;
        transcript.extend_from_slice(&client_random);
        transcript.extend_from_slice(&self.local_random);
        transcript.extend_from_slice(
            self.shared_secret
                .as_ref()
                .ok_or(HandshakeError::BadState)?,
        );
        transcript.extend_from_slice(self.next_protocol.as_bytes());

        let mac_key = self.derived_mac.ok_or(HandshakeError::BadState)?;
        let expected = Self::hmac_tag(&mac_key, &transcript);
        if expected.to_vec() != tag {
            // Also try initiator-order transcript (tolerate ordering ambiguity) before failing.
            let alt = self.transcript_bytes();
            let alt_tag = Self::hmac_tag(&mac_key, &alt);
            if alt_tag.to_vec() != tag {
                return Err(HandshakeError::HmacVerifyFailed);
            }
        }
        self.state = HandshakeState::Done;
        Ok(())
    }

    fn derive_shared_and_keys(&mut self, remote_pub_bytes: &[u8]) -> Result<(), HandshakeError> {
        let secret = self.local_secret.as_ref().ok_or(HandshakeError::BadState)?;
        // Parse remote public.
        let encoded = EncodedPoint::from_bytes(remote_pub_bytes).map_err(|_| HandshakeError::BadPublicKey)?;
        let remote_pub = PublicKey::from_encoded_point(&encoded).into_option().ok_or(HandshakeError::BadPublicKey)?;
        // ECDH
        let shared = ecdh::diffie_hellman(secret.to_nonzero_scalar(), remote_pub.as_affine());
        let shared_bytes = shared.raw_secret_bytes();
        let x: [u8; 32] = (*shared_bytes).into();
        self.shared_secret = Some(x);
        // HKDF
        // salt = client_random || server_random (initiator's random is local_random if initiator else remote_random)
        let (client_random, server_random) = if self.is_initiator {
            (
                self.local_random,
                self.remote_random.ok_or(HandshakeError::BadState)?,
            )
        } else {
            (
                self.remote_random.ok_or(HandshakeError::BadState)?,
                self.local_random,
            )
        };
        let mut salt = Vec::with_capacity(64);
        salt.extend_from_slice(&client_random);
        salt.extend_from_slice(&server_random);
        let info = format!("UKEY2 D2D v1:{}", self.next_protocol);
        let hk = Hkdf::<Sha256>::new(Some(&salt), &x);
        let mut okm = [0u8; 64];
        hk.expand(info.as_bytes(), &mut okm)
            .map_err(|_| HandshakeError::BadState)?;
        let mut enc = [0u8; 32];
        let mut mac = [0u8; 32];
        enc.copy_from_slice(&okm[..32]);
        mac.copy_from_slice(&okm[32..]);
        self.derived_enc = Some(enc);
        self.derived_mac = Some(mac);
        Ok(())
    }

    pub fn derived_keys(&self) -> Option<([u8; 32], [u8; 32])> {
        match (self.derived_enc, self.derived_mac) {
            (Some(e), Some(m)) => Some((e, m)),
            _ => None,
        }
    }

    pub fn is_done(&self) -> bool {
        self.state == HandshakeState::Done
    }
}

// Minimal NextProtocol helper to avoid unused warning.
#[allow(dead_code)]
fn _next_proto(p: NextProtocol) -> String {
    p.as_str().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handshake_round_trip_both_roles() {
        let next = NextProtocol::Sharing.as_str();
        let c_random = [0x11u8; 32];
        let s_random = [0x22u8; 32];
        let c_secret = [0x01u8; 32];
        let s_secret = [0x02u8; 32];
        let mut initiator = Ukey2Handshake::new_initiator_with_secret(c_secret, c_random, next);
        let mut responder = Ukey2Handshake::new_responder_with_secret(s_secret, s_random, next);

        let client_init = initiator.build_client_init();
        let server_init = responder.handle_client_init(&client_init).expect("responder handles ClientInit");
        let client_finish = initiator
            .handle_server_init(&server_init)
            .expect("initiator handles ServerInit");
        responder
            .handle_client_finish(&client_finish)
            .expect("responder verifies ClientFinished");

        assert!(initiator.is_done());
        assert!(responder.is_done());
        let (i_enc, i_mac) = initiator.derived_keys().unwrap();
        let (r_enc, r_mac) = responder.derived_keys().unwrap();
        assert_eq!(i_enc, r_enc, "enc keys must match");
        assert_eq!(i_mac, r_mac, "mac keys must match");
    }

    #[test]
    fn commitment_mismatch_rejected() {
        let next = "sharing";
        let mut responder = Ukey2Handshake::new_responder(next);
        let mut initiator = Ukey2Handshake::new_initiator(next);
        let mut ci = initiator.build_client_init();
        // Corrupt commitment byte inside the frame payload bytes (flip last byte)
        if let Some(b) = ci.last_mut() {
            *b ^= 0xFF;
        }
        let err = responder.handle_client_init(&ci).unwrap_err();
        // Either BadFrame or CommitmentMismatch is acceptable for corrupted frame.
        assert!(matches!(
            err,
            HandshakeError::CommitmentMismatch | HandshakeError::BadFrame | HandshakeError::BadPublicKey
        ));
    }

    #[test]
    fn hkdf_is_deterministic_for_fixed_vectors() {
        let next = "test";
        let c_random = [0xAAu8; 32];
        let s_random = [0xBBu8; 32];
        let secret = [0x05u8; 32];
        let mut h1 = Ukey2Handshake::new_initiator_with_secret(secret, c_random, next);
        let mut r1 = Ukey2Handshake::new_responder_with_secret([0x06u8; 32], s_random, next);
        let ci = h1.build_client_init();
        let si = r1.handle_client_init(&ci).unwrap();
        let cf = h1.handle_server_init(&si).unwrap();
        r1.handle_client_finish(&cf).unwrap();
        let (enc1, mac1) = h1.derived_keys().unwrap();

        // Repeat with same vectors -> same keys.
        let mut h2 = Ukey2Handshake::new_initiator_with_secret(secret, c_random, next);
        let mut r2 = Ukey2Handshake::new_responder_with_secret([0x06u8; 32], s_random, next);
        let ci2 = h2.build_client_init();
        let si2 = r2.handle_client_init(&ci2).unwrap();
        let cf2 = h2.handle_server_init(&si2).unwrap();
        r2.handle_client_finish(&cf2).unwrap();
        let (enc2, mac2) = h2.derived_keys().unwrap();
        assert_eq!(enc1, enc2);
        assert_eq!(mac1, mac2);
    }
}
