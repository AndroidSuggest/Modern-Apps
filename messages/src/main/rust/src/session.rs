//! X3DH session establishment + the Double Ratchet, and the session record format.
//!
//! Because the migration accepts a WhatsApp re-link, the persisted record format is ours
//! rather than `signal-protocol-java`'s protobuf `SessionStructure`. It is a plain,
//! explicit, versioned encoding — easier to audit than a reimplementation of someone
//! else's serializer. The *wire* formats in `wire.rs` remain byte-exact; only what we
//! write to our own database changed.

use crate::crypto::{self, CryptoError, MessageKeys, Result};
use crate::wire::{PreKeySignalMessage, SignalMessage};

/// Bump if the on-disk layout changes; older records are then discarded and the
/// session is rebuilt from a fresh prekey bundle.
const RECORD_VERSION: u8 = 1;

/// Cap on skipped message keys retained per chain, matching libsignal's
/// `MAX_MESSAGE_KEYS` so a malicious counter can't force unbounded growth.
const MAX_MESSAGE_KEYS: usize = 2000;
/// Cap on retained receiver chains (libsignal's `MAX_RECEIVER_CHAINS`).
const MAX_RECEIVER_CHAINS: usize = 5;

#[derive(Clone)]
pub struct ChainKey {
    pub key: [u8; 32],
    pub index: u32,
}

impl ChainKey {
    fn base_material(&self, seed: u8) -> [u8; 32] {
        crypto::hmac_sha256(&self.key, &[seed])
    }

    pub fn next(&self) -> ChainKey {
        ChainKey {
            key: self.base_material(crypto::CHAIN_KEY_SEED),
            index: self.index + 1,
        }
    }

    pub fn message_keys(&self) -> MessageKeys {
        MessageKeys::derive(&self.base_material(crypto::MESSAGE_KEY_SEED))
    }
}

#[derive(Clone)]
pub struct SkippedKey {
    pub index: u32,
    pub cipher_key: [u8; 32],
    pub mac_key: [u8; 32],
    pub iv: [u8; 16],
}

#[derive(Clone)]
pub struct ReceiverChain {
    pub ratchet_key: [u8; 32],
    pub chain_key: ChainKey,
    pub skipped: Vec<SkippedKey>,
}

#[derive(Clone)]
pub struct SessionState {
    pub root_key: [u8; 32],
    pub local_identity: [u8; 32],
    pub remote_identity: [u8; 32],
    pub remote_registration_id: u32,
    /// Our current sending ratchet key pair.
    pub sender_ratchet_private: [u8; 32],
    pub sender_ratchet_public: [u8; 32],
    pub sender_chain: Option<ChainKey>,
    pub previous_counter: u32,
    pub receiver_chains: Vec<ReceiverChain>,
    /// Set on the responder side until the first inbound message arrives, so
    /// outgoing messages are wrapped as `pkmsg`.
    pub pending_pre_key: Option<PendingPreKey>,
}

#[derive(Clone)]
pub struct PendingPreKey {
    pub pre_key_id: Option<u32>,
    pub signed_pre_key_id: u32,
    pub base_key: [u8; 32],
    pub local_registration_id: u32,
}

// ---------------------------------------------------------------------------
// Record serialization
// ---------------------------------------------------------------------------

struct Buf {
    v: Vec<u8>,
}

impl Buf {
    fn new() -> Buf {
        Buf { v: Vec::new() }
    }
    fn u8(&mut self, x: u8) {
        self.v.push(x);
    }
    fn u32(&mut self, x: u32) {
        self.v.extend_from_slice(&x.to_be_bytes());
    }
    fn fixed(&mut self, x: &[u8]) {
        self.v.extend_from_slice(x);
    }
    fn blob(&mut self, x: &[u8]) {
        self.u32(x.len() as u32);
        self.v.extend_from_slice(x);
    }
}

struct Cur<'a> {
    d: &'a [u8],
    p: usize,
}

