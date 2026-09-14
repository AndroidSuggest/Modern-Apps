/// `PairedKeyEncryptionFrame` — `p000\duvx.java:66`, four `bytes` fields.
///
/// The 1:1 mapping is fixed by the mapper `p000\dzrr.java:9-23`, which builds
/// `dzrs(signedData, optionalSignedData, secretIdHash, qrCodeHandshakeData)`
/// (`p000\dzrs.java:133`) from java fields `c`, `e`, `d`, `f` respectively — i.e.
/// fields 1, 3, 2, 4. The declaration order is therefore **not** the constructor
/// order: `secret_id_hash` is field 2, `optional_signed_data` is field 3.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PairedKeyEncryptionFrame {
    /// p000\duvx.java:66 field 1, BYTES (`dzrr.java:12` → `signedData`)
    #[prost(bytes = "vec", tag = "1")]
    pub signed_data: Vec<u8>,
    /// p000\duvx.java:66 field 2, BYTES (`dzrr.java:19` → `secretIdHash`)
    #[prost(bytes = "vec", tag = "2")]
    pub secret_id_hash: Vec<u8>,
    /// p000\duvx.java:66 field 3, BYTES (`dzrr.java:15` → `optionalSignedData`)
    #[prost(bytes = "vec", tag = "3")]
    pub optional_signed_data: Vec<u8>,
    /// p000\duvx.java:66 field 4, BYTES (`dzrr.java:21` → `qrCodeHandshakeData`)
    #[prost(bytes = "vec", tag = "4")]
    pub qr_code_handshake_data: Vec<u8>,
}

/// `PairedKeyResultFrame` — `p000\duwa.java:66`: `1 status`, `2 os_type`.
///
/// Field 2 is an enum, not an int: its verifier `p000\iwkq.java` delegates to
/// `p000\iwkr.java`, the OS-type enum.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PairedKeyResultFrame {
    /// p000\duwa.java:66 field 1, ENUM, verifier p000\duvy.java
    #[prost(enumeration = "PairedKeyResultStatus", tag = "1")]
    pub status: i32,
    /// p000\duwa.java:66 field 2, ENUM, verifier p000\iwkq.java
    #[prost(enumeration = "OsType", tag = "2")]
    pub os_type: i32,
}

/// `PairedKeyResultFrame.Status` — `p000\duvz.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum PairedKeyResultStatus {
    /// p000\duvz.java `UNKNOWN(0)`
    Unknown = 0,
    /// p000\duvz.java `SUCCESS(1)`
    Success = 1,
    /// p000\duvz.java `FAIL(2)`
    Fail = 2,
    /// p000\duvz.java `UNABLE(3)` — what a device with no paired-key certificate sends.
    Unable = 3,
}

/// OS type — `p000\iwkr.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum OsType {
    /// p000\iwkr.java `UNKNOWN_OS_TYPE(0)`
    UnknownOsType = 0,
    /// p000\iwkr.java `ANDROID(1)`
    Android = 1,
    /// p000\iwkr.java `CHROME_OS(2)`
    ChromeOs = 2,
    /// p000\iwkr.java `IOS(3)`
    Ios = 3,
    /// p000\iwkr.java `WINDOWS(4)`
    Windows = 4,
    /// p000\iwkr.java `MACOS(5)`
    Macos = 5,
}

// ===========================================================================
// Nearby Connections wire format — `offline_wire_formats.proto` equivalent
// ===========================================================================

/// `OfflineFrame` — `p000\ivla.java`: `1 version` (enum, verifier `ivky`), `2 v1`.
///
/// Everything sent after the UKEY2 handshake is one of these, encrypted and then
/// length-prefixed (`p000\dnhn.java:212, :219` on write; `:344, :372` on read,
/// where the plaintext is handed to `dnlx.m62950a` to be parsed as an
/// `OfflineFrame`, `p000\dnhn.java:377`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct OfflineFrame {
    /// p000\ivla.java field 1, ENUM, verifier p000\ivky.java
    #[prost(enumeration = "OfflineVersion", tag = "1")]
    pub version: i32,
    /// p000\ivla.java field 2, MESSAGE p000\ivlu.java
    #[prost(message, optional, tag = "2")]
    pub v1: Option<OfflineV1Frame>,
}

