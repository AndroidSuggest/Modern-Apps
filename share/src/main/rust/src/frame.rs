//! Socket framing and protobuf wire formats for Quick Share / Nearby Connections.
//!
//! # Ground truth
//!
//! Every constant, tag number and enum value below was recovered from the GMS
//! 26.24.34 decompile under `C:\Users\Vayun\gms-analysis\jadx-out-stable\sources\`.
//! `p000\xxxx.java` references are relative to that root.
//! `share/QUICK_SHARE_VERIFICATION.md` records the recovery method and the
//! two-sided citation for each claim.
//!
//! Tags come from the protobuf-lite `mo127gY` info strings (field numbers), the Java
//! field declarations (types and defaults) and the Kotlin mappers (semantic names) —
//! never from memory or from an upstream `.proto`. Do not change a tag without
//! updating the citation beside it.
//!
//! # Layering
//!
//! ```text
//! TCP  ──►  int32be(len) ‖ body                       p000\dnhn.java:219, :344
//!                         │
//!                         ├─ UKEY2 Ukey2Message       (until the handshake completes)
//!                         └─ D2D-encrypted body       p000\dnhn.java:212, :372
//!                              └─ OfflineFrame        p000\ivla.java
//!                                   └─ V1Frame        p000\ivlu.java
//!                                        └─ PayloadTransferFrame  p000\ivlo.java
//!                                             └─ BYTES payload = Sharing Frame
//!                                                  p000\duvt.java → p000\duwk.java
//! ```

// ---------------------------------------------------------------------------
// Socket framing: 4-byte big-endian int32 length prefix
//
// Write:  `DataOutputStream.writeInt(len); write(body)`  p000\dnhn.java:218-221
// Read:   `int len = DataInputStream.readInt(); readFully(body)`
//         p000\dnhn.java:344, :362; accounting `len + 4` at :223 / :367.
// The same channel carries the UKEY2 handshake messages and, once the encryptor
// is installed, every encrypted OfflineFrame (p000\dnij.java:102-107 writes the
// UKEY2 messages through the very same `mo62623A`).
// ---------------------------------------------------------------------------

/// Width of the length prefix in bytes.
pub const LENGTH_PREFIX_LEN: usize = 4;

/// Upper bound accepted for a peer-supplied frame length.
///
/// GMS bounds the read at `readInt() >= 0 && readInt() <= jwky.m158405ae()`
/// (`p000\dnhn.java:348-349`). `m158405ae` is a Phenotype flag, so its shipped
/// value is server-side and not recoverable from the APK; 5 MiB is our own
/// bound, chosen to comfortably exceed the 16 KiB payload chunk while still
/// refusing an allocation attack. Treat the number as ours, not as GMS's.
pub const MAX_FRAME_LEN: usize = 5 * 1024 * 1024;

/// Result of attempting to pull one length-prefixed frame off a read buffer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConsumeResult {
    /// Fewer bytes are buffered than the prefix announces; read more and retry.
    Incomplete,
    /// One complete frame body, with the prefix and body removed from the buffer.
    Frame(Vec<u8>),
    /// The announced length is negative or above [`MAX_FRAME_LEN`]. The channel
    /// is unusable — GMS logs and tears down here rather than allocating.
    Invalid,
}

/// Prepend the 4-byte big-endian length prefix to `payload`.
pub fn frame_with_length(payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(LENGTH_PREFIX_LEN + payload.len());
    let len = u32::try_from(payload.len()).unwrap_or(u32::MAX);
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(payload);
    out
}

