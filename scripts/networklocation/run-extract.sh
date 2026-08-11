#!/usr/bin/env bash
set -euo pipefail
cd /mnt/c/Users/Vayun/Documents/code/Modern-Apps
OUT="$HOME/geocoder-scratch/addr.geojsonseq"
PBF="$HOME/geocoder-scratch/planet-latest.osm.pbf"
echo "START $(date -u) -> $OUT"
bash networklocation/tools/extract-osm-addresses.sh "$PBF" "$OUT"
echo "END $(date -u)"
echo "SIZE $(stat -c%s "$OUT") bytes"
echo "LINES $(wc -l < "$OUT")"
