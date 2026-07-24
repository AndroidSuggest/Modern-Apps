# Supply Chain Risks

This document enumerates critical external dependencies for the Modern-Apps monorepo (27 apps + `library/`, `sdk/`, `third_party/`). It covers runtime server dependencies and build-time library dependencies that could break apps if abandoned, shut down, or changed.

## Assumptions

- **Self-hosted infrastructure is assumed indefinite**: `api.vayunmathur.com`, `data.vayunmathur.com`, `findfamily.cc` and any subdomains are excluded from the risk list. These services host self-owned data (e.g. the `health` food/nutrition DB behind `/api/food`, the `openassistant`/`photos` ML models permanently hosted at `data.vayunmathur.com/models`, map pmtiles/zone downloads on `data.vayunmathur.com`, E2EE relays) and proxy a few third-party secrets (Duffel, Google Places/Ratings) to keep F-Droid builds reproducible. Only the *transitive third-party* upstreams (Duffel, Google Maps Platform) are listed — the proxy/DB itself is not a supply-chain risk.
- Offline apps require only Maven Central / Google Maven at build time.

---

## Server Dependencies

### A. Purpose-Aligned Dependencies (server IS the feature)

These services are the raison d'être of the feature. If the service itself ceases to exist, the feature's purpose also ceases. The risk is existential to the product category, not an avoidable implementation choice. Most are also reverse-engineered/unofficial, so breakage from API changes is expected.

