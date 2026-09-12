# Android Auto (gearhead) teardown — for MA Auto

Source: Pixel 7 Pro (`cheetah`), `com.google.android.projection.gearhead`
version **17.5.663214-release** (versionCode 175663214, minSdk 32, targetSdk 37).

APKs pulled to `apk/` (base + arm64_v8a + en + xxxhdpi).
jadx output in `jadx-out/` (26,173 java files, 235 decompile errors — non-fatal).
Raw dex in `dex/`, extracted PEMs in `certs/`.

---

## 1. What role it holds

`android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION`, confirmed live on device:

```
$ adb shell cmd role get-role-holders android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION --user 0
com.google.android.projection.gearhead
```

Role definition (`packages/modules/Permission/PermissionController/res/xml/roles.xml:624`):

- `defaultHolders="config_systemAutomotiveProjection"` → pinnable from an RRO exactly
  like the five roles we already pin in `MaosFrameworkResRRO`.
- `exclusive`, `static`, `systemOnly`, `visible="false"` — no user-facing picker,
  it is a build-time assignment.
- Grants: microphone / location / nearby_devices / notifications permission sets, plus
  `ADD_ALWAYS_UNLOCKED_DISPLAY`, `ADD_TRUSTED_DISPLAY`, `CREATE_VIRTUAL_DEVICE`,
  `CAPTURE_SECURE_VIDEO_OUTPUT`, `TOGGLE_AUTOMOTIVE_PROJECTION`,
  `ASSOCIATE_COMPANION_DEVICES`, `REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION`,
  `RECEIVE_SENSITIVE_NOTIFICATIONS`, `GET_INTENT_SENDER_INTENT`, `CALL_PHONE`,
  `READ_CALENDAR`, `READ_CALL_LOG`, `READ_CONTACTS`, `READ_PHONE_STATE`,
  `RECEIVE_SMS`, `SEND_SMS`.
- App-ops: `android:receive_sensitive_notifications`, `android:read_restricted_messages`.

The role is necessary but **nowhere near sufficient**. Gearhead requests 78 permissions;
the role grants ~20 of them. The rest (`MODIFY_AUDIO_ROUTING`, `TETHER_PRIVILEGED`,
`CALL_PRIVILEGED`, `MANAGE_USB`, `POWER_SAVER`, `READ_PRIVILEGED_PHONE_STATE`,
`CHANGE_COMPONENT_ENABLED_STATE`, `MANAGE_USERS`, `CAPTURE_SECURE_VIDEO_OUTPUT`, …)
come from `signature|privileged` + a privapp-permissions allowlist entry.

---

## 2. Architecture

Historically the projection stack lived in GMS Core (`com.google.android.gms.car.*`).
It has been **absorbed into gearhead** — `com.google.android.apps.auto.carservice.gmscorecompat.CarChimeraService`
is the in-app replacement, and the whole GAL stack ships in this APK. So there is no
GMS dependency to reverse; everything is here.

Two layers:

- **Transport + GAL protocol** — obfuscated into `defpackage`, class families `rs*`,
  `rt*`, `ru*`, `rv*`. Log tags `CAR.GAL.*`.
- **Car UI / apps** — `com.google.android.apps.auto.components` (214 files),
  `com.google.android.gearhead.*`, plus `androidx.car.app` host support.

Public-ish parcelable surface in `com.google.android.gms.car.*` (98 files) is a clean
readable spec of the data model: `CarDisplay`, `CarWindowLayoutParams`,
`CarActivityLayoutParams`, `CarSensorEvent`, `CarAudioConfiguration`, `CarCall`,
`navigation/NavigationState`, `control/CarProperty*`, `senderprotocol/Channel`.

### GAL subsystems (from log tags)

`GAL` (control) · `SECURITY` · `VIDEO` · `AUDIO` · `MIC` · `INPUT` · `SENSOR` ·
`MEDIA` · `RADIO` · `INST` (instrument cluster) · `BT` · `WIFI` · `CAR` ·
`DIAGNOSTICS` · `LATENCY` · `SERVICE` · `SNOOP` · `SYNC`

Each becomes a numbered channel; `senderprotocol/Channel.java` is the per-channel
state machine (states 0=new 1=opening 2=open 4=closed), and channel open is control
message **7** carrying `{priority, service_id}` — note that order, it is easy to get
backwards.

### Two parallel copies of the stack

