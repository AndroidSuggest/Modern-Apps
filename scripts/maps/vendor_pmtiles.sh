#!/bin/bash
set -euo pipefail

# vendor_pmtiles.sh — upload Protomaps v4.pmtiles (137 GB) to R2 bucket `maps`
#
# Final URL after upload: https://data.vayunmathur.com/v4.pmtiles
# Direct R2 custom domain; no Rust server proxy — Range requests served natively.
#
# Usage:
#   R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com \
#   AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... \
#   ./scripts/maps/vendor_pmtiles.sh [--source URL] [--local FILE] [--dry-run]
#
# Options:
#   --source URL   Override SOURCE_URL (default demo-bucket)
#   --local FILE   Use an already-downloaded local file as source (skips download)
#   --dry-run      Log what would happen, don't upload
#   --rclone       Use rclone copyurl streaming instead of aws-cli (useful when
#                  local disk < 137 GB, streams without storing full file)
#
# Env:
#   R2_ENDPOINT           R2 S3 API endpoint, e.g. https://<id>.r2.cloudflarestorage.com
#   R2_BUCKET             Default: maps   (custom domain data.vayunmathur.com)
#   R2_KEY                Default: v4.pmtiles
#   AWS_ACCESS_KEY_ID / R2_ACCESS_KEY_ID
#   AWS_SECRET_ACCESS_KEY / R2_SECRET_ACCESS_KEY
#   AWS_PROFILE           Optional profile name (default uses env creds)
#
# Cost: R2 storage ~$0.015/GB/mo → ~$2/mo for v4 + free egress via custom domain.

SOURCE_URL="https://demo-bucket.protomaps.com/v4.pmtiles"
UPSTREAM_SIZE=137048544411
R2_ENDPOINT="${R2_ENDPOINT:-https://<ACCOUNT_ID>.r2.cloudflarestorage.com}"
R2_BUCKET="${R2_BUCKET:-maps}"
R2_KEY="${R2_KEY:-v4.pmtiles}"
LOCAL_FILE=""
USE_RCLONE=0
DRY_RUN=0
TMP_FILE=""

cleanup() {
    if [[ -n "$TMP_FILE" && -f "$TMP_FILE" && "$USE_RCLONE" == "0" ]]; then
        # Only remove tmp if we created it ourselves for a non-local-source download.
        # If LOCAL_FILE was provided we did not create TMP_FILE.
        :
    fi
}
trap cleanup EXIT

# --- Arg parsing ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --source)  SOURCE_URL="$2"; shift 2 ;;
        --local)   LOCAL_FILE="$2"; shift 2 ;;
        --rclone)  USE_RCLONE=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help)
            sed -n '2,40p' "$0" | sed 's/^# \?//'
            exit 0
            ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

if [[ -n "$LOCAL_FILE" && ! -f "$LOCAL_FILE" ]]; then
    echo "ERROR: --local file not found: $LOCAL_FILE" >&2
    exit 1
fi

# Normalize R2 credential env names (support both naming schemes)
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-${R2_ACCESS_KEY_ID:-}}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-${R2_SECRET_ACCESS_KEY:-}}"

if [[ -z "$AWS_ACCESS_KEY_ID" || -z "$AWS_SECRET_ACCESS_KEY" ]]; then
    echo "ERROR: R2 credentials missing. Set AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY" >&2
    echo "       or R2_ACCESS_KEY_ID + R2_SECRET_ACCESS_KEY + R2_ENDPOINT." >&2
    exit 1
fi

if [[ "$R2_ENDPOINT" == *"<ACCOUNT_ID>"* ]]; then
    echo "ERROR: R2_ENDPOINT still contains placeholder. Set real endpoint:" >&2
    echo "       export R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com" >&2
    exit 1
fi

AWS_ARGS=(--endpoint-url "$R2_ENDPOINT")
if [[ -n "${AWS_PROFILE:-}" ]]; then
    AWS_ARGS+=(--profile "$AWS_PROFILE")
fi

echo "=== vendor_pmtiles.sh ==="
echo "SOURCE_URL = $SOURCE_URL"
echo "R2_ENDPOINT = $R2_ENDPOINT"
echo "R2_BUCKET   = $R2_BUCKET"
echo "R2_KEY      = $R2_KEY"
[[ -n "$LOCAL_FILE" ]] && echo "LOCAL_FILE  = $LOCAL_FILE"
echo "UPSTREAM_SIZE (expected) = $UPSTREAM_SIZE bytes (HEAD from demo-bucket)"

if [[ "$DRY_RUN" == "1" ]]; then
    echo "[dry-run] Would upload to s3://$R2_BUCKET/$R2_KEY"
    exit 0
fi

# --- Path 1: rclone streaming (no local 137 GB file) ---
if [[ "$USE_RCLONE" == "1" ]]; then
    if ! command -v rclone &>/dev/null; then
        echo "ERROR: rclone not found but --rclone requested. Install: https://rclone.org/install/" >&2
        exit 1
    fi
    # rclone copyurl streams source directly to R2 via multipart, no local file.
    # r2 remote must be configured or we pass explicit S3 backend flags.
    echo "[rclone] Streaming $SOURCE_URL -> R2 s3://$R2_BUCKET/$R2_KEY"
    echo "         This may take hours on residential uplink."
    # Use :s3: backend with all params via flags — no rclone.conf needed.
    rclone copyurl "$SOURCE_URL" ":s3:$R2_BUCKET/$R2_KEY" \
        --s3-provider Cloudflare \
        --s3-endpoint "$R2_ENDPOINT" \
        --s3-access-key-id "$AWS_ACCESS_KEY_ID" \
        --s3-secret-access-key "$AWS_SECRET_ACCESS_KEY" \
        --s3-region auto \
        --s3-upload-cutoff 100M \
        --s3-chunk-size 100M \
        --s3-no-check-bucket \
        --header "Cache-Control: public, max-age=31536000, immutable" \
        --header "Content-Type: application/octet-stream" \
        --progress
