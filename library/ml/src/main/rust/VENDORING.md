# Android Vulkan vendoring strategy (`:library:ml`)

Task 5, team `android-vulkan-bringup`. Scope: how the Android patches to
`onnx-vulkan` / `vk-compute` / `onnx-vulkan-core` are carried. Code companion:
`src/memory.rs` (chunked upload, staging pool, RSS accounting, unified-memory
hint, ORT fallback signal).

## Decision: `[patch.crates-io]` with vendored path crates — NOT a git fork

| Option | Verdict | Why |
|---|---|---|
| `[patch.crates-io]` → vendored **path** crates under `third_party/` | **Chosen** | Every byte in-tree and reviewable; lockfile checksums still apply; follows the `third_party/betocore` precedent; `cargo-deny` passes unchanged |
| `git = "https://..."` fork (pinned rev) | Rejected | Adds a non-crates.io source, violating `SUPPLY_CHAIN_RISKS.md` §4 (no git dependencies, `--locked` builds) and `deny.toml` `[sources]` (`unknown-git = "deny"`, `allow-git = []`); each fetch re-trusts the §12 GitHub object path |

The current pin predates this task and is the thing being migrated:
`onnx-vulkan = { git = "https://github.com/automataIA/onnx-vulkan-rs", rev = "c3aa968" }`
(`Cargo.toml:21`). It already contradicts the §4 "no git dependencies" claim;
the migration below removes that contradiction instead of extending it to two
more crates.

## The three Android patches (exact)

Status: **described, not yet applied** — no vendored tree lands in this task
(code + doc, no builds). Each entry records the problem, the shape of the
change, and where it meets this crate, so the vendoring diff can be reviewed
against this spec.

### P1 — Android loader path (`vk-compute` / `ash` entry)

- **Problem.** Desktop loader discovery probes `vulkan-1.dll` /
  `libvulkan.so.0` / `libvulkan.so.1` first. On Android the loader is
  `libvulkan.so` (system library, no `DT_NEEDED` version suffix), so the
  desktop order either fails or picks the wrong candidate. Observable in this
  crate: `src/probe.rs` `CAP_LOADER` stays clear on devices that have Vulkan.
- **Change shape.** Put `libvulkan.so` first in the candidate list when
  `target_os = "android"`, keeping the desktop order as fallback so host
  builds are unaffected. No API change; `probe_device()` semantics unchanged.
- **Upstream-first.** Loader search order is plainly upstreamable (the project
  already carries per-platform cfgs). File the issue + PR upstream; carry the
  local override only until a release containing it lands, then drop it per
  the removal rule below.

### P2 — `OnceLock` single-session → multi-session (`onnx-vulkan`)

- **Problem.** Upstream keeps `Instance` / `Device` / allocator state in
  process-global `OnceLock`s, assuming one session per process. `:library:ml`
  holds many live sessions (the Kotlin `VulkanSessions` handle map), so a
  second model either reuses the first model's device or fails init.
- **Change shape.** Move the shared state into a per-session struct held
  behind `Arc` — one clone per `GpuSession` in `src/vulkan_session.rs` — or,
  if globals must stay, key them by `(device, options)` instead of a bare
  singleton. No global mutation after init; `close()` drops the `Arc`.
  `GpuSession::load/run/close` signatures do not change.
- **Upstream-first.** Multi-session support is a feature upstream plausibly
  wants but may design differently (explicit `Context` type vs keyed
  globals). Propose the `Arc`-per-session design upstream; carry the local
  patch only until upstream picks a direction, then conform to it.

### P3 — allocator tuning for 4–6 GB phones (`onnx-vulkan-core`)

- **Problem.** Desktop block sizes and device-local-only placement OOM or
  thrash on Adreno/Mali unified-memory parts with 4–6 GB total.
- **Change shape.** Cap single allocations at 64 MiB and upload weights in
  tiles (`MAX_CHUNK_BYTES`, `plan_chunks`/`chunk_range` in `src/memory.rs`);
  reuse staging buffers through `StagingPool` instead of allocating per
  chunk; prefer host-visible/coherent memory when `hint_for_device_name`
  reports `AdrenoUnified`/`MaliUnified` (`prefers_host_visible`); track the
  high-water mark with `PeakTracker` for budgets and OOM logs; route to the
  ORT CPU path via `select_path` / `should_fallback_to_ort` when the budget
  plus `LOW_MEMORY_HEADROOM_BYTES` does not fit. The constants are
  upstreamable behind features; the Adreno/Mali heuristics stay local.
- **Upstream-first.** Offer the chunk cap and pool reuse upstream as
  opt-in parameters; keep device-specific defaults local permanently if
  upstream declines them, with the register below recording that outcome.

## Upstream-first policy (per `SUPPLY_CHAIN_RISKS.md`)

1. **Every patch needs an upstream home first**: issue/PR link, minimal diff,
   why it cannot land as-is (if it cannot), and removal criteria — the
   upstream release version that lets us drop the local override.
2. **Record provenance on landing**: upstream rev, vendored-tree hash, and
   patch hash in the register below; diff the tree against upstream on every
   bump (§9.1: without a recorded rev there is no confident security fix).
3. **Prefer a crates.io release bump over carrying a patch**; re-check each
   monthly Dependabot cycle (§6.4).
4. **Never a git URL**: patches ride path overrides only (`deny.toml`
   `[sources]` stays `allow-git = []`). A patch that can only be expressed
   as a fork is a signal to re-scope, not to add a git dep.
5. **Land atomically**: vendored tree + `[patch.crates-io]` entries +
   `Cargo.lock` refresh under `--locked` + `cargo deny check advisories bans
   licenses sources` + this register, all in one diff.

## Vendoring procedure

1. Copy the upstream tree at the recorded rev to
   `third_party/onnx-vulkan{,-core,}/` (names TBD at landing) with the rev
   in a `README` alongside, as `third_party/betocore` already does.
2. Apply P1–P3 as minimal diffs; record hashes in the register.
3. Activate the `[patch.crates-io]` template at the bottom of this crate's
   `Cargo.toml` with path entries, mirrored to the workspace-root manifest
   if cargo only honours the root table.
4. Refresh the lockfile with `--locked`, run `cargo deny check`, update the
   register, and replace the `git` pin in `Cargo.toml:21` with the versioned
   crates.io coordinate the patch table overrides.

## Patch register

| ID | Crate | Subject | Upstream rev | Upstream issue/PR | Local status |
|---|---|---|---|---|---|
| P1 | `vk-compute` | Android loader path (`libvulkan.so` first) | — | — | Proposed (§above) |
| P2 | `onnx-vulkan` | Multi-session (`OnceLock` → per-session `Arc`) | — | — | Proposed (§above) |
| P3 | `onnx-vulkan-core` | Allocator tuning (64 MiB chunks, pool, unified hint) | — | — | Proposed (§above); host-side half implemented in `src/memory.rs` |

## Verification status

No builds ran in this task (code + doc only): no `cargo metadata`, `build`,
or `test`, so the patch shapes above are interface-level and await a
vendored tree plus an Android build to confirm — the same caveat
`SUPPLY_CHAIN_RISKS.md` records under Verification status. `src/memory.rs`
is deliberately host-side so `cargo test -p ml_vulkan` can exercise it
without the `vulkan` feature once a toolchain runs.
