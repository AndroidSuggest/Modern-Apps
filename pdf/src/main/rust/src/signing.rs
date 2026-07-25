//! Detached CMS/PKCS#7 signing for the PDF signer, using RustCrypto instead of
//! Bouncy Castle (which cost ~6 MB of DEX on the Kotlin side). Generates a fresh
//! self-signed RSA-2048 certificate and produces a detached SignedData
//! (`adbe.pkcs7.detached`, SHA-256 with RSA) over the supplied byte-range
//! content. The Kotlin side keeps doing the PDF /ByteRange + /Contents patching.

use cms::builder::{SignedDataBuilder, SignerInfoBuilder};
use cms::cert::CertificateChoices;
use cms::content_info::ContentInfo;
use cms::signed_data::{EncapsulatedContentInfo, SignerIdentifier};
use const_oid::db::rfc5911::ID_DATA;
use der::asn1::OctetString;
use der::{Decode, Encode};
use rsa::pkcs1v15::SigningKey;
use rsa::pkcs8::EncodePublicKey;
use rsa::RsaPrivateKey;
use sha2::Sha256;
use std::str::FromStr;
use std::time::Duration;
use x509_cert::builder::{Builder, CertificateBuilder, Profile};
use x509_cert::name::Name;
use x509_cert::serial_number::SerialNumber;
use x509_cert::spki::SubjectPublicKeyInfoOwned;
use x509_cert::time::Validity;
use x509_cert::Certificate;

/// Build a detached CMS SignedData (DER) over `content`, signed by a freshly
/// generated self-signed RSA-2048 cert with subject `CN=<name>`. Returns `None`
/// on any failure.
pub fn sign_cms(content: &[u8], name: &str) -> Option<Vec<u8>> {
    sign_cms_inner(content, name).ok()
}

fn sign_cms_inner(content: &[u8], name: &str) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    let mut rng = rand::thread_rng();

    // 1. RSA-2048 keypair + PKCS#1 v1.5 / SHA-256 signer.
    let private_key = RsaPrivateKey::new(&mut rng, 2048)?;
    let signing_key = SigningKey::<Sha256>::new(private_key.clone());

    // 2. Self-signed X.509 cert.
    let cn = if name.trim().is_empty() { "PDF Signer" } else { name };
    let subject = Name::from_str(&format!("CN={cn}"))?;
    let serial = SerialNumber::from(rand::random::<u32>().max(1));
    let validity = Validity::from_now(Duration::from_secs(3650 * 24 * 60 * 60))?;

    let spki_der = private_key.to_public_key().to_public_key_der()?;
    let spki = SubjectPublicKeyInfoOwned::from_der(spki_der.as_bytes())?;

    let builder = CertificateBuilder::new(
        Profile::Root,
        serial,
        validity,
        subject,
        spki,
        &signing_key,
    )?;
    let cert: Certificate = builder.build()?;

    // 3. Detached CMS SignedData over the external content.
    let econtent = EncapsulatedContentInfo {
        econtent_type: ID_DATA,
        econtent: None, // detached
    };

    let sid = SignerIdentifier::IssuerAndSerialNumber(cms::cert::IssuerAndSerialNumber {
        issuer: cert.tbs_certificate.issuer.clone(),
        serial_number: cert.tbs_certificate.serial_number.clone(),
    });

    let digest_algorithm = spki::AlgorithmIdentifierOwned {
        oid: const_oid::db::rfc5912::ID_SHA_256,
        parameters: None,
    };

    let signer_info = SignerInfoBuilder::new(
        &signing_key,
        sid,
        digest_algorithm.clone(),
        &econtent,
        Some(content),
    )
    .map_err(|e| format!("signer info: {e:?}"))?;

    let mut builder = SignedDataBuilder::new(&econtent);
    let signed_data = builder
        .add_digest_algorithm(digest_algorithm)
        .map_err(|e| format!("digest alg: {e:?}"))?
        .add_certificate(CertificateChoices::Certificate(cert))
        .map_err(|e| format!("add cert: {e:?}"))?
        .add_signer_info(signer_info)
        .map_err(|e| format!("add signer: {e:?}"))?
        .build()
        .map_err(|e| format!("build: {e:?}"))?;

    let ci: ContentInfo = signed_data;
    Ok(ci.to_der()?)
}

#[cfg(test)]
mod signing_tests {
    use super::*;

    #[test]
    fn produces_parseable_detached_cms() {
        let content = b"hello pdf byte range";
        let der = sign_cms(content, "Unit Test").expect("sign");
        assert!(der.len() > 500, "cms unexpectedly small: {}", der.len());
        // Round-trips as a CMS ContentInfo wrapping SignedData.
        let ci = ContentInfo::from_der(&der).expect("parse ContentInfo");
        assert_eq!(ci.content_type, const_oid::db::rfc5911::ID_SIGNED_DATA);
    }
}
