# Brave Shields for `:web`

Port Brave's four protection subsystems into the existing WebView browser at
`/Users/vayun/Documents/Modern-Apps/web`, keeping the `AndroidView` + `android.webkit.WebView`
architecture exactly as it is.

**Decisions locked in:** Brave's real `adblock-rust` engine via JNI · bundle-only filter lists ·
global toggles + per-site shields panel with allowlist and block counter · **Aggressive** defaults.

---

## Goal

Replace the current 10-entry hardcoded blocklist
(`web/src/main/java/com/vayunmathur/web/ui/WebViewBrowser.kt:159-171`) with the actual Brave
Shields stack:

| Shield | Mechanism in a WebView |
|---|---|
| Network ad/tracker blocking | `adblock` crate in `shouldInterceptRequest` |
| Cosmetic filtering | `url_cosmetic_resources` + `hidden_class_id_selectors`, injected at document start |
| Fingerprinting protection | Farbling JS injected at document start, per-session × per-eTLD+1 seed |
| URL cleaning + HTTPS upgrade | `shouldOverrideUrlLoading` + adblock `$removeparam` rewrites |

---

## Feasibility already verified

- `adblock` **v0.13.2** resolves and **compiles clean for `aarch64-linux-android` in 24s**
  (61 crates, `default-features = false`, features `embedded-domain-resolver` + `full-regex-handling`).
- Required API confirmed present in the crate source: `Engine::new_with_list_text`,
  `use_resources`, `check_network_request(&Request) -> BlockerResult`,
  `url_cosmetic_resources(url) -> UrlSpecificResources`, `hidden_class_id_selectors`,
  `serialize`/`deserialize`.
- `rustNativeLib(crate)` in `build-logic/src/main/kotlin/RustNative.kt` makes wiring a one-liner;
  `pdf/build.gradle.kts:18-32` is the copy-paste precedent.
- ⚠️ **Pre-existing, unrelated env bug:** plain `cargo build --target aarch64-linux-android` fails
  with `can't find crate for core` for **every** Rust module in this repo (verified against
  `library/jni-http`). Setting `RUSTC=$HOME/.cargo/bin/rustc` fixes it. Will add that one line to
  `RustNative.kt` so all 15 native modules build again.

---

## Phase 1 — Filter list assets

Bundle-only, following the repo's established "generator script in `scripts/` + brotli asset"
convention (`scripts/wordmaker/generate_dictionary_assets.py`, `libs.brotli.dec` is already in the
version catalog at `gradle/libs.versions.toml:223`).

- **New** `scripts/generate_shields_lists.py` — developer-run, offline. Fetches EasyList,
  EasyPrivacy, Brave's default + unbreak lists and uBO's `resources.json` (scriptlet/redirect
  bodies), strips comments, concatenates, brotli-compresses.
- **New assets** (checked in):
  - `web/src/main/assets/shields/filters.txt.br` (~700 KB from ~4.5 MB raw)
  - `web/src/main/assets/shields/resources.json.br`
  - `web/src/main/assets/shields/farble.js` (Phase 5, hand-written)

## Phase 2 — Rust engine crate

- **New** `web/src/main/rust/` → crate `web_shields`, `crate-type = ["cdylib"]`.
  Deps: `jni.workspace`, `serde_json.workspace`, `adblock 0.13.2` (features as verified above).
- Register in root `Cargo.toml` `[workspace] members` + add `adblock` to `[workspace.dependencies]`.
- `src/lib.rs` JNI surface (handle = `Box::into_raw` of `Engine`, which is `Sync`):
  - `nativeCreate(filters, resourcesJson) -> jlong`
  - `nativeCreateFromCache(bytes) -> jlong` / `nativeSerialize(handle) -> ByteArray`
  - `nativeCheck(handle, url, sourceUrl, requestType) -> String` (JSON: matched / important /
    exception / redirect body / rewritten_url) — one JNI hop per request
  - `nativeCosmeticResources(handle, url) -> String`
  - `nativeHiddenClassIdSelectors(handle, classes, ids, exceptions) -> String`
  - `nativeDestroy(handle)`
- `web/build.gradle.kts`: add `rustNativeLib("web_shields")`, the `androidComponents` jniLibs block
  (mirroring `pdf`), and `implementation(libs.brotli.dec)`.

## Phase 3 — Kotlin bridge

- **New** `web/src/main/java/com/vayunmathur/web/shields/ShieldsEngine.kt` — process singleton.
  `load(context)` on `Dispatchers.IO`: deserialize `filesDir/shields/engine.dat` if its version tag
  matches the bundled asset, else parse the brotli assets and write the serialized cache.
  Exposes `check(...)`, `cosmetic(url)`, and a `ready` flag.
- Fail-open while not ready — `shouldInterceptRequest` must never block the render thread.

## Phase 4 — Network shield

- **New** `web/src/main/java/com/vayunmathur/web/shields/ShieldsWebViewClient.kt`, an open
  `WebViewClient` subclass that tracks the current main-frame URL (needed because
  `WebResourceRequest` carries no initiator) and implements `shouldInterceptRequest`:
  resource type from `Sec-Fetch-Dest` → `Accept` → extension; per-site allowlist check; blocked →
  empty response with a *type-appropriate* MIME (empty JS, 1×1 GIF for images — `text/plain` for
  everything, as today, itself breaks pages); `$redirect` → serve the uBO resource body;
  increment the per-tab counter.
- Wire into **both** `WebViewBrowser.kt` *and* `PwaActivity.kt` (which has **no**
  `shouldInterceptRequest` today).
- Delete the `adHosts` set and the dead `WebViewModel.adBlockEnabled` (`WebViewModel.kt:93-94`),
  which the interceptor never consulted.