/// Try to consume one length-prefixed frame from the front of `buf`.
///
/// On [`ConsumeResult::Frame`] the prefix and body are drained; on
/// [`ConsumeResult::Incomplete`] and [`ConsumeResult::Invalid`] `buf` is untouched.
pub fn try_consume_frame(buf: &mut Vec<u8>) -> ConsumeResult {
    let Some(prefix) = buf.get(..LENGTH_PREFIX_LEN) else {
        return ConsumeResult::Incomplete;
    };
    let mut prefix_bytes = [0u8; LENGTH_PREFIX_LEN];
    prefix_bytes.copy_from_slice(prefix);
    // GMS reads a *signed* int32, so a high-bit length is a negative length there.
    let announced = i32::from_be_bytes(prefix_bytes);
    if announced < 0 || announced as usize > MAX_FRAME_LEN {
        return ConsumeResult::Invalid;
    }
    let total = LENGTH_PREFIX_LEN.saturating_add(announced as usize);
    let Some(body) = buf.get(LENGTH_PREFIX_LEN..total) else {
        return ConsumeResult::Incomplete;
    };
    let out = body.to_vec();
    let _ = buf.drain(..total);
    ConsumeResult::Frame(out)
}

// ===========================================================================
// Sharing wire format — `sharing/proto/wire_format.proto` equivalent
// ===========================================================================

/// `Frame` — `p000\duvt.java:67`: `1 version` (enum, verifier `duvr`), `2 v1` (message `duwk`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingFrame {
    /// p000\duvt.java:67 field 1, enum verifier p000\duvr.java
    #[prost(enumeration = "SharingVersion", tag = "1")]
    pub version: i32,
    /// p000\duvt.java:67 field 2, message p000\duwk.java
    #[prost(message, optional, tag = "2")]
    pub v1: Option<SharingV1Frame>,
}

/// `Frame.Version` — `p000\duvs.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingVersion {
    /// p000\duvs.java `UNKNOWN_VERSION(0)`
    UnknownVersion = 0,
    /// p000\duvs.java `V1(1)`
    V1 = 1,
}

/// `V1Frame` — `p000\duwk.java:85`, fields 1..8.
///
/// Fields 6 `certificate_info` (`p000\duvh.java`), 7 `progress_update`
/// (`p000\duwd.java`) and 8 `bindings` (`p000\duva.java`) exist on the wire but
/// are deliberately absent here: we neither emit nor need them, and prost does
/// not preserve unknown fields, so declaring them without decoding the nested
/// messages would only invite a wrong guess.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingV1Frame {
    /// p000\duwk.java:85 field 1, enum verifier p000\duwi.java
    #[prost(enumeration = "SharingFrameType", tag = "1")]
    pub r#type: i32,
    /// p000\duwk.java:85 field 2, message p000\duvw.java
    #[prost(message, optional, tag = "2")]
    pub introduction: Option<IntroductionFrame>,
    /// p000\duwk.java:85 field 3, message p000\duvl.java
    #[prost(message, optional, tag = "3")]
    pub connection_response: Option<SharingConnectionResponseFrame>,
    /// p000\duwk.java:85 field 4, message p000\duvx.java
    #[prost(message, optional, tag = "4")]
    pub paired_key_encryption: Option<PairedKeyEncryptionFrame>,
    /// p000\duwk.java:85 field 5, message p000\duwa.java
    #[prost(message, optional, tag = "5")]
    pub paired_key_result: Option<PairedKeyResultFrame>,
}

/// `V1Frame.FrameType` — `p000\duwj.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingFrameType {
    /// p000\duwj.java `UNKNOWN_FRAME_TYPE(0)`
    UnknownFrameType = 0,
    /// p000\duwj.java `INTRODUCTION(1)`
    Introduction = 1,
    /// p000\duwj.java `RESPONSE(2)` — note the name is `RESPONSE`, not `CONNECTION_RESPONSE`.
    Response = 2,
    /// p000\duwj.java `PAIRED_KEY_ENCRYPTION(3)`
    PairedKeyEncryption = 3,
    /// p000\duwj.java `PAIRED_KEY_RESULT(4)`
    PairedKeyResult = 4,
    /// p000\duwj.java `CERTIFICATE_INFO(5)`
    CertificateInfo = 5,
    /// p000\duwj.java `CANCEL(6)`
    Cancel = 6,
    /// p000\duwj.java `PROGRESS_UPDATE(7)`
    ProgressUpdate = 7,
    /// p000\duwj.java `BINDINGS(8)`
    Bindings = 8,
}

