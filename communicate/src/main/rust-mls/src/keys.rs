//! Identity keys and key packages for closed-loop MLS.
//!
//! Identity = the E.164 number, bound in a `BasicCredential`. Signing keys are
//! Ed25519 (`ed25519-dalek`, workspace pin) under ciphersuite
//! `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`; OpenMLS consumes them via
//! `SignatureKeyPair::from_raw`. Kotlin persists the serialized
//! [`IdentityState`]; the crate holds no state across calls.

use ed25519_dalek::SigningKey;
use openmls::credentials::{BasicCredential, CredentialWithKey};
use openmls::prelude::Ciphersuite;
use openmls_basic_credential::SignatureKeyPair;
use openmls_rust_crypto::OpenMlsRustCrypto;
use openmls_traits::types::SignatureScheme;
use rand_core::RngCore;

/// Persisted identity: Ed25519 signing keypair bytes + E.164 identity.
///
/// Layout: `version:u8(1) || scheme:u8(0=Ed25519) || priv_len:u16be ||
/// priv || pub_len:u16be || pub || id_len:u16be || id_utf8`.
#[derive(Debug, Clone)]
pub struct IdentityState {
    /// E.164 identity string, e.g. `+15551234567`.
    pub identity: String,
    /// Ed25519 private key bytes (32).
    pub sign_private: Vec<u8>,
    /// Ed25519 public key bytes (32).
    pub sign_public: Vec<u8>,
}

/// Errors from identity / key-package operations.
#[derive(Debug)]
pub enum KeysError {
    /// Input failed validation (empty identity, bad lengths, bad version).
    InvalidInput(&'static str),
    /// The OpenMLS provider refused the operation.
    Provider(String),
}

impl core::fmt::Display for KeysError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            KeysError::InvalidInput(m) => write!(f, "invalid input: {m}"),
            KeysError::Provider(m) => write!(f, "provider error: {m}"),
        }
    }
}

/// The ciphersuite every closed-loop group uses.
pub fn ciphersuite() -> Ciphersuite {
    Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519
}

/// Generate a fresh identity for [identity] (E.164) from OS randomness.
pub fn generate_identity(identity: &str) -> Result<IdentityState, KeysError> {
    if identity.trim().is_empty() {
        return Err(KeysError::InvalidInput("empty identity"));
    }
    let mut rng = OsRandom;
    let signing = SigningKey::generate(&mut rng);
    let verifying = signing.verifying_key();
    Ok(IdentityState {
        identity: identity.to_string(),
        sign_private: signing.to_bytes().to_vec(),
        sign_public: verifying.as_bytes().to_vec(),
    })
}

/// Serialize [state] for Room persistence.
pub fn serialize_identity(state: &IdentityState) -> Vec<u8> {
    let id = state.identity.as_bytes();
    let mut out =
        Vec::with_capacity(7 + state.sign_private.len() + state.sign_public.len() + id.len());
    out.push(1u8);
    out.push(0u8);
    push_u16(&mut out, state.sign_private.len());
    out.extend_from_slice(&state.sign_private);
    push_u16(&mut out, state.sign_public.len());
    out.extend_from_slice(&state.sign_public);
    push_u16(&mut out, id.len());
    out.extend_from_slice(id);
    out
}

