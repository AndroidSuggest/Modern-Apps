#!/usr/bin/env bash
#
# Build-machine pipeline: OSM planet -> geocoder.geodb -> Cloudflare R2.
#
# Runs the whole geocoder database build end to end:
#   1. Obtain a planet PBF (use --pbf, or download planet-latest.osm.pbf).
#   2. extract-osm-addresses.sh  -> addr.geojsonseq  (osmium tags-filter + export).
#   3. GeocoderGenerator (JVM test) -> geocoder.geodb (packed, mmap'd by the app).
#   4. Print the final byte size.
#   5. Upload to s3://maps/geocoder/geocoder.geodb, served at
#      https://data.vayunmathur.com/geocoder/geocoder.geodb.
#
# This is a HEAVY job: the planet PBF is ~80 GB, extraction needs a few GB of scratch,
# and the generator wants a large heap (GEOCODER_HEAP, default 100g). Run it on the build
# machine, NOT a laptop. Requires: osmium (osmium-tool), a JDK, curl, awscli v2.
#
# Usage:
#   # A) build only, from an already-downloaded planet:
#   ./networklocation/tools/generate-geocoder-db.sh --pbf /data/planet-latest.osm.pbf --skip-upload
#
#   # B) full run (download + build + upload). R2 creds come from the environment:
#   export AWS_ACCESS_KEY_ID=...        # R2 access key id
#   export AWS_SECRET_ACCESS_KEY=...    # R2 secret
#   export R2_ENDPOINT=https://<account>.r2.cloudflarestorage.com
#   ./networklocation/tools/generate-geocoder-db.sh --work /data/geocoder
#
# Options:
#   --pbf PATH         Use an existing planet/region .osm.pbf (skips the download).
#   --planet-url URL   Download source (default: planet-latest.osm.pbf). Download is
#                      resumable (curl -C -).
#   --work DIR         Scratch/output dir (default: a mktemp dir under $TMPDIR).
#   --output PATH      Output .geodb path (default: <work>/geocoder.geodb).
#   --prefix PREFIX    R2 key prefix (default: geocoder/).
#   --heap SIZE        GEOCODER_HEAP passed to Gradle (default: 100g).
#   --skip-upload      Build and size only; do not touch R2.
#   -h, --help         Show this help.
#
# Notes:
# - R2 rejects AWS CLI v2's default CRC64NVME trailing checksum, so we export
#   AWS_REQUEST_CHECKSUM_CALCULATION=when_required (+ the response counterpart), matching how
#   the rest of the repo talks to R2. Bucket is `maps`; region is always `auto`.
# - After upload, pin the printed SHA256 wherever the fetch step verifies the asset.
set -euo pipefail

# --------------------------------------------------------------------------- args
PBF=""
PLANET_URL="https://planet.openstreetmap.org/pbf/planet-latest.osm.pbf"
WORK=""
OUTPUT=""
PREFIX="geocoder/"
HEAP="100g"
SKIP_UPLOAD=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pbf)         PBF="$2"; shift 2;;
    --pbf=*)       PBF="${1#--pbf=}"; shift;;
    --planet-url)  PLANET_URL="$2"; shift 2;;
    --planet-url=*) PLANET_URL="${1#--planet-url=}"; shift;;
    --work)        WORK="$2"; shift 2;;
    --work=*)      WORK="${1#--work=}"; shift;;
    --output)      OUTPUT="$2"; shift 2;;
    --output=*)    OUTPUT="${1#--output=}"; shift;;
    --prefix)      PREFIX="$2"; shift 2;;
    --prefix=*)    PREFIX="${1#--prefix=}"; shift;;
    --heap)        HEAP="$2"; shift 2;;
    --heap=*)      HEAP="${1#--heap=}"; shift;;
    --skip-upload) SKIP_UPLOAD=1; shift;;
    -h|--help)     sed -n '2,60p' "$0"; exit 0;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

[[ "$PREFIX" != */ ]] && PREFIX="$PREFIX/"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
EXTRACT="$SCRIPT_DIR/extract-osm-addresses.sh"

# Scratch/output locations.
if [[ -z "$WORK" ]]; then
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/geocoder.XXXXXX")"
  echo "==> Using scratch dir $WORK"
fi
mkdir -p "$WORK"
GEOJSONSEQ="$WORK/addr.geojsonseq"
[[ -z "$OUTPUT" ]] && OUTPUT="$WORK/geocoder.geodb"
mkdir -p "$(dirname "$OUTPUT")"