/// `IntroductionFrame` — `p000\duvw.java:93`, fields 1..9.
///
/// Field 9 (repeated int64, unpacked) is omitted: `p000\dzra.java` only copies it
/// into `dzrf.f204456h` behind a `use_case` guard and no getter literal survives,
/// so its meaning is not recovered.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct IntroductionFrame {
    /// p000\duvw.java:93 field 1, MESSAGE_LIST of p000\duvq.java;
    /// name from p000\dzra.java `"getFileMetadataList(...)"`
    #[prost(message, repeated, tag = "1")]
    pub file_metadata: Vec<SharingFileMetadata>,
    /// p000\duvw.java:93 field 2, MESSAGE_LIST of p000\duwh.java;
    /// name from p000\dzra.java `"getTextMetadataList(...)"`
    #[prost(message, repeated, tag = "2")]
    pub text_metadata: Vec<TextMetadata>,
    /// p000\duvw.java:93 field 3, STRING
    #[prost(string, tag = "3")]
    pub required_package: String,
    /// p000\duvw.java:93 field 4, MESSAGE_LIST of p000\duwo.java;
    /// name from p000\dzra.java `"getWifiCredentialsMetadataList(...)"`
    #[prost(message, repeated, tag = "4")]
    pub wifi_credentials_metadata: Vec<WifiCredentialsMetadata>,
    /// p000\duvw.java:93 field 5, MESSAGE_LIST of p000\duuy.java;
    /// name from p000\dzra.java `"getAppMetadataList(...)"`
    #[prost(message, repeated, tag = "5")]
    pub app_metadata: Vec<AppMetadata>,
    /// p000\duvw.java:93 field 6, BOOL
    #[prost(bool, tag = "6")]
    pub start_transfer: bool,
    /// p000\duvw.java:93 field 8, ENUM, verifier p000\duvu.java
    #[prost(enumeration = "ShareUseCase", tag = "8")]
    pub use_case: i32,
}

/// `IntroductionFrame.use_case` — `p000\duvv.java`. Note the deliberate gaps.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum ShareUseCase {
    /// p000\duvv.java `UNKNOWN(0)`
    Unknown = 0,
    /// p000\duvv.java `NEARBY_SHARE(1)`
    NearbyShare = 1,
    /// p000\duvv.java `REMOTE_COPY(2)`
    RemoteCopy = 2,
    /// p000\duvv.java `TAP_TO_SHARE(9)`
    TapToShare = 9,
    /// p000\duvv.java `FILE_SYNC(10)`
    FileSync = 10,
}

/// The `mime_type` default baked into `p000\duvq.java:42`.
///
/// proto2 field defaults do not survive into prost, which zero-initialises
/// everything, so builders must set this explicitly and readers must substitute
/// it for an empty string.
pub const DEFAULT_MIME_TYPE: &str = "application/octet-stream";

/// `FileMetadata` — `p000\duvq.java:89`, fields 1..9.
///
/// Semantic names come from the Kotlin mapper `p000\dzra.java`
/// (`"getType(...)"`, `"getMimeType(...)"`, `"getParentFolder(...)"`, and the
/// `dzsg(id, payloadId, …)` argument order, where `id` reads field 6 and
/// `payloadId` reads field 3).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingFileMetadata {
    /// p000\duvq.java:89 field 1, STRING
    #[prost(string, tag = "1")]
    pub name: String,
    /// p000\duvq.java:89 field 2, ENUM, verifier p000\duvo.java
    #[prost(enumeration = "SharingFileType", tag = "2")]
    pub r#type: i32,
    /// p000\duvq.java:89 field 3, INT64
    #[prost(int64, tag = "3")]
    pub payload_id: i64,
    /// p000\duvq.java:89 field 4, INT64
    #[prost(int64, tag = "4")]
    pub size: i64,
    /// p000\duvq.java:89 field 5, STRING, default [`DEFAULT_MIME_TYPE`] (p000\duvq.java:42)
    #[prost(string, tag = "5")]
    pub mime_type: String,
    /// p000\duvq.java:89 field 6, INT64
    #[prost(int64, tag = "6")]
    pub id: i64,
    /// p000\duvq.java:89 field 7, STRING
    #[prost(string, tag = "7")]
    pub parent_folder: String,
    /// p000\duvq.java:89 field 8, INT64
    #[prost(int64, tag = "8")]
    pub hash: i64,
    /// p000\duvq.java:89 field 9, BOOL
    #[prost(bool, tag = "9")]
    pub is_sensitive_content: bool,
}

