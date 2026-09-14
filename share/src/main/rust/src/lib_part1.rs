/// byte[] nativeBuildBleAdvertisement(byte[] data, byte[] deviceToken, boolean fast) -> service-data or null
///
/// Nearby Connections `BleAdvertisement` for GATT `0xFEF3`. `fast` selects the 27-byte
/// legacy budget over extended advertising's 512 (`p000\dscb.java:110-123`); a real
/// endpoint info only fits fast mode for a very short device name.
/// `deviceToken` must be empty or exactly 2 bytes.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeBuildBleAdvertisement<
    'l,
>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jData: JByteArray<'l>,
    jDeviceToken: JByteArray<'l>,
    fast: jboolean,
) -> jbyteArray {
    let Some(data) = bytes_in(&mut env, &jData) else {
        return std::ptr::null_mut();
    };
    let token_bytes = bytes_in(&mut env, &jDeviceToken).unwrap_or_default();
    let device_token = match token_bytes.len() {
        0 => None,
        ble_adv::DEVICE_TOKEN_LEN => {
            let mut t = [0u8; ble_adv::DEVICE_TOKEN_LEN];
            t.copy_from_slice(&token_bytes);
            Some(t)
        }
        _ => return std::ptr::null_mut(),
    };
    let adv = if fast != 0 {
        ble_adv::BleAdvertisement::fast(data, device_token)
    } else {
        ble_adv::BleAdvertisement::extended(data, device_token)
    };
    match adv.serialize() {
        Some(bytes) => bytes_out(&env, &bytes),
        None => std::ptr::null_mut(),
    }
}

/// byte[] nativeParseBleAdvertisement(byte[] serviceData) -> the `data` field, or null
///
/// Returns `null` when the bytes are not a version-2 / socket-version-2
/// `BleAdvertisement`, or when the embedded `serviceIdHash` is not
/// `"NearbySharing"`'s — which filters out any other `0xFEF3` advertiser.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeParseBleAdvertisement<
    'l,
>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jServiceData: JByteArray<'l>,
) -> jbyteArray {
    let Some(raw) = bytes_in(&mut env, &jServiceData) else {
        return std::ptr::null_mut();
    };
    let Some(adv) = ble_adv::BleAdvertisement::parse(&raw) else {
        return std::ptr::null_mut();
    };
    if let Some(hash) = adv.service_id_hash {
        if hash != ble_adv::ble_service_id_hash() {
            return std::ptr::null_mut();
        }
    }
    bytes_out(&env, &adv.data)
}

/// byte[] nativeFastInitiationServiceData(byte[] metadata) -> `FC128E` ‖ metadata
///
/// Service data for the `0xFE2C` FastInitiation beacon. `metadata` must be 2 bytes.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeFastInitiationServiceData<
    'l,
>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jMetadata: JByteArray<'l>,
) -> jbyteArray {
    let Some(raw) = bytes_in(&mut env, &jMetadata) else {
        return std::ptr::null_mut();
    };
    if raw.len() != 2 {
        return std::ptr::null_mut();
    }
    let mut metadata = [0u8; 2];
    metadata.copy_from_slice(&raw);
    bytes_out(&env, &ble_adv::fast_initiation_service_data(metadata))
}

// ---------------------------------------------------------------------------
// Nearby Sharing endpoint info + WifiLanServiceInfo (endpoint_info.rs)
//
// Rust owns the byte layouts; Kotlin owns Base64, which is a platform API
// (`android.util.Base64` flag `URL_SAFE or NO_PADDING or NO_WRAP` = 11, per
// `p000\bloa.java:29`), so these return raw bytes.
// ---------------------------------------------------------------------------

/// byte[] nativeBuildEndpointInfo(String deviceName, int deviceType) -> blob or null
///
/// The Nearby Sharing endpoint-info blob every peer needs to list us. Null for a blank
/// device name. The metadata key inside is a random decoy — Everyone mode needs no real
/// credential (see `endpoint_info.rs`).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeBuildEndpointInfo<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jDeviceName: JString<'l>,
    deviceType: jint,
) -> jbyteArray {
    let Some(name) = str_in(&mut env, &jDeviceName) else {
        return std::ptr::null_mut();
    };
    let device_type = endpoint_info::DeviceType::from_raw(u8::try_from(deviceType).unwrap_or(0));
    match endpoint_info::build(&name, device_type, session::fill_random) {
        Some(blob) => bytes_out(&env, &blob),
        None => std::ptr::null_mut(),
    }
}

