//! Signal protocol v3 wire formats.
//!
//! Field numbers are transcribed from `signal-protocol-java` 2.8.1's generated
//! `SignalProtos` constants (read via `javap -constants`), not from memory —
//! `PreKeySignalMessage` in particular is *not* numbered sequentially.
//!
//!   SignalMessage                { ratchetKey=1, counter=2, previousCounter=3, ciphertext=4 }
//!   PreKeySignalMessage          { preKeyId=1, baseKey=2, identityKey=3, message=4,
//!                                  registrationId=5, signedPreKeyId=6 }
//!   SenderKeyMessage             { id=1, iteration=2, ciphertext=3 }
//!   SenderKeyDistributionMessage { id=1, iteration=2, chainKey=3, signingKey=4 }

use crate::crypto::{self, CryptoError, Result};

/// `CiphertextMessage.CURRENT_VERSION`
pub const CURRENT_VERSION: u8 = 3;
/// `SignalMessage.MAC_LENGTH`
pub const MAC_LENGTH: usize = 8;

pub const WHISPER_TYPE: u8 = 2;
pub const PREKEY_TYPE: u8 = 3;
pub const SENDERKEY_TYPE: u8 = 4;
pub const SENDERKEY_DISTRIBUTION_TYPE: u8 = 5;

// ---------------------------------------------------------------------------
// Minimal protobuf codec (only the two wire types these messages use)
// ---------------------------------------------------------------------------

pub struct PbWriter {
    pub buf: Vec<u8>,
}

impl Default for PbWriter {
    fn default() -> Self {
        Self::new()
    }
}

impl PbWriter {
    pub fn new() -> PbWriter {
        PbWriter { buf: Vec::new() }
    }

    fn tag(&mut self, field: u32, wire_type: u32) {
        self.varint(((field << 3) | wire_type) as u64);
    }

    fn varint(&mut self, mut v: u64) {
        loop {
            let byte = (v & 0x7f) as u8;
            v >>= 7;
            if v == 0 {
                self.buf.push(byte);
                return;
            }
            self.buf.push(byte | 0x80);
        }
    }

    pub fn uint32(&mut self, field: u32, value: u32) {
        self.tag(field, 0);
        self.varint(value as u64);
    }

    pub fn bytes(&mut self, field: u32, value: &[u8]) {
        self.tag(field, 2);
        self.varint(value.len() as u64);
        self.buf.extend_from_slice(value);
    }
}

pub struct PbReader<'a> {
    data: &'a [u8],
    pos: usize,
}

pub enum PbValue<'a> {
    Varint(u64),
    Bytes(&'a [u8]),
}

