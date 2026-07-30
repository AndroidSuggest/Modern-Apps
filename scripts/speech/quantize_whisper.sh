#!/usr/bin/env bash
# Weight-only block-quantizes the whisper-tiny ncnn bundle (ncnnllm2int), shrinking the
# Gemm/attention weights. The AAR's ncnn is built with NCNN_WEIGHT_QUANT=ON so it
# auto-dequantizes at load — no app changes needed, just replace the assets.
#
# Requires host tools (same as small100):
#   cmake -DNCNN_BUILD_TOOLS=ON -DNCNN_WEIGHT_QUANT=ON -DNCNN_INT8=ON -DNCNN_BUILD_EXAMPLES=OFF <ncnn-android-root>
#   && make ncnnllm2int ncnnllm2table -j
#
# Usage:
#   ./scripts/speech/quantize_whisper.sh [bundle_dir] [out_root]
#   bundle_dir defaults to the bundled assets (speech/src/main/assets/whisper-tiny).
#
# After it runs, copy a variant over the assets and rebuild the speech app, e.g.:
#   cp scripts/speech/quantized/int8/* speech/src/main/assets/whisper-tiny/
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUNDLE="${1:-$REPO_ROOT/speech/src/main/assets/whisper-tiny}"
OUT_ROOT="${2:-$SCRIPT_DIR/quantized}"
TOOLS="/Users/vayun/Documents/ncnn-android/build-host/tools/quantize"

if [[ ! -f "$BUNDLE/whisper_tiny_encoder.ncnn.param" ]]; then
  echo "bundle not found: $BUNDLE (missing whisper_tiny_encoder.ncnn.param)" >&2
  exit 1
fi
for bin in ncnnllm2int ncnnllm2table; do
  [[ -x "$TOOLS/$bin" ]] || { echo "missing tool $TOOLS/$bin — build ncnn host tools first (see header)"; exit 1; }
done

# Big weight-bearing nets to quantize. embed_token is an Embed layer; ncnnllm2int only
# touches Gemm/MHA weights, so it stays fp16 there (harmless — copied through if unchanged).
NETS=(whisper_tiny_encoder.ncnn whisper_tiny_decoder.ncnn whisper_tiny_proj_out.ncnn whisper_tiny_embed_token.ncnn)
# Passthrough (small or non-quantizable) files.
COPY=(whisper_tiny_fbank.ncnn.param whisper_tiny_fbank.ncnn.bin \
      whisper_tiny_embed_position.ncnn.param whisper_tiny_embed_position.ncnn.bin \
      whisper_vocab.txt)

quant_variant() {
  local bits="$1" block="$2" out="$OUT_ROOT/int${bits}${3:+-$3}"
  mkdir -p "$out"
  echo "=== int$bits block=$block -> $out ==="
  for net in "${NETS[@]}"; do
    "$TOOLS/ncnnllm2int" "$BUNDLE/$net.param" "$BUNDLE/$net.bin" \
      "$out/$net.param" "$out/$net.bin" method=mseclip bits="$bits" block="$block" \
      || { echo "  (ncnnllm2int left $net unchanged — copying fp16)"; cp "$BUNDLE/$net.param" "$BUNDLE/$net.bin" "$out/"; }
    echo "  $net -> $(du -h "$out/$net.bin" | cut -f1)"
  done
  for f in "${COPY[@]}"; do cp "$BUNDLE/$f" "$out/"; done
  echo "  total: $(du -sh "$out" | cut -f1)"
}

echo "fp16 original: $(du -sh "$BUNDLE" | cut -f1)"
quant_variant 8 64          # int8 — safest quality for ASR
quant_variant 4 64          # int4 — smallest

echo ""
echo "Pick a variant, copy it over the assets, and rebuild:"
echo "  cp $OUT_ROOT/int8/* $REPO_ROOT/speech/src/main/assets/whisper-tiny/ && ./install speech"
echo "Whisper quality is sensitive — verify int4 transcription before shipping; int8 is the safer default."
