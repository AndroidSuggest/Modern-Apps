// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// This file incorporates work covered by the following copyright and
// permission notice:
//
//   Copyright (c) Mullvad VPN AB. All rights reserved.
//   Copyright (c) 2019 Cloudflare, Inc. All rights reserved.
//
// SPDX-License-Identifier: MPL-2.0

use crate::crypto::aead::{Aad, CHACHA20_POLY1305, LessSafeKey, Nonce, UnboundKey};
use crate::noise::errors::WireGuardError;
use crate::noise::index_table::{Index, IndexTable};
use crate::noise::session::Session;
use crate::packet::{Packet, WgCookieReply, WgHandshakeBase, WgHandshakeInit, WgHandshakeResp};
use crate::sleepyinstant::Instant;
use crate::x25519;
use aead::{Aead, Payload};
use blake2::digest::{FixedOutput, KeyInit};
use blake2::{Blake2s256, Blake2sMac, Digest};
use chacha20poly1305::XChaCha20Poly1305;
use constant_time_eq::constant_time_eq_n;
use std::convert::TryInto;
use std::time::{Duration, SystemTime};
use zerocopy::IntoBytes;

pub(crate) const LABEL_MAC1: &[u8; 8] = b"mac1----";
pub(crate) const LABEL_COOKIE: &[u8; 8] = b"cookie--";
const KEY_LEN: usize = 32;
const TIMESTAMP_LEN: usize = 12;

// initiator.chaining_key = HASH(CONSTRUCTION)
const INITIAL_CHAIN_KEY: [u8; KEY_LEN] = [
    96, 226, 109, 174, 243, 39, 239, 192, 46, 195, 53, 226, 160, 37, 210, 208, 22, 235, 66, 6, 248,
    114, 119, 245, 45, 56, 209, 152, 139, 120, 205, 54,
];

// initiator.chaining_hash = HASH(initiator.chaining_key || IDENTIFIER)
const INITIAL_CHAIN_HASH: [u8; KEY_LEN] = [
    34, 17, 179, 97, 8, 26, 197, 102, 105, 18, 67, 219, 69, 138, 213, 50, 45, 156, 108, 102, 34,
    147, 232, 183, 14, 225, 156, 101, 186, 7, 158, 243,
];

#[inline]
pub(crate) fn b2s_hash(data1: &[u8], data2: &[u8]) -> [u8; 32] {
    let mut hash = Blake2s256::new();
    hash.update(data1);
    hash.update(data2);
    hash.finalize().into()
}

#[inline]
/// RFC 2401 HMAC+Blake2s, not to be confused with *keyed* Blake2s
pub(crate) fn b2s_hmac(key: &[u8], data1: &[u8]) -> [u8; 32] {
    use blake2::digest::Update;
    type HmacBlake2s = hmac::SimpleHmac<Blake2s256>;
    let mut hmac = HmacBlake2s::new_from_slice(key).unwrap();
    hmac.update(data1);
    hmac.finalize_fixed().into()
}

#[inline]
/// Like [`b2s_hmac`], but chain data1 and data2 together
pub(crate) fn b2s_hmac2(key: &[u8], data1: &[u8], data2: &[u8]) -> [u8; 32] {
    use blake2::digest::Update;
    type HmacBlake2s = hmac::SimpleHmac<Blake2s256>;
    let mut hmac = HmacBlake2s::new_from_slice(key).unwrap();
    hmac.update(data1);
    hmac.update(data2);
    hmac.finalize_fixed().into()
}

#[inline]
pub(crate) fn b2s_keyed_mac_16(key: &[u8], data1: &[u8]) -> [u8; 16] {
    let mut hmac = Blake2sMac::new_from_slice(key).unwrap();
    blake2::digest::Update::update(&mut hmac, data1);
    hmac.finalize_fixed().into()
}

#[inline]
pub(crate) fn b2s_keyed_mac_16_2(key: &[u8], data1: &[u8], data2: &[u8]) -> [u8; 16] {
    let mut hmac = Blake2sMac::new_from_slice(key).unwrap();
    blake2::digest::Update::update(&mut hmac, data1);
    blake2::digest::Update::update(&mut hmac, data2);
    hmac.finalize_fixed().into()
}

