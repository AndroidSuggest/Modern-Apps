use crate::*;

/// Whether `bytes` is a standard-encrypted PDF that needs a (non-empty) password
/// the empty password does not satisfy. Returns: 0 no, 1 needs password, 2
/// unsupported encryption (e.g. AES).
pub(crate) fn pdf_password_state(bytes: &[u8]) -> i32 {
    let mut doc = match Document::load_mem(bytes) {
        Ok(d) => d,
        Err(_) => return 0,
    };
    if doc.trailer.get(b"Encrypt").is_err() {
        return 0;
    }
    match decrypt_in_place(&mut doc, b"") {
        DecryptStatus::Ok => 0,
        DecryptStatus::NeedPassword => 1,
        DecryptStatus::Unsupported => 2,
    }
}

#[derive(PartialEq, Debug)]
pub(crate) enum DecryptStatus {
    Ok,
    NeedPassword,
    Unsupported,
}

/// Apply a cipher (`apply`) to every string and stream inside `obj`.
pub(crate) fn crypt_object(obj: &mut Object, apply: &dyn Fn(&[u8]) -> Vec<u8>) {
    match obj {
        Object::String(s, _) => *s = apply(s),
        Object::Array(a) => {
            for o in a.iter_mut() {
                crypt_object(o, apply);
            }
        }
        Object::Dictionary(d) => {
            let keys: Vec<Vec<u8>> = d.iter().map(|(k, _)| k.clone()).collect();
            for k in keys {
                if let Ok(v) = d.get_mut(&k) {
                    crypt_object(v, apply);
                }
            }
        }
        Object::Stream(st) => {
            let keys: Vec<Vec<u8>> = st.dict.iter().map(|(k, _)| k.clone()).collect();
            for k in keys {
                if let Ok(v) = st.dict.get_mut(&k) {
                    crypt_object(v, apply);
                }
            }
            st.content = apply(&st.content);
        }
        _ => {}
    }
}

/// First `/ID` element bytes from the trailer, or empty.
pub(crate) fn trailer_id0(doc: &Document) -> Vec<u8> {
    if let Ok(Object::Array(a)) = doc.trailer.get(b"ID") {
        if let Some(Object::String(s, _)) = a.first() {
            return s.clone();
        }
    }
    Vec::new()
}

#[derive(Clone, Copy, PartialEq)]
pub(crate) enum CryptMethod {
    Rc4,
    AesV2,
    AesV3,
}

