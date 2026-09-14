//! Nearby Sharing endpoint-info blob and Nearby Connections `WifiLanServiceInfo`.
//!
//! These are the two structures that make a device *listed* by Quick Share. Both are
//! recovered from the GMS 26.24.34 decompile; the obfuscated classes kept their
//! `toString()` names, so `dzqk` is `Advertisement(version, encryptedMetadataKey,
//! deviceType, deviceName, qrCodeAdvertisingToken, vendorId)` and `dzrl` is
//! `EncryptedMetadataKey(cipherText, salt)`.
//!
//! The same blob is required in three places, each of which drops us on a parse
//! failure:
//!
//! 1. BLE `0xFEF3` `BleAdvertisement.data` — `p000\eafg.java:89-93`
//!    (`"Failed to parse endpoint %s (%s)"`).
//! 2. the mDNS `_FC9F5ED42C8A._tcp` record's `n` TXT attribute — `p000\dnux.java:103-105`
//!    (`"Cannot deserialize WifiLanServiceInfo: EndpointInfo is missing"`).
//! 3. `ConnectionRequestFrame.endpoint_info` — `p000\each.java:2092-2097`
//!    (`"Failed to parse incoming connection from endpoint %s. Disconnecting."`).
//!
//! Everyone mode needs no real metadata key: `p000\eafm.java:7-14` looks the credential
//! up and passes `null` through when nothing matches, and `dzqk.m69795a` only bails with
//! `"Decode contact-only mode advertisement without credential"` when the **plaintext
//! name is absent**. So a random salt + cipher text plus a plaintext name decodes fine.

/// Minimum endpoint-info length — `p000\dzqj.java:18-21` rejects anything shorter.
pub const MIN_ENDPOINT_INFO_LEN: usize = 17;

/// `EncryptedMetadataKey.salt` width — `p000\dzrl.java:20-23` accepts only 2.
pub const SALT_LEN: usize = 2;

/// `EncryptedMetadataKey.cipherText` width — `p000\dzrl.java:20-23` accepts only 14.
pub const METADATA_KEY_LEN: usize = 14;

/// Longest device name — `p000\dzqk.java:43-45` throws above 32 bytes.
pub const MAX_NAME_LEN: usize = 32;

/// Advertisement version we emit.
///
/// `p000\dzqj.java:26-29` accepts 0 or 1, and all three GMS call sites write 1
/// (`p000\dzph.java:355`, `p000\dzxp.java:360`, `p000\eair.java:93`).
pub const ENDPOINT_INFO_VERSION: u8 = 1;

/// Device type, picking the icon the peer shows — `p000\eanu.java:6-22`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeviceType {
    Unknown = 0,
    Phone = 1,
    Tablet = 2,
    Laptop = 3,
    Car = 4,
    Foldable = 5,
    Xr = 6,
}

impl DeviceType {
    /// The device type for `raw`, or [`DeviceType::Unknown`] for anything else —
    /// `p000\eanu.java`'s `default` branch.
    pub fn from_raw(raw: u8) -> Self {
        match raw {
            1 => Self::Phone,
            2 => Self::Tablet,
            3 => Self::Laptop,
            4 => Self::Car,
            5 => Self::Foldable,
            6 => Self::Xr,
            _ => Self::Unknown,
        }
    }
}

/// TLV type carrying the 1-byte vendor id — `p000\dzqj.java:80-83`.
///
/// Type 1 is the QR-code advertising token (`:79`), which Everyone mode has no use for.
const TLV_VENDOR_ID: u8 = 2;

/// Write `value` into the `width` bits of `byte` starting at `shift`.
///
/// `p000\dzrm.java:7-13` (`m69822a`).
fn set_bits(byte: u8, value: u8, shift: u32, width: u32) -> u8 {
    let mask = ((1u16 << width) - 1) as u8;
    (byte & !(mask << shift)) | ((value & mask) << shift)
}

/// Read the `width` bits of `byte` starting at `shift`.
///
/// `p000\dzrm.java:16-18` (`m69823b`).
fn get_bits(byte: u8, shift: u32, width: u32) -> u8 {
    let mask = ((1u16 << width) - 1) as u8;
    (byte >> shift) & mask
}

/// A parsed Nearby Sharing endpoint-info blob.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EndpointInfo {
    /// 0 or 1 — `p000\dzqj.java:26-29`.
    pub version: u8,
    /// Device type, driving the icon the peer renders.
    pub device_type: DeviceType,
    /// The plaintext device name, absent in contact-only mode.
    pub device_name: Option<String>,
    /// `vendorId` from the type-2 TLV, 0 when it is absent.
    pub vendor_id: u8,
}