| App | External Service | Endpoints / Evidence | Why Inherent | Impact if Gone | Source Files |
|---|---|---|---|---|---|
| `youpipe`, `education` | **YouTube** | `https://www.youtube.com/watch?v=`, `*.googlevideo.com`, SABR streaming, `sabr_po_token.js` | Product is a YouTube client; YouTube is the content source | No playback. Breaks on anti-bot changes (common, requires extractor updates) | `youpipe/src/main/java/com/vayunmathur/youpipe/util/Extractor.kt`, `MyDownloader.kt`, `youpipe/extractor/src/main/java/org/schabi/newpipe/extractor/` (vendored NewPipe Extractor) |
| `messages` (gmessages) | **Google Messages for Web** | `https://messages.google.com/web/authentication`, `https://instantmessaging-pa.googleapis.com`, `https://instantmessaging-pa.clients6.google.com`, API key `AIzaSyCA...`, UA `Chrome/146.0.0.0` | Purpose is aggregating user's Google Messages account | QR pairing / long-poll fails; requires UA / PbLite RPC bump | `messages/src/main/java/com/vayunmathur/messages/gmessages/Constants.kt`, `RpcClient.kt`, `PairFlow.kt` |
| `messages` (gvoice) | **Google Voice (unofficial web API)** | `clients6.google.com/voice/v1/...`, `signaler-pa.clients6.google.com`, `waa-pa.clients6.google.com`, Origin `https://voice.google.com` | Purpose is aggregating Google Voice | SMS/calls/history stops on Voice web change | `messages/src/main/java/com/vayunmathur/messages/gvoice/Constants.kt`, `GVoiceRpcClient.kt` |
| `messages` (whatsapp) | **WhatsApp Web + media CDN** | `wss://web.whatsapp.com/ws/chat`, `s.whatsapp.net`, `*.whatsapp.net`, `mmg.whatsapp.net` | Purpose is aggregating WhatsApp | Noise `XX_25519_AESGCM_SHA256` handshake breaks, media upload fails | `messages/src/main/java/com/vayunmathur/messages/whatsapp/WhatsAppProtocol.kt`, `WhatsAppWebSocket.kt` |
| `messages` (signal) | **Signal** | `wss://chat.signal.org/v1/websocket/`, `chat.signal.org/v1/devices/capabilities`, Signal CDN attachments, `signal-root.crt.der` | Purpose is aggregating Signal | Sealed sender / ratchet / attachment upload breaks | `messages/src/main/java/com/vayunmathur/messages/signal/SignalClient.kt` |
| `messages` (meta) | **Messenger / Instagram (Lightspeed/MQTT)** | Facebook MQTT, `www.messenger.com`, Mercury upload, `LS_RESP`, `ORCA_TYPING` | Purpose is aggregating Messenger/IG | MQTT `versionId` mismatch rejected, login token expiry | `messages/src/main/java/com/vayunmathur/messages/meta/MetaClient.kt`, `MetaMqttClient.kt`, `MetaBootstrap.kt` |
| `messages` (telegram) | **Telegram MTProto DCs** | Telegram data centers, MTProto | Purpose is aggregating Telegram | DC migration / flood wait / auth key invalidation | `messages/src/main/java/com/vayunmathur/messages/telegram/TelegramClient.kt`, `mtproto/` |
| `email` | **Per-provider IMAP/SMTP + OAuth** | Gmail `imap.gmail.com:993` / `smtp.gmail.com:465`, Outlook `outlook.office365.com:993` / `smtp-mail.outlook.com:587` + `https://login.microsoftonline.com/common/oauth2/v2.0/authorize`, Yahoo, iCloud `imap.mail.me.com`, Fastmail, generic custom | Purpose is connecting to user's own email provider; provider IS the mailbox | That provider's account stops syncing. Microsoft identity outage blocks Outlook token refresh | `email/src/main/java/com/vayunmathur/email/data/ProviderPresets.kt`, `OutlookOAuth.kt` |
| `everysync` | **Google Contacts/Calendar, Google Health, iCloud CalDAV/CardDAV, Generic DAV** | `https://accounts.google.com/o/oauth2/v2/auth`, `oauth2.googleapis.com/token`, `www.googleapis.com/carddav/v1`, `apidata.googleusercontent.com/caldav/v2`, `health.googleapis.com/v4/.../dataPoints`, `caldav.icloud.com`, `contacts.icloud.com`, generic `/.well-known/caldav` discovery via `DavClient.kt` PROPFIND/REPORT | Purpose is syncing user's external calendars/contacts/health from those providers. Generic DAV servers are user-provided (not third-party) | Google/iCloud sync stops; Health backfill (steps, HR, sleep etc) halts | `everysync/src/main/java/com/vayunmathur/everysync/auth/OAuthConfig.kt`, `provider/impl/GoogleProvider.kt`, `remote/GoogleHealthClient.kt`, `provider/impl/DavProviders.kt`, `remote/DavClient.kt` |
| `travel` | **Duffel (Flights + Stays) via self-hosted proxy** | `https://api.vayunmathur.com/api/travel/*` proxying Duffel token server-side: `/places`, `/flights`, `/flights-async`, `/offers`, `/airlines`, `/aircraft`, `/orders`, `/orders/{id}/pay`, etc. Stays: `https://api.vayunmathur.com/api/stays/*` `/search`, `/rates`, `/quote`, `/bookings` | Purpose is flight + hotel search/booking; Duffel is the inventory aggregator. Proxy itself is assumed indefinite, but upstream Duffel is third-party | Flight/hotel search, pricing, seat maps, booking, trips hub fails. Reference data (airlines/cities) cached partially | `travel/src/main/java/com/vayunmathur/travel/network/TravelApi.kt`, `StaysApi.kt` |

Mitigation for purpose-aligned: pin UA versions (`Chrome/146`, Sec-CH-UA), isolate per-bridge circuit breaker, implement extractor/protocol version checks in CI, surface degraded state per account.

### B. Replaceable / Infrastructure Dependencies (implementation choice, could be self-hosted or swapped)

These servers provide data/functionality that is NOT intrinsically tied to a single vendor. We currently depend on a community/FOSS provider or CDN, but could mirror, self-host, or swap with modest engineering.

