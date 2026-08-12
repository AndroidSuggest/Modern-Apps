//! SGP.22 eUICC Local Profile Assistant (LPA) native core.
//!
//! This crate owns the SGP.22 protocol logic — ASN.1 (BER/DER) encode/decode,
//! the local ISD-R command set (ES10), SM-DP+ orchestration (ES9+), and the
//! download crypto (P-256 / BrainpoolP256r1 ECDSA/ECKA, GSMA CI chain
//! validation, AES Bound Profile Package decryption). Kotlin owns the UI, the
//! APDU transport (a telephony logical channel to the ISD-R), and system
//! integration (`EuiccService`); Rust reaches the eUICC by calling back into
//! Kotlin `EuiccNative.transmitApdu`, and reaches the SM-DP+ over HTTP through
//! the `library:network` JNI bridge.
//!
//! Reimplemented for this repo, following the open-source OpenEUICC
//! (GPL-3.0-only). Ported files carry attribution in their own headers.
//!
//! Phase 3 adds the ASN.1 + ES10 (local ISD-R) layers and the STORE DATA
//! transport; Phase 4 adds profile management; Phase 5 adds the ES9+/SM-DP+
//! orchestration and the profile download/install flow.

mod asn1;
mod base64;
mod download;
mod es10;
mod es9p;
mod jni;

/// Version string reported by the native core.
pub const VERSION: &str = concat!("euicc-core ", env!("CARGO_PKG_VERSION"));