/// `FileMetadata.Type` — `p000\duvp.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingFileType {
    /// p000\duvp.java `UNKNOWN(0)`
    Unknown = 0,
    /// p000\duvp.java `IMAGE(1)`
    Image = 1,
    /// p000\duvp.java `VIDEO(2)`
    Video = 2,
    /// p000\duvp.java `ANDROID_APP(3)`
    AndroidApp = 3,
    /// p000\duvp.java `AUDIO(4)`
    Audio = 4,
    /// p000\duvp.java `DOCUMENT(5)` — the catch-all. Our previous `File = 5` was a
    /// name that does not exist in the enum.
    Document = 5,
    /// p000\duvp.java `CONTACT_CARD(6)`
    ContactCard = 6,
}

/// `TextMetadata` — `p000\duwh.java:79`, fields **2..7** (there is no field 1).
///
/// Names from `p000\dzra.java`: `"getTextTitle(...)"`, `"getType(...)"`, and the
/// `dzsr(id, payloadId, type, textTitle, size, isSensitive)` argument order which
/// reads fields 6, 4, 3, 2, 5, 7 in that sequence (`p000\dzsr.java:20-31`, where
/// the constructor rejects `size <= 0`).
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct TextMetadata {
    /// p000\duwh.java:79 field 2, STRING
    #[prost(string, tag = "2")]
    pub text_title: String,
    /// p000\duwh.java:79 field 3, ENUM, verifier p000\duwf.java
    #[prost(enumeration = "TextType", tag = "3")]
    pub r#type: i32,
    /// p000\duwh.java:79 field 4, INT64
    #[prost(int64, tag = "4")]
    pub payload_id: i64,
    /// p000\duwh.java:79 field 5, INT64
    #[prost(int64, tag = "5")]
    pub size: i64,
    /// p000\duwh.java:79 field 6, INT64
    #[prost(int64, tag = "6")]
    pub id: i64,
    /// p000\duwh.java:79 field 7, BOOL
    #[prost(bool, tag = "7")]
    pub is_sensitive_text: bool,
}

/// `TextMetadata.Type` — `p000\duwg.java`.
///
/// This enum is **1-based**: `UNKNOWN` occupies 0. The plan's writeup §5.3 listed
/// `0=TEXT, 1=URL, …`, which the decompile contradicts.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum TextType {
    /// p000\duwg.java `UNKNOWN(0)`
    Unknown = 0,
    /// p000\duwg.java `TEXT(1)`
    Text = 1,
    /// p000\duwg.java `URL(2)`
    Url = 2,
    /// p000\duwg.java `ADDRESS(3)`
    Address = 3,
    /// p000\duwg.java `PHONE_NUMBER(4)`
    PhoneNumber = 4,
}

/// `WifiCredentialsMetadata` — `p000\duwo.java`, fields **2..5** (no field 1).
///
/// Names from `p000\dzra.java`: `"getSsid(...)"`, `"getSecurityType(...)"`, and the
/// same `(id, payloadId, …)` argument order that reads field 5 then field 4.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct WifiCredentialsMetadata {
    /// p000\duwo.java field 2, STRING
    #[prost(string, tag = "2")]
    pub ssid: String,
    /// p000\duwo.java field 3, ENUM, verifier p000\duwm.java
    #[prost(enumeration = "WifiSecurityType", tag = "3")]
    pub security_type: i32,
    /// p000\duwo.java field 4, INT64
    #[prost(int64, tag = "4")]
    pub payload_id: i64,
    /// p000\duwo.java field 5, INT64
    #[prost(int64, tag = "5")]
    pub id: i64,
}