impl<'a> PbReader<'a> {
    pub fn new(data: &'a [u8]) -> PbReader<'a> {
        PbReader { data, pos: 0 }
    }

    fn varint(&mut self) -> Result<u64> {
        let mut out: u64 = 0;
        let mut shift = 0;
        loop {
            if self.pos >= self.data.len() {
                return Err(CryptoError("truncated varint"));
            }
            if shift >= 64 {
                return Err(CryptoError("varint too long"));
            }
            let b = self.data[self.pos];
            self.pos += 1;
            out |= ((b & 0x7f) as u64) << shift;
            if b & 0x80 == 0 {
                return Ok(out);
            }
            shift += 7;
        }
    }

    /// Yields (field number, value). Unknown fields are skipped by the caller
    /// simply ignoring field numbers it does not recognise.
    pub fn next_field(&mut self) -> Result<Option<(u32, PbValue<'a>)>> {
        if self.pos >= self.data.len() {
            return Ok(None);
        }
        let key = self.varint()?;
        let field = (key >> 3) as u32;
        match key & 7 {
            0 => Ok(Some((field, PbValue::Varint(self.varint()?)))),
            2 => {
                let len = self.varint()? as usize;
                if self.pos + len > self.data.len() {
                    return Err(CryptoError("truncated length-delimited field"));
                }
                let slice = &self.data[self.pos..self.pos + len];
                self.pos += len;
                Ok(Some((field, PbValue::Bytes(slice))))
            }
            1 => {
                if self.pos + 8 > self.data.len() {
                    return Err(CryptoError("truncated 64-bit field"));
                }
                self.pos += 8;
                Ok(Some((field, PbValue::Varint(0))))
            }
            5 => {
                if self.pos + 4 > self.data.len() {
                    return Err(CryptoError("truncated 32-bit field"));
                }
                self.pos += 4;
                Ok(Some((field, PbValue::Varint(0))))
            }
            _ => Err(CryptoError("unsupported protobuf wire type")),
        }
    }
}

fn version_byte(version: u8) -> u8 {
    (version << 4) | CURRENT_VERSION
}

fn check_version(b: u8) -> Result<()> {
    let v = b >> 4;
    if v < CURRENT_VERSION {
        return Err(CryptoError("legacy message version"));
    }
    if v > CURRENT_VERSION {
        return Err(CryptoError("unknown message version"));
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// SignalMessage  (version || protobuf || mac[8])
// ---------------------------------------------------------------------------

pub struct SignalMessage {
    pub ratchet_key: [u8; 32],
    pub counter: u32,
    pub previous_counter: u32,
    pub ciphertext: Vec<u8>,
    /// Full serialized form, needed to re-verify the MAC.
    pub serialized: Vec<u8>,
}

impl SignalMessage {
    pub fn new(
        mac_key: &[u8; 32],
        sender_identity: &[u8; 32],
        receiver_identity: &[u8; 32],
        ratchet_key: &[u8; 32],
        counter: u32,
        previous_counter: u32,
        ciphertext: &[u8],
    ) -> SignalMessage {
        let mut w = PbWriter::new();
        w.bytes(1, &crypto::serialize_public(ratchet_key));
        w.uint32(2, counter);
        w.uint32(3, previous_counter);
        w.bytes(4, ciphertext);

        let mut out = Vec::with_capacity(1 + w.buf.len() + MAC_LENGTH);
        out.push(version_byte(CURRENT_VERSION));
        out.extend_from_slice(&w.buf);
        let mac = Self::mac(mac_key, sender_identity, receiver_identity, &out);
        out.extend_from_slice(&mac);

        SignalMessage {
            ratchet_key: *ratchet_key,
            counter,
            previous_counter,
            ciphertext: ciphertext.to_vec(),
            serialized: out,
        }
    }

    /// `SignalMessage.getMac`: HMAC over senderIdentity || receiverIdentity || message,
    /// truncated to 8 bytes.
    fn mac(
        mac_key: &[u8; 32],
        sender_identity: &[u8; 32],
        receiver_identity: &[u8; 32],
        serialized_without_mac: &[u8],
    ) -> [u8; MAC_LENGTH] {
        let mut buf = Vec::new();
        buf.extend_from_slice(&crypto::serialize_public(sender_identity));
        buf.extend_from_slice(&crypto::serialize_public(receiver_identity));
        buf.extend_from_slice(serialized_without_mac);
        let full = crypto::hmac_sha256(mac_key, &buf);
        let mut out = [0u8; MAC_LENGTH];
        out.copy_from_slice(&full[..MAC_LENGTH]);
        out
    }

    pub fn parse(data: &[u8]) -> Result<SignalMessage> {
        if data.len() < 1 + MAC_LENGTH {
            return Err(CryptoError("SignalMessage too short"));
        }
        check_version(data[0])?;
        let body = &data[1..data.len() - MAC_LENGTH];

        let mut ratchet_key = None;
        let mut counter = None;
        let mut previous_counter = 0u32;
        let mut ciphertext = None;

        let mut r = PbReader::new(body);
        while let Some((field, value)) = r.next_field()? {
            match (field, value) {
                (1, PbValue::Bytes(b)) => ratchet_key = Some(crypto::parse_public(b)?),
                (2, PbValue::Varint(v)) => counter = Some(v as u32),
                (3, PbValue::Varint(v)) => previous_counter = v as u32,
                (4, PbValue::Bytes(b)) => ciphertext = Some(b.to_vec()),
                _ => {}
            }
        }

        Ok(SignalMessage {
            ratchet_key: ratchet_key.ok_or(CryptoError("SignalMessage missing ratchetKey"))?,
            counter: counter.ok_or(CryptoError("SignalMessage missing counter"))?,
            previous_counter,
            ciphertext: ciphertext.ok_or(CryptoError("SignalMessage missing ciphertext"))?,
            serialized: data.to_vec(),
        })
    }

    pub fn verify_mac(
        &self,
        mac_key: &[u8; 32],
        sender_identity: &[u8; 32],
        receiver_identity: &[u8; 32],
    ) -> bool {
        if self.serialized.len() < MAC_LENGTH {
            return false;
        }
        let split = self.serialized.len() - MAC_LENGTH;
        let expected = Self::mac(mac_key, sender_identity, receiver_identity, &self.serialized[..split]);
        crypto::ct_eq(&self.serialized[split..], &expected)
    }
}

// ---------------------------------------------------------------------------
// PreKeySignalMessage  (version || protobuf)
// ---------------------------------------------------------------------------

pub struct PreKeySignalMessage {
    pub registration_id: u32,
    pub pre_key_id: Option<u32>,
    pub signed_pre_key_id: u32,
    pub base_key: [u8; 32],
    pub identity_key: [u8; 32],
    pub message: Vec<u8>,
}

impl PreKeySignalMessage {
    pub fn serialize(&self) -> Vec<u8> {
        let mut w = PbWriter::new();
        if let Some(id) = self.pre_key_id {
            w.uint32(1, id);
        }
        w.bytes(2, &crypto::serialize_public(&self.base_key));
        w.bytes(3, &crypto::serialize_public(&self.identity_key));
        w.bytes(4, &self.message);
        w.uint32(5, self.registration_id);
        w.uint32(6, self.signed_pre_key_id);

        let mut out = Vec::with_capacity(1 + w.buf.len());
        out.push(version_byte(CURRENT_VERSION));
        out.extend_from_slice(&w.buf);
        out
    }

    pub fn parse(data: &[u8]) -> Result<PreKeySignalMessage> {
        if data.is_empty() {
            return Err(CryptoError("PreKeySignalMessage empty"));
        }
        check_version(data[0])?;

        let mut registration_id = 0u32;
        let mut pre_key_id = None;
        let mut signed_pre_key_id = None;
        let mut base_key = None;
        let mut identity_key = None;
        let mut message = None;

        let mut r = PbReader::new(&data[1..]);
        while let Some((field, value)) = r.next_field()? {
            match (field, value) {
                (1, PbValue::Varint(v)) => pre_key_id = Some(v as u32),
                (2, PbValue::Bytes(b)) => base_key = Some(crypto::parse_public(b)?),
                (3, PbValue::Bytes(b)) => identity_key = Some(crypto::parse_public(b)?),
                (4, PbValue::Bytes(b)) => message = Some(b.to_vec()),
                (5, PbValue::Varint(v)) => registration_id = v as u32,
                (6, PbValue::Varint(v)) => signed_pre_key_id = Some(v as u32),
                _ => {}
            }
        }

        Ok(PreKeySignalMessage {
            registration_id,
            pre_key_id,
            signed_pre_key_id: signed_pre_key_id
                .ok_or(CryptoError("PreKeySignalMessage missing signedPreKeyId"))?,
            base_key: base_key.ok_or(CryptoError("PreKeySignalMessage missing baseKey"))?,
            identity_key: identity_key
                .ok_or(CryptoError("PreKeySignalMessage missing identityKey"))?,
            message: message.ok_or(CryptoError("PreKeySignalMessage missing message"))?,
        })
    }
}

// ---------------------------------------------------------------------------
// SenderKeyMessage  (version || protobuf || signature[64])
// ---------------------------------------------------------------------------

pub struct SenderKeyMessage {
    pub key_id: u32,
    pub iteration: u32,
    pub ciphertext: Vec<u8>,
    pub serialized: Vec<u8>,
}

impl SenderKeyMessage {
    pub fn new(
        key_id: u32,
        iteration: u32,
        ciphertext: &[u8],
        signing_private: &[u8; 32],
        random: &[u8; 64],
    ) -> SenderKeyMessage {
        let mut w = PbWriter::new();
        w.uint32(1, key_id);
        w.uint32(2, iteration);
        w.bytes(3, ciphertext);

        let mut out = Vec::with_capacity(1 + w.buf.len() + 64);
        out.push(version_byte(CURRENT_VERSION));
        out.extend_from_slice(&w.buf);
        let sig = crypto::sign(signing_private, &out, random);
        out.extend_from_slice(&sig);

        SenderKeyMessage {
            key_id,
            iteration,
            ciphertext: ciphertext.to_vec(),
            serialized: out,
        }
    }

    pub fn parse(data: &[u8]) -> Result<SenderKeyMessage> {
        if data.len() < 1 + 64 {
            return Err(CryptoError("SenderKeyMessage too short"));
        }
        check_version(data[0])?;
        let body = &data[1..data.len() - 64];

        let mut key_id = None;
        let mut iteration = None;
        let mut ciphertext = None;
        let mut r = PbReader::new(body);
        while let Some((field, value)) = r.next_field()? {
            match (field, value) {
                (1, PbValue::Varint(v)) => key_id = Some(v as u32),
                (2, PbValue::Varint(v)) => iteration = Some(v as u32),
                (3, PbValue::Bytes(b)) => ciphertext = Some(b.to_vec()),
                _ => {}
            }
        }

        Ok(SenderKeyMessage {
            key_id: key_id.ok_or(CryptoError("SenderKeyMessage missing id"))?,
            iteration: iteration.ok_or(CryptoError("SenderKeyMessage missing iteration"))?,
            ciphertext: ciphertext.ok_or(CryptoError("SenderKeyMessage missing ciphertext"))?,
            serialized: data.to_vec(),
        })
    }

    pub fn verify_signature(&self, signing_public: &[u8; 32]) -> bool {
        if self.serialized.len() < 64 {
            return false;
        }
        let split = self.serialized.len() - 64;
        let mut sig = [0u8; 64];
        sig.copy_from_slice(&self.serialized[split..]);
        crypto::verify(signing_public, &self.serialized[..split], &sig)
    }
}

// ---------------------------------------------------------------------------
// SenderKeyDistributionMessage  (version || protobuf)
// ---------------------------------------------------------------------------

pub struct SenderKeyDistributionMessage {
    pub key_id: u32,
    pub iteration: u32,
    pub chain_key: Vec<u8>,
    pub signing_key: [u8; 32],
}

impl SenderKeyDistributionMessage {
    pub fn serialize(&self) -> Vec<u8> {
        let mut w = PbWriter::new();
        w.uint32(1, self.key_id);
        w.uint32(2, self.iteration);
        w.bytes(3, &self.chain_key);
        w.bytes(4, &crypto::serialize_public(&self.signing_key));

        let mut out = Vec::with_capacity(1 + w.buf.len());
        out.push(version_byte(CURRENT_VERSION));
        out.extend_from_slice(&w.buf);
        out
    }

    pub fn parse(data: &[u8]) -> Result<SenderKeyDistributionMessage> {
        if data.is_empty() {
            return Err(CryptoError("SenderKeyDistributionMessage empty"));
        }
        check_version(data[0])?;

        let mut key_id = None;
        let mut iteration = None;
        let mut chain_key = None;
        let mut signing_key = None;
        let mut r = PbReader::new(&data[1..]);
        while let Some((field, value)) = r.next_field()? {
            match (field, value) {
                (1, PbValue::Varint(v)) => key_id = Some(v as u32),
                (2, PbValue::Varint(v)) => iteration = Some(v as u32),
                (3, PbValue::Bytes(b)) => chain_key = Some(b.to_vec()),
                (4, PbValue::Bytes(b)) => signing_key = Some(crypto::parse_public(b)?),
                _ => {}
            }
        }

        Ok(SenderKeyDistributionMessage {
            key_id: key_id.ok_or(CryptoError("SKDM missing id"))?,
            iteration: iteration.ok_or(CryptoError("SKDM missing iteration"))?,
            chain_key: chain_key.ok_or(CryptoError("SKDM missing chainKey"))?,
            signing_key: signing_key.ok_or(CryptoError("SKDM missing signingKey"))?,
        })
    }
}
