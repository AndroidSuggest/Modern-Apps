# MA Auto — handoff

**Status: the GAL link authenticates and stays alive against Google's Desktop Head Unit.
It is blocked at service discovery, one message short of opening the video channel.
Nothing has been rendered on a head unit yet.**

> This file lives under `analysis/`, which is **gitignored**. It will not survive a clean
> checkout. Move it somewhere tracked if you want it to persist.

Companion documents:
- `analysis/maauto/FINDINGS.md` — the full gearhead teardown: crypto, framing, message IDs,
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

Verified live against `desktop-head-unit.exe` 2.0 over TCP:

```
HU → VersionRequest (1.7)          PH → VersionResponse (1.7, OK)   ✓
HU → SslHandshake ×2               PH → SslHandshake ×2             ✓  TLS 1.2 established
HU → AuthComplete (STATUS_SUCCESS) PH → ServiceDiscoveryRequest
HU → MessageError (0xff, empty)                                     ✗  BLOCKED HERE
HU → PingRequest every 1s          PH → PingResponse                ✓  link stays healthy
```

**The extracted GAL credential is accepted by a real Google head unit implementation.** That
was the biggest open risk in the whole project and it is now closed.

The ping/pong after the error is important evidence: DHU keeps pinging and keeps accepting
our *encrypted* replies, so **application-data TLS works in both directions**. The failure is
specific to the ServiceDiscoveryRequest, not to crypto, framing or the transport.

Identical failure on a Pixel 8 (MAOS) and a Pixel 9 Pro XL (GrapheneOS), so it is purely
protocol — nothing to do with the OS or with privileges.

### Not done
- No pixels on a head unit. The video pipeline is written but never reached.
- No MAOS integration at all: the app is **sideloaded**, holds no role, and none of the
  role/privapp/RRO work from plan Phase 3 has been started.
- USB (AOAP) and wireless transports not written. Only TCP, which is what DHU uses.
- Audio, input and sensor channels not written.

---

## 3. The blocker, precisely

DHU answers our ServiceDiscoveryRequest (control message 5) with MessageError (255, empty
payload) and then carries on pinging.

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

Five commits, all pathspec-limited so other agents' work was never swept in:

```
92cddbe0e auto: video pipeline, head unit server and DHU-verified GAL handshake
2a3737aac auto: wire transport, framing and control session into a connection
08d8cd0cc auto: TLS record codec and stream transport
020ef11b9 build-logic: stop packaging .proto sources into every APK
50e474943 auto: GAL frame codec, transport interface and control session
```

Everything before those was squashed into `5ac87e232 "various fixes"` by a rebase — the
module scaffolding, metadata, README/issue-list registration, the lint exclusion and
`SUPPLY_CHAIN_RISKS.md` §10.2 all landed there.

**74 unit tests, all passing.** `:auto:protocol:testDebugUnitTest`.

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

---

## 8. Suggested order from here

1. **Unblock discovery** — §3. Small, and everything else is behind it.
2. **Video to the DHU.** The pipeline exists but is untested past construction. Expect to
   iterate on the media setup → config → focus → start ordering; a head unit silently drops
   frames sent before it has acknowledged the start request.
3. **Input**, so the DHU's touch does something. Channel messages are mapped in FINDINGS.md.
4. **MAOS integration** (plan Phase 3) — the role move is the risky part. Both the
   privapp-permissions entry and the `roles.xml` patch are boot-fatal if wrong, so change one
   at a time and flash between.
5. Audio, sensors, then the real car UI.

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
- The `0x04` CONTROL flag does **not** mean "control channel". Route by channel id 0. DHU
  never sets the bit on anything it sends; gearhead does set it on protobuf control messages.
- The head unit speaks first. The phone answers a version request, it does not send one.
