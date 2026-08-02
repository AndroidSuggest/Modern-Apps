#!/usr/bin/env bash
#
# Stage an offline Piper (VITS) voice, in ncnn format, as a single zip for the model
# mirror. The :speech app downloads it at runtime — it is NOT bundled in the APK — from
#   https://data.vayunmathur.com/models/piper/voice3.zip
# and extracts it (see PiperModel). The zip must contain the voice's CONTENTS at its root:
#   <voice>_enc_p.ncnn.{param,bin}   text encoder
#   <voice>_dp.ncnn.{param,bin}      duration predictor
#   <voice>_flow.ncnn.{param,bin}
#   <voice>_dec.ncnn.{param,bin}     HiFi-GAN vocoder
#   <voice>_emb_g.ncnn.{param,bin}   multi-speaker voices only
#   en-word_id.bin                   grapheme-to-phoneme dictionary
#   config.json                      sample rate + inference scales
#
# This replaces the old sherpa-onnx path: TTS now runs on ncnn via
# com.vayunmathur.ncnn.Vits, so there is no .onnx, no tokens.txt and no espeak-ng-data/.
#
# ---------------------------------------------------------------------------
# Converting a checkpoint to the five ncnn nets (do this once, by hand)
# ---------------------------------------------------------------------------
#   1. git clone https://github.com/OHF-Voice/piper1-gpl
#      cd piper1-gpl && git checkout 113931937cf235fc881afd1ca4be209bc6919bc7
#   2. curl -fLO https://raw.githubusercontent.com/nihui/ncnn-android-piper/master/piper1-gpl.patch
#      git apply piper1-gpl.patch
#   3. python3 -m venv .venv && source .venv/bin/activate
#      python3 -m pip install -e '.[train]' && pip install -U pnnx
#   4. Grab <voice>.ckpt and config.json from
#      https://huggingface.co/datasets/rhasspy/piper-checkpoints
#   5. curl -fLO https://raw.githubusercontent.com/nihui/ncnn-android-piper/master/export_ncnn.py
#      python3 export_ncnn.py <voice>.ckpt
#
# Then point this script at the directory holding the .ncnn.* files and config.json.
#
# ---------------------------------------------------------------------------
#   ./scripts/speech/fetch_piper_model.sh <converted-dir> [word-list]
# ---------------------------------------------------------------------------
set -euo pipefail

SRC="${1:-}"
WORDS="${2:-}"
DEST="dist/speech-mirror/piper"

if [ ! -f settings.gradle.kts ]; then
  echo "Run this from the repo root (where settings.gradle.kts is)." >&2
  exit 1
fi
if [ -z "$SRC" ] || [ ! -d "$SRC" ]; then
  echo "Usage: $0 <dir with *.ncnn.param/bin and config.json> [word-list]" >&2
  exit 1
fi
command -v zip >/dev/null || { echo "'zip' is required." >&2; exit 1; }
command -v espeak-ng >/dev/null || {
  echo "'espeak-ng' is required to build the phoneme dictionary (build-time only;" >&2
  echo "it is NOT shipped on-device). brew install espeak-ng" >&2
  exit 1
}

ABS_DEST="$(pwd)/$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Identify the voice from the encoder, the same way Vits and PiperModel do.
ENC="$(find "$SRC" -maxdepth 1 -name '*_enc_p.ncnn.param' -print -quit)"
if [ -z "$ENC" ]; then
  echo "No *_enc_p.ncnn.param in $SRC — did export_ncnn.py run?" >&2
  exit 1
fi
VOICE="$(basename "$ENC" _enc_p.ncnn.param)"
echo "Voice: $VOICE"

if [ ! -f "$SRC/config.json" ]; then
  echo "config.json missing from $SRC (needed for the sample rate and phoneme_id_map)." >&2
  exit 1
fi

# _emb_g is only present on multi-speaker voices, so it is copied if it exists but not required.
for net in _enc_p _dp _flow _dec; do
  for ext in param bin; do
    f="$SRC/${VOICE}${net}.ncnn.${ext}"
    [ -f "$f" ] || { echo "Missing $f" >&2; exit 1; }
    cp "$f" "$TMP/"
  done
done
for ext in param bin; do
  f="$SRC/${VOICE}_emb_g.ncnn.${ext}"
  [ -f "$f" ] && cp "$f" "$TMP/"
done
cp "$SRC/config.json" "$TMP/"

# Phoneme dictionary. Without a word list, fetch CMUdict and strip it to bare words.
# NOTE: The old shell pipeline (grep -v + awk + sed + LC_ALL=C grep) broke on macOS
# where sed/grep fail with "illegal byte sequence" on UTF-8 CMUdict and produced only
# 33k words (missing HELLO/WORLD), causing the engine to fall back to spelling
# letter-by-letter. Use Python for robust parsing.
if [ -z "$WORDS" ]; then
  echo "Fetching CMUdict for the word list..."
  curl -fL https://raw.githubusercontent.com/Alexir/CMUdict/master/cmudict-0.7b \
    -o "$TMP/cmudict.txt"
  python3 <<PY
import re
from pathlib import Path
pattern = re.compile(r"^[A-Za-z'-]+$")
seen=set()
words=[]
src=Path("$TMP/cmudict.txt")
for line in src.read_text(encoding='utf-8', errors='ignore').splitlines():
    if line.startswith(';;;'): continue
    line=line.strip()
    if not line: continue
    w=line.split()[0] if line.split() else ''
    w=re.sub(r'\(\d+\)$','',w)
    if not w: continue
    if any(c.isspace() for c in w): continue
    if not pattern.match(w): continue
    low=w.lower()
    if low in seen: continue
    seen.add(low)
    words.append(w)
Path("$TMP/words.txt").write_text("\n".join(words), encoding='utf-8')
print(f"CMUdict: {len(words)} words")
PY
  WORDS="$TMP/words.txt"
fi
echo "Word list: $WORDS ($(wc -l < "$WORDS" | tr -d ' ') lines)"

./scripts/speech/generate_piper_dict.py \
  --config "$TMP/config.json" \
  --words "$WORDS" \
  --voice en-us \
  --out "$TMP/en-word_id.bin"

mkdir -p "${ABS_DEST}"
rm -f "${ABS_DEST}/voice.zip" "${ABS_DEST}/voice2.zip" "${ABS_DEST}/voice3.zip"
# Zip the CONTENTS (not the wrapping folder) so entries sit at the zip root.
# Produce voice.zip for compat and voice3.zip as the cache-busted canonical name
# (voice.zip was old sherpa, voice2.zip had 33k-word dict missing HELLO).
( cd "$TMP" && rm -f cmudict.txt words.txt && zip -r -q -X "${ABS_DEST}/voice.zip" . )
cp "${ABS_DEST}/voice.zip" "${ABS_DEST}/voice2.zip"
cp "${ABS_DEST}/voice.zip" "${ABS_DEST}/voice3.zip"

echo
echo "Staged ${DEST}/voice.zip + voice2.zip + voice3.zip (same content)"
du -sh "${ABS_DEST}/voice.zip" "${ABS_DEST}/voice3.zip"
shasum -a 256 "${ABS_DEST}/voice.zip"
shasum -a 256 "${ABS_DEST}/voice3.zip"
echo "Upload voice3.zip to: https://data.vayunmathur.com/models/piper/voice3.zip"
echo "And voice.zip for compat. SHA-256 must match PiperModel.FILES pin."