As with `rvd`/`rvg` and `jby`/`jca` in the crypto, the whole endpoint layer exists twice:
an `r*` family (`ruq` base, `rth` control, `rvb` sensor, `rug` media) and a `j*` family
(`jbj` base, `izu` control, `jdk` media sink, `jem` video, `jdi` mic, `jar` input). The
`j*` family is the live one — it is what `com.google.android.apps.auto.carservice`
drives. Message IDs agree across both where they overlap.

### Frame layout

```
byte 0      channel id
byte 1      flags
bytes 2-3   payload length, big-endian uint16   (on-wire length, post-encryption)
bytes 4-7   total message length, big-endian int32  -- only when FIRST && !LAST
```

| Flag | Bit | Meaning |
|------|-----|---------|
| FIRST | `0x01` | first fragment |
| LAST | `0x02` | last fragment |
| CONTROL | `0x04` | control-channel message |
| ENCRYPTED | `0x08` | payload is TLS-wrapped |

An unfragmented message is `FIRST|LAST` = `0x03` with a 4-byte header. Default fragment
size is **16128** (`rto.a()`); control messages may not be fragmented at all. Above the
frame, every channel's payload is `[2-byte big-endian message type][protobuf]`, read as
an unsigned `char` so `0xFFFF` works.

Sender is `rts.a`; receiver is the `rtr` state machine, which replies with a 2-byte
`0xFFFF` framing-error control frame and tears down on malformed input.

### Control channel message types

Read off the dispatch in `defpackage/rth.java`:

| ID | Dir | Message |
|----|-----|---------|
| 1 | recv | VersionRequest — **the head unit asks first**; two raw big-endian uint16s |
| 2 | send | VersionResponse — major, minor, status, again raw shorts (8-byte frame with the type) |
| 3 | both | SSL handshake data (wrapped/unwrapped by `rvg`) |
| 4 | recv | AuthComplete (`STATUS_SUCCESS`, `..._CERT_EXPIRED`, `..._CERT_NOT_YET_VALID`) |
| 5 | send | ServiceDiscoveryRequest (`xoa`) |
| 6 | recv | ServiceDiscoveryResponse (`xob`, `repeated Service`) |
| 7 / 8 | send / recv | ChannelOpenRequest / Response |
| 11 / 12 | recv / send | PingRequest / PingResponse |
| 14 | recv | NavigationFocusNotification (`NAV_FOCUS_NATIVE` / projected) |
| 15 / 16 | both | ByeByeRequest / Response (reasons: `USER_SELECTION`, `DEVICE_SWITCH`) |
| 18 | send | AudioFocusRequest |
| 19 | recv | AudioFocusNotification (`AUDIO_FOCUS_STATE_*`) |
| 24 | recv | CallAvailabilityStatus |
| 26 | recv | ServiceDiscoveryUpdate (hot-add/update a service) |
| 255 | both | MessageError |
| 65535 | both | FramingError |

**Messages 1 and 2 are not protobuf.** Every other control message carries a protobuf
payload; version negotiation carries raw big-endian uint16s, written a short at a time
(`jcn.v((short) …)` in `izu`). The response is major, minor, then the status as a signed
enum value truncated to a short, so `STATUS_NO_COMPATIBLE_VERSION` (-1) goes out as
`0xFFFF`.

**The head unit opens the conversation.** The control endpoint logs "Car requests protocol
version %s" on receiving message 1 — the phone does not send a version request, it answers
one. A sender that speaks first will sit unanswered. Gearhead 17.5 advertises 1.7, and
`rth` gates the service-discovery request on having reached at least 1.6.

**The head unit advertises the services; the phone consumes the list.** For a sender
reimplementation that inverts the obvious reading: we *parse* `xob`, we do not build it.

### Service table (`xnz`)

Cross-checked against the human-readable dumper `rta.a(xnz)`.

| Field | Payload | Service |
|-------|---------|---------|
| 1 | required int32 | service id (== the channel id) |
| 2 | `xnx` | SensorSource |
| 3 | `xko` | MediaSink (audio *and* video) |
| 4 | `xjw` | InputSource |
| 5 | `xkp` | MediaSource (mic) |
| 6 | `xht` | Bluetooth |
| 7 | `xnb` | Radio |
| 8 | `xlo` | NavigationStatus |
| 9 | `xkn` | MediaPlaybackStatus |
| 10 | `xmf` | PhoneStatus |
| 11 | `xki` | MediaBrowser |
| 12 | `xoz` | VendorExtension |
| 13 | `xjm` | Notification |
| 14 | `xpl` | WifiProjection |