# --------------------------------------------------------------------------- deps
need() { command -v "$1" >/dev/null 2>&1 || { echo "missing dependency: $1" >&2; exit 1; }; }
need osmium
need curl
[[ -x "$REPO_ROOT/gradlew" ]] || { echo "gradlew not found at $REPO_ROOT/gradlew" >&2; exit 1; }
[[ -f "$EXTRACT" ]] || { echo "extract script not found: $EXTRACT" >&2; exit 1; }

# --------------------------------------------------------------------------- 1. PBF
if [[ -z "$PBF" ]]; then
  PBF="$WORK/planet-latest.osm.pbf"
  echo "==> [1/5] Downloading planet PBF"
  echo "         from $PLANET_URL"
  echo "         to   $PBF   (this is ~80 GB; resumable with curl -C -)"
  curl -L --fail --retry 5 --retry-delay 30 -C - -o "$PBF" "$PLANET_URL"
else
  echo "==> [1/5] Using existing PBF: $PBF"
fi
[[ -f "$PBF" ]] || { echo "PBF not found: $PBF" >&2; exit 1; }
echo "    PBF size: $(du -h "$PBF" | cut -f1)"

# --------------------------------------------------------------------------- 2. extract
echo "==> [2/5] Extracting addressed objects to GeoJSONSeq"
bash "$EXTRACT" "$PBF" "$GEOJSONSEQ"

# --------------------------------------------------------------------------- 3. generate
echo "==> [3/5] Packing geocoder.geodb (GEOCODER_HEAP=$HEAP)"
(
  cd "$REPO_ROOT"
  GEOCODER_HEAP="$HEAP" ./gradlew :networklocation:testDebugUnitTest \
    --tests '*GeocoderGenerator' \
    --rerun-tasks --console=plain \
    -Dgeocoder.input="$GEOJSONSEQ" \
    -Dgeocoder.output="$OUTPUT"
)
[[ -f "$OUTPUT" ]] || { echo "generator did not produce $OUTPUT" >&2; exit 1; }

# --------------------------------------------------------------------------- 4. size
BYTES="$(stat -c%s "$OUTPUT" 2>/dev/null || stat -f%z "$OUTPUT")"
SHA="$(sha256sum "$OUTPUT" 2>/dev/null | cut -d' ' -f1 || shasum -a 256 "$OUTPUT" | cut -d' ' -f1)"
echo "==> [4/5] Built $OUTPUT"
echo "    size: $BYTES bytes ($(du -h "$OUTPUT" | cut -f1))"
echo "    sha256: $SHA"

# --------------------------------------------------------------------------- 5. upload
if [[ "$SKIP_UPLOAD" -eq 1 ]]; then
  echo "==> [5/5] --skip-upload set; leaving $OUTPUT local. Done."
  exit 0
fi

: "${AWS_ACCESS_KEY_ID:?set AWS_ACCESS_KEY_ID (R2 access key id)}"
: "${AWS_SECRET_ACCESS_KEY:?set AWS_SECRET_ACCESS_KEY (R2 secret)}"
: "${R2_ENDPOINT:?set R2_ENDPOINT=https://<account>.r2.cloudflarestorage.com}"
need aws
if [[ "$R2_ENDPOINT" == *"<account>"* || "$R2_ENDPOINT" == *"<ACCOUNT_ID>"* ]]; then
  echo "R2_ENDPOINT still contains a placeholder: $R2_ENDPOINT" >&2
  exit 1
fi

# R2 compatibility: skip AWS CLI v2's default CRC64NVME trailing checksum, which R2 rejects.
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required
export AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-auto}"

BUCKET="maps"
KEY="${PREFIX}geocoder.geodb"
echo "==> [5/5] Uploading to s3://$BUCKET/$KEY"
aws s3 cp "$OUTPUT" "s3://$BUCKET/$KEY" \
  --endpoint-url "$R2_ENDPOINT" \
  --content-type "application/octet-stream" \
  --cache-control "public, max-age=31536000, immutable" \
  --expected-size "$BYTES"

echo "--- head-object s3://$BUCKET/$KEY ---"
aws s3api head-object --bucket "$BUCKET" --key "$KEY" --endpoint-url "$R2_ENDPOINT"

echo ""
echo "Uploaded: https://data.vayunmathur.com/$KEY"
echo "  bytes:  $BYTES"
echo "  sha256: $SHA"
echo "Pin this sha256 wherever the app's fetch step verifies the geocoder asset."