else
    # --- Path 2: aws s3 cp (with auto-multipart) ---
    if ! command -v aws &>/dev/null; then
        echo "ERROR: aws CLI not found. Install aws-cli v2 or use --rclone." >&2
        echo "  https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html" >&2
        exit 1
    fi

    if [[ -n "$LOCAL_FILE" ]]; then
        SRC_PATH="$LOCAL_FILE"
        echo "[aws] Uploading local file $SRC_PATH (${LOCAL_FILE}) -> s3://$R2_BUCKET/$R2_KEY"
    else
        # Download to tmp if no local file and not rclone path.
        # Note: needs 137 GB free. Warn if insufficient.
        FREE_KB=$(df -k . | awk 'NR==2{print $4}')
        FREE_BYTES=$((FREE_KB * 1024))
        echo "Free disk in $(pwd): $((FREE_BYTES / 1024 / 1024)) MB"
        if [[ "$FREE_BYTES" -lt $((UPSTREAM_SIZE + 5 * 1024 * 1024 * 1024)) ]]; then
            echo "WARNING: Possibly insufficient disk for $UPSTREAM_SIZE byte download." >&2
            echo "         Prefer running on prod server with high bandwidth + large disk," >&2
            echo "         or use --rclone for direct streaming upload, or --local with a" >&2
            echo "         pre-downloaded file." >&2
            read -rp "Continue anyway? [y/N] " ANS
            [[ "$ANS" == "y" || "$ANS" == "Y" ]] || exit 1
        fi
        TMP_FILE="./v4.pmtiles.download"
        SRC_PATH="$TMP_FILE"
        if [[ -f "$TMP_FILE" ]]; then
            echo "Found existing $TMP_FILE — will attempt resume with curl -C -."
            if command -v curl &>/dev/null; then
                curl -L -C - -o "$TMP_FILE" "$SOURCE_URL"
            else
                wget -c -O "$TMP_FILE" "$SOURCE_URL"
            fi
        else
            echo "Downloading $SOURCE_URL -> $TMP_FILE (137 GB, will take hours) ..."
            if command -v curl &>/dev/null; then
                curl -L -o "$TMP_FILE" "$SOURCE_URL"
            else
                wget -O "$TMP_FILE" "$SOURCE_URL"
            fi
        fi
        ACTUAL_SIZE=$(stat -f%z "$TMP_FILE" 2>/dev/null || stat -c%s "$TMP_FILE" 2>/dev/null || echo 0)
        echo "Downloaded size: $ACTUAL_SIZE bytes (expected $UPSTREAM_SIZE)"
        if [[ "$ACTUAL_SIZE" != "$UPSTREAM_SIZE" ]]; then
            echo "WARNING: size mismatch — upstream may have changed. Continuing anyway." >&2
        fi
    fi

    echo "[aws] Multipart upload s3://$R2_BUCKET/$R2_KEY"
    echo "      aws s3 cp handles multipart automatically for files >8 MB."
    aws s3 cp "$SRC_PATH" "s3://$R2_BUCKET/$R2_KEY" \
        "${AWS_ARGS[@]}" \
        --content-type "application/octet-stream" \
        --cache-control "public, max-age=31536000, immutable" \
        --expected-size "$UPSTREAM_SIZE" \
        --only-show-errors 2>&1 || \
    aws s3 cp "$SRC_PATH" "s3://$R2_BUCKET/$R2_KEY" \
        "${AWS_ARGS[@]}" \
        --content-type "application/octet-stream" \
        --cache-control "public, max-age=31536000, immutable" 2>&1

    if [[ -z "$LOCAL_FILE" && -n "$TMP_FILE" && -f "$TMP_FILE" ]]; then
        read -rp "Upload finished. Delete local $TMP_FILE ? [y/N] " DEL
        if [[ "$DEL" == "y" || "$DEL" == "Y" ]]; then
            rm -f "$TMP_FILE"
            echo "Deleted $TMP_FILE"
        fi
    fi
fi

echo ""
echo "=== Verification ==="
echo "1. Head object:"

aws s3api head-object --bucket "$R2_BUCKET" --key "$R2_KEY" "${AWS_ARGS[@]}" || \
    echo "   (head-object failed — check credentials/bucket)"

echo ""
echo "2. Range-request check (requires public custom domain data.vayunmathur.com):"
set +e
if command -v curl &>/dev/null; then
    echo "   curl -I https://data.vayunmathur.com/$R2_KEY"
    curl -sI "https://data.vayunmathur.com/$R2_KEY" | head -n 20
    echo ""
    echo "   curl -r 0-1023 https://data.vayunmathur.com/$R2_KEY | hexdump (magic should be PMTiles)"
    curl -s -r 0-1023 "https://data.vayunmathur.com/$R2_KEY" | head -c 10 | od -c || true
    echo ""
    echo "   Expect: P M T i l e s 3 magic at offset 0."
fi
set -e

echo ""
echo "Done. Final public URL: https://data.vayunmathur.com/$R2_KEY"
echo "MapTileCache.BASEMAP_PMTILES_URL now points here; style.json protomaps/protomapsOnline too."
