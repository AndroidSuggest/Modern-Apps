#!/usr/bin/env bash
# Quantizes SMaLL-100 ncnn bundle via ncnn weight-only block quantization.
#
# Inputs:  fp16 bundle dir from `/tmp/small100/bundle/upload/` or R2 download
#          containing encoder.ncnn.{param,bin} + decoder.ncnn.{param,bin}
# Outputs: quantized bundle under scripts/translate/quantized/int4/ and int8/
#
# Requires host tools built via:
#   cmake -DNCNN_BUILD_TOOLS=ON -DNCNN_WEIGHT_QUANT=ON -DNCNN_INT8=ON ... \
#   && make ncnnllm2table ncnnllm2int -j
#
# Usage:
#   ./scripts/translate/quantize_small100.sh [bundle_dir] [out_root]
#   bundle_dir defaults to $HOME/.claude/jobs/.../bundle/upload if present,
#   else scripts/translate/bundle (user-provided).
#   out_root defaults to scripts/translate/quantized
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Resolve input bundle: arg > canonical job output > fallback
DEFAULT_BUNDLE="/Users/vayun/.claude/jobs/1dfd8c6b/tmp/small100/bundle/upload"
BUNDLE="${1:-}"
if [[ -z "$BUNDLE" ]]; then
  if [[ -d "$DEFAULT_BUNDLE" ]]; then
    BUNDLE="$DEFAULT_BUNDLE"
  else
    BUNDLE="$SCRIPT_DIR/bundle"
  fi
fi

OUT_ROOT="${2:-$SCRIPT_DIR/quantized}"
TOOLS="/Users/vayun/Documents/ncnn-android/build-host/tools/quantize"

if [[ ! -f "$BUNDLE/encoder.ncnn.param" ]]; then
  echo "bundle not found: $BUNDLE does not contain encoder.ncnn.param" >&2
  exit 1
fi
for bin in ncnnllm2int ncnnllm2table; do
  if [[ ! -x "$TOOLS/$bin" ]]; then
    echo "missing tool $TOOLS/$bin — build host tools first:" >&2
    echo "  mkdir -p /tmp/ncnn-host && cd /tmp/ncnn-host && cmake -DNCNN_BUILD_TOOLS=ON -DNCNN_WEIGHT_QUANT=ON -DNCNN_INT8=ON -DNCNN_BUILD_EXAMPLES=OFF ... <ncnn-android-root> && make ncnnllm2int ncnnllm2table -j" >&2
    exit 1
  fi
done

mkdir -p "$OUT_ROOT/int4" "$OUT_ROOT/int8" "$OUT_ROOT/int4-128"

echo "=== Original sizes ==="
ls -lh "$BUNDLE"/encoder.ncnn.bin "$BUNDLE"/decoder.ncnn.bin
du -sh "$BUNDLE"

quant_one() {
  local bits="$1" block="$2" method="$3" in_param="$4" in_bin="$5" out_param="$6" out_bin="$7"
  echo ""
  echo "--- Quantizing $(basename "$in_param") bits=$bits block=$block method=$method ---"
  "$TOOLS/ncnnllm2int" "$in_param" "$in_bin" "$out_param" "$out_bin" method="$method" bits="$bits" block="$block"
  echo " -> $(du -h "$out_bin" | cut -f1) $out_bin"
}

# int4 mseclip block=64 — best size/quality without calibration data (auto-dequant in ncnn with NCNN_WEIGHT_QUANT=ON)
quant_one 4 64 mseclip "$BUNDLE/encoder.ncnn.param" "$BUNDLE/encoder.ncnn.bin" "$OUT_ROOT/int4/encoder.ncnn.param" "$OUT_ROOT/int4/encoder.ncnn.bin"
quant_one 4 64 mseclip "$BUNDLE/decoder.ncnn.param" "$BUNDLE/decoder.ncnn.bin" "$OUT_ROOT/int4/decoder.ncnn.param" "$OUT_ROOT/int4/decoder.ncnn.bin"
# copy supporting files (fp32/fp16, not quantized)
cp -v "$BUNDLE/sentencepiece.bpe.model" "$BUNDLE/vocab.txt" "$BUNDLE/pos_weights.f32.bin" "$OUT_ROOT/int4/"

# int4 mseclip block=128 — smaller scales overhead
quant_one 4 128 mseclip "$BUNDLE/encoder.ncnn.param" "$BUNDLE/encoder.ncnn.bin" "$OUT_ROOT/int4-128/encoder.ncnn.param" "$OUT_ROOT/int4-128/encoder.ncnn.bin"
quant_one 4 128 mseclip "$BUNDLE/decoder.ncnn.param" "$BUNDLE/decoder.ncnn.bin" "$OUT_ROOT/int4-128/decoder.ncnn.param" "$OUT_ROOT/int4-128/decoder.ncnn.bin"
cp -v "$BUNDLE/sentencepiece.bpe.model" "$BUNDLE/vocab.txt" "$BUNDLE/pos_weights.f32.bin" "$OUT_ROOT/int4-128/"

# int8 mseclip block=64 — quality fallback
quant_one 8 64 mseclip "$BUNDLE/encoder.ncnn.param" "$BUNDLE/encoder.ncnn.bin" "$OUT_ROOT/int8/encoder.ncnn.param" "$OUT_ROOT/int8/encoder.ncnn.bin"
quant_one 8 64 mseclip "$BUNDLE/decoder.ncnn.param" "$BUNDLE/decoder.ncnn.bin" "$OUT_ROOT/int8/decoder.ncnn.param" "$OUT_ROOT/int8/decoder.ncnn.bin"
cp -v "$BUNDLE/sentencepiece.bpe.model" "$BUNDLE/vocab.txt" "$BUNDLE/pos_weights.f32.bin" "$OUT_ROOT/int8/"

echo ""
echo "=== Final size report ==="
echo "fp16 original: $(du -sh "$BUNDLE" | cut -f1) ($(ls -lh "$BUNDLE"/encoder.ncnn.bin "$BUNDLE"/decoder.ncnn.bin | awk '{print $5}' | paste -sd+ -))"
echo "int4 b64:      $(du -sh "$OUT_ROOT/int4" | cut -f1)  encoder=$(du -h "$OUT_ROOT/int4/encoder.ncnn.bin" | cut -f1) decoder=$(du -h "$OUT_ROOT/int4/decoder.ncnn.bin" | cut -f1)"
echo "int4 b128:     $(du -sh "$OUT_ROOT/int4-128" | cut -f1)  encoder=$(du -h "$OUT_ROOT/int4-128/encoder.ncnn.bin" | cut -f1) decoder=$(du -h "$OUT_ROOT/int4-128/decoder.ncnn.bin" | cut -f1)"
echo "int8 b64:      $(du -sh "$OUT_ROOT/int8" | cut -f1)  encoder=$(du -h "$OUT_ROOT/int8/encoder.ncnn.bin" | cut -f1) decoder=$(du -h "$OUT_ROOT/int8/decoder.ncnn.bin" | cut -f1)"
echo ""
echo "Quantized params embed Embed still fp16 (131M * fp16 = 262M per net); only Gemm+MHA quantized."
echo "Compat: ncnn loader auto-dequantizes if built with -DNCNN_WEIGHT_QUANT=ON (AAR: src/main/cpp/CMakeLists.txt)."
echo ""
echo "Next: parity / BLEU validation via scripts/translate/quant_check.py and upload via upload_small100_r2.sh:"
echo "  ./scripts/translate/upload_small100_r2.sh $OUT_ROOT/int4 --prefix small100-int4/"