/// `WifiCredentialsMetadata.SecurityType` — `p000\duwn.java`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum WifiSecurityType {
    /// p000\duwn.java `UNKNOWN_SECURITY_TYPE(0)`
    UnknownSecurityType = 0,
    /// p000\duwn.java `OPEN(1)`
    Open = 1,
    /// p000\duwn.java `WPA_PSK(2)`
    WpaPsk = 2,
    /// p000\duwn.java `WEP(3)`
    Wep = 3,
    /// p000\duwn.java `SAE(4)`
    Sae = 4,
}

/// `AppMetadata` — `p000\duuy.java`, fields 1..7.
///
/// Names from `p000\dzra.java`: `"getAppName(...)"` (field 1),
/// `"getPackageName(...)"` (field 7), and the per-APK zip of field 5 with fields 3
/// and 6 into `dzqn(fileName, payloadId, fileSize)` plus `dzqo(id, appName, size,
/// packageName, files)` which reads field 4 then 1 then 2 then 7.
///
/// Present so a GMS-originated APK introduction round-trips; `:share` never emits it.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct AppMetadata {
    /// p000\duuy.java field 1, STRING
    #[prost(string, tag = "1")]
    pub app_name: String,
    /// p000\duuy.java field 2, INT64
    #[prost(int64, tag = "2")]
    pub size: i64,
    /// p000\duuy.java field 3, INT64_LIST_PACKED
    #[prost(int64, repeated, tag = "3")]
    pub payload_id: Vec<i64>,
    /// p000\duuy.java field 4, INT64
    #[prost(int64, tag = "4")]
    pub id: i64,
    /// p000\duuy.java field 5, STRING_LIST
    #[prost(string, repeated, tag = "5")]
    pub file_name: Vec<String>,
    /// p000\duuy.java field 6, INT64_LIST_PACKED
    #[prost(int64, repeated, tag = "6")]
    pub file_size: Vec<i64>,
    /// p000\duuy.java field 7, STRING
    #[prost(string, tag = "7")]
    pub package_name: String,
}

/// `ConnectionResponseFrame` (Sharing) — `p000\duvl.java:67`.
///
/// Field 2 is a `map<int64, p000\duuz.java>` (`p000\duvi.java` declares the entry
/// as `INT64 -> MESSAGE duuz`). We neither emit nor read it.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SharingConnectionResponseFrame {
    /// p000\duvl.java:67 field 1, ENUM, verifier p000\duvj.java
    #[prost(enumeration = "SharingResponseStatus", tag = "1")]
    pub status: i32,
}

/// `ConnectionResponseFrame.Status` — `p000\duvk.java`.
///
/// `ACCEPT` is **1**. The previous code sent `0` to accept, i.e. `UNKNOWN`, and `1`
/// to reject, i.e. `ACCEPT` — an inversion that also made accept indistinguishable
/// from an unset field.
#[derive(Clone, Copy, Debug, PartialEq, Eq, prost::Enumeration)]
#[repr(i32)]
pub enum SharingResponseStatus {
    /// p000\duvk.java `UNKNOWN(0)`
    Unknown = 0,
    /// p000\duvk.java `ACCEPT(1)`
    Accept = 1,
    /// p000\duvk.java `REJECT(2)`
    Reject = 2,
    /// p000\duvk.java `NOT_ENOUGH_SPACE(3)`
    NotEnoughSpace = 3,
    /// p000\duvk.java `UNSUPPORTED_ATTACHMENT_TYPE(4)`
    UnsupportedAttachmentType = 4,
    /// p000\duvk.java `TIMED_OUT(5)`
    TimedOut = 5,
}

include!("frame_part1.rs");
include!("frame_part2.rs");