Internal purpose enum `rro` is the definitive list of what gearhead implements:
`1 CONTROL, 2 VIDEO_SINK, 3 AUDIO_SINK_GUIDANCE, 4 AUDIO_SINK_SYSTEM, 5 AUDIO_SINK_MEDIA,
6 AUDIO_SOURCE, 7 SENSOR_SOURCE, 8 INPUT_SOURCE, 9 BLUETOOTH, 10 NAVIGATION_STATUS,
11 MEDIA_PLAYBACK_STATUS, 12 MEDIA_BROWSER, 13 PHONE_STATUS, 14 NOTIFICATION, 15 RADIO,
16 VENDOR_EXTENSION, 17 WIFI_PROJECTION, 18 WIFI_DISCOVERY, 19 CAR_CONTROL,
20 CAR_LOCAL_MEDIA, 21 BUFFERED_MEDIA_SINK, 22 CAR_INTENT`.

Supporting protos: `xko` MediaSink `{1 codec_type(xkj), 2 audio_type, 3 repeated xhf
audio_configs, 4 repeated xpd video_configs}` · `xhf` AudioConfiguration `{1 required
uint32 sampling_rate, 2 required uint32 number_of_bits, 3 required uint32 channels}` ·
`xpd` VideoConfiguration `{1 resolution(xpc), 2 frame_rate, 3 width_margin,
4 height_margin, 5 density}` · `xkj` codecs `{1 PCM, 2 AAC_LC, 3 H264_BP, 4 AAC_LC_ADTS,
5 VP9, 6 AV1, 7 H265}` · `xpc` resolutions `{800x480, 1280x720, 1920x1080, 2560x1440,
3840x2160, 720x1280, 1080x1920, 1440x2560, 2160x3840}`.

### Non-control channel message IDs

Non-control channels use **0x8000-based** IDs, unlike the control channel's small ints.

**Third jadx-adjacent trap — the `wub.o` off-by-one.** `jdk` and `jdi` dispatch on
`int iO = wub.o(i)`, and `wub.o` maps a wire id to a **1-based enum index** — for this
range it is simply `i + 1` (`0→1, 1→2, 32768→32769, …`). Sends do *not* go through it:
`jbj.o` writes `jcnVar.v((short) i)` with the raw id into a `payload + 2` buffer. So
every `iO ==` comparison in those classes is **one greater than the real wire id**, while
every `k(id, …)` call site is already raw. Reading the comparisons as wire ids gives you
every received media message off by one. `jem` and `jar` override `a()` and compare the
raw id directly, so they do not have this shift.

The corrected IDs below agree with the public aasdk/openauto tables, which is a useful
independent check on the whole decoding.

**Media sink — `jdk`, shared by video and all three audio sinks:**

| Wire ID | Dir | Message |
|---------|-----|---------|
| `0x0000` | recv | MediaDataWithTimestamp — 8-byte timestamp then the payload |
| `0x0001` | recv | MediaData |
| `0x8000` | send | MediaSetupRequest (`xof`) |
| `0x8001` | send | MediaStartRequest (`xoh`) |
| `0x8002` | send | MediaStopRequest (`xoi`, empty) |
| `0x8003` | recv | Config (`xiu`) |
| `0x8004` | recv | MediaAck (`xgz`) — carries the frame counter mod 256 |
| `0x800B` | recv | (no payload) |
| `0x8013` | recv | (`xkq`) |

**Video — `jem` (service 2), extends `jdk`, compares raw:**

| Wire ID | Dir | Message |
|---------|-----|---------|
| `0x8007` | send | VideoFocusRequest (`xph` `{2 mode(xpe), 3 reason(xpg)}`) |
| `0x8008` | recv | VideoFocusIndication (`xpf` `{mode, unsolicited}`) |
| `0x800A` | send | UpdateUiConfigRequest (`xow` `{1 xop}`) |

Video focus modes (`xpe`): `VIDEO_FOCUS_NATIVE`, `VIDEO_FOCUS_PROJECTED`,
`VIDEO_FOCUS_NATIVE_TRANSIENT`, `VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS`. Input focus is
derived from video focus rather than negotiated separately.

**Microphone / MediaSource — `jdi` (service 6). The *car* owns this microphone**, so the
phone receives audio and sends the acks (`jaz`, the concrete subclass, sends `0x8004`).

