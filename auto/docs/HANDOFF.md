# MA Auto — handoff

**Status: the full GAL bring-up completes against Google's Desktop Head Unit —
discovery → channel opens → media setup → focus → start — and H.264 frames
stream with head-unit acks. Render path (decode→surface) still unconfirmed.**

> This file is tracked at `auto/docs/HANDOFF.md` (moved out of gitignored
> `analysis/maauto/` in `5c323e528`). Companion teardown lives at
> `auto/docs/FINDINGS.md`.

Companion documents:
- `auto/docs/FINDINGS.md` — the full gearhead teardown: crypto, framing, message IDs,
  service table, protobuf recovery. Read that first for anything protocol-shaped.
- `C:\Users\Vayun\.llms\plans\ma_auto_projection.plan.md` — the approved plan, including the
  role strategy.

---

## 1. What this is

MA Auto is the **sender** side of Android Auto: it replaces Google's
`com.google.android.projection.gearhead` on the phone and speaks the Google Automotive Link
(GAL) protocol to a car head unit. Decided scope, from the plan: sender only, tested against
the Desktop Head Unit, shipping Google's extracted GAL credential.

---

## 2. Where it actually got to

Verified live against `desktop-head-unit.exe` 2.0 over TCP (Run 8 + video run):

```
HU → VersionRequest (1.7)          PH → VersionResponse (1.7, OK)   ✓
HU → SslHandshake ×2               PH → SslHandshake ×2             ✓  TLS 1.2 established
HU → AuthComplete (STATUS_SUCCESS) PH → ServiceDiscoveryRequest    ✓  field 5 only
HU → ServiceDiscoveryResponse      PH → ChannelOpen ×7 (services 1-7 in wire order) ✓
HU → ChannelOpenResponse (SUCCESS) PH → MediaSetupRequest (video)  ✓
HU → MediaConfig (index 0)         PH → VideoFocusRequest → MediaStart → H.264 frames ✓
HU → PingRequest every 1s          PH → PingResponse                ✓  link stays healthy
```

**The full GAL bring-up completes against a real Google head unit
implementation, and H.264 frames stream with head-unit acks.** The biggest open
risk in the whole project (credential acceptance) is closed, and so is every
post-discovery sub-blocker from Task 6 (see §3).

The ping/pong after the error is important evidence: DHU keeps pinging and keeps accepting
our *encrypted* replies, so **application-data TLS works in both directions**. The failure is
specific to the ServiceDiscoveryRequest, not to crypto, framing or the transport.

Identical failure on a Pixel 8 (MAOS) and a Pixel 9 Pro XL (GrapheneOS), so it is purely
protocol — nothing to do with the OS or with privileges.

### Not done
- Render path unconfirmed: frames are encoded, sent and acked, but nobody has
  confirmed they decode and display on the DHU surface yet.
- No MAOS integration at all: the app is **sideloaded**, holds no role, and none of the
  role/privapp/RRO work from plan Phase 3 has been started.
- USB (AOAP) and wireless transports not written. Only TCP, which is what DHU uses.
- Audio, input and sensor channels advertised but no traffic verified on them yet.

---

## 10. Phase 6 — Maps mirror + host-vs-mirror decision (maps-dev, task 9)

**Decision: MIRROR. The host stays a prototype.**

The head unit only ever sees our ch2 H.264, so the host path adds a second
renderer, a service bind and a template round-trip without changing a single
wire byte. `HostVsMirror.recommend` (pure, JVM-tested in
`HostVsMirrorTest`) says MIRROR whenever the `:library:map` renderer loads --
which is every arm64 build -- and only says HOST when the host is fully
ready (maps car-app service present, projection role held, `app-projected`
on the classpath) AND the mirror cannot draw at all (no renderer, no
location). `GearheadHostProbe.probe` gathers those facts on device without
hard-linking the host stack (`Class.forName`, never an import); its
one-line verdict feeds this report and the phone debug row, not a live
switch. Mirror is the session default regardless.

Side-by-side (prototype comparison, no DHU run in this environment):