/// `OfflineFrame.Version` — `p000\ivkz.java` accepts exactly `{0, 1}`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum OfflineVersion {
    /// p000\ivkz.java maps 0
    UnknownVersion = 0,
    /// p000\ivkz.java maps 1
    V1 = 1,
}

/// `V1Frame` (Nearby Connections) — `p000\ivlu.java`, fields 1..13.
///
/// We declare only the fields on the WIFI_LAN direct path. Omitted on purpose:
/// 5 `bandwidth_upgrade_negotiation` (`ivkb`), 8 `paired_key_encryption` (`ivle`,
/// the *Connections* one — distinct from the Sharing frame above), 9
/// `authentication_message` (`ivjb`), 10 `authentication_result` (`ivjc`), 11
/// `auto_resume` (`ivji`), 12 `auto_reconnect` (`ivjf`), 13
/// `bandwidth_upgrade_retry` (`ivkf`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct OfflineV1Frame {
    /// p000\ivlu.java field 1, ENUM, verifier p000\ivls.java
    #[prost(enumeration = "OfflineFrameType", tag = "1")]
    pub r#type: i32,
    /// p000\ivlu.java field 2, MESSAGE p000\ivkl.java
    #[prost(message, optional, tag = "2")]
    pub connection_request: Option<ConnectionRequestFrame>,
    /// p000\ivlu.java field 3, MESSAGE p000\ivko.java
    #[prost(message, optional, tag = "3")]
    pub connection_response: Option<OfflineConnectionResponseFrame>,
    /// p000\ivlu.java field 4, MESSAGE p000\ivlo.java
    #[prost(message, optional, tag = "4")]
    pub payload_transfer: Option<PayloadTransferFrame>,
    /// p000\ivlu.java field 5, MESSAGE p000\ivkb.java
    #[prost(message, optional, tag = "5")]
    pub bandwidth_upgrade_negotiation: Option<BandwidthUpgradeNegotiationFrame>,
    /// p000\ivlu.java field 6, MESSAGE p000\ivks.java
    #[prost(message, optional, tag = "6")]
    pub keep_alive: Option<KeepAliveFrame>,
    /// p000\ivlu.java field 7, MESSAGE p000\ivkq.java
    #[prost(message, optional, tag = "7")]
    pub disconnection: Option<DisconnectionFrame>,
}

/// `BandwidthUpgradeNegotiationFrame` - `p000\ivkb.java`.
///
/// Field numbering from `ivkb`'s protobuf-lite schema: `1` is the event type (enum verified
/// by `p000\ivjn.java` → `p000\ivjo.java`), `2` `upgrade_path_info` (`ivka`), `3`
/// `client_introduction` (`ivjl`), `4` (`ivjm`) and `5` (`ivjp`).
///
/// Only the event type is modelled: `:share` never upgrades, it only declines.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BandwidthUpgradeNegotiationFrame {
    /// p000\ivkb.java field 1, ENUM, verifier p000\ivjn.java
    #[prost(enumeration = "BandwidthUpgradeEvent", tag = "1")]
    pub event_type: i32,
}

/// `BandwidthUpgradeNegotiationFrame.EventType` - `p000\ivjo.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum BandwidthUpgradeEvent {
    /// p000\ivjo.java `UNKNOWN_EVENT_TYPE(0)`
    UnknownEventType = 0,
    /// p000\ivjo.java `UPGRADE_PATH_AVAILABLE(1)`
    UpgradePathAvailable = 1,
    /// p000\ivjo.java `LAST_WRITE_TO_PRIOR_CHANNEL(2)`
    LastWriteToPriorChannel = 2,
    /// p000\ivjo.java `SAFE_TO_CLOSE_PRIOR_CHANNEL(3)`
    SafeToClosePriorChannel = 3,
    /// p000\ivjo.java `CLIENT_INTRODUCTION(4)`
    ClientIntroduction = 4,
    /// p000\ivjo.java `UPGRADE_FAILURE(5)` - what `:share` always answers with.
    UpgradeFailure = 5,
    /// p000\ivjo.java `CLIENT_INTRODUCTION_ACK(6)`
    ClientIntroductionAck = 6,
    /// p000\ivjo.java `UPGRADE_PATH_REQUEST(7)`
    UpgradePathRequest = 7,
}

