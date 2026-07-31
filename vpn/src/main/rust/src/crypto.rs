//! Shim that mimics ring/aws-lc-rs aead + error API using chacha20poly1305 but fixing
//! the fact that aead::Buffer is not implemented for &[u8] slice — so we re-implement the tiny wrapper
//! around the crate's raw ChaCha20Poly1305 + poly1305 by using direct XChaCha? No — easier: use older
//! `aead` 0.5.2 generic `AeadInPlace` that works with Vec<u8> (via Buffer impl), and hand-roll ring-like shim.

pub mod error {
    #[derive(Debug, Clone, Copy)]
    pub struct Unspecified;
    impl std::fmt::Display for Unspecified {
        fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { write!(f, "Unspecified") }
    }
    impl std::error::Error for Unspecified {}
}

pub mod aead {
    use super::error;
    use chacha20poly1305::aead::generic_array::GenericArray;
    use chacha20poly1305::aead::{AeadMutInPlace, KeyInit};
    use chacha20poly1305::{ChaCha20Poly1305, Key as CKey, Tag};

    #[derive(Clone, Copy, Debug)]
    pub struct Algorithm;
    pub static CHACHA20_POLY1305: Algorithm = Algorithm;

    pub struct UnboundKey { bytes: [u8; 32] }
    impl UnboundKey {
        pub fn new(_alg: &Algorithm, key: &[u8]) -> Result<Self, error::Unspecified> {
            if key.len() != 32 { return Err(error::Unspecified); }
            let mut bytes = [0u8; 32]; bytes.copy_from_slice(key);
            Ok(Self { bytes })
        }
    }

    #[derive(Clone, Copy)]
    pub struct Nonce(pub [u8; 12]);
    impl Nonce { pub fn assume_unique_for_key(n: [u8; 12]) -> Self { Self(n) } }

    pub struct Aad<B>(pub B);
    impl<B: AsRef<[u8]>> Aad<B> {
        pub fn from(b: B) -> Self { Self(b) }
    }
    impl<'a> From<&'a [u8]> for Aad<&'a [u8]> { fn from(b: &'a [u8]) -> Self { Self(b) } }

    pub struct LessSafeKey { inner: ChaCha20Poly1305 }
    impl LessSafeKey {
        pub fn new(unbound: UnboundKey) -> Self {
            Self { inner: ChaCha20Poly1305::new(CKey::from_slice(&unbound.bytes)) }
        }

        pub fn seal_in_place_separate_tag(
            &self, nonce: Nonce, aad: Aad<&[u8]>, in_out: &mut [u8],
        ) -> Result<Tag, error::Unspecified> {
            let nonce_ga = GenericArray::from_slice(&nonce.0);
            // We need to handle ciphertext in separate scratch since chacha20poly1305 API encrypts Vec<u8>
            // Use the detach API — it mutates in_out and returns tag without needing Buffer for slice.
            // The slice overload exists via `AeadInPlace` generic — but for detach it's directly slice-safe.
            // In chacha20poly1305 0.10, `encrypt_in_place_detached` is on `AeadMutInPlace` where Buffer = slice? Actually it is slice-safe.
            // To stay safe we copy through Vec when needed.
            self.inner.clone().encrypt_in_place_detached(nonce_ga, aad.0, in_out).map_err(|_| error::Unspecified)
        }

        // Ring's less-safe key mutates buffer that is ct || tag, and returns pt slice.
        // We implement by cloning inner via decrypt.
        pub fn open_in_place<'a>(
            &self, nonce: Nonce, aad: Aad<&[u8]>, in_out: &'a mut [u8],
        ) -> Result<&'a mut [u8], error::Unspecified> {
            let nonce_ga = GenericArray::from_slice(&nonce.0);
            let len = in_out.len();
            if len < 16 { return Err(error::Unspecified); }
            // Split off tag? No ring open_in_place expects in_out = ct || tag and returns pt (= ct without tag) in same buf.
            // chacha20poly1305's `decrypt_in_place_detached` splits them, but `decrypt_in_place` expects ct||tag as one buffer and returns pt via &().
            // The easiest: use `decrypt_in_place_detached` by splitting last 16 bytes.
            let (ct, tag_bytes) = in_out.split_at_mut(len - 16);
            let tag = Tag::from_slice(tag_bytes);
            self.inner.clone()
                .decrypt_in_place_detached(nonce_ga, aad.0, ct, tag)
                .map_err(|_| error::Unspecified)?;
            // Now shrink logically — caller truncates after returned slice length? The ring version
            // returns truncated slice referencing same allocation.
            // SAFETY: we have decrypted in place; remaining len must be len-16 for plaintext.
            // Return re-borrowed ct as Result slice — via transmuting length.
            // Workaround: return mut ptr to first ct.len() of original allocation via unsafe? Simpler:
            // We cannot easily produce &'a mut [u8] of length len-16 from &'a mut [u8] of length len without unsafe because
            // `decrypt_in_place_detached` already decrypted. So just use raw slice cast.
            let plain_len = len - 16;
            let ptr = ct.as_mut_ptr();
            // SAFETY: ptr derived from ct which is inside in_out, length = plain_len, still within allocation, disjoint check okay.
            let plain = unsafe { std::slice::from_raw_parts_mut(ptr, plain_len) };
            Ok(plain)
        }
    }
}