| Wire ID | Dir | Message |
|---------|-----|---------|
| `0x0000` | recv | audio data, 8-byte timestamp prefix |
| `0x8004` | send | MediaAck (`xgz`) |
| `0x8006` | recv | MicrophoneRequest (`xkt`) |

**Input — `jar` (service 8), compares raw:**

| Wire ID | Dir | Message |
|---------|-----|---------|
| `0x8001` | recv | InputReport (`xjt` `{3 xom touch, 4 xkf key_events}`) |
| `0x8002` | send | KeyBindingRequest |
| `0x8003` | recv | KeyBindingResponse (`xkc` `{status}`) |

**Sensor — `rvb` (service 7):**

| Wire ID | Dir | Message |
|---------|-----|---------|
| `0x8001` | send | SensorRequest (`xnu` `{1 sensor_type, 2 min_update_period}`), synchronous, 2000 ms timeout; `-1` unsubscribes |
| `0x8002` | recv | SensorResponse (`xnv`) |
| `0x8003` | recv | SensorBatch (`xnr`) |
| `0x8004` | recv | SensorError (`xns`) |

26 sensor types in `xny`: `LOCATION, COMPASS, SPEED, RPM, ODOMETER, FUEL, PARKING_BRAKE,
GEAR, OBDII_DIAGNOSTIC_CODE, NIGHT_MODE, ENVIRONMENT_DATA, HVAC_DATA,
DRIVING_STATUS_DATA, DEAD_RECKONING_DATA, PASSENGER_DATA, DOOR_DATA, LIGHT_DATA,
TIRE_PRESSURE_DATA, ACCELEROMETER_DATA, GYROSCOPE_DATA, GPS_SATELLITE_DATA, TOLL_CARD,
VEHICLE_ENERGY_MODEL_DATA, TRAILER_DATA, RAW_VEHICLE_ENERGY_MODEL, RAW_EV_TRIP_SETTINGS`.

**Still unmapped: both transports.** Leads for Phase 2:

- **USB (AOAP)** — start at `com/google/android/gms/carsetup/setup/UsbConnectionHelper.java`,
  which is *not* obfuscated, plus `CarUsbReceiver` / `CarUsbReceiverTPlus` and the action
  `com.google.android.gms.car.usb.USB_ACCESSORY_FORCE_START`.
- **Wireless** — `WirelessSetupSharedService` and `com.google.android.gms.car.wifi.BT_START`.
  `defpackage/ebe.java` is a phenotype-flag dumper holding the whole
  `WirelessProjectionInGearhead__*` set, which maps the feature surface without reading the
  implementation: Bluetooth ACL/HFP tracking with per-stage timeouts, an RFCOMM fallback
  (`fallback_to_rfcomm_on_t_minus`), CDM association driving the trigger
  (`associate_bt_devices_and_trigger_wireless_from_cdm`), a WiFi network request supporting
  hidden SSIDs, dual-STA, and local-only-network handling. Useful as a checklist of what a
  complete wireless bring-up has to handle.

Two service→endpoint factory indexes exist, one per family: `qqv` implements `ruu` for the
`r*` family, and the `j*` family has its own. Either enumerates every service the stack can
open, if more are needed than the ones tabulated above.

---

## 3. The crypto — this is the important part

`defpackage/rth.java:180-209` builds the SSL context:

```java
SSLContext ctx = SSLContext.getInstance("TLSv1.2");
...
engine.setUseClientMode(false);
engine.setNeedClientAuth(true);
```

**The phone is the TLS server. The head unit is the TLS client, and must present a
client certificate.** Both sides chain to the same private Google root.

Two certs are embedded (extracted to `certs/blob0.pem`, `certs/blob1.pem`):

| | Subject | Issuer | Valid |
|-|---------|--------|-------|
| `blob0` (leaf, `defpackage/rvd.java`) | `O=CarService` | `O=Google Automotive Link` | 2014-07-04 → **2026-12-09** |
| `blob1` (root CA, `rve.java`/`rth.java`) | `O=Google Automotive Link` | self | 2014-06-06 → 2044-06-05 |

The root is loaded into an in-memory keystore under alias `GAL` and is the *only*
trust anchor — the system trust store is not used.

The leaf's **private key is present in the APK, encrypted**, and is recoverable.
`rth.java` (and an identical duplicate in `jca.java`):

