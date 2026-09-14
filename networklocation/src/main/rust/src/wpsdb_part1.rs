/// `lookup(handle, keyHi, keyLo) -> double[3]` (`[lat, lon, accuracyMeters]`), or null for an
/// unknown key. The key is 128 bits split across two longs: a 48-bit WiFi MAC leaves `keyHi`
/// zero, an 84-bit cell key does not. A negative accuracy means the store had none.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_WpsStoreNative_lookup<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    key_hi: jlong,
    key_lo: jlong,
) -> jdoubleArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let reader = unsafe { &*(handle as *const Handle) };
    let key = ((key_hi as u64 as u128) << 64) | (key_lo as u64 as u128);
    match reader.lookup(key) {
        Some((lat, lon, acc)) => {
            let arr = match env.new_double_array(3) {
                Ok(a) => a,
                Err(_) => return std::ptr::null_mut(),
            };
            if env.set_double_array_region(&arr, 0, &[lat, lon, acc]).is_err() {
                return std::ptr::null_mut();
            }
            arr.into_raw()
        }
        None => std::ptr::null_mut(),
    }
}

/// `close(handle)` — frees the reader, its mapping and its dup'd fd. Safe to call with 0.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_WpsStoreNative_close<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Handle));
        }
    }
}
