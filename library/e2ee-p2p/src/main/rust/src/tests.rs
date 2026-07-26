//! Interop + round-trip tests. The vectors file holds real Bouncy Castle 1.85
//! output (keys as DER, a KEM ciphertext + shared secret, and an ML-DSA
//! signature) — proving the Rust side is byte-compatible with deployed data.

use super::*;
use std::collections::HashMap;

const VECTORS: &str = include_str!("../testvectors/bc_pqc_vectors.txt");

fn unhex(s: &str) -> Vec<u8> {
    let s = s.trim();
    (0..s.len() / 2)
        .map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).unwrap())
        .collect()
}

fn vectors() -> HashMap<String, String> {
    let mut m = HashMap::new();
    for line in VECTORS.lines() {
        if let Some(eq) = line.find('=') {
            m.insert(line[..eq].to_string(), line[eq + 1..].to_string());
        }
    }
    m
}

#[test]
fn rust_decaps_bouncycastle_ciphertext() {
    let v = vectors();
    let priv_der = unhex(&v["MLKEM_PRIV_DER"]);
    let ct = unhex(&v["MLKEM_CT"]);
    let expected_ss = unhex(&v["MLKEM_SS"]);
    let ss = mlkem_decaps_der(&priv_der, &ct).expect("decaps");
    assert_eq!(ss, expected_ss, "Rust ML-KEM decaps must match BC's shared secret");
}

#[test]
fn rust_verifies_bouncycastle_signature() {
    let v = vectors();
    let pub_der = unhex(&v["MLDSA_PUB_DER"]);
    let msg = unhex(&v["MLDSA_MSG"]);
    let sig = unhex(&v["MLDSA_SIG"]);
    assert!(mldsa_verify_der(&pub_der, &msg, &sig), "Rust must verify BC's ML-DSA signature");
    // Tamper -> reject.
    let mut bad = msg.clone();
    bad[0] ^= 1;
    assert!(!mldsa_verify_der(&pub_der, &bad, &sig));
}

#[test]
fn public_key_der_reencodes_identically() {
    // Re-wrapping the raw key extracted from BC's SPKI must reproduce BC's exact DER.
    let v = vectors();
    let kem_pub = unhex(&v["MLKEM_PUB_DER"]);
    let raw = spki_raw(&kem_pub, KEM_EK).unwrap();
    assert_eq!(spki_wrap(&KEM_PUB_PREFIX, raw), kem_pub);
    let dsa_pub = unhex(&v["MLDSA_PUB_DER"]);
    let raw = spki_raw(&dsa_pub, DSA_PK).unwrap();
    assert_eq!(spki_wrap(&DSA_PUB_PREFIX, raw), dsa_pub);
}

#[test]
fn kem_roundtrip() {
    let (pub_der, priv_der) = mlkem_keygen_der();
    assert_eq!(pub_der.len(), KEM_PUB_PREFIX.len() + KEM_EK);
    assert_eq!(priv_der.len(), 2498);
    let (ct, ss1) = mlkem_encaps_der(&pub_der).expect("encaps");
    let ss2 = mlkem_decaps_der(&priv_der, &ct).expect("decaps");
    assert_eq!(ss1, ss2);
}

#[test]
fn dsa_roundtrip() {
    let (pub_der, priv_der) = mldsa_keygen_der();
    assert_eq!(pub_der.len(), DSA_PUB_PREFIX.len() + DSA_PK);
    assert_eq!(priv_der.len(), 4098);
    let msg = b"hello e2ee";
    let sig = mldsa_sign_der(&priv_der, msg).expect("sign");
    assert!(mldsa_verify_der(&pub_der, msg, &sig));
    assert!(!mldsa_verify_der(&pub_der, b"other", &sig));
}

#[test]
fn our_generated_privkey_decaps() {
    // Keys we generate (BC seed+expanded layout) must be readable by our own parser.
    let (pub_der, priv_der) = mlkem_keygen_der();
    let (ct, ss1) = mlkem_encaps_der(&pub_der).unwrap();
    assert_eq!(mlkem_decaps_der(&priv_der, &ct).unwrap(), ss1);
}