/// Truncate `name` to at most [`MAX_NAME_LEN`] bytes on a UTF-8 boundary.
///
/// A blind byte truncation could split a multi-byte character, and
/// `p000\dzqj.java:51-54` rejects a name that decodes to U+FFFD.
fn truncate_name(name: &str) -> &str {
    if name.len() <= MAX_NAME_LEN {
        return name;
    }
    let mut end = MAX_NAME_LEN;
    while end > 0 && !name.is_char_boundary(end) {
        end -= 1;
    }
    name.get(..end).unwrap_or("")
}

/// Build the endpoint-info blob for Everyone mode.
///
/// Mirrors the builder at `p000\dzqk.java:134-186`: a header byte, then the 2-byte salt
/// and the 14-byte cipher text, then a length-prefixed UTF-8 name. No TLVs are emitted —
/// both are optional and `dzqj` treats their absence as `null` / 0.
///
/// The metadata key is a **decoy**, in the same spirit as `payload::SECRET_ID_HASH_LEN`
/// and `SIGNED_DATA_DECOY_LEN`: `:share` holds no contact certificate, and Everyone mode
/// never needs the key to decrypt to anything (see the module docs). `random_bytes` must
/// fill its slice from a CSPRNG so the decoy is not a constant pattern.
///
/// Returns `None` for a blank name, which `p000\dzqk.java:46-48` rejects outright.
pub fn build(
    device_name: &str,
    device_type: DeviceType,
    random_bytes: impl Fn(&mut [u8]),
) -> Option<Vec<u8>> {
    let name = truncate_name(device_name);
    if name.is_empty() {
        return None;
    }
    let name_bytes = name.as_bytes();
    let mut header = set_bits(0, ENDPOINT_INFO_VERSION, 5, 3);
    header = set_bits(header, device_type as u8, 1, 3);
    // Bit 4 is the contact-only flag: 0 means a plaintext name follows.
    header = set_bits(header, 0, 4, 1);

    let mut out = Vec::with_capacity(MIN_ENDPOINT_INFO_LEN + 1 + name_bytes.len());
    out.push(header);
    let mut key = [0u8; SALT_LEN + METADATA_KEY_LEN];
    random_bytes(&mut key);
    out.extend_from_slice(&key);
    out.push(name_bytes.len() as u8);
    out.extend_from_slice(name_bytes);
    Some(out)
}

/// Parse an endpoint-info blob, or `None` if a peer would reject it.
///
/// Field for field `p000\dzqj.java:11-92`, including the `< 17` length floor, the
/// version check, the `1..=32` name bound, the U+FFFD rejection and the trailing TLV
/// walk.
pub fn parse(raw: &[u8]) -> Option<EndpointInfo> {
    // dzqj.java:18-21 — "Incorrect advertisement format: size (%s) is less than
    // minimum size (%s)."
    if raw.len() < MIN_ENDPOINT_INFO_LEN {
        return None;
    }
    let header = *raw.first()?;
    let version = get_bits(header, 5, 3);
    // dzqj.java:26-29 — "unknown version"; only 0 and 1 are accepted.
    if version != 0 && version != ENDPOINT_INFO_VERSION {
        return None;
    }
    let device_type = DeviceType::from_raw(get_bits(header, 1, 3));
    let contact_only = get_bits(header, 4, 1) == 1;
    let mut cursor = 1 + SALT_LEN + METADATA_KEY_LEN;

    let device_name = if contact_only {
        None
    } else {
        // dzqj.java:42-45 — the name bit is clear but no length byte follows.
        let name_len = *raw.get(cursor)? as usize;
        cursor += 1;
        // dzqj.java:47, :56 — "device name length %s is wrong."
        if name_len == 0 || name_len > MAX_NAME_LEN {
            return None;
        }
        let name_bytes = raw.get(cursor..cursor + name_len)?;
        cursor += name_len;
        // dzqj.java:50-54 — GMS decodes lossily and then rejects U+FFFD, so any
        // sequence that is not valid UTF-8 is refused.
        let name = std::str::from_utf8(name_bytes).ok()?;
        if name.contains('\u{FFFD}') {
            return None;
        }
        Some(name.to_owned())
    };

    // dzqj.java:62-78 — `type(1) len(1) value(len)` until the buffer runs out; a
    // truncated record is "wrong TLV format" and fails the whole parse.
    let mut vendor_id = 0u8;
    while cursor < raw.len() {
        let tlv_type = *raw.get(cursor)?;
        cursor += 1;
        let tlv_len = *raw.get(cursor)? as usize;
        cursor += 1;
        let value = raw.get(cursor..cursor + tlv_len)?;
        cursor += tlv_len;
        if tlv_type == TLV_VENDOR_ID {
            vendor_id = value.first().copied().unwrap_or(0);
        }
    }

    Some(EndpointInfo {
        version,
        device_type,
        device_name,
        vendor_id,
    })
}

