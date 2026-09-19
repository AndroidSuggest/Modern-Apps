# AGENTS.md — Modern-Apps

Monorepo: ~100 Gradle modules + Rust workspace. Do scoped work only.

## 1. Scope first (law)
1. Each module has its own `AGENTS.md` (e.g. `notes/AGENTS.md`, `games/voxels/AGENTS.md`, `library/ui/AGENTS.md`) — auto-loaded on read. Read it instead of listing the dir.
2. State `Scope: <dir/> + <shared>` before any scan. Stay inside those prefixes.
3. Expand only with one-line justification. Never root-recurse.
4. Don't read `CONTRIBUTING.md` whole (26KB). Pull a section only on demand.
5. Prefer 1 targeted read over 10 searches.

### How to pick scope
| Task | Scope |
| :--- | :--- |
| UI bug in one app | that app + `library/ui` |
| DB / Room | that app + `library/room` |
| Network / TLS / certs | that app + `library/network` |
| Icons, scaffold, theme, messenger | that app + `library/ui` |
| Cross-app share / assistant tool | both apps + `library` (DTO in `library.intents.<app>`) |
| New app module | `settings.gradle.kts` + `build-logic/` + reference app (`backup/`, else `notes/`) |
| Store listing / screenshots | that app + `metadata_data/` |
| Rust / JNI / native | that app's `src/main/rust/` + root `Cargo.toml` |
| Build convention change | `build-logic/` only |
| Lint rule change | `lint-rules/` only |
| Games hub achievement | game + `sdk/games` |

### Shared modules (only pull when row above says so)
| Module | Path | What it owns |
| :--- | :--- | :--- |
| base | `library/` | nav keys, `AssistantIntent`, IPC DTOs (`library.intents.<app>`), `RoomRepository` base |
| ui | `library/ui/` | `Icons.kt` (ONLY `Icon()`/`painterResource()` allowed), `AppScaffold` family, `DynamicTheme`, messenger/`AppMessages`, motion helpers |
| network | `library/network/` | `NetworkClient`, pinned CAs in `assets/ca/`, `TrustBundle` (FIRST_PARTY/STANDARD/EXTENDED/MUSICBRAINZ/SYSTEM) |
| room | `library/room/` | Room 3 + SQLCipher (`implementRoom(libs)`) |
| map / ml / ocr | `library/map/`, `library/ml/`, `library/ocr/` | vector maps (INTERNET itself) / inference / OCR |
| image/media | `library/image/`, `library/media/` | image loading / AV playback |
| e2ee-p2p | `library/e2ee-p2p/` | E2EE, p2p |
| widgets/work | `library/widgets/`, `library/work/` | Glance widgets / WorkManager |
| biometric/ink | `library/biometric/`, `library/ink/` | biometric / stylus ink |
| downloadservice | `library/downloadservice/` | foreground downloads (INTERNET itself) |
| locationprovider | `library/locationprovider/` | location abstraction |
| *-stubs | `library/*-stubs/` | compile-only hidden-API stubs |
| sdk:games/cast/openassistant | `sdk/games/`, `sdk/cast/`, `sdk/openassistant/` | achievements / casting / assistant tools |
| build-logic | `build-logic/` | `common-conventions-app`, `launcherIcon`, `rustNativeLib` |
| lint-rules | `lint-rules/` | ToastUsage + DirectComposeAnimation (fatal) + 6 advisory |

### Apps (Gradle key → dir)
Reference: `backup/` satisfies every rule; `notes/` second + `intents/` demo. `camera` + `games/voxels` hand-commit screenshots.
`appstore→appstore/` `astronomy→astronomy/` `auto→auto/,auto/protocol/` `backup→backup/` `calculator→calculator/` `calendar→calendar/` `camera→camera/` `cast→cast/,cast/protocol/,cast/tv/` `clock→clock/` `code→code/` `communicate→communicate/` `contacts→contacts/` `education→education/` `email→email/` `emergency→emergency/` `euicc→euicc/` `everysync→everysync/` `files→files/` `findfamily→findfamily/` `flashcards→flashcards/` `fooddelivery→fooddelivery/` `health→health/` `keyboard→keyboard/` `launcher→launcher/` `logviewer→logviewer/` `maps→maps/` `measure→measure/` `music→music/` `musicbrainz→musicbrainz/` `networklocation→networklocation/` `notes→notes/` `office→office/` `openassistant→openassistant/` `passwords→passwords/` `pdf→pdf/` `photos→photos/` `parentalcontrols→parentalcontrols/` `screentime→screentime/` `setupwizard→setupwizard/` `share→share/` `speech→speech/` `taxi→taxi/` `things→things/` `translate→translate/` `travel→travel/` `tuner→tuner/` `updater→updater/` `vpn→vpn/` `weather→weather/` `web→web/` `youpipe→youpipe/,youpipe/extractor/` `tools-holidaygen→tools/holidaygen/` `games-hub→games/hub/` `games-alchemist→games/alchemist/` `games-arrows→games/arrows/` `games-chess→games/chess/` `games-logicgate→games/logicgate/` `games-minesweeper→games/minesweeper/` `games-nonogram→games/nonogram/` `games-pipes→games/pipes/` `games-solitaire→games/solitaire/` `games-sudoku→games/sudoku/` `games-unblockjam→games/unblockjam/` `games-voxels→games/voxels/` `games-wordmaker→games/wordmaker/`
Slash == colon for Gradle (`games/voxels` == `games:voxels`).

