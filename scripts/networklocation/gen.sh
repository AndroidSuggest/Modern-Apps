#!/usr/bin/env bash
set -euo pipefail
cd /mnt/c/Users/Vayun/Documents/code/Modern-Apps
export GEOCODER_HEAP="${GEOCODER_HEAP:-110g}"
export GEOCODER_INPUT="$HOME/geocoder-scratch/addr.geojsonseq"
export GEOCODER_OUTPUT="$HOME/geocoder-scratch/geocoder.geodb"
echo "GEN START $(date -u)"
echo "  heap=$GEOCODER_HEAP"
echo "  input=$GEOCODER_INPUT ($(stat -c%s "$GEOCODER_INPUT") bytes)"
echo "  output=$GEOCODER_OUTPUT"
rc=0
java -jar gradle/wrapper/gradle-wrapper.jar :networklocation:testDebugUnitTest \
  --tests '*GeocoderGenerator' \
  --rerun-tasks --no-configuration-cache --console=plain || rc=$?
echo "GEN END $(date -u) rc=$rc"
if [ -f "$GEOCODER_OUTPUT" ]; then
  echo "OUTPUT_BYTES $(stat -c%s "$GEOCODER_OUTPUT")"
  echo "OUTPUT_SHA $(sha256sum "$GEOCODER_OUTPUT" | cut -d" " -f1)"
fi
