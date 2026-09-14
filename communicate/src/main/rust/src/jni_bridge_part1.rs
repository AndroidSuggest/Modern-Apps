fn decrypt_prekey_inner<'a>(
    env: &mut JNIEnv<'a>,
    local_priv: JByteArray<'a>,
    local_pub: JByteArray<'a>,
    signed_pre_priv: JByteArray<'a>,
    one_time_priv: JByteArray<'a>,
    prekey_bytes: JByteArray<'a>,
) -> jobjectArray {
    let local_priv_b = match bytes_in(env, &local_priv) {
        Some(b) => b,
        None => {
            throw_runtime(env, "local_identity_private null");
            return std::ptr::null_mut();
        }
    };
    let local_pub_b = match bytes_in(env, &local_pub) {
        Some(b) => b,
        None => {
            throw_runtime(env, "local_identity_public null");
            return std::ptr::null_mut();
        }
    };
    let signed_priv_b = match bytes_in(env, &signed_pre_priv) {
        Some(b) => b,
        None => {
            throw_runtime(env, "signed_pre_key_private null");
            return std::ptr::null_mut();
        }
    };
    let pkmsg_b = match bytes_in(env, &prekey_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(env, "prekey_bytes null");
            return std::ptr::null_mut();
        }
    };

    let local_priv_32 = match parse_32("local_identity_private", &local_priv_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };
    let local_pub_32 = match parse_32("local_identity_public", &local_pub_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };
    let signed_priv_32 = match parse_32("signed_pre_key_private", &signed_priv_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(env, &e);
            return std::ptr::null_mut();
        }
    };

    let one_time_opt: Option<[u8; 32]> = if one_time_priv.is_null() {
        None
    } else {
        match bytes_in(env, &one_time_priv) {
            Some(b) => {
                if b.is_empty() {
                    None
                } else {
                    match parse_32("one_time_private", &b) {
                        Ok(v) => Some(v),
                        Err(e) => {
                            throw_runtime(env, &e);
                            return std::ptr::null_mut();
                        }
                    }
                }
            }
            None => None,
        }
    };

    let prekey_msg = match PreKeySignalMessage::parse(&pkmsg_b) {
        Ok(m) => m,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };

    let mut state = match session::process_pre_key_message(
        &prekey_msg,
        &local_priv_32,
        &local_pub_32,
        &signed_priv_32,
        one_time_opt.as_ref(),
    ) {
        Ok(s) => s,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };

    let inner_msg = match SignalMessage::parse(&prekey_msg.message) {
        Ok(m) => m,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };

    let mut rng = OsRng;
    let pt = match session::decrypt(&mut rng, &mut state, &inner_msg) {
        Ok(p) => p,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };

    let new_sess = state.serialize();
    pair_out(env, &pt, &new_sess)
}

// Group

fn create_sender_key_inner<'a>(env: &mut JNIEnv<'a>) -> jobjectArray {
    let mut rng = OsRng;
    let (state, skdm) = group::create(&mut rng);
    let state_bytes = state.serialize();
    let skdm_bytes = skdm.serialize();
    pair_out(env, &state_bytes, &skdm_bytes)
}