// ---------------------------------------------------------------------------
// WifiLanServiceInfo — the mDNS instance name
// ---------------------------------------------------------------------------

/// Minimum `WifiLanServiceInfo` length — `p000\dnux.java:87-90` requires 8 raw bytes.
pub const MIN_WIFI_LAN_SERVICE_INFO_LEN: usize = 8;

/// Only supported `WifiLanServiceInfo` version — `p000\dnux.java:91-95`.
pub const WIFI_LAN_VERSION: u8 = 1;

/// Endpoint-id width — `p000\dnuw.java:179-181` refuses anything but 4 characters.
pub const ENDPOINT_ID_LEN: usize = 4;

/// The PCP Quick Share advertises under.
///
/// Quick Share uses `Strategy(1, 1)` (`p000\dzye.java:23`), which `dnsi.m63137x` maps to
/// 3 (`p000\dnsi.java:922`); `p000\dnux.java:110-113` accepts 1, 2 or 3.
pub const WIFI_LAN_PCP: u8 = 3;

/// A parsed `WifiLanServiceInfo` instance name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WifiLanServiceInfo {
    /// Pre-connection protocol, 1..=3.
    pub pcp: u8,
    /// The peer's 4-character endpoint id.
    pub endpoint_id: String,
    /// The 3-byte truncated service-id hash — `FC 9F 5E` for `"NearbySharing"`.
    pub service_id_hash: [u8; crate::ble_adv::BLE_SERVICE_ID_HASH_LEN],
}

/// Build the raw `WifiLanServiceInfo` that Base64-encodes into the mDNS instance name.
///
/// `p000\dnuw.java:175-199`: `(version << 5) | pcp`, the 4 ASCII endpoint-id bytes, the
/// 3-byte service-id hash, then the optional UWB-address length and flags bytes.
///
/// Only the mandatory 8 bytes are emitted. `p000\dnux.java:119` and `:134` guard both
/// trailing fields with `wrap.remaining() > 0`, and the flags byte's base value (`r24` at
/// `p000\dnuw.java:194-199`) did not survive decompilation — so omitting them is correct
/// and guesses nothing.
///
/// Returns `None` unless `endpoint_id` is exactly [`ENDPOINT_ID_LEN`] ASCII characters.
pub fn build_wifi_lan_service_info(endpoint_id: &str) -> Option<Vec<u8>> {
    if endpoint_id.len() != ENDPOINT_ID_LEN || !endpoint_id.is_ascii() {
        return None;
    }
    let mut out = Vec::with_capacity(MIN_WIFI_LAN_SERVICE_INFO_LEN);
    out.push((WIFI_LAN_VERSION << 5) | WIFI_LAN_PCP);
    out.extend_from_slice(endpoint_id.as_bytes());
    out.extend_from_slice(&crate::ble_adv::ble_service_id_hash());
    Some(out)
}

/// Parse a `WifiLanServiceInfo`, applying the checks at `p000\dnux.java:86-118`.
///
/// Lets our browse side filter foreign `_FC9F5ED42C8A._tcp` advertisers the same way GMS
/// does. The service-id hash is returned rather than checked, since GMS does not check it
/// here either — the service *type* already selected for `"NearbySharing"`.
pub fn parse_wifi_lan_service_info(raw: &[u8]) -> Option<WifiLanServiceInfo> {
    if raw.len() < MIN_WIFI_LAN_SERVICE_INFO_LEN {
        return None;
    }
    let header = *raw.first()?;
    // dnux.java:91-95 — "unsupported Version %d".
    if (header & 0xE0) >> 5 != WIFI_LAN_VERSION {
        return None;
    }
    // dnux.java:108-113 — "unsupported V1 PCP %d".
    let pcp = header & 0x1F;
    if !(1..=3).contains(&pcp) {
        return None;
    }
    let endpoint_id = std::str::from_utf8(raw.get(1..1 + ENDPOINT_ID_LEN)?)
        .ok()?
        .to_owned();
    let mut service_id_hash = [0u8; crate::ble_adv::BLE_SERVICE_ID_HASH_LEN];
    service_id_hash.copy_from_slice(raw.get(5..5 + crate::ble_adv::BLE_SERVICE_ID_HASH_LEN)?);
    Some(WifiLanServiceInfo {
        pcp,
        endpoint_id,
        service_id_hash,
    })
}