pub(crate) fn b2s_mac_24(key: &[u8], data1: &[u8]) -> [u8; 24] {
    let mut hmac = Blake2sMac::new_from_slice(key).unwrap();
    blake2::digest::Update::update(&mut hmac, data1);
    hmac.finalize_fixed().into()
}

#[inline]
/// This wrapper involves an extra copy and MAY BE SLOWER
fn aead_chacha20_seal(ciphertext: &mut [u8], key: &[u8], counter: u64, data: &[u8], aad: &[u8]) {
    let mut nonce: [u8; 12] = [0; 12];
    nonce[4..12].copy_from_slice(&counter.to_le_bytes());

    aead_chacha20_seal_inner(ciphertext, key, nonce, data, aad)
}

#[inline]
fn aead_chacha20_seal_inner(
    ciphertext: &mut [u8],
    key: &[u8],
    nonce: [u8; 12],
    data: &[u8],
    aad: &[u8],
) {
    let key = LessSafeKey::new(UnboundKey::new(&CHACHA20_POLY1305, key).unwrap());

    ciphertext[..data.len()].copy_from_slice(data);

    let tag = key
        .seal_in_place_separate_tag(
            Nonce::assume_unique_for_key(nonce),
            Aad::from(aad),
            &mut ciphertext[..data.len()],
        )
        .unwrap();

    ciphertext[data.len()..].copy_from_slice(tag.as_ref());
}

#[inline]
/// This wrapper involves an extra copy and MAY BE SLOWER
fn aead_chacha20_open(
    buffer: &mut [u8],
    key: &[u8],
    counter: u64,
    data: &[u8],
    aad: &[u8],
) -> Result<(), WireGuardError> {
    let mut nonce: [u8; 12] = [0; 12];
    nonce[4..].copy_from_slice(&counter.to_le_bytes());

    aead_chacha20_open_inner(buffer, key, nonce, data, aad)
        .map_err(|_| WireGuardError::InvalidAeadTag)?;
    Ok(())
}

#[inline]
fn aead_chacha20_open_inner(
    buffer: &mut [u8],
    key: &[u8],
    nonce: [u8; 12],
    data: &[u8],
    aad: &[u8],
) -> Result<(), crate::crypto::error::Unspecified> {
    let key = LessSafeKey::new(UnboundKey::new(&CHACHA20_POLY1305, key).unwrap());

    let mut inner_buffer = data.to_owned();

    let plaintext = key.open_in_place(
        Nonce::assume_unique_for_key(nonce),
        Aad::from(aad),
        &mut inner_buffer,
    )?;

    buffer.copy_from_slice(plaintext);

    Ok(())
}

#[derive(Debug)]
/// This struct represents a 12 byte [`Tai64N`](https://cr.yp.to/libtai/tai64.html) timestamp
struct Tai64N {
    secs: u64,
    nano: u32,
}

#[derive(Debug)]
/// This struct computes a [`Tai64N`](https://cr.yp.to/libtai/tai64.html) timestamp from current system time
struct TimeStamper {
    duration_at_start: Duration,
    instant_at_start: Instant,
}

impl TimeStamper {
    /// Create a new [`TimeStamper`]
    pub fn new() -> TimeStamper {
        TimeStamper {
            duration_at_start: SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .unwrap(),
            instant_at_start: Instant::now(),
        }
    }

    /// Take time reading and generate a 12 byte timestamp
    pub fn stamp(&self) -> [u8; 12] {
        const TAI64_BASE: u64 = (1u64 << 62) + 37;
        let mut ext_stamp = [0u8; 12];
        let stamp = Instant::now().duration_since(self.instant_at_start) + self.duration_at_start;
        ext_stamp[0..8].copy_from_slice(&(stamp.as_secs() + TAI64_BASE).to_be_bytes());
        ext_stamp[8..12].copy_from_slice(&stamp.subsec_nanos().to_be_bytes());
        ext_stamp
    }
}

impl Tai64N {
    /// A zeroed out timestamp
    fn zero() -> Tai64N {
        Tai64N { secs: 0, nano: 0 }
    }