1. `state = new byte[48]` (zeroed)
2. `roc.aQ(leafCertPem.getBytes(UTF_8), state, salt)`
3. `roc.aQ(rootCertPem.getBytes(UTF_8), state, salt)`
4. 7 further rounds of `roc.aQ(state, state, salt)`
5. AES-256 key = `state[0..32]`, IV = `state[32..48]`
6. `AES/CBC/PKCS5Padding` decrypt → a PEM private key

`roc.n()` then strips exactly 28 bytes of header and 26 of footer before
base64-decoding into a `PKCS8EncodedKeySpec`, which pins the plaintext to
`-----BEGIN PRIVATE KEY-----\n` + wrapped base64 + `-----END PRIVATE KEY-----\n`.
That is 1704 bytes, padding to the 1712-byte ciphertext exactly.

`rvd` maps the accessors onto `rvg`'s static arrays **crosswise**, which is easy to
misread: `b()` returns `rvg.c` (the 1712-byte ciphertext) and `c()` returns `rvg.b`
(the 256-byte salt, of which only the first 48 bytes are ever indexed).

`roc.aQ` itself, from the bytecode:

```
for (v1 = 0; v1 < data.length; v1++)
    for (v2 = 0; v2 < 48; v2++)
        state[v2] = (byte)((((state[v2]&255)>>7 | (state[v2]&255)*2) + 33)
                           ^ salt[v2 % salt.length] ^ data[v1]);
```

**jadx renders the outer loop as `for (byte b2 : bArr)`, which caches `data[v1]`
once per outer iteration. The real bytecode does `aget-byte v4, v6, v1` *inside*
the inner loop and re-reads it 48 times.** That is equivalent for the two cert
passes but not for the 7 fold rounds, where `data` and `state` are the same array
and the inner loop overwrites the byte being read. Porting jadx's Java verbatim
produces a plausible-looking but wrong key — `analysis/maauto/disasm.py` dumps the
bytecode that settles it.

So the key material is whitened with a KDF whose entire input is also in the APK.
No hardware keystore, no server provisioning.

`analysis/maauto/extract_key.py` recovers it; `verify_tls.sh` confirms the result:

```
gal-client-cert.pem: OK                    (chains to the GAL root)
key modulus == cert modulus
New, TLSv1.2, Cipher is ECDHE-RSA-AES256-GCM-SHA384
Verify return code: 0 (ok)                 (mutual auth, GAL root as sole anchor)
```

Recovered material is in `analysis/maauto/certs/` (gitignored):
`gal-client-cert.pem`, `gal-client-key.pem`, `gal-root.pem`.

### A remotely-provisioned path also exists

`rvg`'s constructor reads six phenotype flags (`adbo.gu()` `a`–`f`): a cert PEM, an
encrypted key blob and a salt, each paired with a SHA-1 checksum. If all three
validate it builds `rvf` from them instead of the hardcoded `rvd`. So Google can
rotate the credential remotely without shipping an APK — which is presumably how
they intend to handle the December expiry. Worth watching: if the phenotype cert
becomes mandatory, the hardcoded one may stop being accepted by head units.

### Consequences

- A third-party **head unit** can authenticate to a real phone by reusing this
  GAL-signed cert+key as its client cert. This is exactly how openauto/aasdk work.
- A third-party **phone-side sender** can authenticate to a real car for the same
  reason — the car trusts the GAL root and this leaf chains to it.
- **The leaf expires 2026-12-09, ~3 months from now.** Google will rotate it in a
  gearhead update. We cannot mint a replacement (no root key), so anything built on
  this cert has a hard expiry and would need re-extraction from each new gearhead
  release. This is the single biggest risk to any MA Auto that talks to real hardware.

---

## 4. Protobuf definitions are mechanically recoverable

Every `x*` class is protobuf-lite gencode with an intact `RawMessageInfo` descriptor in
its `dynamicMethod`, e.g. `defpackage/xhf.java`:

```java
return new abpg(a,
    "\u0001\u0003\u0000\u0001\u0001\u0003\u0003\u0000\u0000\u0003\u0001ᔋ\u0000\u0002ᔋ\u0001\u0003ᔋ\u0002",
    new Object[]{"b", "c", "d", "e"});
```