// ---------------------------------------------------------------------------
// Nearby Connections BLE endpoint payload — `BleAdvertisement.data`
// ---------------------------------------------------------------------------

/// Bytes before the endpoint info in a BLE `BleAdvertisement.data` field.
///
/// `pcp/version(1) ‖ serviceIdHash(3) ‖ endpointId(4) ‖ endpointInfoLen(1)`.
pub const BLE_PAYLOAD_PREFIX_LEN: usize = 9;

/// Build the `BleAdvertisement.data` payload for `0xFEF3`.
///
/// The Nearby Sharing blob is **nested** inside a Nearby Connections envelope; advertising
/// the bare blob makes the peer's medium layer parse the advertisement and then quietly drop
/// the endpoint, because it never finds an endpoint id.
///
/// Layout, measured from a Pixel 7 Pro advertising Quick Share in "Everyone" mode on GMS
/// 26.24.34 (its `NearbyMediums` log prints the same `data` this produces):
///
/// ```text
/// 23                    (version 1 << 5) | pcp 3   — same header byte as WifiLanServiceInfo
/// FC 9F 5E              serviceIdHash
/// 58 30 48 54           endpointId, 4 ASCII bytes  ("X0HT")
/// 25                    endpointInfo length        (37)
/// 22 …                  endpointInfo               (the Nearby Sharing blob)
/// 0C C4 13 44 C6 D0     bluetoothMacAddress        — optional, omitted here
/// 00 00                 trailing optionals         — omitted here
/// ```
///
/// The Bluetooth MAC and the two trailing bytes are deliberately not emitted: `:share`
/// accepts connections over WIFI_LAN only, so advertising a MAC would invite a Bluetooth
/// medium it cannot serve. If a peer turns out to require them, this is where to add them.
///
/// Returns `None` unless `endpoint_id` is exactly [`ENDPOINT_ID_LEN`] ASCII characters and
/// `endpoint_info` fits the single length byte.
pub fn build_ble_endpoint_payload(endpoint_id: &str, endpoint_info: &[u8]) -> Option<Vec<u8>> {
    if endpoint_id.len() != ENDPOINT_ID_LEN || !endpoint_id.is_ascii() {
        return None;
    }
    let len = u8::try_from(endpoint_info.len()).ok()?;
    let mut out = Vec::with_capacity(BLE_PAYLOAD_PREFIX_LEN + endpoint_info.len());
    out.push((WIFI_LAN_VERSION << 5) | WIFI_LAN_PCP);
    out.extend_from_slice(&crate::ble_adv::ble_service_id_hash());
    out.extend_from_slice(endpoint_id.as_bytes());
    out.push(len);
    out.extend_from_slice(endpoint_info);
    Some(out)
}

/// A parsed `BleAdvertisement.data` payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BleEndpointPayload {
    /// The peer's 4-character endpoint id.
    pub endpoint_id: String,
    /// The nested Nearby Sharing endpoint-info blob, for [`parse`].
    pub endpoint_info: Vec<u8>,
}

/// Parse a `BleAdvertisement.data` payload, or `None` if it is not one.
///
/// Applies the same version, PCP and service-id-hash checks as the mDNS side, and requires
/// the declared endpoint-info length to fit. Trailing bytes (Bluetooth MAC, UWB, flags) are
/// ignored rather than rejected — a real advertiser sends them.
pub fn parse_ble_endpoint_payload(raw: &[u8]) -> Option<BleEndpointPayload> {
    if raw.len() < BLE_PAYLOAD_PREFIX_LEN {
        return None;
    }
    let header = *raw.first()?;
    if (header & 0xE0) >> 5 != WIFI_LAN_VERSION {
        return None;
    }
    if !(1..=3).contains(&(header & 0x1F)) {
        return None;
    }
    if raw.get(1..4)? != crate::ble_adv::ble_service_id_hash() {
        return None;
    }
    let endpoint_id = std::str::from_utf8(raw.get(4..8)?).ok()?.to_owned();
    let info_len = *raw.get(8)? as usize;
    let endpoint_info = raw.get(9..9 + info_len)?.to_vec();
    Some(BleEndpointPayload {
        endpoint_id,
        endpoint_info,
    })
}

include!("endpoint_info_part1.rs");