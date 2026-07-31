#!/usr/bin/env bash
#
# Vendor the official sherpa-onnx Android AAR (Apache-2.0) into speech/libs so the :speech
# app can run offline Piper/VITS text-to-speech (com.k2fsa.sherpa.onnx.OfflineTts). The AAR
# bundles libonnxruntime.so for all four ABIs, so :speech does NOT depend on
# onnxruntime-android separately. Referenced from settings.gradle.kts via flatDir and from
# speech/build.gradle.kts as implementation(":sherpa-onnx@aar").
#
# Source: https://github.com/k2-fsa/sherpa-onnx/releases (tag v<VER>).
#
#   ./scripts/speech/fetch_sherpa_onnx.sh
#
set -euo pipefail

VER="${SHERPA_ONNX_VERSION:-1.13.4}"
DEST="speech/libs"
OUT="$DEST/sherpa-onnx.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VER}/sherpa-onnx-${VER}.aar"

if [ ! -f settings.gradle.kts ]; then
  echo "Run this from the repo root (where settings.gradle.kts is)." >&2
  exit 1
fi

if [ -f "$OUT" ]; then
  echo "sherpa-onnx AAR already present at ${OUT} — nothing to do."
  echo "(delete it and re-run to upgrade; set SHERPA_ONNX_VERSION to pick a version.)"
  exit 0
fi

mkdir -p "${DEST}"
echo "Downloading sherpa-onnx ${VER} AAR (~47 MB)..."
curl -fL "${URL}" -o "${OUT}"

echo "Done:"
du -sh "${OUT}"