    /// Parse a timestamp from a 12 byte u8 slice
    fn parse(buf: &[u8; 12]) -> Result<Tai64N, WireGuardError> {
        if buf.len() < 12 {
            return Err(WireGuardError::InvalidTai64nTimestamp);
        }

        let (sec_bytes, nano_bytes) = buf.split_at(std::mem::size_of::<u64>());
        let secs = u64::from_be_bytes(sec_bytes.try_into().unwrap());
        let nano = u32::from_be_bytes(nano_bytes.try_into().unwrap());

        // WireGuard does not actually expect tai64n timestamp, just monotonically increasing one
        //if secs < (1u64 << 62) || secs >= (1u64 << 63) {
        //    return Err(WireGuardError::InvalidTai64nTimestamp);
        //};
        //if nano >= 1_000_000_000 {
        //   return Err(WireGuardError::InvalidTai64nTimestamp);
        //}

        Ok(Tai64N { secs, nano })
    }

    /// Check if this timestamp represents a time that is chronologically after the time represented
    /// by the other timestamp
    pub fn after(&self, other: &Tai64N) -> bool {
        (self.secs > other.secs) || ((self.secs == other.secs) && (self.nano > other.nano))
    }
}

/// Parameters used by the noise protocol
struct NoiseParams {
    /// Our static public key
    static_public: x25519::PublicKey,
    /// Our static private key
    static_private: x25519::StaticSecret,
    /// Static public key of the other party
    peer_static_public: x25519::PublicKey,
    /// A shared key = DH(`static_private`, `peer_static_public`)
    static_shared: [u8; 32],
    /// A pre-computation of HASH("mac1----", `peer_static_public`) for this peer
    sending_mac1_key: [u8; KEY_LEN],
    /// An optional preshared key
    preshared_key: Option<[u8; KEY_LEN]>,
}

impl std::fmt::Debug for NoiseParams {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("NoiseParams")
            .field("static_public", &self.static_public)
            .field("static_private", &"<redacted>")
            .field("peer_static_public", &self.peer_static_public)
            .field("static_shared", &"<redacted>")
            .field("sending_mac1_key", &self.sending_mac1_key)
            .field("preshared_key", &self.preshared_key)
            .finish()
    }
}

struct HandshakeInitSentState {
    local_index: Index,
    hash: [u8; KEY_LEN],
    chaining_key: [u8; KEY_LEN],
    ephemeral_private: x25519::ReusableSecret,
    time_sent: Instant,
}

impl std::fmt::Debug for HandshakeInitSentState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HandshakeInitSentState")
            .field("local_index", &self.local_index)
            .field("hash", &self.hash)
            .field("chaining_key", &self.chaining_key)
            .field("ephemeral_private", &"<redacted>")
            .field("time_sent", &self.time_sent)
            .finish()
    }
}

#[derive(Debug)]
enum HandshakeState {
    /// No handshake in process
    None,
    /// We initiated the handshake
    InitSent(HandshakeInitSentState),
    /// Handshake initiated by peer
    InitReceived {
        hash: [u8; KEY_LEN],
        chaining_key: [u8; KEY_LEN],
        peer_ephemeral_public: x25519::PublicKey,
        peer_index: u32,
    },
    /// Handshake was established too long ago (implies no handshake is in progress)
    Expired,
}

/// WireGuard handshake state machine.
///
/// Manages the Noise protocol handshake for establishing secure sessions.
pub struct Handshake {
    params: NoiseParams,
    /// Shared table for session indices.
    index_table: IndexTable,
    /// Allow to have two outgoing handshakes in flight, because sometimes we may receive a delayed
    /// response to a handshake with bad networks
    previous: HandshakeState,
    /// Current handshake state
    state: HandshakeState,
    cookies: Cookies,
    /// The timestamp of the last handshake we received
    last_handshake_timestamp: Tai64N,
    // TODO: make TimeStamper a singleton
    stamper: TimeStamper,
    pub(super) last_rtt: Option<u32>,
}

#[derive(Default)]
struct Cookies {
    last_mac1: Option<[u8; 16]>,
    index: u32,
    write_cookie: Option<[u8; 16]>,
}

