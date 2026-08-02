#!/usr/bin/env bash
# Rust supply-chain audit – counts what actually gets compiled into the Android .so files.
# Armv8-only: aarch64-linux-android target, normal edges only (no build-deps, no dev-deps, no Windows/WASM cfg).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "=== Root workspace Cargo.lock total (unfiltered) ==="
grep -c "^\[\[package\]\]" Cargo.lock
echo "unique crates:"; grep 'name = "' Cargo.lock | sed -E 's/name = "([^"]+)".*/\1/' | sort -u | wc -l

echo ""
echo "=== Filtered compile graph for aarch64-linux-android normal edges ==="
rustup run 1.97.0 cargo tree --workspace --target aarch64-linux-android --edges normal 2>&1 | grep -v warning | wc -l
echo "unique crates in filtered graph:"
rustup run 1.97.0 cargo tree --workspace --target aarch64-linux-android --edges normal 2>&1 | grep -v warning | LC_ALL=C sed -E 's/.* ([a-zA-Z0-9_.-]+) v.*/\1/' | sort -u | wc -l

echo ""
echo "=== Per-crate filtered normal counts (armv8) ==="
for p in astronomy_engine photos_fx office_engine weather_om pdf_render camera_stitch passwords_kdbx e2ee_pqc; do
  cnt=$(rustup run 1.97.0 cargo tree -p "$p" --target aarch64-linux-android --edges normal 2>&1 | grep -v warning | wc -l)
  echo "$p: $cnt"
done

echo ""
echo "=== Banned platform crates check (should be 0 for normal armv8 edges) ==="
if rustup run 1.97.0 cargo tree --workspace --target aarch64-linux-android --edges normal 2>&1 | grep -iE "windows-sys|walkdir|same-file|winapi-util|bindgen|clang-sys|prettyplease|regex" | grep -v "windows-link" ; then
  echo "FAIL: banned crates present in normal edges"
  exit 1
else
  echo "OK: no banned Windows/bindgen in normal edges"
fi

echo ""
echo "=== Heavy crates justification ==="
echo "nalgebra 0.33 (camera bundle adjustment SVD/LU/Rodrigues) – justified, cannot replace with glam"
echo "openjp2 (PDF JPEG2000) – justified, no pure-std replacement"
echo "fips203/fips204 (PQC ML-KEM-768/ML-DSA-65) – justified, FIPS standards"
echo "lopdf 0.36 (safe PDF parser) – justified, memory-safe vs pdfium"

echo ""
echo "=== Biggest reductions done ==="
echo "- om-file-format-sys: pre-generated bindings_android.rs/host.rs, build.rs only cc (saved ~20 crates)"
echo "- weather ureq removed: SliceBackend + Kotlin NetworkClient.performRequestBytes (saved ~90 crates ring/rustls/icu/url/webpki)"
echo "- camera image removed: jpeg-decoder+jpeg-encoder single-function rewrite (saved moxcms/pxfm/bytemuck chain)"
echo "- photos rayon removed: stdlib threads only (saved 6 crates)"
echo "- PDF toolchain: removed x86_64-linux-android target"
echo "- Root workspace Cargo.toml centralized versions (mirrors gradle/libs.versions.toml)"