/// byte[] nativeParseEndpointInfo(byte[] blob) -> JSON utf8 or null
///
/// `{"deviceName":"Pixel 7","deviceType":1,"version":1,"vendorId":0}`, with `deviceName`
/// omitted for a contact-only advertisement. Null when a real device would reject the
/// blob, so callers can use it as a filter as well as a decoder.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeParseEndpointInfo<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jBlob: JByteArray<'l>,
) -> jbyteArray {
    let Some(raw) = bytes_in(&mut env, &jBlob) else {
        return std::ptr::null_mut();
    };
    let Some(info) = endpoint_info::parse(&raw) else {
        return std::ptr::null_mut();
    };
    let name_field = match info.device_name {
        Some(name) => format!("\"deviceName\":\"{}\",", json_escape(&name)),
        None => String::new(),
    };
    let json = format!(
        "{{{}\"deviceType\":{},\"version\":{},\"vendorId\":{}}}",
        name_field, info.device_type as i32, info.version, info.vendor_id,
    );
    bytes_out(&env, json.as_bytes())
}

/// byte[] nativeBuildWifiLanServiceInfo(String endpointId) -> 8 raw bytes or null
///
/// Base64 these (URL-safe, unpadded, unwrapped) to get the mDNS instance name GMS
/// expects. Null unless `endpointId` is exactly 4 ASCII characters.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeBuildWifiLanServiceInfo<
    'l,
>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jEndpointId: JString<'l>,
) -> jbyteArray {
    let Some(endpoint_id) = str_in(&mut env, &jEndpointId) else {
        return std::ptr::null_mut();
    };
    match endpoint_info::build_wifi_lan_service_info(&endpoint_id) {
        Some(bytes) => bytes_out(&env, &bytes),
        None => std::ptr::null_mut(),
    }
}

/// byte[] nativeParseWifiLanServiceInfo(byte[] raw) -> JSON utf8 or null
///
/// `{"endpointId":"ABCD","pcp":3}`. Null when the bytes fail the version, PCP or length
/// checks GMS applies (`p000\dnux.java:86-118`), which filters foreign advertisers on the
/// same service type.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeParseWifiLanServiceInfo<
    'l,
>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jRaw: JByteArray<'l>,
) -> jbyteArray {
    let Some(raw) = bytes_in(&mut env, &jRaw) else {
        return std::ptr::null_mut();
    };
    let Some(info) = endpoint_info::parse_wifi_lan_service_info(&raw) else {
        return std::ptr::null_mut();
    };
    let json = format!(
        "{{\"endpointId\":\"{}\",\"pcp\":{}}}",
        json_escape(&info.endpoint_id),
        info.pcp,
    );
    bytes_out(&env, json.as_bytes())
}

/// byte[] nativeBuildBleEndpointPayload(String endpointId, byte[] endpointInfo) -> `BleAdvertisement.data` or null
///
/// Wraps the Nearby Sharing blob in the Nearby Connections BLE envelope
/// (`pcp/version ‖ serviceIdHash ‖ endpointId ‖ len ‖ blob`). Advertising the bare blob
/// leaves the peer with no endpoint id, and it drops us without logging a parse failure.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeBuildBleEndpointPayload<
    'l,
>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jEndpointId: JString<'l>,
    jEndpointInfo: JByteArray<'l>,
) -> jbyteArray {
    let Some(endpoint_id) = str_in(&mut env, &jEndpointId) else {
        return std::ptr::null_mut();
    };
    let Some(info) = bytes_in(&mut env, &jEndpointInfo) else {
        return std::ptr::null_mut();
    };
    match endpoint_info::build_ble_endpoint_payload(&endpoint_id, &info) {
        Some(bytes) => bytes_out(&env, &bytes),
        None => std::ptr::null_mut(),
    }
}