/// Decrypt a standard-encrypted document (RC4 or AES) in place with `password`.
pub(crate) fn decrypt_in_place(doc: &mut Document, password: &[u8]) -> DecryptStatus {
    let enc_id = match doc.trailer.get(b"Encrypt").and_then(|o| o.as_reference()) {
        Ok(id) => id,
        Err(_) => return DecryptStatus::Unsupported,
    };
    let (o, u, ue, p, r, length, method) = {
        let enc = match doc.get_dictionary(enc_id) {
            Ok(d) => d,
            Err(_) => return DecryptStatus::Unsupported,
        };
        let filter = enc.get(b"Filter").ok().and_then(|o| o.as_name().ok());
        if filter != Some(b"Standard".as_ref()) {
            // Only the Standard security handler is supported. Public-key /
            // certificate handlers (e.g. /Filter /Adobe.PubSec) are infeasible
            // here: decryption requires the recipient's private key, which the
            // viewer does not possess. Report as Unsupported rather than failing
            // silently or corrupting bytes.
            return DecryptStatus::Unsupported;
        }
        let v = enc.get(b"V").ok().and_then(num).unwrap_or(0.0) as i64;
        let r = enc.get(b"R").ok().and_then(num).unwrap_or(0.0) as i64;
        // Determine the crypt method.
        let method = if v >= 5 {
            CryptMethod::AesV3
        } else if v == 4 {
            // Read /CF /StdCF /CFM.
            let cfm = enc
                .get(b"CF")
                .ok()
                .and_then(|o| o.as_dict().ok())
                .and_then(|cf| cf.get(b"StdCF").ok())
                .and_then(|s| s.as_dict().ok())
                .and_then(|s| s.get(b"CFM").ok())
                .and_then(|o| o.as_name().ok());
            match cfm {
                Some(b) if b == b"AESV3" => CryptMethod::AesV3,
                Some(b) if b == b"AESV2" => CryptMethod::AesV2,
                Some(b) if b == b"V2" => CryptMethod::Rc4,
                _ => return DecryptStatus::Unsupported,
            }
        } else {
            CryptMethod::Rc4
        };
        let o = enc.get(b"O").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let u = enc.get(b"U").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let ue = enc.get(b"UE").ok().and_then(|o| o.as_str().ok()).map(|s| s.to_vec()).unwrap_or_default();
        let p = enc.get(b"P").ok().and_then(num).unwrap_or(0.0) as i32;
        let default_len = if method == CryptMethod::AesV2 { 128.0 } else { 40.0 };
        let length = enc.get(b"Length").ok().and_then(num).unwrap_or(default_len) as usize;
        (o, u, ue, p, r, length, method)
    };

    let id0 = trailer_id0(doc);
    let n = if method == CryptMethod::AesV2 { 16 } else { (length / 8).clamp(5, 16) };

    // Derive the file key.
    let key = match method {
        CryptMethod::AesV3 => match crypto::authenticate_v5(password, &u, &ue, r as u8) {
            Some(k) => k,
            None => return DecryptStatus::NeedPassword,
        },
        _ => match crypto::authenticate(password, &o, &u, p, &id0, n, r as u8) {
            Some(k) => k,
            None => return DecryptStatus::NeedPassword,
        },
    };

    let ids: Vec<ObjectId> = doc.objects.keys().copied().collect();
    for id in ids {
        if id == enc_id {
            continue;
        }
        let apply: Box<dyn Fn(&[u8]) -> Vec<u8>> = match method {
            CryptMethod::Rc4 => {
                let okey = crypto::object_key(&key, id.0, id.1, n);
                Box::new(move |d: &[u8]| crypto::rc4(&okey, d))
            }
            CryptMethod::AesV2 => {
                let okey = crypto::object_key_aes(&key, id.0, id.1, n);
                Box::new(move |d: &[u8]| crypto::aes_cbc_decrypt(&okey, d))
            }
            CryptMethod::AesV3 => {
                let k = key.clone();
                Box::new(move |d: &[u8]| crypto::aes_cbc_decrypt(&k, d))
            }
        };
        if let Some(obj) = doc.objects.get_mut(&id) {
            crypt_object(obj, &apply);
        }
    }
    doc.trailer.remove(b"Encrypt");
    DecryptStatus::Ok
}

/// Which standard-security-handler algorithm to write on save.
#[derive(Clone, Copy, PartialEq)]
pub(crate) enum EncryptAlgo {
    /// RC4-128, revision 3 (V2/R3).
    Rc4_128,
    /// AES-128, revision 4 (V4/R4, AESV2).
    Aes128,
    /// AES-256, revision 6 (V5/R6, AESV3).
    Aes256,
}

/// Cryptographically-secure random bytes for salts/IVs, sourced from the OS
/// CSPRNG. Falls back to md5-based mixing (seed + wall clock) only if the OS
/// RNG is unavailable, preserving the no-panic invariant.
fn rand_bytes(n: usize, seed: &[u8]) -> Vec<u8> {
    use rand::RngCore;
    let mut out = vec![0u8; n];
    if rand::rngs::OsRng.try_fill_bytes(&mut out).is_ok() {
        return out;
    }
    use md5::{Digest, Md5};
    let t = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    out.clear();
    let mut ctr: u64 = 0;
    while out.len() < n {
        let mut m = Md5::new();
        m.update(seed);
        m.update(t.to_le_bytes());
        m.update(ctr.to_le_bytes());
        let d: [u8; 16] = m.finalize().into();
        out.extend_from_slice(&d);
        ctr += 1;
    }
    out.truncate(n);
    out
}