/// `V1Frame.FrameType` (Nearby Connections) - `p000\ivlt.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum OfflineFrameType {
    /// p000\ivlt.java `UNKNOWN_FRAME_TYPE(0)`
    UnknownFrameType = 0,
    /// p000\ivlt.java `CONNECTION_REQUEST(1)`
    ConnectionRequest = 1,
    /// p000\ivlt.java `CONNECTION_RESPONSE(2)`
    ConnectionResponse = 2,
    /// p000\ivlt.java `PAYLOAD_TRANSFER(3)`
    PayloadTransfer = 3,
    /// p000\ivlt.java `BANDWIDTH_UPGRADE_NEGOTIATION(4)`
    BandwidthUpgradeNegotiation = 4,
    /// p000\ivlt.java `KEEP_ALIVE(5)`
    KeepAlive = 5,
    /// p000\ivlt.java `DISCONNECTION(6)`
    Disconnection = 6,
    /// p000\ivlt.java `PAIRED_KEY_ENCRYPTION(7)`
    PairedKeyEncryption = 7,
    /// p000\ivlt.java `AUTHENTICATION_MESSAGE(8)`
    AuthenticationMessage = 8,
    /// p000\ivlt.java `AUTHENTICATION_RESULT(9)`
    AuthenticationResult = 9,
    /// p000\ivlt.java `AUTO_RESUME(10)`
    AutoResume = 10,
    /// p000\ivlt.java `AUTO_RECONNECT(11)`
    AutoReconnect = 11,
    /// p000\ivlt.java `BANDWIDTH_UPGRADE_RETRY(12)`
    BandwidthUpgradeRetry = 12,
}

/// `ConnectionRequestFrame` — `p000\ivkl.java:120`, 15 fields plus a oneof.
///
/// Field vocabulary from `p000\dnlw.java:305`
/// (`ConnectRequestParameters{endpointId=…, endpointInfo=…, handshakeData=…,
/// nonce=…, mediums=…, keepAliveIntervalMillis=…, keepAliveTimeoutMillis=…,
/// deviceType=…, localDeviceInfo=…}`). Fields 1 and 2 are both validated as
/// required by `p000\dnlx.java:669` (`"missing endpointId field."`) and `:675`
/// (`"missing endpointName field."`).
///
/// Omitted: 7 `medium_metadata` (`ivkw`), 10 `device_type`, 11 `device_info`,
/// the 12/13 oneof (`ivkp` / `ivlr`), 14 `connections_device_type`
/// (`p000\ivkh.java`), 15 (`ivkt`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ConnectionRequestFrame {
    /// p000\ivkl.java:120 field 1, STRING — required per p000\dnlx.java:669
    #[prost(string, tag = "1")]
    pub endpoint_id: String,
    /// p000\ivkl.java:120 field 2, STRING — required per p000\dnlx.java:675
    #[prost(string, tag = "2")]
    pub endpoint_name: String,
    /// p000\ivkl.java:120 field 3, BYTES
    #[prost(bytes = "vec", tag = "3")]
    pub handshake_data: Vec<u8>,
    /// p000\ivkl.java:120 field 4, INT32
    #[prost(int32, tag = "4")]
    pub nonce: i32,
    /// p000\ivkl.java:120 field 5, ENUM_LIST (unpacked — hence `packed = "false"`),
    /// verifier p000\ivkj.java
    #[prost(enumeration = "ConnectionsMedium", repeated, packed = "false", tag = "5")]
    pub mediums: Vec<i32>,
    /// p000\ivkl.java:120 field 6, BYTES
    #[prost(bytes = "vec", tag = "6")]
    pub endpoint_info: Vec<u8>,
    /// p000\ivkl.java:120 field 8, INT32
    #[prost(int32, tag = "8")]
    pub keep_alive_interval_millis: i32,
    /// p000\ivkl.java:120 field 9, INT32
    #[prost(int32, tag = "9")]
    pub keep_alive_timeout_millis: i32,
}