## Phase 5 — Cosmetic filtering

- Inject via `WebViewCompat.addDocumentStartJavaScript` when
  `WebViewFeature.DOCUMENT_START_SCRIPT` is supported, else fall back to `onPageStarted` +
  `evaluateJavascript`.
- Pass 1: URL-specific hide selectors → `<style>` + Brave's `injected_script`.
- Pass 2 (Brave's model): a `MutationObserver` collects new class/id attributes and reports them
  through `WebViewCompat.addWebMessageListener` → `hidden_class_id_selectors` → inject more CSS.

## Phase 6 — Fingerprinting protection (Aggressive)

`web/src/main/assets/shields/farble.js`, prefixed at injection with a per-session × per-eTLD+1
seed so values are stable within a page but differ across sites and restarts. Farbles canvas
(`toDataURL`/`getImageData`/`toBlob` LSBs), WebGL (`getParameter` unmasked vendor/renderer,
`readPixels`), AudioContext, `measureText` font metrics, `hardwareConcurrency`, `deviceMemory`,
empty `plugins`/`mimeTypes`, `navigator.languages`. Aggressive additionally forces UTC
`getTimezoneOffset`/`Intl` and disables `RTCPeerConnection` (local-IP leak).

## Phase 7 — URL cleaning + HTTPS upgrade

- **New** `web/src/main/java/com/vayunmathur/web/shields/UrlCleaner.kt` — pure Kotlin, no
  `android.net.Uri`, so it unit-tests on the JVM. Strips Brave's tracking-param list
  (`utm_*`, `fbclid`, `gclid`, `msclkid`, `igshid`, `twclid`, `yclid`, `dclid`, `mc_eid`, …);
  adblock `$removeparam` rules supply the rest via `rewritten_url`.
- HTTPS upgrade in `shouldOverrideUrlLoading`; Aggressive = HTTPS-only (block rather than
  silently downgrade) + `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`.

## Phase 8 — Settings, state, persistence

- `ShieldLevel` enum (`OFF` / `STANDARD` / `AGGRESSIVE`) + `ShieldsSettings` in `web/util/`.
- Globals in `WebViewModel` through the existing `web_prefs` / `persistPrefs()` pattern
  (`WebViewModel.kt:38-44, 675-688`); incognito windows persist nothing.
- Per-site overrides: new Room entity `ShieldSetting(host, …)` + `MIGRATION_2_3` in
  `web/src/main/java/com/vayunmathur/web/data/WebDatabase.kt` (currently `version = 2`).
- Per-tab blocked counters in the VM, reset on main-frame navigation.

## Phase 9 — UI

- Add `IconShield()` to `library/ui/.../Icons.kt` — CONTRIBUTING.md forbids `Icon()` /
  `painterResource` / direct `androidx.compose.material.icons` imports in app modules.
- Shield button with blocked count in `BrowserChrome` actions (`BrowserPage.kt:575`), using the
  existing `Surface`+`Text` chip idiom since `:library:ui` exports `BadgedBox` but **not** `Badge`.
- **New** `ShieldsPanel` as a `ModalBottomSheet` (exported by `:library:ui`): per-site master
  toggle, per-shield switches, blocked breakdown, reload-to-apply.
- `SettingsPage.kt:124-129`: replace the dead "Ad-tracker blocking — Always on" row with a real
  Shields section + a `Route.Shields` page for per-site exceptions.
- All copy into `web/src/main/res/values/strings.xml` via `stringResource`.

## Phase 10 — Tests

`web/src/test/` does not exist yet; `kotlin.test` + JUnit4 are already wired for every app module
by `common-conventions-app.gradle.kts:195-196`, so **no Gradle change is needed**.

- `UrlCleanerTest.kt` — param stripping, HTTPS upgrade decisions
- `ResourceTypeTest.kt` — `Sec-Fetch-Dest`/`Accept`/extension → adblock `RequestType`
- `ShieldsSettingsTest.kt` — level → effective-shield resolution, per-site override precedence

---

## Risks

1. **`shouldInterceptRequest` is hot and off-thread.** Engine calls must be lock-free on the read
   path and fail-open before load completes.
2. **Aggressive farbling breaks sites.** The per-site shields panel is the escape hatch; that is
   why Phase 9 is not optional.
3. **Two WebView hosts drift.** `WebViewBrowser.kt` and `PwaActivity.kt` already have divergent
   copies of `applySettings`; Phase 4 extracts the shared client so shields can't be wired to only one.
4. **Cargo env bug** must be fixed in `RustNative.kt` first or nothing native builds.

## Files touched

**New:** `web/src/main/rust/{Cargo.toml,src/lib.rs}` · `web/src/main/assets/shields/*` ·
`web/src/main/java/com/vayunmathur/web/shields/{ShieldsEngine,ShieldsWebViewClient,UrlCleaner,ShieldsSettings}.kt` ·
`web/src/main/java/com/vayunmathur/web/ui/ShieldsPanel.kt` · `web/src/test/...` ·
`scripts/generate_shields_lists.py`

**Modified:** `Cargo.toml` · `build-logic/src/main/kotlin/RustNative.kt` · `web/build.gradle.kts` ·
`web/src/main/java/com/vayunmathur/web/ui/{WebViewBrowser,BrowserPage,SettingsPage}.kt` ·
`web/src/main/java/com/vayunmathur/web/PwaActivity.kt` ·
`web/src/main/java/com/vayunmathur/web/util/WebViewModel.kt` ·
`web/src/main/java/com/vayunmathur/web/data/WebDatabase.kt` · `web/src/main/MainActivity.kt` (route) ·
`web/src/main/res/values/strings.xml` · `library/ui/.../Icons.kt`