/// Serialize `handle` encrypted with the given passwords, defaulting to AES-128
/// (V4/R4) — modern and widely supported.
pub(crate) fn save_encrypted(handle: i64, user_pw: &[u8], owner_pw: &[u8]) -> Option<Vec<u8>> {
    let bytes = save_document(handle)?;
    encrypt_doc_bytes(&bytes, user_pw, owner_pw, EncryptAlgo::Aes128)
}

/// Encrypt a serialized PDF (`bytes`) with `algo` and the given passwords,
/// returning the re-serialized encrypted document. Testable without the registry.
pub(crate) fn encrypt_doc_bytes(
    bytes: &[u8],
    user_pw: &[u8],
    owner_pw: &[u8],
    algo: EncryptAlgo,
) -> Option<Vec<u8>> {
    let mut doc = Document::load_mem(bytes).ok()?;
    // Ensure an /ID exists (used by RC4/AES-128 key derivation).
    let id0 = {
        let existing = trailer_id0(&doc);
        if existing.is_empty() {
            let h = rand_bytes(16, bytes);
            doc.trailer.set(
                "ID",
                Object::Array(vec![
                    Object::String(h.clone(), lopdf::StringFormat::Hexadecimal),
                    Object::String(h.clone(), lopdf::StringFormat::Hexadecimal),
                ]),
            );
            h
        } else {
            existing
        }
    };
    let owner = if owner_pw.is_empty() { user_pw } else { owner_pw };
    let p: i32 = -4; // allow all operations

    // Build the /Encrypt dict and per-object cipher factory.
    let (enc, make_apply): (Dictionary, Box<dyn Fn(ObjectId) -> Box<dyn Fn(&[u8]) -> Vec<u8>>>) =
        match algo {
            EncryptAlgo::Rc4_128 => {
                let (n, rev) = (16usize, 3u8);
                let o = crypto::compute_o(owner, user_pw, n, rev);
                let key = crypto::compute_key(user_pw, &o, p, &id0, n, rev);
                let u = crypto::compute_u(&key, &id0, rev);
                let mut enc = Dictionary::new();
                enc.set("Filter", name_obj("Standard"));
                enc.set("V", Object::Integer(2));
                enc.set("R", Object::Integer(3));
                enc.set("Length", Object::Integer(128));
                enc.set("P", Object::Integer(p as i64));
                enc.set("O", Object::String(o, lopdf::StringFormat::Literal));
                enc.set("U", Object::String(u, lopdf::StringFormat::Literal));
                let key2 = key.clone();
                let make = move |id: ObjectId| -> Box<dyn Fn(&[u8]) -> Vec<u8>> {
                    let okey = crypto::object_key(&key2, id.0, id.1, n);
                    Box::new(move |d: &[u8]| crypto::rc4(&okey, d))
                };
                (enc, Box::new(make))
            }
            EncryptAlgo::Aes128 => {
                let (n, rev) = (16usize, 4u8);
                let o = crypto::compute_o(owner, user_pw, n, rev);
                let key = crypto::compute_key(user_pw, &o, p, &id0, n, rev);
                let u = crypto::compute_u(&key, &id0, rev);
                let mut cf = Dictionary::new();
                let mut stdcf = Dictionary::new();
                stdcf.set("CFM", name_obj("AESV2"));
                stdcf.set("Length", Object::Integer(16));
                cf.set("StdCF", Object::Dictionary(stdcf));
                let mut enc = Dictionary::new();
                enc.set("Filter", name_obj("Standard"));
                enc.set("V", Object::Integer(4));
                enc.set("R", Object::Integer(4));
                enc.set("Length", Object::Integer(128));
                enc.set("P", Object::Integer(p as i64));
                enc.set("CF", Object::Dictionary(cf));
                enc.set("StmF", name_obj("StdCF"));
                enc.set("StrF", name_obj("StdCF"));
                enc.set("O", Object::String(o, lopdf::StringFormat::Literal));
                enc.set("U", Object::String(u, lopdf::StringFormat::Literal));
                let key2 = key.clone();
                let seed = id0.clone();
                let make = move |id: ObjectId| -> Box<dyn Fn(&[u8]) -> Vec<u8>> {
                    let okey = crypto::object_key_aes(&key2, id.0, id.1, n);
                    let seed = seed.clone();
                    Box::new(move |d: &[u8]| {
                        let iv = rand_bytes(16, &[&seed[..], d.get(..8).unwrap_or(d)].concat());
                        let mut iv16 = [0u8; 16];
                        iv16.copy_from_slice(&iv[..16]);
                        crypto::aes_cbc_encrypt(&okey, &iv16, d)
                    })
                };
                (enc, Box::new(make))
            }
            EncryptAlgo::Aes256 => {
                let rev = 6u8;
                let file_key = rand_bytes(32, &id0);
                let salt_bytes = rand_bytes(32, &[&id0[..], b"salts"].concat());
                let mut salts = [[0u8; 8]; 4];
                for (i, s) in salts.iter_mut().enumerate() {
                    s.copy_from_slice(&salt_bytes[i * 8..i * 8 + 8]);
                }
                let (u, ue, o, oe) = crypto::compute_v5(user_pw, owner, &file_key, &salts, rev);
                let perms = crypto::compute_perms_v5(&file_key, p);
                let mut cf = Dictionary::new();
                let mut stdcf = Dictionary::new();
                stdcf.set("CFM", name_obj("AESV3"));
                stdcf.set("Length", Object::Integer(32));
                cf.set("StdCF", Object::Dictionary(stdcf));
                let mut enc = Dictionary::new();
                enc.set("Filter", name_obj("Standard"));
                enc.set("V", Object::Integer(5));
                enc.set("R", Object::Integer(6));
                enc.set("Length", Object::Integer(256));
                enc.set("P", Object::Integer(p as i64));
                enc.set("CF", Object::Dictionary(cf));
                enc.set("StmF", name_obj("StdCF"));
                enc.set("StrF", name_obj("StdCF"));
                enc.set("O", Object::String(o, lopdf::StringFormat::Literal));
                enc.set("U", Object::String(u, lopdf::StringFormat::Literal));
                enc.set("OE", Object::String(oe, lopdf::StringFormat::Literal));
                enc.set("UE", Object::String(ue, lopdf::StringFormat::Literal));
                enc.set("Perms", Object::String(perms, lopdf::StringFormat::Literal));
                let fk = file_key.clone();
                let seed = id0.clone();
                let make = move |_id: ObjectId| -> Box<dyn Fn(&[u8]) -> Vec<u8>> {
                    // AESV3 uses the file key directly (no per-object key).
                    let fk = fk.clone();
                    let seed = seed.clone();
                    Box::new(move |d: &[u8]| {
                        let iv = rand_bytes(16, &[&seed[..], d.get(..8).unwrap_or(d)].concat());
                        let mut iv16 = [0u8; 16];
                        iv16.copy_from_slice(&iv[..16]);
                        crypto::aes_cbc_encrypt(&fk, &iv16, d)
                    })
                };
                (enc, Box::new(make))
            }
        };

    let enc_id = doc.add_object(enc);
    let ids: Vec<ObjectId> = doc.objects.keys().copied().collect();
    for id in ids {
        if id == enc_id {
            continue;
        }
        let apply = make_apply(id);
        if let Some(obj) = doc.objects.get_mut(&id) {
            crypt_object(obj, &apply);
        }
    }
    doc.trailer.set("Encrypt", Object::Reference(enc_id));

    let mut out = Vec::new();
    doc.save_to(&mut out).ok()?;
    Some(out)
}