impl<'a> Cur<'a> {
    fn new(d: &'a [u8]) -> Cur<'a> {
        Cur { d, p: 0 }
    }
    fn u8(&mut self) -> Result<u8> {
        if self.p >= self.d.len() {
            return Err(CryptoError("truncated session record"));
        }
        let x = self.d[self.p];
        self.p += 1;
        Ok(x)
    }
    fn u32(&mut self) -> Result<u32> {
        if self.p + 4 > self.d.len() {
            return Err(CryptoError("truncated session record"));
        }
        let x = u32::from_be_bytes(self.d[self.p..self.p + 4].try_into().unwrap());
        self.p += 4;
        Ok(x)
    }
    fn arr32(&mut self) -> Result<[u8; 32]> {
        if self.p + 32 > self.d.len() {
            return Err(CryptoError("truncated session record"));
        }
        let mut o = [0u8; 32];
        o.copy_from_slice(&self.d[self.p..self.p + 32]);
        self.p += 32;
        Ok(o)
    }
    fn arr16(&mut self) -> Result<[u8; 16]> {
        if self.p + 16 > self.d.len() {
            return Err(CryptoError("truncated session record"));
        }
        let mut o = [0u8; 16];
        o.copy_from_slice(&self.d[self.p..self.p + 16]);
        self.p += 16;
        Ok(o)
    }
    fn done(&self) -> bool {
        self.p >= self.d.len()
    }
}

impl SessionState {
    pub fn serialize(&self) -> Vec<u8> {
        let mut b = Buf::new();
        b.u8(RECORD_VERSION);
        b.fixed(&self.root_key);
        b.fixed(&self.local_identity);
        b.fixed(&self.remote_identity);
        b.u32(self.remote_registration_id);
        b.fixed(&self.sender_ratchet_private);
        b.fixed(&self.sender_ratchet_public);
        match &self.sender_chain {
            Some(c) => {
                b.u8(1);
                b.fixed(&c.key);
                b.u32(c.index);
            }
            None => b.u8(0),
        }
        b.u32(self.previous_counter);

        b.u32(self.receiver_chains.len() as u32);
        for rc in &self.receiver_chains {
            b.fixed(&rc.ratchet_key);
            b.fixed(&rc.chain_key.key);
            b.u32(rc.chain_key.index);
            b.u32(rc.skipped.len() as u32);
            for sk in &rc.skipped {
                b.u32(sk.index);
                b.fixed(&sk.cipher_key);
                b.fixed(&sk.mac_key);
                b.fixed(&sk.iv);
            }
        }

        match &self.pending_pre_key {
            Some(p) => {
                b.u8(1);
                b.u32(p.pre_key_id.unwrap_or(0));
                b.u8(if p.pre_key_id.is_some() { 1 } else { 0 });
                b.u32(p.signed_pre_key_id);
                b.fixed(&p.base_key);
                b.u32(p.local_registration_id);
            }
            None => b.u8(0),
        }
        b.v
    }

