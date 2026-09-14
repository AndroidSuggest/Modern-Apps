#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_createSenderKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| create_sender_key_inner(&mut env))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in createSenderKey",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_processSenderKey<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    skdm: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| process_sender_key_inner(&mut env, skdm))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in processSenderKey",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_encryptGroup<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    state: JByteArray<'local>,
    plaintext: JByteArray<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| encrypt_group_inner(&mut env, state, plaintext))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in encryptGroup",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_decryptGroup<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    state: JByteArray<'local>,
    ciphertext: JByteArray<'local>,
) -> jobjectArray {
    match catch_unwind(AssertUnwindSafe(|| decrypt_group_inner(&mut env, state, ciphertext))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in decryptGroup",
            );
            std::ptr::null_mut()
        }
    }
}

// -- Signal sealed sender (same Rust primitives, Signal JNI class) --

fn sealed_sender_encrypt_inner<'a>(
    env: &mut JNIEnv<'a>,
    plaintext: JByteArray<'a>,
    recipient_aci: jni::objects::JString<'a>,
) -> jbyteArray {
    let pt = match bytes_in(env, &plaintext) {
        Some(b) => b,
        None => { throw_runtime(env, "plaintext null"); return std::ptr::null_mut(); }
    };
    let aci: String = match env.get_string(&recipient_aci) {
        Ok(s) => s.into(),
        Err(_) => { throw_runtime(env, "recipientAci null"); return std::ptr::null_mut(); }
    };
    let sealed = signal::sealed_sender_encrypt(&pt, &aci);
    bytes_out(env, &sealed)
}

fn sealed_sender_decrypt_inner<'a>(
    env: &mut JNIEnv<'a>,
    ciphertext: JByteArray<'a>,
) -> jbyteArray {
    let ct = match bytes_in(env, &ciphertext) {
        Some(b) => b,
        None => { throw_runtime(env, "ciphertext null"); return std::ptr::null_mut(); }
    };
    // Try empty-key path (Kotlin supplies ACI out-of-band; Rust stub uses empty for round-trip tests)
    let pt = match signal::sealed_sender_decrypt_any(&ct) {
        Ok(p) => p,
        Err(CryptoError(e)) => { throw_runtime(env, e); return std::ptr::null_mut(); }
    };
    bytes_out(env, &pt)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_sealedSenderEncrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    plaintext: JByteArray<'local>,
    recipient_aci: jni::objects::JString<'local>,
    _recipient_device_id: jint,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| sealed_sender_encrypt_inner(&mut env, plaintext, recipient_aci))) {
        Ok(v) => v, Err(_) => { let _ = env.exception_clear(); let _ = env.throw_new("java/lang/RuntimeException", "Native panic in sealedSenderEncrypt"); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_sealedSenderDecrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ciphertext: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| sealed_sender_decrypt_inner(&mut env, ciphertext))) {
        Ok(v) => v, Err(_) => { let _ = env.exception_clear(); let _ = env.throw_new("java/lang/RuntimeException", "Native panic in sealedSenderDecrypt"); std::ptr::null_mut() }
    }
}

// Also expose the same symbols under RustWhatsAppCrypto so a single .so serves both clients
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_sealedSenderEncrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    plaintext: JByteArray<'local>,
    recipient_aci: jni::objects::JString<'local>,
    recipient_device_id: jint,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| sealed_sender_encrypt_inner(&mut env, plaintext, recipient_aci))) {
        Ok(v) => v, Err(_) => { let _ = env.exception_clear(); let _ = env.throw_new("java/lang/RuntimeException", "Native panic in sealedSenderEncrypt"); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_whatsapp_e2e_RustWhatsAppCrypto_sealedSenderDecrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ciphertext: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| sealed_sender_decrypt_inner(&mut env, ciphertext))) {
        Ok(v) => v, Err(_) => { let _ = env.exception_clear(); let _ = env.throw_new("java/lang/RuntimeException", "Native panic in sealedSenderDecrypt"); std::ptr::null_mut() }
    }
}

// Signal PQXDH Kyber bridge — real PQXDH goes via libsignal Java SessionBuilder; Rust stub keeps build green
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_processPreKeyBundle<'local>(
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
    _kyber_pre_key_id: jint,
    _kyber_pre_key_public: JByteArray<'local>,
    _kyber_pre_key_signature: JByteArray<'local>,
    _kyber_ciphertext: JByteArray<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        process_prekey_bundle_inner(&mut env, local_private, local_public, local_reg_id, reg_id, pre_key_id, pre_key_public, signed_pre_key_id, signed_pre_key_public, signed_pre_key_sig, identity_key)
    })) { Ok(v) => v, Err(_) => { let _ = env.exception_clear(); let _ = env.throw_new("java/lang/RuntimeException", "Native panic in RustSignalCrypto.processPreKeyBundle"); std::ptr::null_mut() } }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_signal_e2e_RustSignalCrypto_markKyberPreKeyUsed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    kyber_id: jint,
    signed_ec_id: jint,
    base_key: JByteArray<'local>,
) -> jboolean {
    match catch_unwind(AssertUnwindSafe(|| {
        let b = match bytes_in(&mut env, &base_key) { Some(x) => x, None => { let _ = env.throw_new("java/lang/RuntimeException","baseKey null"); return 0 as jboolean; } };
        if crate::signal::mark_kyber_pre_key_used(kyber_id, signed_ec_id, &b) { 1 } else { 0 }
    })) { Ok(v) => v, Err(_) => { let _ = env.exception_clear(); 0 } }
}
