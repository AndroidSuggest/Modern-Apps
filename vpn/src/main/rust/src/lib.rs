pub mod crypto;
pub mod noise;
pub mod packet;
pub mod tun;
mod sleepyinstant;

pub mod x25519 {
    pub use x25519_dalek::{PublicKey, ReusableSecret, StaticSecret};
}

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jstring, jlong};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

type Handle = u64;
struct Entry {
    tunn: noise::Tunn,
    _table: noise::index_table::IndexTable,
    _rl: Arc<noise::rate_limiter::RateLimiter>,
}
static TUNNELS: OnceLock<Mutex<HashMap<Handle, Entry>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn reg() -> &'static Mutex<HashMap<Handle, Entry>> { TUNNELS.get_or_init(|| Mutex::new(HashMap::new())) }

fn decode_key(s: &str) -> Option<[u8; 32]> {
    let clean = s.trim();
    let bytes = if clean.len() == 44 && clean.ends_with('=') {
        use base64::Engine; base64::engine::general_purpose::STANDARD.decode(clean).ok()?
    } else if clean.len() == 43 {
        use base64::Engine; base64::engine::general_purpose::STANDARD.decode(format!("{clean}=")).ok()?
    } else if clean.len() == 64 {
        hex::decode(clean).ok()?
    } else {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.decode(clean)
            .or_else(|_| base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(clean)).ok()?
    };
    if bytes.len() != 32 { return None; }
    let mut arr = [0u8; 32]; arr.copy_from_slice(&bytes); Some(arr)
}
fn b64e(k: &[u8]) -> String { use base64::Engine; base64::engine::general_purpose::STANDARD.encode(k) }
fn js_to_rs(env: &mut JNIEnv, js: &JString) -> String { env.get_string(js).map(|s| s.into()).unwrap_or_default() }
fn rs_to_js<'a>(env: &mut JNIEnv<'a>, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_init(_env: JNIEnv, _class: JClass) {}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_generatePrivateKey<'a>(
    mut env: JNIEnv<'a>, _class: JClass<'a>,
) -> jstring {
    let secret = x25519_dalek::StaticSecret::random_from_rng(rand::rngs::OsRng);
    rs_to_js(&mut env, &b64e(secret.as_bytes()))
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_derivePublicKey<'a>(
    mut env: JNIEnv<'a>, _class: JClass<'a>, priv_j: JString<'a>,
) -> jstring {
    let ps = js_to_rs(&mut env, &priv_j);
    let Some(pb) = decode_key(&ps) else { return std::ptr::null_mut(); };
    let secret = x25519_dalek::StaticSecret::from(pb);
    rs_to_js(&mut env, &b64e(x25519_dalek::PublicKey::from(&secret).as_bytes()))
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_newTunnel<'a>(
    mut env: JNIEnv<'a>, _class: JClass<'a>,
    priv_j: JString<'a>, peer_j: JString<'a>, psk_j: JString<'a>, keepalive: i32,
) -> jlong {
    let priv_s = js_to_rs(&mut env, &priv_j);
    let peer_s = js_to_rs(&mut env, &peer_j);
    let psk_s = js_to_rs(&mut env, &psk_j);
    let Some(priv_b) = decode_key(&priv_s) else { return -1; };
    let Some(peer_b) = decode_key(&peer_s) else { return -2; };
    let psk = if psk_s.trim().is_empty() { None } else { decode_key(&psk_s) };
    let ka = if keepalive <= 0 { None } else { Some(keepalive as u16) };
    let sec = x25519_dalek::StaticSecret::from(priv_b);
    let pubk = x25519_dalek::PublicKey::from(peer_b);
    let table = noise::index_table::IndexTable::from_os_rng();
    let rl = Arc::new(noise::rate_limiter::RateLimiter::new(
        &x25519_dalek::PublicKey::from(&sec), 100,
    ));
    let tunn = noise::Tunn::new(sec, pubk, psk, ka, table.clone(), rl.clone());
    let h = NEXT.fetch_add(1, Ordering::Relaxed);
    reg().lock().unwrap().insert(h, Entry { tunn, _table: table, _rl: rl });
    h as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_freeTunnel(_env: JNIEnv, _class: JClass, handle: jlong) {
    reg().lock().unwrap().remove(&(handle as u64));
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_formatHandshakeInit<'a>(
    mut env: JNIEnv<'a>, _class: JClass<'a>, handle: jlong,
) -> jbyteArray {
    let mut guard = reg().lock().unwrap();
    let e = match guard.get_mut(&(handle as u64)) { Some(x) => x, None => return std::ptr::null_mut() };
    let pkt = match e.tunn.format_handshake_initiation(false) { Some(p) => p, None => return std::ptr::null_mut() };
    env.byte_array_from_slice(pkt.as_bytes()).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_consumeIncomingPacketDetailed<'a>(
    mut env: JNIEnv<'a>, _class: JClass<'a>, handle: jlong, bytes: JByteArray<'a>,
) -> jbyteArray {
    let slice = match env.convert_byte_array(&bytes) { Ok(b) => b, Err(_) => return std::ptr::null_mut() };
    let mut guard = reg().lock().unwrap();
    let e = match guard.get_mut(&(handle as u64)) { Some(x) => x, None => return std::ptr::null_mut() };
    let pkt = packet::Packet::from_bytes(bytes::BytesMut::from(&slice[..]));
    let wg = match pkt.try_into_wg() { Ok(p) => p, Err(_) => return std::ptr::null_mut() };
    let res = e.tunn.handle_incoming_packet(wg);
    let (tag, payload) = match res {
        noise::TunnResult::WriteToNetwork(p) => { let o: packet::Packet = p.into(); (1u8, o.as_bytes().to_vec()) }
        noise::TunnResult::WriteToTunnel(p) => {
            if p.is_empty() { (3u8, Vec::new()) } else { (2u8, p.as_bytes().to_vec()) }
        }
        _ => return std::ptr::null_mut(),
    };
    let mut out = Vec::with_capacity(1 + payload.len());
    out.push(tag); out.extend_from_slice(&payload);
    env.byte_array_from_slice(&out).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_encapsulate<'a>(
    mut env: JNIEnv<'a>, _class: JClass<'a>, handle: jlong, ip_bytes: JByteArray<'a>,
) -> jbyteArray {
    let slice = match env.convert_byte_array(&ip_bytes) { Ok(b) => b, Err(_) => return std::ptr::null_mut() };
    let mut guard = reg().lock().unwrap();
    let e = match guard.get_mut(&(handle as u64)) { Some(x) => x, None => return std::ptr::null_mut() };
    let pkt = packet::Packet::from_bytes(bytes::BytesMut::from(&slice[..]));
    let mut mtu = tun::MtuWatcher::new(1280);
    let Some(kind) = e.tunn.handle_outgoing_packet(pkt, Some(&mut mtu)) else { return std::ptr::null_mut() };
    let out: packet::Packet = kind.into();
    env.byte_array_from_slice(out.as_bytes()).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_tickTimersDetailed<'a>(
    mut env: JNIEnv<'a>, _class: JClass<'a>, handle: jlong,
) -> jbyteArray {
    let mut guard = reg().lock().unwrap();
    let e = match guard.get_mut(&(handle as u64)) { Some(x) => x, None => return std::ptr::null_mut() };
    match e.tunn.update_timers() {
        Ok(Some(kind)) => {
            let out: packet::Packet = kind.into();
            let mut buf = vec![1u8];
            buf.extend_from_slice(out.as_bytes());
            env.byte_array_from_slice(&buf).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
        }
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_getStats<'a>(
    mut env: JNIEnv<'a>, _class: JClass<'a>, handle: jlong,
) -> jstring {
    let guard = reg().lock().unwrap();
    let e = match guard.get(&(handle as u64)) { Some(x) => x, None => return std::ptr::null_mut() };
    let (time, tx, rx, loss, rtt) = e.tunn.stats();
    let s = format!(
        "{{\"handshakeMs\":{},\"tx\":{},\"rx\":{},\"loss\":{},\"rtt\":{}}}",
        time.map(|d| d.as_millis() as u64).unwrap_or(0), tx, rx, loss, rtt.unwrap_or(0)
    );
    rs_to_js(&mut env, &s)
}
