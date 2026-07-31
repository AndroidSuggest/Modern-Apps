#!/usr/bin/env bash
#
# Stage an offline Piper (VITS) voice as a single zip for the model mirror. The :speech app
# downloads it at runtime — it is NOT bundled in the APK — from
#   https://data.vayunmathur.com/models/piper/voice.zip
# and extracts it (see PiperModel). The zip must contain the voice's CONTENTS at its root:
# <voice>.onnx, tokens.txt, and espeak-ng-data/.
#
# Voices: https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models  (vits-piper-*)
#
#   ./scripts/speech/fetch_piper_model.sh                          # amy-low  (~64 MB)
#   ./scripts/speech/fetch_piper_model.sh vits-piper-en_US-kristin-medium
#
set -euo pipefail

VOICE="${1:-vits-piper-en_US-amy-low}"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${VOICE}.tar.bz2"
DEST="dist/speech-mirror/piper"

if [ ! -f settings.gradle.kts ]; then
  echo "Run this from the repo root (where settings.gradle.kts is)." >&2
  exit 1
fi
command -v zip >/dev/null || { echo "'zip' is required." >&2; exit 1; }

ABS_DEST="$(pwd)/$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading ${VOICE}..."
curl -fL "${URL}" -o "${TMP}/voice.tar.bz2"
echo "Extracting..."
tar xjf "$TMP/voice.tar.bz2" -C "$TMP"
# The Piper JSON config isn't needed at runtime (sherpa reads the .onnx metadata + tokens).
rm -f "$TMP/${VOICE}"/*.onnx.json

mkdir -p "${ABS_DEST}"
rm -f "${ABS_DEST}/voice.zip"
# Zip the voice dir's CONTENTS (not the wrapping folder) so entries sit at the zip root.
( cd "${TMP}/${VOICE}" && zip -r -q -X "${ABS_DEST}/voice.zip" . )

echo
echo "Staged ${DEST}/voice.zip"
du -sh "${ABS_DEST}/voice.zip"
echo "Upload it to: https://data.vayunmathur.com/models/piper/voice.zip"