/// `Medium` — `p000\ivkk.java`. `:share` only ever offers `WifiLan`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum ConnectionsMedium {
    /// p000\ivkk.java `UNKNOWN_MEDIUM(0)`
    UnknownMedium = 0,
    /// p000\ivkk.java `MDNS(1)`
    Mdns = 1,
    /// p000\ivkk.java `BLUETOOTH(2)`
    Bluetooth = 2,
    /// p000\ivkk.java `WIFI_HOTSPOT(3)`
    WifiHotspot = 3,
    /// p000\ivkk.java `BLE(4)`
    Ble = 4,
    /// p000\ivkk.java `WIFI_LAN(5)`
    WifiLan = 5,
    /// p000\ivkk.java `WIFI_AWARE(6)`
    WifiAware = 6,
    /// p000\ivkk.java `NFC(7)`
    Nfc = 7,
    /// p000\ivkk.java `WIFI_DIRECT(8)`
    WifiDirect = 8,
    /// p000\ivkk.java `WEB_RTC(9)`
    WebRtc = 9,
    /// p000\ivkk.java `BLE_L2CAP(10)`
    BleL2cap = 10,
    /// p000\ivkk.java `USB(11)`
    Usb = 11,
    /// p000\ivkk.java `WEB_RTC_NON_CELLULAR(12)`
    WebRtcNonCellular = 12,
    /// p000\ivkk.java `AWDL(13)`
    Awdl = 13,
}

/// `ConnectionResponseFrame` (Nearby Connections) — `p000\ivko.java:86`.
///
/// The info string declares fields 1, 2, 3, 4, 5, 7, 8, 9 — field 6 is gone.
/// Two status fields coexist and GMS always writes **both**: the legacy int32
/// `status` (field 1) and the enum `response` (field 3), which `p000\dnlx.java:1041-1051`
/// derives from the status as `(status == 0 ? 2 : 3) - 1`.
///
/// `status` is `Option` rather than a bare `i32` because **its presence is
/// load-bearing**, unlike every other scalar in this file. `p000\dnsi.java:6911`
/// reads acceptance as:
///
/// ```text
/// has(response) ? verify(response) == 2       // i.e. response == 1
///               : has(status) && status == 0
/// ```
///
/// so a response carrying neither field, or `status = 0` merely *defaulted* rather
/// than written, reads as a **rejection**. Emitting `Some(0)` reproduces GMS's
/// `08 00`.
///
/// Omitted: 4 `os_info` (`ivld`), 7, 8 (`ivkt`), 9.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct OfflineConnectionResponseFrame {
    /// p000\ivko.java:86 field 1, INT32 — legacy status; presence matters, see above
    #[prost(int32, optional, tag = "1")]
    pub status: Option<i32>,
    /// p000\ivko.java:86 field 2, BYTES
    #[prost(bytes = "vec", tag = "2")]
    pub handshake_data: Vec<u8>,
    /// p000\ivko.java:86 field 3, ENUM, verifier p000\ivkm.java → p000\ivkn.java
    #[prost(enumeration = "OfflineResponseStatus", optional, tag = "3")]
    pub response: Option<i32>,
    /// p000\ivko.java:86 field 5, INT32
    #[prost(int32, tag = "5")]
    pub multiplex_socket_bitmask: i32,
}

impl OfflineConnectionResponseFrame {
    /// Whether the peer accepted, by GMS's own rule (`p000\dnsi.java:6911`).
    ///
    /// Field 3 wins when present; only if it is absent does the legacy `status`
    /// decide, and then it must have been explicitly written.
    pub fn accepted(&self) -> bool {
        match self.response {
            Some(response) => response == OfflineResponseStatus::Accept as i32,
            None => self.status == Some(OFFLINE_RESPONSE_STATUS_ACCEPT),
        }
    }
}

/// `ConnectionResponseFrame.ResponseStatus` (field 3) — `p000\ivkn.java` accepts
/// exactly `{0, 1, 2}`.
///
/// R8 stripped the value names, but the mapping is still recoverable from the code
/// on both ends. `p000\dnlx.java:1041-1051` (`m62960k`, the sole builder) writes
/// `(status == 0 ? 2 : 3) - 1`, and `p000\dnsi.java:6911` accepts when
/// `ivkn.m131628a(response) == 2`, where `m131628a` maps `0→1, 1→2, 2→3`
/// (`p000\ivkn.java`). Both agree: **1 is accept, 2 is reject**.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum OfflineResponseStatus {
    /// p000\ivkn.java maps 0 — never written by GMS.
    Unknown = 0,
    /// `status == 0`, i.e. accepted.
    Accept = 1,
    /// `status != 0`, i.e. rejected.
    Reject = 2,
}

/// Legacy `ConnectionResponseFrame.status` value meaning "connection accepted".
///
/// `p000\dncj.java:1204` (`acceptConnection`) calls the builder with a literal `0`.
pub const OFFLINE_RESPONSE_STATUS_ACCEPT: i32 = 0;

