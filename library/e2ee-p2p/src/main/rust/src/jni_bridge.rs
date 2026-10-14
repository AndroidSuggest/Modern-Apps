//! JNI surface for `com.vayunmathur.e2ee.PqcNative`. All keys cross as DER bytes.

use crate::*;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jbyteArray, jobjectArray};
use jni::{Env, EnvUnowned};

fn bytes_in(env: &Env, arr: &JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(arr).ok()
}

fn bytes_out(env: &mut Env, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Return a Java `byte[][]` of exactly two elements, or null.
fn pair_out(env: &mut Env, a: &[u8], b: &[u8]) -> jobjectArray {
    let arr = match env.new_object_type_array::<JByteArray>(2, JByteArray::null()) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    let ja = match env.byte_array_from_slice(a) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    let jb = match env.byte_array_from_slice(b) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    if arr.set_element(env, 0, &ja).is_err() || arr.set_element(env, 1, &jb).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemKeygen<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let (pub_der, priv_der) = mlkem_keygen_der();
        Ok(pair_out(env, &pub_der, &priv_der))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemLinkKeygen<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let seed = mlkem_link_seed_new();
        match mlkem_link_keygen_from_seed(&seed) {
            Some((pub_der, _)) => Ok(pair_out(env, &seed, &pub_der)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemLinkPubFromSeed<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    seed: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let s = match bytes_in(env, &seed) {
            Some(s) => s,
            None => return Ok(std::ptr::null_mut()),
        };
        match mlkem_link_keygen_from_seed(&s) {
            Some((pub_der, _)) => Ok(bytes_out(env, &pub_der)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMldsaKeygen<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let (pub_der, priv_der) = mldsa_keygen_der();
        Ok(pair_out(env, &pub_der, &priv_der))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemEncaps<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    pub_der: JByteArray<'l>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let der = match bytes_in(env, &pub_der) {
            Some(d) => d,
            None => return Ok(std::ptr::null_mut()),
        };
        match mlkem_encaps_der(&der) {
            Some((ct, ss)) => Ok(pair_out(env, &ct, &ss)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemDecaps<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    priv_der: JByteArray<'l>,
    ct: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let (der, ctb) = match (bytes_in(env, &priv_der), bytes_in(env, &ct)) {
            (Some(a), Some(b)) => (a, b),
            _ => return Ok(std::ptr::null_mut()),
        };
        match mlkem_decaps_der(&der, &ctb) {
            Some(ss) => Ok(bytes_out(env, &ss)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMldsaSign<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    priv_der: JByteArray<'l>,
    msg: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let (der, m) = match (bytes_in(env, &priv_der), bytes_in(env, &msg)) {
            (Some(a), Some(b)) => (a, b),
            _ => return Ok(std::ptr::null_mut()),
        };
        match mldsa_sign_der(&der, &m) {
            Some(sig) => Ok(bytes_out(env, &sig)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMldsaVerify<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    pub_der: JByteArray<'l>,
    msg: JByteArray<'l>,
    sig: JByteArray<'l>,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
        let (der, m, s) = match (
            bytes_in(env, &pub_der),
            bytes_in(env, &msg),
            bytes_in(env, &sig),
        ) {
            (Some(a), Some(b), Some(c)) => (a, b, c),
            _ => return Ok(false),
        };
        Ok(mldsa_verify_der(&der, &m, &s))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