#[derive(Debug)]
/// Partial handshake information extracted from a handshake initiation packet.
///
/// This contains the peer's index and public key without requiring knowledge
/// of which peer sent the packet.
pub struct HalfHandshake {
    /// The sender's session index.
    pub peer_index: u32,
    /// The sender's static public key.
    pub peer_static_public: [u8; 32],
}

/// Parse a handshake initiation packet without prior knowledge of the sender.
///
/// This performs cryptographic validation to extract the peer's public key
/// from the handshake initiation, allowing the receiver to identify the peer.
///
/// # Errors
///
/// Returns an error if the packet is malformed or cryptographic validation fails.
pub fn parse_handshake_anon(
    static_private: &x25519::StaticSecret,
    static_public: &x25519::PublicKey,
    packet: &WgHandshakeInit,
) -> Result<HalfHandshake, WireGuardError> {
    let peer_index = packet.sender_idx.get();
    // initiator.chaining_key = HASH(CONSTRUCTION)
    let mut chaining_key = INITIAL_CHAIN_KEY;
    // initiator.hash = HASH(HASH(initiator.chaining_key || IDENTIFIER) || responder.static_public)
    let mut hash = INITIAL_CHAIN_HASH;
    hash = b2s_hash(&hash, static_public.as_bytes());
    // msg.unencrypted_ephemeral = DH_PUBKEY(initiator.ephemeral_private)
    let peer_ephemeral_public = x25519::PublicKey::from(packet.unencrypted_ephemeral);
    // initiator.hash = HASH(initiator.hash || msg.unencrypted_ephemeral)
    hash = b2s_hash(&hash, peer_ephemeral_public.as_bytes());
    // temp = HMAC(initiator.chaining_key, msg.unencrypted_ephemeral)
    // initiator.chaining_key = HMAC(temp, 0x1)
    chaining_key = b2s_hmac(
        &b2s_hmac(&chaining_key, peer_ephemeral_public.as_bytes()),
        &[0x01],
    );
    // temp = HMAC(initiator.chaining_key, DH(initiator.ephemeral_private, responder.static_public))
    let ephemeral_shared = static_private.diffie_hellman(&peer_ephemeral_public);
    let temp = b2s_hmac(&chaining_key, &ephemeral_shared.to_bytes());
    // initiator.chaining_key = HMAC(temp, 0x1)
    chaining_key = b2s_hmac(&temp, &[0x01]);
    // key = HMAC(temp, initiator.chaining_key || 0x2)
    let key = b2s_hmac2(&temp, &chaining_key, &[0x02]);

    let mut peer_static_public = [0u8; KEY_LEN];
    // msg.encrypted_static = AEAD(key, 0, initiator.static_public, initiator.hash)
    aead_chacha20_open(
        &mut peer_static_public,
        &key,
        0,
        packet.encrypted_static.as_bytes(),
        &hash,
    )?;

    Ok(HalfHandshake {
        peer_index,
        peer_static_public,
    })
}

impl NoiseParams {
    /// New noise params struct from our secret key, peers public key, and optional preshared key
    fn new(
        static_private: x25519::StaticSecret,
        static_public: x25519::PublicKey,
        peer_static_public: x25519::PublicKey,
        preshared_key: Option<[u8; 32]>,
    ) -> NoiseParams {
        let static_shared = static_private.diffie_hellman(&peer_static_public);
        let initial_sending_mac_key = b2s_hash(LABEL_MAC1, peer_static_public.as_bytes());

        NoiseParams {
            static_public,
            static_private,
            peer_static_public,
            static_shared: static_shared.to_bytes(),
            sending_mac1_key: initial_sending_mac_key,
            preshared_key,
        }
    }

    /// Set a new private key
    fn set_static_private(
        &mut self,
        static_private: x25519::StaticSecret,
        static_public: x25519::PublicKey,
    ) {
        let check_key = x25519::PublicKey::from(&static_private);
        assert_eq!(check_key.as_bytes(), static_public.as_bytes());

        self.static_private = static_private;
        self.static_public = static_public;

        self.static_shared = self.static_private.diffie_hellman(&self.peer_static_public).to_bytes();
    }

    fn set_preshared_key(&mut self, preshared_key: Option<[u8; 32]>) {
        self.preshared_key = preshared_key;
    }
}

include!("handshake_part1.rs");
include!("handshake_part2.rs");