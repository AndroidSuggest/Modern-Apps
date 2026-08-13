//! Classic Signal protocol v3 for the WhatsApp bridge.
//!
//! WhatsApp companion sessions speak Signal protocol v3 (X3DH + Double Ratchet +
//! Sender Keys). `org.signal:libsignal-android` dropped non-PQ X3DH, which is why this
//! bridge previously depended on the pure-Java `org.whispersystems:signal-protocol-java`
//! shipped through a Shadow-relocated module. This crate replaces it.
//!
//! Design: every entry point is **pure**. Kotlin owns persistence (Room) and passes
//! session/sender-key records in as opaque bytes, getting updated records back. That
//! avoids JNI callbacks into Kotlin stores entirely, and keeps all crypto state
//! transitions in one auditable place.
//!
//! Wire formats are byte-exact with signal-protocol-java (constants transcribed from its
//! bytecode — see `wire.rs`). The *persisted record* format is our own, which is safe
//! because the migration accepts a one-time WhatsApp re-link.

pub mod wa_binary;
pub mod wa_tokens;
pub mod crypto;
pub mod group;
pub mod session;
pub mod wire;

#[cfg(target_os = "android")]
mod jni_bridge;

#[cfg(test)]
mod tests;

use rand_core::{CryptoRng, RngCore};

/// The OS CSPRNG. Kept behind one accessor so tests can swap in a deterministic RNG.
pub struct OsRng;

impl RngCore for OsRng {
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
        getrandom_fill(dest);
    }
    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), rand_core::Error> {
        self.fill_bytes(dest);
        Ok(())
    }
}

impl CryptoRng for OsRng {}

fn getrandom_fill(dest: &mut [u8]) {
    rand_core::OsRng.fill_bytes(dest)
}
