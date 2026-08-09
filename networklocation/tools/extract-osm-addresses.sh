#!/usr/bin/env bash
#
# Extract every addressed object from an OSM planet (or region) extract into a GeoJSONSeq file
# that GeocoderGenerator packs into geocoder.geodb.
#
#   ./extract-osm-addresses.sh /path/planet.osm.pbf /path/addr.geojsonseq
#
# Needs `osmium` (osmium-tool). On the planet this takes a while and ~a few GB of scratch.
# Try a country/region extract first to validate the pipeline before the full planet.
set -euo pipefail

IN="${1:?usage: extract-osm-addresses.sh <planet.osm.pbf> <out.geojsonseq>}"
OUT="${2:?usage: extract-osm-addresses.sh <planet.osm.pbf> <out.geojsonseq>}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> Filtering objects that carry addr:housenumber (nodes, ways, relations)…"
# Keep any object with a house number OR a street; -R keeps referenced nodes so way/area
# geometries can be assembled for centroids.
osmium tags-filter "$IN" \
    n/addr:housenumber w/addr:housenumber r/addr:housenumber \
    n/addr:street w/addr:street r/addr:street \
    -o "$TMP/addr.osm.pbf"

echo "==> Exporting to GeoJSONSeq (one Feature per line)…"
# --add-unique-id gives stable ids; areas/ways are exported with their geometry and the
# generator averages coordinates to a representative point.
osmium export "$TMP/addr.osm.pbf" \
    -f geojsonseq \
    --geometry-types=point,linestring,polygon \
    -o "$OUT" \
    --overwrite

echo "==> Done: $OUT ($(du -h "$OUT" | cut -f1))"
echo "Next: GEOCODER_HEAP=100g ./gradlew :networklocation:testDebugUnitTest --tests '*GeocoderGenerator' \\"
echo "      -Dgeocoder.input=$OUT -Dgeocoder.output=networklocation/src/main/assets/geocoder.geodb"