/// byte[] nativeParseBleEndpointInfo(byte[] data) -> the nested endpoint-info blob, or null
///
/// `data` is a `BleAdvertisement.data` field as returned by `nativeParseBleAdvertisement`.
/// Null when it is not a `"NearbySharing"` endpoint payload.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeParseBleEndpointInfo<
    'l,
>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jData: JByteArray<'l>,
) -> jbyteArray {
    let Some(raw) = bytes_in(&mut env, &jData) else {
        return std::ptr::null_mut();
    };
    match endpoint_info::parse_ble_endpoint_payload(&raw) {
        Some(payload) => bytes_out(&env, &payload.endpoint_info),
        None => std::ptr::null_mut(),
    }
}

/// String nativeParseBleEndpointId(byte[] data) -> the peer's 4-character endpoint id, or null
///
/// The same id the peer publishes in its mDNS `WifiLanServiceInfo`, so the BLE and mDNS legs
/// of discovery can be merged instead of listing one device twice.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeParseBleEndpointId<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jData: JByteArray<'l>,
) -> jni::sys::jobject {
    let Some(raw) = bytes_in(&mut env, &jData) else {
        return std::ptr::null_mut();
    };
    let Some(payload) = endpoint_info::parse_ble_endpoint_payload(&raw) else {
        return std::ptr::null_mut();
    };
    match env.new_string(payload.endpoint_id) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------------------
// Nearby Presence — retained, but NOT on the Quick Share path.
//
// Presence is a separate subsystem advertising under `0xFCF1`. BetoCore's
// credential / D2D / payload FFI has no Java callers in GMS 26.24.34, and whether
// betocore is live for Quick Share at all could not be determined (writeup §11.1 /
// §10.1 — see `share/QUICK_SHARE_VERIFICATION.md`). These entry points stay
// compiled and unit-tested so the work is not lost; discovery uses the `ble_adv`
// codec above instead.
// ---------------------------------------------------------------------------

/// byte[] nativeBuildPresenceAdvert(String deviceName) -> advert bytes or null
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeBuildPresenceAdvert<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jName: JString<'l>,
) -> jbyteArray {
    let name = str_in(&mut env, &jName).unwrap_or_else(|| "Share".to_string());
    match crate::presence::build_presence_advert(&name) {
        Some(b) => bytes_out(&env, &b),
        None => std::ptr::null_mut(),
    }
}

/// byte[] nativeParsePresenceAdvert(byte[] serviceData) -> JSON utf8 or null
/// Returns JSON: {"deviceName":"Pixel 7","deviceType":1,"txPower":0,"isTruncated":false}
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeParsePresenceAdvert<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jBytes: JByteArray<'l>,
) -> jbyteArray {
    let bytes = match bytes_in(&mut env, &jBytes) {
        Some(b) => b,
        None => return std::ptr::null_mut(),
    };
    match crate::presence::parse_presence_advert_json(&bytes) {
        Some(json) => bytes_out(&env, &json),
        None => std::ptr::null_mut(),
    }
}

/// String nativeParsePresenceAdvertName(byte[] advertBytes) -> display name or null
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeParsePresenceAdvertName<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jBytes: JByteArray<'l>,
) -> jni::sys::jobject {
    let bytes = match bytes_in(&mut env, &jBytes) {
        Some(b) => b,
        None => return std::ptr::null_mut(),
    };
    let name = match crate::presence::parse_presence_advert_name(&bytes) {
        Some(n) => n,
        None => return std::ptr::null_mut(),
    };
    match env.new_string(name) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// String nativeQueryTrace(long handle) -> recent protocol events, one per line, or null.
///
/// Diagnostic: names the frames each side actually exchanged. A peer that goes quiet gives
/// no other clue about which frame it disliked, and the wire is encrypted, so a packet
/// capture cannot answer it either.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryTrace<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jni::sys::jobject {
    let text = with_session(handle, None, |s| Some(s.trace_text()));
    match text {
        Some(t) => match env.new_string(t) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

/// void nativeDestroy(long handle)
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeDestroy<'l>(
    _env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) {
    let mut map = match sessions().lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    let _ = map.remove(&handle);
}