| | Mirror (`CarMapsMirror`) | Host (`MapsCarAppService`) |
|---|---|---|
| Renderer | own `SurfaceMapRenderer`, same archive/tiles as the phone map | car-app host surface via `app-projected` |
| Wire cost | zero new bytes: pixels ride existing ch2 H.264 | same ch2 H.264 plus service bind + template round-trip |
| Latency | one Choreographer loop, on-demand frames | host surface callback + our encode on top |
| Fidelity | full basemap, puck, route, POI; heading-up flat (renderer has no tilt) | `NavigationTemplate` maneuvers + ETA for free |
| Needs role? | no (offscreen surface, no trusted display) | yes (`SYSTEM_AUTOMOTIVE_PROJECTION` for host bind) |
| Needs Maps app? | no (renderer only, `:library:map` dep) | yes (`com.vayunmathur.maps` installed with car service) |
| Fallback | is the fallback | mirror stays fallback even if host prototypes |

What landed (all new files; no other agent's in-flight file touched):

- `auto/protocol/.../MapsGuidance.kt` -- pure `NavSnapshot` shaping:
  `snapshotFromFix` (decimal degrees to e7), `toSensorEvents` (LOCATION /
  SPEED / DRIVING_STATUS always, NIGHT only when known so unknown keeps
  last-known), `toNavStatus` + `encodeNavStatus` (ch10 args + wire render
  through `NavStatusCodec`). Android-free, JVM-tested in
  `MapsGuidanceTest` (6 tests).
- `auto/protocol/.../HostVsMirror.kt` -- pure `HostFacts` / `MirrorFacts`
  / `recommend` (JVM-tested in `HostVsMirrorTest`, 6 tests).
- `platform/NavGuidanceMonitor.kt` -- GPS + network fixes into
  `NavSnapshot`s (last-known seed, motion-heuristic parked, GPS-course
  bearing above 1 m/s else null cone-off, phone-night read); route
  guidance overlays through the `RouteProvider` seam (null = idle map).
  Same "monitor owns its source" shape as `MediaPlaybackMonitor`.
- `platform/CarMapsMirror.kt` -- `SurfaceMapRenderer` into a caller-owned
  surface (own renderer beside the encoder's; composition is the
  bring-up owner's work). Route pushes gated by reference identity, like
  `NavMapScreen.pushedRoute`. Main-thread marshalled; `setDark` follows
  the system theme.
- `platform/GearheadHostProbe.kt` -- on-device fact gathering + one-line
  verdict (`describe()` / `log()`), no host hard-link.
- `auto/build.gradle.kts` -- `implementation(project(":library:map"))`
  for the mirror only.

Guidance hooks for sensors-dev (their channels, their files -- seams only):

- ch7 live values: fold `MapsGuidance.toSensorEvents(snapshot)` through
  `SensorChannel`'s `onValues` seam into `SensorSnapshot.withEvents`.
  The monitor's night read duplicates `ProjectionService.isNightNow`
  deliberately (that file is in-flight); pass the monitor's read in as
  the `NightSource` so the two agree, and dedup when the tree settles.
- ch10 turn updates: forward `MapsGuidance.toNavStatus(snapshot)` to
  `NavStatusChannel.postStatus` as the route advances; the grant-time
  inactive stub stays the no-guidance state.
- ch3 TTS: `GuidanceChannel.startStream` / `stopStream` stay audio-dev's;
  the monitor produces no audio, only the timing (guidanceActive flips)
  a TTS owner could cue on.
- ch8 map touches: route through `VideoSinkChannel` /
  `CarDisplay.injectTouch` like the media card tap -- the mirror draws
  into its own surface, so pan/zoom needs the service to forward scaled
  ch8 frames to the mirror's gesture path (not yet wired; the mirror
  exposes no gesture entry point on purpose until the service owns the
  composition).

Verdict status: Maps visible + pannable and the guidance stub stable are
**not yet shown** -- no DHU/ADB in this environment by hygiene rule. The
JVM leg is green (`:auto:protocol:testDebugUnitTest`); the live leg (DHU
shows the mirror, ch8 pans it, ch10 posts live turns) needs a DHU + Pixel
run and belongs to the task 4 (DHU live-verification) owner via the
`ma-auto-parity` team channel.

---

## 9. Decompiler traps

## 3. The blocker, precisely — RESOLVED (Tasks 3+6)

DHU *used to* answer our ServiceDiscoveryRequest (control message 5) with
MessageError (255, empty payload) and then carry on pinging. That blocker is
closed, along with every sub-blocker found past it. What each turned out to be:

1. **Discovery content** (Task 3, `8449cbeb7`): send only field 5 =
   `"Google Pixel 8"` (MANUFACTURER-first), framing `0x0B`.
2. **0x8 gating** (`a93db5e18`): wait for ChannelOpenResponse before media
   setup; parse `xir` status.
3. **Non-fatal 0xff** (`d9694dab4`): inbound MessageError is observed, session
   stays ACTIVE (gearhead's `izu` logs and carries on).
4. **Open queue** (`e5d2ff668`→`01abb3bbe`): sequential opens in HU wire order;
   bare 0xff drains the pending open into refusedChannels.
5. **CONTROL on 0x7** (`0b0a7cb6e`→`91f7b1d72`): 0x7 goes `0x0F` (CONTROL set),
   scoped to the open path only; 0x5 stays CONTROL-less as accepted.
6. **Target-channel opens** (`200b2c92b`): 0x7 rides the channel being opened
   (`izd.b` sends via `izl.g(this.b, …)`); ch0-framed opens drew
   STATUS_INVALID_CHANNEL (-5).
7. **CONTROL-less setup** (`86039cc7c`): service-channel sends never set
   CONTROL (`jbe.i` flags from `Lizm.f`, false for all service traffic);
   our CONTROL-flagged 0x8000 was 0xff'd.
8. **TLS wrap guard** (`572de7515`): fail loudly on zero-progress wrap instead
   of spinning (also fixed a hung test worker).

### What has been ruled out
- **Encryption.** DHU decrypts our PingResponses happily and we decrypt its messages.
- **The CONTROL frame flag.** Tried both with and without `0x04` set; identical failure.
- **Direction.** Confirmed twice: `izu.java` handles inbound message 6 and logs
  `SDP_RESPONSE_RECEIVED`. The phone sends 5, the head unit answers 6.
- **Device/OS.** Same on MAOS and GrapheneOS.

### The strongest untested lead
`defpackage/jog.java` is where the **live** control endpoint builds the request. Read it —
it is short and not obfuscated beyond names. What it does:

| Field | Java | Set when |
|-------|------|----------|
| 1 | `xoa.c` bytes | only if `izu.g != null` |
| 2 | `xoa.d` bytes | only if `izu.h != null` |
| 3 | `xoa.e` bytes | only if `izu.i != null` |
| 4 | `xoa.f` string | only if `izu.f != null` |
| 5 | `xoa.g` string | **always** |
| 6 | `xoa.h` message `xgs` | only if version high enough and `izu.j != null` |

Field 5 is composed from `Build.MANUFACTURER` and `Build.MODEL`: unless MODEL
already starts with MANUFACTURER, gearhead sends `MANUFACTURER + " " + MODEL`
(e.g. `"Google Pixel 8"` — manufacturer first, not `"Pixel 8 Google"`). This is
verified Dalvik ground truth: `disasm_jog.txt` shows `bJ(MODEL, MANUFACTURER,
" ")` and `a.bJ` returns `str2 + str3 + str`, i.e. MANUFACTURER + " " + MODEL;
hasbit 16 = field 5 confirmed. It is the **only unconditional field** — `g/h/i`
come from optional head-unit resource config, `f` from an optional string
resource, `j` from a version-gated cert string, and `jog` nulls `g/h/i/f` after
the first send, so every repeat is field-5-only by construction.

It is then sent with `((jbj) obj).k(5, …)`, and `k()` routes to `o(i, msg, true,
…)` — but `m()` there constructs `new izn(z, false, …)`, and `izn.a` is
**isEncrypted**, `izn.b` is **isMediaPayload** (see `izn.toString()`:
"isEncrypted;isMediaPayload;callbackId;maxUnackedDuration", confirmed against
the disassembled `Lizm;-><init>` and the `Ljbe` flag assembly where
`Lizm->h`(encrypted)→`0x08` and `Lizm->f`(isControl/canFragment)→`0x04`).
So the `true` sets the **ENCRYPTED** bit, not CONTROL: gearhead's message-5
frame is FIRST|LAST|ENCRYPTED = `0x0B` with the CONTROL bit **clear** — same as
every other control-channel frame both sides send. The earlier "CONTROL on/off"
experiments were testing a bit neither side ever sets on channel 0; no further
flag experiments are needed.

**Fix applied (Task 3):** send *only* field 5 containing `"Google Pixel 8"`
(composed by `composeDiscoveryLabel()` in `GalControlSession.kt`), framing
unchanged at `0x0B`. The old code sent field 4 = `MODEL` + field 5 =
`MANUFACTURER`, which matches gearhead on neither count.

If that still fails, capture the decrypted bytes gearhead itself sends: install real Android
Auto on a phone, point DHU at it through `dhu_relay.py`, and compare. The relay only sees
ciphertext, so you would need to also log gearhead's plaintext — easier is to diff our
request against the field table above until it matches byte for byte.

Also worth checking: `izu.i(5, par.GAL_READ_WRITE_MESSAGE_RAND)` is an early-return guard
before the request is built. Whatever that gates might matter.

---

## 4. What is committed

Protocol bring-up commits (Tasks 3+6), all pathspec-limited so other agents'
work was never swept in:

```
52e64a7b2 maauto: show car Presentation on main thread
86039cc7c maauto: clear CONTROL on service-channel sends
200b2c92b maauto: frame channel-open on its target channel
572de7515 maauto: fail TLS wrap on zero progress, fix tests
91f7b1d72 maauto: scope CONTROL bit to channel-open sends
0b0a7cb6e maauto: set CONTROL on encrypted channel-0 sends
01abb3bbe maauto: drain open queue on bare 0xff, try service 1
e5d2ff668 maauto: open channels sequentially in HU wire order
c0126946b maauto: walk refusal test to ACTIVE before 0x8
b73fe6848 maauto: log full control payloads inbound
e1389aa18 maauto: repair MessageStatus enum mangled by edit
d9694dab4 maauto: survive inbound MessageError without closing
a93db5e18 maauto: gate media setup on ChannelOpenResponse
9689479d3 maauto: pass renamed device fields in GalConnection
8449cbeb7 maauto: send field-5-only ServiceDiscoveryRequest
5c323e528 maauto: preserve protocol handoff docs
ff9ea758e maauto: record completed bring-up in handoff
```

Earlier foundation commits (handshake, transport, TLS codec, frame codec)
predate this list; see `git log --oneline -- auto/` for the full history.

**85+ unit tests, all passing.** `:auto:protocol:testDebugUnitTest`.

---

## 5. Map of the code

### `auto/protocol/` — the wire protocol, deliberately Android-free
Keep it that way: it is what makes the handshake host-testable. Logging goes through the
`trace: (String) -> Unit` hook the app supplies, **not** `android.util.Log`.

| File | Role |
|------|------|
| `FrameHeader.kt` | 4/8-byte frame header, flags |
| `Fragmenter.kt` / `FrameWriter.kt` / `FrameReader.kt` | splitting, framing, reassembly |
| `MessageCodec.kt` | the 2-byte big-endian message type prefix |
| `GalMessage.kt` | every message ID and the service enum |
| `VersionNegotiation.kt` | messages 1/2 — raw shorts, not protobuf |
| `GalCredential.kt` | loads the PEMs, builds the TLS context |
| `TlsCodec.kt` | per-frame application-data wrap/unwrap |
| `GalControlSession.kt` | the handshake state machine, no I/O |
| `GalConnection.kt` | ties transport + framing + TLS + session together |
| `GalTransport.kt` / `StreamTransport.kt` | byte-stream abstraction |
| `src/main/proto/gal/*.proto` | control, services, media |
| `src/main/assets/gal/*.pem` | the GAL credential (see §7) |

### `auto/` — the Android side
| File | Role |
|------|------|
| `network/HeadUnitServer.kt` | TCP listener on 5277, loopback-bound |
| `service/ProjectionService.kt` | foreground service, single-threaded pump loop |
| `platform/VideoSinkChannel.kt` | media setup → focus → start → stream |
| `platform/VideoEncoder.kt` | MediaCodec H.264 Baseline, surface input |
| `platform/CarDisplay.kt` | virtual display + `Presentation` placeholder UI |

`ProjectionService` is single-threaded **on purpose**: one `SSLEngine` backs the connection
and an engine is not thread-safe, so reads, writes and encoder draining share a thread.

### `analysis/maauto/` — tooling (gitignored)
| File | Role |
|------|------|
| `FINDINGS.md` | the teardown. Read it. |
| `extract_key.py` | recovers the GAL private key from the APK |
| `verify_tls.sh` | proves the credential does a mutual-auth handshake |
| `dump_protos.py` | decodes any protobuf-lite descriptor in the APK to exact field numbers |
| `disasm.py` | androguard bytecode dump, for when jadx lies |
| `dhu_relay.py` | MITM between DHU and phone, dumps both directions |
| `dhu_probe.py` | stands in for the phone, dumps DHU's opening bytes |
| `jadx-out/` | the decompiled APK |

---

## 6. How to run the test loop

```powershell
# build + install (Pixel 8 / MAOS is 44050DLJH001PC)
cd C:\Users\Vayun\Documents\code\Modern-Apps
.\gradlew.bat :auto:assembleDev
adb -s 44050DLJH001PC install -r auto\build\outputs\apk\dev\auto-dev.apk

# DHU reaches the phone through an adb forward; the PHONE listens
adb -s 44050DLJH001PC forward tcp:5277 tcp:5277
adb -s 44050DLJH001PC shell am start -n com.vayunmathur.auto/.MainActivity

# logs, in another window
adb -s 44050DLJH001PC logcat -v time MaAuto.Service:V MaAuto.Server:V MaAuto.Video:V "*:S"

# the head unit
C:\Android\Sdk\extras\google\auto\desktop-head-unit.exe
```

To see the wire instead, forward to 5278 and put the relay in the middle:

```powershell
adb -s 44050DLJH001PC forward tcp:5278 tcp:5277
python analysis\maauto\dhu_relay.py 30      # listens on 5277, relays to 5278
C:\Android\Sdk\extras\google\auto\desktop-head-unit.exe
```

### Things that will waste your time if you do not know them
- **DHU is interactive.** Piping its stdout closes its stdin and it exits in under a second.
  Launch it with `Start-Process` (its own window), or relay to see the wire.
- **`adb logcat -d` was unreliable here.** It repeatedly showed a stale buffer. Use a live
  capture (`Start-Job` around `adb logcat`) when checking a fresh run.
- **DHU defaults** to 800x480, 160 dpi, 30 fps, touch enabled — `config\default.ini`. That is
  `VIDEO_800x480` = 1.
- **Two devices are usually attached.** Always pass `-s`.
- The Gradle build wedged once for ~9 minutes, almost certainly lock contention with another
  agent's build. The same work took 12s afterwards. If a build hangs at "Calculating task
  graph", kill it rather than waiting.

---

## 7. The credential

`auto/protocol/src/main/assets/gal/` — leaf cert, its RSA private key, and the GAL root. All
three ship inside the APK. Recovered from gearhead by `extract_key.py`; documented in
`SUPPLY_CHAIN_RISKS.md` §10.2.

**It expires 2026-12-09 and cannot be renewed by us.** We do not hold the root key. Google
also has a remote-provisioning path (`rvf`, fed by six phenotype flags), so the embedded cert
could stop being accepted before that date. Keep `extract_key.py` working so it can be
re-extracted from a newer gearhead.

### Expiry surfacing (Phase 8, landed)

The phone status screen shows the leaf's remaining validity, parsed at runtime so it
stays correct across a rotation with no code change: `GalCredential.daysRemaining()`
(`CertificateFactory`, pure JVM — `auto/protocol` stays Android-free) →
`ProjectionService.publishCredentialExpiry()` seeds `AutoSessionState.credentialDaysLeft`
once per service start (never reset per session; the credential outlives connections) →
`AutoViewModel` → `SessionSnapshot.credentialDaysLeft` → `SessionCard.CredentialRow`.
Quiet day-count while far out, escalating warnings at 90 / 30 / 7 days, expired notice
at zero; all text in `strings.xml` (`session_credential_*`, plurals per CONTRIBUTING.md).

### Renewal decision: re-extract, not rvf (Phase 8)

`rvg(Context)` reads six phenotype flags (`adbo.gu()` `a`–`f`): a cert PEM, an encrypted
key blob and a salt, each paired with a SHA-1 checksum, and builds `rvf` from them instead
of the hardcoded `rvd` when all three validate (`rvg.java:38-54`). `rvf` itself is a trivial
`rvh` holder — cert string, key blob, salt — decrypted by the same `rth`/`jca` path.

That path is Google's own rotation channel **into the genuine gearhead app**, not something
a third-party sender can tap: the phenotype values are delivered server-side to Google's
package, and we have no way to fetch or inject them. So `rvf` is unusable to us by
construction, regardless of what cert it would carry. **Recommendation: re-extract from
each new gearhead release.** `extract_key.py` is pinned to the 17.5.663214 decompile
(`rvg`/`rvd`/`rth` class names and array shapes); if a newer gearhead renames those
families the script needs re-pinning to the new names, then `verify_tls.sh` re-proves the
triple before it ships. Not yet re-run against a newer APK here — that needs a current
gearhead pull plus `jadx` and the `openssl` CLI, none of which this environment had.

### Rotation runbook

1. Pull the current gearhead APK and re-run `analysis/maauto/extract_key.py`. On
   `MATCH`, it writes `analysis/maauto/certs/` (`gal-client-cert.pem`,
   `gal-client-key.pem`, `gal-root.pem`).
2. Run `analysis/maauto/verify_tls.sh`: chain verifies against the GAL root, key
   modulus equals cert modulus, TLS 1.2 mutual-auth handshake returns 0 (ok).
3. Swap the three files under `auto/protocol/src/main/assets/gal/` — same names, same
   PEM formats (`BEGIN CERTIFICATE` leaf + root, PKCS#8 `BEGIN PRIVATE KEY`). No code
   change: `GalCredential.create()` parses whatever ships, and the session card reads
   the new expiry at the next service start.
4. Run `:auto:protocol:testDebugUnitTest` — `GalCredentialRotationTest` proves the
   fixture-swap shape (fresh CA + leaf + key through `create()` into a mutual-auth
   handshake), and `GalCredentialTest` re-proves the shipped triple.
5. Live leg: full DHU handshake per §6 with the rotated PEMs in place. Needs DHU +
   Pixel access — coordinate with the task 4 (DHU live-verification) owner via the
   `ma-auto-parity` team channel; the JVM leg above is the most that can be shown
   without it.

---

## 8. Suggested order from here

1. **Confirm the render path** — frames stream and are acked, but nobody has
   confirmed they decode and display on the DHU surface yet.
2. **Input**, so the DHU's touch does something. Channel messages are mapped in FINDINGS.md.
3. **MAOS integration** (plan Phase 3) — the role move is the risky part. Both the
   privapp-permissions entry and the `roles.xml` patch are boot-fatal if wrong, so change one
   at a time and flash between.
4. Audio, sensors, then the real car UI.

Phase 3 in the plan has the full role strategy: MA Auto takes
`SYSTEM_AUTOMOTIVE_PROJECTION`, MA Cast moves to `COMPANION_DEVICE_APP_STREAMING` (which
carries the same `virtual_device` permission set and is non-exclusive). That needs two new
patches because that role has no `defaultHolders` and an RRO cannot add a new framework
resource name.

Note the current `CarDisplay` uses a **private** virtual display, which needs no permission.
That is why the DHU test works on a stock phone. Launching *other apps'* activities onto the
car display is what needs `ADD_TRUSTED_DISPLAY`, and input injection needs
`CREATE_VIRTUAL_DEVICE` — both role-carried.

---

## 9. Decompiler traps

Four of these have already cost real time. Each produces plausible, compiling, **wrong**
code rather than an error. Details in FINDINGS.md.

1. `roc.aQ` — jadx caches a loop variable the bytecode re-reads. Gave a confident wrong key.
2. `abpe.java:501` — the hasbit branch is inverted.
3. `wub.o` — received media message IDs are compared against `id + 1`.
4. Java escapes in descriptor strings — field 8 is `\b`, 12 is `\f`, 13 is `\r`.

**When something is load-bearing, disassemble it** with `disasm.py` instead of reading jadx.
I lost a long stretch to trap 1 after a 6,400-candidate brute force found nothing.

Two more found from live wire capture, not decompilation:
- The `0x04` CONTROL flag does **not** mean "control channel". Route by channel id.
  The full rule, verified live across Runs 5-8: DHU→phone never sets the bit on
  anything. Phone→HU sets it **only** on channel-0 channel-open requests (0x7,
  framed `0x0F`). Discovery (0x5), ping responses and byebye ride CONTROL-clear
  (`0x0B`) and are accepted that way; every service-channel send (setup, focus,
  start, media) rides CONTROL-clear (`0x0B`) — a CONTROL-flagged 0x8000 is
  0xff'd. And the 0x7 must be framed on the TARGET channel, not channel 0
  (ch0-framed opens draw STATUS_INVALID_CHANNEL, -5).
- The head unit speaks first. The phone answers a version request, it does not send one.
