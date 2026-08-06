//! Own CBC impl to drop `cbc` crate (tiny wrapper pulling cipher 0.4.4 duplicate)
//! Single-function usage per user rule – write it ourselves. Removes cbc 0.1.2 + cipher 0.4.4 + inout + crypto-common duplicate.

use aes::cipher::{generic_array::GenericArray, BlockDecrypt, BlockEncrypt, KeyInit};

pub fn enc_aes256_nopad_zeroiv(key: &[u8], data: &[u8]) -> Vec<u8> {
    assert!(key.len()==32 && data.len().is_multiple_of(16));
    use aes::Aes256;
    let cipher = Aes256::new(GenericArray::from_slice(key));
    let mut out = Vec::with_capacity(data.len());
    let mut prev=[0u8;16];
    for chunk in data.chunks(16) {
        let mut b=[0u8;16];
        for i in 0..16 { b[i]=chunk[i]^prev[i]; }
        let mut ga=GenericArray::from(b);
        cipher.encrypt_block(&mut ga);
        out.extend_from_slice(&ga);
        prev.copy_from_slice(&ga);
    }
    out
}

pub fn dec_aes256_nopad_zeroiv(key: &[u8], ct: &[u8]) -> Vec<u8> {
    use aes::Aes256;
    assert!(key.len()==32 && ct.len().is_multiple_of(16));
    let cipher=Aes256::new(GenericArray::from_slice(key));
    let mut out=Vec::with_capacity(ct.len());
    let mut prev=[0u8;16];
    for chunk in ct.chunks(16) {
        let mut dec=*GenericArray::from_slice(chunk);
        cipher.decrypt_block(&mut dec);
        let mut plain=[0u8;16];
        for i in 0..16 { plain[i]=dec[i]^prev[i]; }
        out.extend_from_slice(&plain);
        prev.copy_from_slice(chunk);
    }
    out
}

pub fn cbc_enc(key: &[u8], iv: &[u8;16], data: &[u8]) -> Vec<u8> {
    let pad = 16 - (data.len()%16);
    let mut padded=Vec::with_capacity(data.len()+pad);
    padded.extend_from_slice(data);
    padded.extend(std::iter::repeat_n(pad as u8, pad));
    let ct = match key.len() {
        16 => { use aes::Aes128; let cipher=Aes128::new(GenericArray::from_slice(&key[..16])); let mut out=Vec::with_capacity(padded.len()); let mut prev=*iv; for c in padded.chunks(16){ let mut b=[0u8;16]; for i in 0..16{ b[i]=c[i]^prev[i]; } let mut ga=GenericArray::from(b); cipher.encrypt_block(&mut ga); out.extend_from_slice(&ga); prev.copy_from_slice(&ga);} out},
        24 => { use aes::Aes192; let cipher=Aes192::new(GenericArray::from_slice(&key[..24])); let mut out=Vec::with_capacity(padded.len()); let mut prev=*iv; for c in padded.chunks(16){ let mut b=[0u8;16]; for i in 0..16{ b[i]=c[i]^prev[i]; } let mut ga=GenericArray::from(b); cipher.encrypt_block(&mut ga); out.extend_from_slice(&ga); prev.copy_from_slice(&ga);} out},
        32 => { use aes::Aes256; let cipher=Aes256::new(GenericArray::from_slice(&key[..32])); let mut out=Vec::with_capacity(padded.len()); let mut prev=*iv; for c in padded.chunks(16){ let mut b=[0u8;16]; for i in 0..16{ b[i]=c[i]^prev[i]; } let mut ga=GenericArray::from(b); cipher.encrypt_block(&mut ga); out.extend_from_slice(&ga); prev.copy_from_slice(&ga);} out},
        _ => Vec::new()
    };
    let mut res=Vec::with_capacity(16+ct.len());
    res.extend_from_slice(iv);
    res.extend_from_slice(&ct);
    res
}

pub fn cbc_dec(key: &[u8], data: &[u8]) -> Vec<u8> {
    if data.len()<16 { return Vec::new(); }
    let (iv,ct)=data.split_at(16);
    let mut out=Vec::with_capacity(ct.len());
    let mut prev=[0u8;16]; prev.copy_from_slice(iv);
    match key.len() {
        16 => { use aes::Aes128; let cipher=Aes128::new(GenericArray::from_slice(&key[..16])); for chunk in ct.chunks(16){ let mut dec=*GenericArray::from_slice(chunk); cipher.decrypt_block(&mut dec); let mut plain=[0u8;16]; for i in 0..16{ plain[i]=dec[i]^prev[i]; } out.extend_from_slice(&plain); prev.copy_from_slice(chunk);} },
        24 => { use aes::Aes192; let cipher=Aes192::new(GenericArray::from_slice(&key[..24])); for chunk in ct.chunks(16){ let mut dec=*GenericArray::from_slice(chunk); cipher.decrypt_block(&mut dec); let mut plain=[0u8;16]; for i in 0..16{ plain[i]=dec[i]^prev[i]; } out.extend_from_slice(&plain); prev.copy_from_slice(chunk);} },
        32 => { use aes::Aes256; let cipher=Aes256::new(GenericArray::from_slice(&key[..32])); for chunk in ct.chunks(16){ let mut dec=*GenericArray::from_slice(chunk); cipher.decrypt_block(&mut dec); let mut plain=[0u8;16]; for i in 0..16{ plain[i]=dec[i]^prev[i]; } out.extend_from_slice(&plain); prev.copy_from_slice(chunk);} },
        _ => {}
    };
    if out.is_empty(){ return Vec::new(); }
    let pad = out[out.len()-1] as usize;
    if pad==0 || pad>16 || pad>out.len(){ return Vec::new(); }
    for &b in &out[out.len()-pad..]{ if b as usize != pad { return Vec::new(); } }
    out.truncate(out.len()-pad);
    out
}

pub fn aes128_cbc_enc_nopad(key: &[u8], iv: &[u8], data: &[u8]) -> Vec<u8> {
    use aes::Aes128;
    assert!(key.len()==16 && iv.len()==16 && data.len().is_multiple_of(16));
    let cipher=Aes128::new(GenericArray::from_slice(&key[..16]));
    let mut out=Vec::with_capacity(data.len());
    let mut prev=[0u8;16]; prev.copy_from_slice(iv);
    for chunk in data.chunks(16){
        let mut b=[0u8;16];
        for i in 0..16{ b[i]=chunk[i]^prev[i]; }
        let mut ga=GenericArray::from(b);
        cipher.encrypt_block(&mut ga);
        out.extend_from_slice(&ga);
        prev.copy_from_slice(&ga);
    }
    out
}