    pub fn deserialize(data: &[u8]) -> Result<SessionState> {
        let mut c = Cur::new(data);
        if c.u8()? != RECORD_VERSION {
            return Err(CryptoError("unsupported session record version"));
        }
        let root_key = c.arr32()?;
        let local_identity = c.arr32()?;
        let remote_identity = c.arr32()?;
        let remote_registration_id = c.u32()?;
        let sender_ratchet_private = c.arr32()?;
        let sender_ratchet_public = c.arr32()?;
        let sender_chain = if c.u8()? == 1 {
            let key = c.arr32()?;
            let index = c.u32()?;
            Some(ChainKey { key, index })
        } else {
            None
        };
        let previous_counter = c.u32()?;

        let n_chains = c.u32()? as usize;
        if n_chains > MAX_RECEIVER_CHAINS {
            return Err(CryptoError("too many receiver chains"));
        }
        let mut receiver_chains = Vec::with_capacity(n_chains);
        for _ in 0..n_chains {
            let ratchet_key = c.arr32()?;
            let key = c.arr32()?;
            let index = c.u32()?;
            let n_skipped = c.u32()? as usize;
            if n_skipped > MAX_MESSAGE_KEYS {
                return Err(CryptoError("too many skipped message keys"));
            }
            let mut skipped = Vec::with_capacity(n_skipped);
            for _ in 0..n_skipped {
                skipped.push(SkippedKey {
                    index: c.u32()?,
                    cipher_key: c.arr32()?,
                    mac_key: c.arr32()?,
                    iv: c.arr16()?,
                });
            }
            receiver_chains.push(ReceiverChain {
                ratchet_key,
                chain_key: ChainKey { key, index },
                skipped,
            });
        }

        let pending_pre_key = if c.u8()? == 1 {
            let id = c.u32()?;
            let has_id = c.u8()? == 1;
            Some(PendingPreKey {
                pre_key_id: if has_id { Some(id) } else { None },
                signed_pre_key_id: c.u32()?,
                base_key: c.arr32()?,
                local_registration_id: c.u32()?,
            })
        } else {
            None
        };

        if !c.done() {
            return Err(CryptoError("trailing bytes in session record"));
        }

        Ok(SessionState {
            root_key,
            local_identity,
            remote_identity,
            remote_registration_id,
            sender_ratchet_private,
            sender_ratchet_public,
            sender_chain,
            previous_counter,
            receiver_chains,
            pending_pre_key,
        })
    }
}

// ---------------------------------------------------------------------------
// Root key ratchet
// ---------------------------------------------------------------------------

/// `RootKey.createChain`: HKDF(DH, salt = rootKey, info = "WhisperRatchet", 64)
fn ratchet_root(root_key: &[u8; 32], dh: &[u8; 32]) -> ([u8; 32], ChainKey) {
    let d = crypto::hkdf(dh, root_key, crypto::INFO_RATCHET, crypto::ROOT_SECRETS_LEN);
    let mut new_root = [0u8; 32];
    let mut chain = [0u8; 32];
    new_root.copy_from_slice(&d[0..32]);
    chain.copy_from_slice(&d[32..64]);
    (new_root, ChainKey { key: chain, index: 0 })
}

/// X3DH master secret -> (rootKey, chainKey), per `RatchetingSession`:
/// 0xFF*32 || DH1 || DH2 || DH3 [|| DH4], then HKDF(info = "WhisperText", 64).
fn derive_initial(secrets: &[[u8; 32]]) -> ([u8; 32], ChainKey) {
    let mut ikm = Vec::with_capacity(32 + secrets.len() * 32);
    ikm.extend_from_slice(&[0xffu8; 32]);
    for s in secrets {
        ikm.extend_from_slice(s);
    }
    let d = crypto::hkdf(&ikm, &[], crypto::INFO_TEXT, crypto::ROOT_SECRETS_LEN);
    let mut root = [0u8; 32];
    let mut chain = [0u8; 32];
    root.copy_from_slice(&d[0..32]);
    chain.copy_from_slice(&d[32..64]);
    (root, ChainKey { key: chain, index: 0 })
}

pub struct PreKeyBundle {
    pub registration_id: u32,
    pub pre_key_id: Option<u32>,
    pub pre_key_public: Option<[u8; 32]>,
    pub signed_pre_key_id: u32,
    pub signed_pre_key_public: [u8; 32],
    pub signed_pre_key_signature: Vec<u8>,
    pub identity_key: [u8; 32],
}

/// Alice side of X3DH (`RatchetingSession.initializeSession` for the initiator).
#[allow(clippy::too_many_arguments)]
pub fn process_pre_key_bundle<R: rand_core::RngCore + rand_core::CryptoRng>(
    rng: &mut R,
    bundle: &PreKeyBundle,
    local_identity_private: &[u8; 32],
    local_identity_public: &[u8; 32],
    local_registration_id: u32,
) -> Result<SessionState> {
    // The signed prekey signature is over 0x05 || signedPreKeyPublic.
    if bundle.signed_pre_key_signature.len() != 64 {
        return Err(CryptoError("signed prekey signature must be 64 bytes"));
    }
    let mut sig = [0u8; 64];
    sig.copy_from_slice(&bundle.signed_pre_key_signature);
    if !crypto::verify(
        &bundle.identity_key,
        &crypto::serialize_public(&bundle.signed_pre_key_public),
        &sig,
    ) {
        return Err(CryptoError("signed prekey signature is invalid"));
    }

    let (base_private, base_public) = crypto::generate_key_pair(rng);

    // DH1 = IK_a x SPK_b, DH2 = EK_a x IK_b, DH3 = EK_a x SPK_b, DH4 = EK_a x OPK_b
    let mut secrets = vec![
        crypto::agreement(local_identity_private, &bundle.signed_pre_key_public),
        crypto::agreement(&base_private, &bundle.identity_key),
        crypto::agreement(&base_private, &bundle.signed_pre_key_public),
    ];
    if let Some(opk) = bundle.pre_key_public {
        secrets.push(crypto::agreement(&base_private, &opk));
    }
    let (root_key, chain_key) = derive_initial(&secrets);

    // Alice immediately ratchets forward onto her own sending chain, using the
    // peer's signed prekey as the first receiving ratchet key.
    let (send_private, send_public) = crypto::generate_key_pair(rng);
    let dh = crypto::agreement(&send_private, &bundle.signed_pre_key_public);
    let (root_key, send_chain) = ratchet_root(&root_key, &dh);

    Ok(SessionState {
        root_key,
        local_identity: *local_identity_public,
        remote_identity: bundle.identity_key,
        remote_registration_id: bundle.registration_id,
        sender_ratchet_private: send_private,
        sender_ratchet_public: send_public,
        sender_chain: Some(send_chain),
        previous_counter: 0,
        receiver_chains: vec![ReceiverChain {
            ratchet_key: bundle.signed_pre_key_public,
            chain_key,
            skipped: Vec::new(),
        }],
        pending_pre_key: Some(PendingPreKey {
            pre_key_id: bundle.pre_key_id,
            signed_pre_key_id: bundle.signed_pre_key_id,
            base_key: base_public,
            local_registration_id,
        }),
    })
}

/// Bob side of X3DH: build the session from an inbound `pkmsg`.
#[allow(clippy::too_many_arguments)]
pub fn process_pre_key_message(
    msg: &PreKeySignalMessage,
    local_identity_private: &[u8; 32],
    local_identity_public: &[u8; 32],
    signed_pre_key_private: &[u8; 32],
    one_time_pre_key_private: Option<&[u8; 32]>,
) -> Result<SessionState> {
    let mut secrets = vec![
        crypto::agreement(signed_pre_key_private, &msg.identity_key),
        crypto::agreement(local_identity_private, &msg.base_key),
        crypto::agreement(signed_pre_key_private, &msg.base_key),
    ];
    if let Some(opk) = one_time_pre_key_private {
        secrets.push(crypto::agreement(opk, &msg.base_key));
    }
    let (root_key, chain_key) = derive_initial(&secrets);

    Ok(SessionState {
        root_key,
        local_identity: *local_identity_public,
        remote_identity: msg.identity_key,
        remote_registration_id: msg.registration_id,
        // Bob's first sending ratchet key is his signed prekey.
        sender_ratchet_private: *signed_pre_key_private,
        sender_ratchet_public: crypto::public_from_private(signed_pre_key_private),
        sender_chain: Some(chain_key),
        previous_counter: 0,
        receiver_chains: Vec::new(),
        pending_pre_key: None,
    })
}

// ---------------------------------------------------------------------------
// Encrypt / decrypt
// ---------------------------------------------------------------------------

pub struct Encrypted {
    pub is_pre_key: bool,
    pub body: Vec<u8>,
}

pub fn encrypt(state: &mut SessionState, plaintext: &[u8]) -> Result<Encrypted> {
    let chain = state
        .sender_chain
        .as_ref()
        .ok_or(CryptoError("session has no sending chain"))?
        .clone();
    let keys = chain.message_keys();
    state.sender_chain = Some(chain.next());

    let ciphertext = crypto::aes_cbc_encrypt(&keys.cipher_key, &keys.iv, plaintext);
    let msg = SignalMessage::new(
        &keys.mac_key,
        &state.local_identity,
        &state.remote_identity,
        &state.sender_ratchet_public,
        chain.index,
        state.previous_counter,
        &ciphertext,
    );

    match &state.pending_pre_key {
        Some(p) => {
            let pk = PreKeySignalMessage {
                registration_id: p.local_registration_id,
                pre_key_id: p.pre_key_id,
                signed_pre_key_id: p.signed_pre_key_id,
                base_key: p.base_key,
                identity_key: state.local_identity,
                message: msg.serialized,
            };
            Ok(Encrypted { is_pre_key: true, body: pk.serialize() })
        }
        None => Ok(Encrypted { is_pre_key: false, body: msg.serialized }),
    }
}

pub fn decrypt<R: rand_core::RngCore + rand_core::CryptoRng>(
    rng: &mut R,
    state: &mut SessionState,
    msg: &SignalMessage,
) -> Result<Vec<u8>> {
    // A ratchet key we have not seen starts a new receiving chain.
    if !state.receiver_chains.iter().any(|c| c.ratchet_key == msg.ratchet_key) {
        ratchet(rng, state, &msg.ratchet_key);
    }

    let chain_idx = state
        .receiver_chains
        .iter()
        .position(|c| c.ratchet_key == msg.ratchet_key)
        .ok_or(CryptoError("no receiver chain for ratchet key"))?;

    // Replay of an already-skipped message.
    if let Some(pos) = state.receiver_chains[chain_idx]
        .skipped
        .iter()
        .position(|k| k.index == msg.counter)
    {
        let sk = state.receiver_chains[chain_idx].skipped.remove(pos);
        let keys = MessageKeys { cipher_key: sk.cipher_key, mac_key: sk.mac_key, iv: sk.iv };
        return finish_decrypt(state, msg, &keys);
    }

    let current = state.receiver_chains[chain_idx].chain_key.index;
    if msg.counter < current {
        return Err(CryptoError("duplicate or out-of-order message"));
    }
    if (msg.counter - current) as usize > MAX_MESSAGE_KEYS {
        return Err(CryptoError("message counter jumps too far ahead"));
    }

    // Skip forward, retaining the keys we stepped over.
    let mut chain = state.receiver_chains[chain_idx].chain_key.clone();
    while chain.index < msg.counter {
        let keys = chain.message_keys();
        state.receiver_chains[chain_idx].skipped.push(SkippedKey {
            index: chain.index,
            cipher_key: keys.cipher_key,
            mac_key: keys.mac_key,
            iv: keys.iv,
        });
        chain = chain.next();
    }
    let keys = chain.message_keys();
    state.receiver_chains[chain_idx].chain_key = chain.next();

    let skipped = &mut state.receiver_chains[chain_idx].skipped;
    if skipped.len() > MAX_MESSAGE_KEYS {
        let excess = skipped.len() - MAX_MESSAGE_KEYS;
        skipped.drain(0..excess);
    }

    finish_decrypt(state, msg, &keys)
}

fn finish_decrypt(
    state: &SessionState,
    msg: &SignalMessage,
    keys: &MessageKeys,
) -> Result<Vec<u8>> {
    // MAC is over remote||local from our point of view (the sender computed
    // sender||receiver, and we are the receiver).
    if !msg.verify_mac(&keys.mac_key, &state.remote_identity, &state.local_identity) {
        return Err(CryptoError("message MAC failed"));
    }
    crypto::aes_cbc_decrypt(&keys.cipher_key, &keys.iv, &msg.ciphertext)
}

/// A full DH ratchet step, triggered by a ratchet key we have not seen before.
///
/// Both halves happen together, as the Double Ratchet requires: first derive the
/// receiving chain from our *current* ratchet private key, then rotate to a fresh
/// key pair and derive the new sending chain from it.
fn ratchet<R: rand_core::RngCore + rand_core::CryptoRng>(
    rng: &mut R,
    state: &mut SessionState,
    their_ratchet_key: &[u8; 32],
) {
    // Close out the current sending chain so the peer can bound its skip loop.
    if let Some(c) = &state.sender_chain {
        state.previous_counter = c.index;
    }

    // Receiving half.
    let dh = crypto::agreement(&state.sender_ratchet_private, their_ratchet_key);
    let (root_key, recv_chain) = ratchet_root(&state.root_key, &dh);
    state.root_key = root_key;
    state.receiver_chains.push(ReceiverChain {
        ratchet_key: *their_ratchet_key,
        chain_key: recv_chain,
        skipped: Vec::new(),
    });
    if state.receiver_chains.len() > MAX_RECEIVER_CHAINS {
        state.receiver_chains.remove(0);
    }

    // Sending half: rotate our ratchet key pair.
    let (private, public) = crypto::generate_key_pair(rng);
    let dh = crypto::agreement(&private, their_ratchet_key);
    let (root_key, send_chain) = ratchet_root(&state.root_key, &dh);
    state.root_key = root_key;
    state.sender_ratchet_private = private;
    state.sender_ratchet_public = public;
    state.sender_chain = Some(send_chain);

    // Once the peer has replied, our messages are plain `msg` rather than `pkmsg`.
    state.pending_pre_key = None;
}
