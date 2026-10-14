"""Upload any local file to R2 as planet2.mamaps (bucket: maps).

Usage: python upload_planet.py <local-file>
Verifies server-side size matches, then deletes nothing local. Progress
prints every ~1 GB.

Serves https://data.vayunmathur.com/planet2.mamaps, which the apps'
basemap URLs point at (library/map urls.rs, maps MapTileCache.kt).

SECURITY: credentials/config come from ENVIRONMENT VARIABLES, never baked
into this file (the analysis/ original carried hardcoded keys and must
stay gitignored there). Never paste keys on the command line — `export`
them instead, ideally from a secret store.

Required env:
  R2_ENDPOINT           S3 API endpoint, e.g. https://<ACCOUNT_ID>.r2.cloudflarestorage.com
  R2_ACCESS_KEY_ID      R2 access key id
  R2_SECRET_ACCESS_KEY  R2 secret access key
Optional env:
  R2_BUCKET             Default: maps
"""
import os
import sys
import threading

import boto3
from boto3.s3.transfer import TransferConfig

BUCKET = os.environ.get("R2_BUCKET", "maps")
KEY = "planet2.mamaps"

PART_SIZE = 100 * 1024 * 1024  # 100 MB parts, 10-way concurrency


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {os.path.basename(sys.argv[0])} <local-file>", flush=True)
        return 2
    missing = [v for v in ("R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY")
               if not os.environ.get(v)]
    if missing:
        print(f"missing env: {', '.join(missing)} (see module docstring)", flush=True)
        return 2
    src = sys.argv[1]
    if not os.path.isfile(src):
        print(f"no such file: {src}", flush=True)
        return 2
    size = os.path.getsize(src)
    print(f"uploading {src} ({size / 1e9:.1f} GB) to s3://{BUCKET}/{KEY}", flush=True)

    s3 = boto3.client(
        "s3",
        endpoint_url=os.environ["R2_ENDPOINT"],
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
    )
    config = TransferConfig(
        multipart_threshold=PART_SIZE,
        multipart_chunksize=PART_SIZE,
        max_concurrency=10,
        use_threads=True,
    )

    lock = threading.Lock()
    done = [0]
    last_mark = [0]

    def progress(n: int) -> None:
        with lock:
            done[0] += n
            gb = done[0] / 1e9
            if gb - last_mark[0] >= 1.0 or done[0] >= size:
                last_mark[0] = gb
                pct = 100.0 * done[0] / size
                print(f"  {gb:.1f}/{size / 1e9:.1f} GB ({pct:.1f}%)", flush=True)

    try:
        s3.upload_file(src, BUCKET, KEY, Config=config, Callback=progress)
    except Exception as e:
        print(f"UPLOAD FAILED: {e}", flush=True)
        return 1

    remote = s3.head_object(Bucket=BUCKET, Key=KEY)["ContentLength"]
    print(f"done. remote size {remote} bytes, local {size} bytes", flush=True)
    return 0 if remote == size else 2


if __name__ == "__main__":
    sys.exit(main())