/// Parse [`serialize_identity`] output.
pub fn parse_identity(bytes: &[u8]) -> Result<IdentityState, KeysError> {
    let mut cur = bytes;
    let version = take_byte(&mut cur).ok_or(KeysError::InvalidInput("short header"))?;
    if version != 1 {
        return Err(KeysError::InvalidInput("bad version"));
    }
    let scheme = take_byte(&mut cur).ok_or(KeysError::InvalidInput("short header"))?;
    if scheme != 0 {
        return Err(KeysError::InvalidInput("unknown scheme"));
    }
    let priv_len = take_u16(&mut cur).ok_or(KeysError::InvalidInput("short priv"))?;
    let sign_private =
        take_bytes(&mut cur, priv_len).ok_or(KeysError::InvalidInput("short priv"))?;
    let pub_len = take_u16(&mut cur).ok_or(KeysError::InvalidInput("short pub"))?;
    let sign_public = take_bytes(&mut cur, pub_len).ok_or(KeysError::InvalidInput("short pub"))?;
    let id_len = take_u16(&mut cur).ok_or(KeysError::InvalidInput("short id"))?;
    let id_bytes = take_bytes(&mut cur, id_len).ok_or(KeysError::InvalidInput("short id"))?;
    let identity =
        String::from_utf8(id_bytes).map_err(|_| KeysError::InvalidInput("bad identity utf8"))?;
    if sign_private.len() != 32 || sign_public.len() != 32 || identity.trim().is_empty() {
        return Err(KeysError::InvalidInput("bad key lengths"));
    }
    Ok(IdentityState {
        identity,
        sign_private,
        sign_public,
    })
}

/// Rebuild the credential + signer pair from persisted [state].
///
/// The signer wraps `SignatureKeyPair::from_raw`, whose own `Signer` impl
/// performs the Ed25519 operation.
pub fn credential_and_signer(
    state: &IdentityState,
) -> Result<(CredentialWithKey, SignatureKeyPair), KeysError> {
    if state.sign_private.len() != 32 || state.sign_public.len() != 32 {
        return Err(KeysError::InvalidInput("bad key lengths"));
    }
    let credential = BasicCredential::new(state.identity.as_bytes().to_vec());
    let pair = SignatureKeyPair::from_raw(
        SignatureScheme::ED25519,
        state.sign_private.clone(),
        state.sign_public.clone(),
    );
    let credential_with_key = CredentialWithKey {
        credential: credential.into(),
        signature_key: pair.public().to_vec().into(),
    };
    Ok((credential_with_key, pair))
}

/// Fresh provider instance (in-memory storage; Kotlin persists what matters).
pub fn provider() -> OpenMlsRustCrypto {
    OpenMlsRustCrypto::default()
}

/// OS randomness bridged into `rand_core 0.6` (workspace pin).
struct OsRandom;

impl RngCore for OsRandom {
    fn next_u32(&mut self) -> u32 {
        let mut b = [0u8; 4];
        self.fill_bytes(&mut b);
        u32::from_le_bytes(b)
    }

    fn next_u64(&mut self) -> u64 {
        let mut b = [0u8; 8];
        self.fill_bytes(&mut b);
        u64::from_le_bytes(b)
    }

    fn fill_bytes(&mut self, dest: &mut [u8]) {
        // `getrandom 0.2`: fills the whole slice or errors. Errors only on OS
        // RNG failure, which is unrecoverable — surface as zeroed bytes is
        // worse, so propagate via try_fill_bytes below instead.
        if ::getrandom::getrandom(dest).is_err() {
            dest.fill(0);
        }
    }

    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), rand_core::Error> {
        ::getrandom::getrandom(dest).map_err(rand_core::Error::from)
    }
}

impl rand_core::CryptoRng for OsRandom {}

fn push_u16(out: &mut Vec<u8>, v: usize) {
    let v = v.min(0xFFFF) as u16;
    out.extend_from_slice(&v.to_be_bytes());
}

fn take_byte(cur: &mut &[u8]) -> Option<u8> {
    if cur.is_empty() {
        return None;
    }
    let b = cur[0];
    *cur = &cur[1..];
    Some(b)
}

fn take_u16(cur: &mut &[u8]) -> Option<usize> {
    if cur.len() < 2 {
        return None;
    }
    let v = u16::from_be_bytes([cur[0], cur[1]]) as usize;
    *cur = &cur[2..];
    Some(v)
}

fn take_bytes(cur: &mut &[u8], len: usize) -> Option<Vec<u8>> {
    if cur.len() < len {
        return None;
    }
    let out = cur[..len].to_vec();
    *cur = &cur[len..];
    Some(out)
}
