#!/usr/bin/env bash
#
# Stage the multilingual Whisper-tiny ncnn model (13 files, ~113 MB) for upload to the model
# mirror. The :speech app downloads these at runtime — they are NOT bundled in the APK — from
#   https://data.vayunmathur.com/models/whisper-tiny/<file>
# (see WhisperModel.FILES). Upload the staged files under that path, preserving names.
#
# Source: nihui/ncnn-android-whisper release tag "models" (pre-converted by nihui).
#
#   ./scripts/speech/fetch_whisper_model.sh
#
set -euo pipefail

BASE="https://github.com/nihui/ncnn-android-whisper/releases/download/models"
DEST="dist/speech-mirror/whisper-tiny"

FILES=(
  whisper_tiny_decoder.ncnn.bin        whisper_tiny_decoder.ncnn.param
  whisper_tiny_embed_position.ncnn.bin whisper_tiny_embed_position.ncnn.param
  whisper_tiny_embed_token.ncnn.bin    whisper_tiny_embed_token.ncnn.param
  whisper_tiny_encoder.ncnn.bin        whisper_tiny_encoder.ncnn.param
  whisper_tiny_fbank.ncnn.bin          whisper_tiny_fbank.ncnn.param
  whisper_tiny_proj_out.ncnn.bin       whisper_tiny_proj_out.ncnn.param
  whisper_vocab.txt
)

if [ ! -f settings.gradle.kts ]; then
  echo "Run this from the repo root (where settings.gradle.kts is)." >&2
  exit 1
fi

mkdir -p "$DEST"
for f in "${FILES[@]}"; do
  echo "Downloading ${f}..."
  curl -fL "${BASE}/${f}" -o "${DEST}/${f}"
done

echo
echo "Staged ${#FILES[@]} files in ${DEST}"
du -sh "${DEST}"
echo "Upload them to: https://data.vayunmathur.com/models/whisper-tiny/"
echo "SHA-256 (must match WhisperModel.FILES):"
( cd "${DEST}" && shasum -a 256 * 2>/dev/null || sha256sum * )