/// Legacy `ConnectionResponseFrame.status` value meaning "connection rejected".
///
/// `p000\dncj.java:1622` (`rejectConnection`) calls the builder with a literal `8004`,
/// the same code `evaluateConnectionResult` reports for "rejected by one or both
/// sides" (`p000\dnsi.java:4383`).
pub const OFFLINE_RESPONSE_STATUS_REJECT: i32 = 8004;

/// `KeepAliveFrame` — `p000\ivks.java`: `1 ack:bool`, `2 seq_num:uint32`.
///
/// Field 2 is UINT32 (info-string type `0x0B`), not INT32.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct KeepAliveFrame {
    /// p000\ivks.java field 1, BOOL
    #[prost(bool, tag = "1")]
    pub ack: bool,
    /// p000\ivks.java field 2, UINT32
    #[prost(uint32, tag = "2")]
    pub seq_num: u32,
}

/// `DisconnectionFrame` — `p000\ivkq.java`: two BOOL fields.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct DisconnectionFrame {
    /// p000\ivkq.java field 1, BOOL
    #[prost(bool, tag = "1")]
    pub request_safe_to_disconnect: bool,
    /// p000\ivkq.java field 2, BOOL
    #[prost(bool, tag = "2")]
    pub ack_safe_to_disconnect: bool,
}

/// `PayloadTransferFrame` — `p000\ivlo.java`: `1 packet_type` (enum, verifier
/// `ivli`), `2 payload_header`, `3 payload_chunk`, `4 control_message`.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadTransferFrame {
    /// p000\ivlo.java field 1, ENUM, verifier p000\ivli.java
    #[prost(enumeration = "PayloadPacketType", tag = "1")]
    pub packet_type: i32,
    /// p000\ivlo.java field 2, MESSAGE p000\ivln.java
    #[prost(message, optional, tag = "2")]
    pub payload_header: Option<PayloadHeader>,
    /// p000\ivlo.java field 3, MESSAGE p000\ivlk.java
    #[prost(message, optional, tag = "3")]
    pub payload_chunk: Option<PayloadChunk>,
    /// p000\ivlo.java field 4, MESSAGE
    #[prost(message, optional, tag = "4")]
    pub control_message: Option<ControlMessage>,
}

/// `PayloadTransferFrame.PacketType`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum PayloadPacketType {
    /// Unset.
    Unknown = 0,
    /// A header and/or chunk of payload bytes.
    Data = 1,
    /// A control event (cancel / error / completed).
    Control = 2,
}

/// `PayloadHeader` — `p000\ivln.java`, fields 1..7.
///
/// Every field carries a hasbit. The protobuf-lite info string at `p000\ivln.java:82` types
/// them `ဂ`/`᠌`/`ဂ`/`ဇ`/`ဈ`/`ဈ`/`ဂ` = `0x1002, 0x180C, 0x1002, 0x1007, 0x1008, 0x1008,
/// 0x1002`; the `0x1000` bit is explicit presence. So `is_sensitive = false` is meant to be
/// *present and false*, which is not the same wire image as absent.
///
/// This matters: `is_sensitive` is modelled as `Option<bool>` and always written, because a
/// `PayloadHeader` without field 4 is not one GMS acts on. `rquickshare`, an independent
/// non-GMS implementation that interoperates with real Quick Share, likewise sets
/// `is_sensitive: Some(false)` on every header it builds
/// (`core_lib/src/hdl/inbound.rs::send_encrypted_frame`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct PayloadHeader {
    /// p000\ivln.java field 1, INT64
    #[prost(int64, tag = "1")]
    pub id: i64,
    /// p000\ivln.java field 2, ENUM, verifier p000\ivll.java
    #[prost(enumeration = "PayloadType", tag = "2")]
    pub r#type: i32,
    /// p000\ivln.java field 3, INT64
    #[prost(int64, tag = "3")]
    pub total_size: i64,
    /// p000\ivln.java field 4, BOOL with a hasbit — presence is load-bearing, see above.
    #[prost(bool, optional, tag = "4")]
    pub is_sensitive: Option<bool>,
    /// p000\ivln.java field 5, STRING
    #[prost(string, tag = "5")]
    pub file_name: String,
    /// p000\ivln.java field 6, STRING
    #[prost(string, tag = "6")]
    pub parent_folder: String,
}