| App | External Service | Endpoints / Evidence | Why Replaceable | Impact if Gone | Alternative |
|---|---|---|---|---|---|
| `weather` | **Open-Meteo Stack (FOSS, keyless)** | `https://api.open-meteo.com/v1/forecast`, `geocoding-api.open-meteo.com/v1/search`, `air-quality-api.open-meteo.com/v1/air-quality`, `map-tiles.open-meteo.com/data_spatial/<model>/latest.json` + `.om` tiles `.../YYYY/MM/DD/HHMMZ/...om` decoded by `libweather_om.so` (vendored C/Rust in `third_party/om-file-format-sys`) | Weather data is commodity; Open-Meteo is one FOSS provider among many. API is keyless and opensource, self-hostable | Forecast, city search, air-quality, weather map shading fails. `WeatherCacheStore` mitigates partially | Self-host Open-Meteo `om-file-format` + DWD ICON model, or swap to PirateWeather, OpenWeather, NOAA GFS direct |
| `maps` ratings + live traffic | **Google Places + traffic via self-hosted proxy (transitive)** | `https://api.vayunmathur.com/maps/traffic` (live traffic tiles, `OfflineRouter.kt`), `place_match`, `place_rating` wrapping Google Places (`Reviews.kt`) | Ratings/traffic proxy holds secret; Google is upstream. **Routing is fully offline** via `offlinerouter.so` (C++ `maps/src/main/cpp/`, `OfflineRouter.findRouteNative`) + bundled pmtiles `world_z0-6.pmtiles` + `admin0.fgb/admin1.fgb` + GTFS — no online routing endpoint is called | Place ratings + live-traffic overlay fail; turn-by-turn routing continues fully offline with pre-downloaded zones | Ratings could be OSM/Wikidata; self-host a traffic source. (Online routing already removed in favor of the offline router) |
| `youpipe` extras | **SponsorBlock / DeArrow (community)** — data APIs now self-hosted | Segment + branding data served from `https://api.vayunmathur.com/api/skipSegments` and `/api/branding` (a local mirror of the SponsorBlock DB, no runtime call to ajay.app). The DeArrow rendered thumbnail frame still uses `https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=` (needs the actual video; not mirrorable from the DB dump) | Crowdsourced, best-effort extras, not core YouTube | Sponsor skips + DeArrow titles/branding continue from the local mirror even if ajay.app is down; only custom thumbnail *images* depend on upstream | ✅ **Partially implemented** — SponsorBlock + DeArrow **data** mirrored into `sponsorblock.db` (`location_share_server/sb_build.sh` rsyncs the public DB dumps; served by `handlers/sponsorblock.rs`). Remaining third-party hop is the DeArrow thumbnail *frame* renderer (would require self-hosting `DeArrowThumbnailCache`: yt-dlp + ffmpeg) |
| `maps` / `travel` / `photos` / `weather` | **MapLibre rendering + Wikidata enrichment** | `org.maplibre.compose:maplibre-compose:0.13.0` → `libmaplibre.so`, `https://www.wikidata.org/w/rest.php/wikibase/v1/entities/items/{id}` | Map renderer is FOSS community project (vs Mapbox), Wikidata is enrichment only | Map rendering would need fork/mirror if MapLibre artifacts gone; Wikidata fail is graceful null | Vendor MapLibre AAR, or switch to OpenGL self-render; Wikidata can be removed |

`health` food search hits `api.vayunmathur.com/api/food` — a **self-hosted nutrition DB** (not a third-party proxy), so it is excluded from the risk list per Assumptions.

Notably **offline after first launch**: `astronomy`, `calendar`, `clock`, `contacts` (only `libphonenumber` locally), `files`, `notes` (Room + Ink), `things`, `camera` (on-device `tasks-vision:0.10.14` BlazeFace), `pdf` (Rust `libpdf_render.so`), `music` (Media3 ExoPlayer + local scan), `games/*`, `passwords` (biometric + KeePass), `library/*`, `health` core.

---

## Library / Build Dependencies

### Build Infrastructure