### Never scan
`target/`, `scripts/`, `build/`, `.gradle/`, `.kotlin/`, `*/build/`, `*/schemas/` (single file ok), `metadata_data/photos/`, `third_party/`, `firmware/`, `*.onnx`, `*.log`, `personal/`.

## 2. Conventions (check every time)
- `Toast` banned (fatal lint). Use `rememberMessenger().show()` in composable, `AppMessages.show()` elsewhere.
- Never `Icon()`/`painterResource()`/`androidx.compose.material.icons.*` in app. Add `IconXxx()` to `library/ui/.../Icons.kt`.
- Never raw `Scaffold`. Use `AppScaffold` family. Never raw `androidx.compose.animation` at call site — use `library.ui` helpers. Never `imePadding()/navigationBarsPadding()/systemBarsPadding()` in `library.ui`.
- Room 3 only. Literal `androidx.room.` flagged even in comments. Never `buildDatabase()` at call site — subclass `library.room.RoomRepository`.
- Network only via `library.network.NetworkClient` + `TrustBundle` narrowest (`FIRST_PARTY/STANDARD/EXTENDED/MUSICBRAINZ/SYSTEM`).
- Package roots closed set: `ui data domain platform network intents service provider widget notifications auth sync telephony`. No `util/`. One public `@Composable` per file, named as file. `domain/` pure (no Android/Compose).
- Escape hatches verbatim: `// PACKAGE STRUCTURE EXCEPTION (JNI)`, `// RAW SCAFFOLD EXCEPTION: <reason>`, `// TOAST EXCEPTION: <reason>`, `// RAW ANIMATION EXCEPTION: <reason>`.

## 3. Verify (slice touched, never whole repo)
```
./gradlew :<module>:compileDevKotlin
./gradlew :<module>:lint
./gradlew :<module>:checkMetadata
```
Gradle defaults to small heap (`-Xmx8g`, 2 workers). Don't raise it. New build logic must be configuration-cache compatible. New/changed UI previews: `./gradlew :<module>:metadata`.

## 4. Tools — use these, not raw gradle/adb
Work in repo dir. Experiments/logs go in `analysis/` (gitignored).

`./install` — build + session-install (handles `.idsig`/fs-verity + `versionCodeOverride` automatically):
```
./install <module> [...]              # dev by default
./install [dev|release] <module> [...]
./install voxels                      # shorthand → games:voxels; dooraccess → personal:dooraccess
./install dev games/voxels            # slash == colon
./install contacts calendar           # multi-module, one gradlew invocation
./install 9 maps                      # 8 = Pixel 8, 9 = Pixel 9 (default: first non-emulator)
./install all / ./install dev all     # full repo (auto big-heap); release all caps R8 workers
./install --dry-run dev contacts
```
Rules: `dev` default, `release` only when asked, `debug` blocked. Never uninstall / clear data (emulator-only exception, never touch physical device). Never give placeholder commands — output copy-pasteable.

`./dhu` — Desktop Head Unit loopback for `auto/` (Pixel 8 default):
```
./dhu                                 # adb forward tcp:5277 + live logcat → analysis/dhu-<ts>.log + DHU window
./dhu -NoLog                          # DHU only
./dhu -DeviceId <serial>              # non-default device
```

## 5. Git / safety
- Other agents share the tree. Prohibited except reading state: `stash/revert/reset/checkout . /clean` etc. Only `add` + `commit` allowed, and only paths you changed — check `git status` first, never `add -A/.`, never stage without committing instantly.
- Commit: one line ≤80 chars: `appname: short description (#iss)`.
- LF only; never commit line-ending churn. `FAIL_ON_PROJECT_REPOS` — never add repos in modules. Read `SUPPLY_CHAIN_RISKS.md` before new third-party dep.