The header is ten 13-bit-continuation varints over `char`s: `flags, fieldCount,
oneofCount, hasBitsCount, minFieldNumber, maxFieldNumber, numEntries, mapFieldCount,
repeatedFieldCount, checkInitializedCount`. Each field entry is `fieldNumber`,
`typeAndFlags`, and a `hasBitIndex` for singular fields with presence. In
`typeAndFlags`: `& 0xFF` is the field type ordinal, `0x100` required, `0x200`
enforce-UTF8, `0x400` participates in `checkInitialized`, `0x800` legacy closed enum,
`0x1000` the field has a hasbit. The decoder is `abpe.c()`.

Worked example — `xhf` decodes to:

```proto
message AudioConfiguration {
  required uint32 sampling_rate      = 1;
  required uint32 number_of_bits     = 2;
  required uint32 number_of_channels = 3;
}
```

**Structure is 100% recoverable; names are not.** Field numbers, wire types, cardinality,
required/optional and packedness are exact. Message and field names are obfuscated
(`xoa`, `c`). Names come from three places: the `rta.a()` and `rvb.j()` debug dumpers,
log format strings, and the public aasdk/openauto `.proto` files, whose field numbers
match what is in this APK.

`analysis/maauto/dump_protos.py` decodes any class on demand:

```
$ python3 dump_protos.py xhf
message xhf {
  required uint32 field1 = 1;  // checkInit
  required uint32 field2 = 2;  // checkInit
  required uint32 field3 = 3;  // checkInit
}
```

Validated three ways: `xhf` reproduces the known `AudioConfiguration` shape, `xnz` yields
exactly the 9 `checkInit` fields its own header declares, and `xob` reproduces the service
table derived independently from the `rta.a()` dumper.

### Two more traps in the descriptor path

- **`abpe.java:501` is inverted.** jadx renders it as `if ((iCharAt10 & 4096) != 0 || i87 >
  17)` for the *no hasbit* case, i.e. it reads `0x1000` as meaning the field has none. It
  is the other way round. `xhf`'s fields are `0x150B` = hasbit | checkInit | required |
  UINT32 and each is followed by a hasbit index.
- **Java escapes in the descriptor string.** The descriptor is a UTF-16 string full of
  small code points, so field number 8 is written `\b`, 12 is `\f` and 13 is `\r`. An
  unescaper that only handles `\uXXXX` and `\n` leaves those as a literal backslash plus a
  letter and silently shifts every later field by one — which is what made `xnz` decode
  correctly through field 7 and turn to noise afterwards. Chained `str.replace` calls are
  also wrong here regardless of order, because the descriptors genuinely contain
  backslashes; the unescaper has to be a single left-to-right scan.

## 5. Native code

The arm64 split contains **no protocol or codec native library** — only
`libgmm-jni.so` (maps), `libhwrword.so` (handwriting), `libresampling_jni.so`,
and generic Google infra libs. Video/audio go through platform `MediaCodec`;
TLS through Conscrypt. Nothing to port.

---

## 6. Components

56 activities, 87 services, 42 receivers, 11 providers. Exported entry points that
matter:

- `carservice.service.impl.GearheadCarStartupService` — `com.google.android.gms.car.CAR_STARTUP_NOTIFICATION`
- `carservice.gmscorecompat.CarChimeraService` — `com.google.android.gms.car.service.START`
- `carservice.gmscorecompat.CarSetupServiceImpl` — `com.google.android.gms.car.CAR_SETUP_SERVICE`
- `carservice.companion.CarProcessCompanionDeviceService` — CDM automotive profile
- `apps.auto.wireless.setup.service.impl.WirelessSetupSharedService` — wireless AA bring-up
- `components.telecom.service.CarProjectionInCallServiceImpl` — `InCallService` for projected calls
- `notifications.SharedNotificationListenerManager$ListenerService` — `NotificationListenerService`
- `service.CarSystemUiControllerService`, `appdecor.AppDecorService` — projected window decor

Transports: USB (AOAP, `CarUsbReceiver` / `CarUsbReceiverTPlus`, action
`com.google.android.gms.car.usb.USB_ACCESSORY_FORCE_START`) and wireless
(`com.google.android.gms.car.wifi.BT_START` → RFCOMM handshake → TCP socket).

---

## 7. Scope

Decided: **sender side** — MA Auto replaces gearhead on the phone and projects to a
real head unit. Holds `SYSTEM_AUTOMOTIVE_PROJECTION`. Testing against an emulated
head unit on a second device (no real car available yet). Ships the extracted
credential, accepting the 2026-12-09 expiry.