- **Maven repositories**: `google()` (filtered), `mavenCentral()`, `gradlePluginPortal()` in `build-logic/settings`, plus **`https://jitpack.io`** in root `settings.gradle.kts`. JitPack now builds only `Stockfish-Library` (games:chess) — `nanojson` was vendored to `:third_party:nanojson` (✅ mitigation #2). JitPack remains a reproducible-build SPOF for Stockfish only.
- **Launch icon generator**: `build-logic/src/main/kotlin/LauncherIconGen.kt` downloads `https://raw.githubusercontent.com/google/material-design-icons/819d786.../symbols/android/{icon}/...24px.xml` at build time, cached under `~/.gradle/material-symbols-cache`. Build needs GitHub availability on first (uncached) build. ⚠️ Not yet mitigated.
- **Alpha toolchain**: `agp = 9.4.0-alpha04`, `composeBom = 2026.06.01` (future-dated), `navigation3 = 1.2.0-alpha05`, `material3 = 1.5.0-alpha23`, `biometric = 1.4.0-alpha07`, `camerax = 1.7.0-alpha02`, `datastore = 1.3.0-alpha09`. ⚠️ Stable release branch should pin stable versions (not yet done).
- **Version drift**: `okhttp 4.12.0` (2y old, 5.x exists) ⚠️ still pinned. `ktor` now unified at catalog `3.5.1` (messages inline `3.2.3` removed ✅) and `mediapipe tasks-vision` unified at `0.10.35` (camera inline `0.10.14` removed ✅).
- **Vendored third_party**: `third_party/nanojson/` (vendored `TeamNewPipe/nanojson`, was JitPack — ✅ mitigation #2), `third_party/om-file-format-sys/` (C + Rust bindings for `open-meteo/om-file-format` GPL-2.0-only) patched for reproducible `readdir` sort, `maps/src/main/cpp/sqlite3.c` amalgamation (SQLite NDK workaround), `youpipe/extractor/` full NewPipe Extractor GPLv3, `library/ocr/assets/det.onnx, rec.onnx` from `RapidAI/RapidOCR` Apache-2.0.

All versions in `gradle/libs.versions.toml`.

### High-Risk External Libraries (single maintainer, abandoned, or proprietary CDN)

| Library | Version | Used By | Why High Risk | File |
|---|---|---|---|---|
| `com.github.vayun-mathur:Stockfish-Library` via JitPack | `1.1.0` | `games/chess` | Personal fork on JitPack, if GitHub repo deleted build breaks. Only remaining JitPack dep | `games/chess/build.gradle.kts` |
| `jitpack.io` itself | — | above | Supply chain SPOF | `settings.gradle.kts` |
| `io.github.dokar3:quickjs-kt` | `1.0.5` | `youpipe` | Single maintainer (dokar3), JS eval for YouTube signature decipher — critical path | `youpipe/build.gradle.kts`, `libs.versions.toml:8` |
| `org.whispersystems:signal-protocol-java` shaded via ShadowJar | `2.8.1` + `com.gradleup.shadow:9.0.0` | `whatsapp-signal` (`configuration = shaded`) → `messages` | Abandoned, replaced by `libsignal-android`; needed only because `libsignal-android:0.86.5` removed X3DH needed for WhatsApp bridge. Protobuf relocation `com.google.protobuf -> com.vayunmathur.messages.shadedproto` hack | `whatsapp-signal/build.gradle.kts` |
| `dev.whyoleg.cryptography:cryptography-core` + `cryptography-provider-jdk` | `0.6.0` | `library:e2ee-p2p` → `office` E2EE | Single person (whyoleg), crypto-critical for PQC KEM | `library/e2ee-p2p/build.gradle.kts`, `libs.versions.toml:75` |
| `org.linguafranca.pwdb:KeePassJava2-dom` | `2.2.4` | `passwords` | Single maintainer, last release 2021, security-sensitive KDBX parsing | `passwords/build.gradle.kts`, `libs.versions.toml:82` |
| `org.wololo:flatgeobuf` | `3.29.0` | `maps` | Niche geo format, single maintainer | `maps/build.gradle.kts`, `libs.versions.toml:13` |
| `com.google.code.findbugs:jsr305` + `javax.annotation-api:1.3.2` | `3.0.2` / `1.3.2` | `extractor` | Deprecated 7+ years, replaced by JetBrains annotations | `libs.versions.toml:5,114` |
| `org.mozilla:rhino` + `rhino-engine` | `1.8.1` pinned | `extractor` | Pinned old because `1.9.0` requires minSdk 26 comment in `libs.versions.toml:2`; Mozilla but old | `libs.versions.toml:3,107` |

### Medium-Risk (small org / community, but active)

| Library | Version | Used By | Notes | File |
|---|---|---|---|---|
| `org.jsoup:jsoup` | `1.22.2` | `youpipe/extractor` | Single but very active, critical for HTML parsing | `libs.versions.toml:54` |
| `org.brotli:dec` | `0.1.2` | `extractor` | Google JVM port, low activity | `libs.versions.toml:9` |
| `org.maplibre.compose:maplibre-compose` | `0.13.0` → `libmaplibre.so` native | `maps`, `weather`, `findfamily`, `photos` | Community-maintained FOSS renderer, better than Mapbox but smaller than Google Maps | `libs.versions.toml:78` |
| `com.google.mediapipe:tasks-vision` | `0.10.35` (unified) | `camera` (face), vision segmentation | Native lib size; inline `0.10.14` drift resolved | `camera/build.gradle.kts:124`, `libs.versions.toml` |
| `com.microsoft.onnxruntime:onnxruntime-android` | `1.27.0` | `library:ocr` (det/rec), `openassistant` (SigLIP), `photos`, `pdf` | MIT, Microsoft-maintained, low risk but native `.so` | `library/ocr/build.gradle.kts`, `libs.versions.toml:85` |
| `net.zetetic:sqlcipher-android` | `4.17.0` | `library:room` → `passwords`, `notes` | Small company, adds ~2MB `.so`, encrypted DB | `library/room/build.gradle.kts`, `libs.versions.toml:31` |
| `io.coil-kt:coil-compose` + `coil-svg` + `coil-video` | `2.7.0` | `photos`, `maps`, `email`, `travel`, `education`, `pdf` | <3 maintainers, but widely adopted image loader | `libs.versions.toml:50` |
| `org.bouncycastle:bcprov-jdk18on` + `bcpkix-jdk18on` | `1.85` | `pdf`, `passwords`, `messages`, `e2ee-p2p` | Small org, long-lived, well-audited PQC ML-KEM/ML-DSA; resource conflicts handled in `common-conventions-app` | `libs.versions.toml:84,226` |
| `com.google.zxing:core` | `3.5.4` | `messages` (QR), `library:ocr`, `pdf` | Old, community fork `zxing-cpp` preferred | `libs.versions.toml:83` |
| `com.google.ai.edge.litertlm:litertlm-android` | `0.14.0` experimental | `openassistant` | Google AI Edge experimental, GPU backend `libLiteRtTopKOpenClSampler.so`, requires `kotlinx-coroutines 1.11.0` conflict win | `openassistant/build.gradle.kts`, `libs.versions.toml:30` |
| `com.gradleup.shadow` | `9.0.0` | `whatsapp-signal` shaded jar | Single org (gradleup), medium risk | `whatsapp-signal/build.gradle.kts` |

### Low Risk / Platform (Google/JetBrains) — Not Critical

Well-maintained: `androidx.*` (`core-ktx 1.19.0`, `lifecycle 2.11.0`, `room 2.8.4`, `sqlite 2.7.0`, `work 2.11.2`, `datastore`, `camera`, `webkit`, `browser 1.9.0`, `biometric`, `glance`, `exifinterface`, `credentials`, `autofill`), `composeBom 2026.06.01`, `material3 1.5.0-alpha23`, `material 1.14.0`, `foundation 1.12.0-beta02`, `ink 1.1.0-alpha04`, `navigation3`, `media3-exoplayer 1.11.0-beta01`, `ktor 3.5.1` + `okhttp 4.12.0` + `okio 3.17.0`, `kotlin 2.4.0` + `coroutines 1.11.0` + `serialization-json 1.11.0` + `datetime 0.8.0`, `ksp 2.3.10`, `protobuf 4.36.0-RC1` / `protobuf-javalite 4.35.0`, `libphonenumber 9.0.34`, `fhir-model 1.0.0-beta02`, `health/connect-client 1.2.0-alpha04`, etc.

---

## Transitive Dependencies Behind Self-Hosted Proxy (for awareness)

Even assuming `api.vayunmathur.com` stays up, its upstreams are third-party:

- **Duffel API** (flights + stays) — proxied token held server-side, no secret in APK. See `travel/network/TravelApi.kt`, `StaysApi.kt`.
- **Google Maps Platform** (Place Details/Ratings only) — proxied via `place_match`, `place_rating`. See `Reviews.kt`. Live traffic tiles come via `api.vayunmathur.com/maps/traffic` (`OfflineRouter.kt`). **Routing is no longer proxied** — it runs entirely on-device via `offlinerouter.so` (`OfflineRouter.findRouteNative`), so there is no Google Routes dependency.

If those upstreams shut, our proxy returns errors — same UX as purpose-aligned but fixable by swapping provider behind same proxy endpoint.

---

## Offline-First Guarantee

After initial model download (openassistant/photos) and map zone downloads (maps), these apps work airplane-mode: `astronomy`, `calendar`, `clock`, `contacts`, `files`, `notes`, `things`, `camera`, `pdf`, `music`, `games`, `passwords`, `health` core (Health Connect), `library` modules (`ocr` det/rec, `ink`, `biometric`, `room`).