fn process_sender_key_inner<'a>(env: &mut JNIEnv<'a>, skdm_bytes: JByteArray<'a>) -> jbyteArray {
    let skdm_b = match bytes_in(env, &skdm_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(env, "skdm_bytes null");
            return std::ptr::null_mut();
        }
    };
    let skdm = match SenderKeyDistributionMessage::parse(&skdm_b) {
        Ok(s) => s,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let state = match group::process(&skdm) {
        Ok(s) => s,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    bytes_out(env, &state.serialize())
}

fn encrypt_group_inner<'a>(
    env: &mut JNIEnv<'a>,
    state_bytes: JByteArray<'a>,
    plaintext: JByteArray<'a>,
) -> jobjectArray {
    let sess_b = match bytes_in(env, &state_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(env, "state_bytes null");
            return std::ptr::null_mut();
        }
    };
    let pt_b = match bytes_in(env, &plaintext) {
        Some(b) => b,
        None => {
            throw_runtime(env, "plaintext null");
            return std::ptr::null_mut();
        }
    };
    let mut state = match group::SenderKeyState::deserialize(&sess_b) {
        Ok(s) => s,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let mut rng = OsRng;
    let ct = match group::encrypt(&mut rng, &mut state, &pt_b) {
        Ok(c) => c,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let new_state = state.serialize();
    pair_out(env, &ct, &new_state)
}

fn decrypt_group_inner<'a>(
    env: &mut JNIEnv<'a>,
    state_bytes: JByteArray<'a>,
    ciphertext: JByteArray<'a>,
) -> jobjectArray {
    let sess_b = match bytes_in(env, &state_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(env, "state_bytes null");
            return std::ptr::null_mut();
        }
    };
    let ct_b = match bytes_in(env, &ciphertext) {
        Some(b) => b,
        None => {
            throw_runtime(env, "ciphertext null");
            return std::ptr::null_mut();
        }
    };
    let mut state = match group::SenderKeyState::deserialize(&sess_b) {
        Ok(s) => s,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let pt = match group::decrypt(&mut state, &ct_b) {
        Ok(p) => p,
        Err(CryptoError(e)) => {
            throw_runtime(env, e);
            return std::ptr::null_mut();
        }
    };
    let new_state = state.serialize();
    pair_out(env, &pt, &new_state)
}

// ---------------------------------------------------------------------------
// JNI exports — wrapped in catch_unwind
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_generateKeyPair<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| generate_keypair_inner(&mut env))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in generateKeyPair",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_publicFromPrivate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    private: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| public_from_private_inner(&mut env, private))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in publicFromPrivate",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_x25519Agreement<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    private: JByteArray<'local>,
    public: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| x25519_agreement_inner(&mut env, private, public))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in x25519Agreement",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_sign<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    private: JByteArray<'local>,
    message: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| sign_inner(&mut env, private, message))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new("java/lang/RuntimeException", "Native panic in sign");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_verify<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    public: JByteArray<'local>,
    message: JByteArray<'local>,
    signature: JByteArray<'local>,
) -> jboolean {
    match catch_unwind(AssertUnwindSafe(|| verify_inner(&mut env, public, message, signature))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_processPreKeyBundle<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    local_private: JByteArray<'local>,
    local_public: JByteArray<'local>,
    local_reg_id: jint,
    reg_id: jint,
    pre_key_id: jint,
    pre_key_public: JByteArray<'local>,
    signed_pre_key_id: jint,
    signed_pre_key_public: JByteArray<'local>,
    signed_pre_key_sig: JByteArray<'local>,
    identity_key: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        process_prekey_bundle_inner(
            &mut env,
            local_private,
            local_public,
            local_reg_id,
            reg_id,
            pre_key_id,
            pre_key_public,
            signed_pre_key_id,
            signed_pre_key_public,
            signed_pre_key_sig,
            identity_key,
        )
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in processPreKeyBundle",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_encrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: JByteArray<'local>,
    plaintext: JByteArray<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| encrypt_inner(&mut env, session, plaintext))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new("java/lang/RuntimeException", "Native panic in encrypt");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_decryptMessage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: JByteArray<'local>,
    ciphertext: JByteArray<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| decrypt_message_inner(&mut env, session, ciphertext))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in decryptMessage",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_decryptPreKeyMessage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    local_private: JByteArray<'local>,
    local_public: JByteArray<'local>,
    signed_private: JByteArray<'local>,
    one_time_private: JByteArray<'local>,
    prekey_bytes: JByteArray<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| {
        decrypt_prekey_inner(
            &mut env,
            local_private,
            local_public,
            signed_private,
            one_time_private,
            prekey_bytes,
        )
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in decryptPreKeyMessage",
            );
            std::ptr::null_mut()
        }
    }
}
