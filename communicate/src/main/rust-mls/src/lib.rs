//! MLS (RFC 9420) for the RCS line, backed by OpenMLS.
//!
//! Design mirrors the Signal crate in `../rust`: every entry point is
//! **pure**. Kotlin owns persistence (Room) and passes group state, key
//! packages and proposals in as opaque TLS-serialized bytes, getting updated
//! state back. The crate never calls back into Kotlin and holds no globals.
//!
//! Interop note: this is closed-loop, our-app-to-our-app. RCS is the wire
//! (MLS handshake + application messages ride SIP MESSAGE / MSRP as opaque
//! payloads); key packages are exchanged out-of-band through the same
//! transport. Google Messages' proprietary key distribution is not involved.

pub mod group;
pub mod keys;
pub mod message;
pub mod store;

#[cfg(target_os = "android")]
#[allow(unsafe_code)]
mod jni_bridge;

#[cfg(test)]
mod tests;
