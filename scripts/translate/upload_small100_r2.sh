#!/usr/bin/env bash
# Uploads the Small100 ncnn bundle (fp16 or int4 quantized) to R2 bucket `maps`,
# served at https://data.vayunmathur.com/models/small100/ (or subpath).
#
# Credentials are read from the environment (never hardcoded). Run from trusted shell:
#
#   export AWS_ACCESS_KEY_ID=...        # R2 access key id
#   export AWS_SECRET_ACCESS_KEY=...    # R2 secret
#   export R2_ENDPOINT=https://<account>.r2.cloudflarestorage.com
#   ./scripts/translate/upload_small100_r2.sh <bundle_dir> [--prefix small100/]
#   # int4 quantized:
#   ./scripts/translate/upload_small100_r2.sh scripts/translate/quantized/int4
#
# <bundle_dir> must contain: encoder.ncnn.{param,bin}, decoder.ncnn.{param,bin},
# sentencepiece.bpe.model, vocab.txt, pos_weights.f32.bin
#
# Quantization notes:
# - int4 weight-only block quant (ncnnllm): 680 MB vs 1.14 GB fp16, term 401=bits4 b64 mseclip
# - Embed stays fp16 (131M*fp16=262MB per net); Gemm+MHA quantized (36+13 layers)
# - AAR must be built with -DNCNN_WEIGHT_QUANT=ON (src/main/cpp/CMakeLists.txt + build.gradle.kts)
# - After upload, update Small100Model.kt SHA256 via: shasum -a 256 bundle/* | cut -d' ' -f1
set -euo pipefail

BUNDLE=""
PREFIX="small100/"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) PREFIX="$2"; shift 2;;
    --prefix=*) PREFIX="${1#--prefix=}"; shift;;
    -h|--help)
      echo "usage: upload_small100_r2.sh <bundle_dir> [--prefix small100/]"
      echo "  default prefix small100/ (int4 replaces current); use small100-int4/ for side-by-side"
      exit 0;;
    *) BUNDLE="$1"; shift;;
  esac
done

if [[ -z "$BUNDLE" ]]; then
  echo "usage: upload_small100_r2.sh <bundle_dir> [--prefix small100/]" >&2
  exit 1
fi

: "${AWS_ACCESS_KEY_ID:?set AWS_ACCESS_KEY_ID}"
: "${AWS_SECRET_ACCESS_KEY:?set AWS_SECRET_ACCESS_KEY}"
: "${R2_ENDPOINT:?set R2_ENDPOINT}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}"

# Validate
for f in encoder.ncnn.param encoder.ncnn.bin decoder.ncnn.param decoder.ncnn.bin sentencepiece.bpe.model vocab.txt pos_weights.f32.bin; do
  if [[ ! -f "$BUNDLE/$f" ]]; then
    echo "missing $BUNDLE/$f" >&2
    exit 1
  fi
done

echo "=== Local bundle $BUNDLE (upload to s3://maps/models/$PREFIX) ==="
ls -lh "$BUNDLE"
echo "SHA256:"
for f in encoder.ncnn.param encoder.ncnn.bin decoder.ncnn.param decoder.ncnn.bin; do
  echo "  $f $(shasum -a 256 "$BUNDLE/$f" | cut -d' ' -f1)"
done

# Normalize prefix to end with /
[[ "$PREFIX" != */ ]] && PREFIX="$PREFIX/"

aws s3 sync "$BUNDLE" "s3://maps/models/$PREFIX" \
  --endpoint-url "$R2_ENDPOINT" --checksum-algorithm CRC32

echo "--- uploaded objects s3://maps/models/$PREFIX ---"
aws s3 ls "s3://maps/models/$PREFIX" --endpoint-url "$R2_ENDPOINT"

echo ""
echo "If you replaced small100/ with int4, also update Small100Model.kt FILES SHA256:"
for f in encoder.ncnn.param encoder.ncnn.bin decoder.ncnn.param decoder.ncnn.bin sentencepiece.bpe.model vocab.txt pos_weights.f32.bin; do
  printf '  %s: %s\n' "$f" "$(shasum -a 256 "$BUNDLE/$f" | cut -d' ' -f1)"
done
