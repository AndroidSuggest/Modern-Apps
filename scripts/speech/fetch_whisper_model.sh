#!/usr/bin/env bash
#
# Fetch the multilingual Whisper-tiny ncnn model (13 files, ~113 MB) and drop them into
# the :speech app assets so they get bundled in the APK and loaded by the ncnn AAR's
# com.vayunmathur.ncnn.Whisper (AssetManager) — no runtime download, no extraction.
#
# Source: nihui/ncnn-android-whisper release tag "models" (pre-converted by nihui).
# The model is committed with the app, so this is only needed to re-fetch / restore it:
#
#   ./scripts/speech/fetch_whisper_model.sh
#
set -euo pipefail

BASE="https://github.com/nihui/ncnn-android-whisper/releases/download/models"
DEST="speech/src/main/assets/whisper-tiny"

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

if [ "$(ls -1 "$DEST" 2>/dev/null | wc -l | tr -d ' ')" = "13" ]; then
  echo "Model already present at ${DEST} (13 files) — nothing to do."
  exit 0
fi

mkdir -p "$DEST"
for f in "${FILES[@]}"; do
  echo "Downloading $f…"
  curl -fL "$BASE/$f" -o "$DEST/$f"
done

echo "Done. Bundled ${#FILES[@]} files:"
du -sh "$DEST"
echo
echo "Now: ./install dev speech"
