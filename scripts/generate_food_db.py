#!/usr/bin/env python3
"""Offline generator: Open Food Facts CSV export -> health/src/main/assets/food.bin.br

The health app's recipe builder searches food entirely on-device. This builds
the data it searches, as a compressed asset compiled into the APK, so the app
never calls a nutrition API at runtime.

The asset is a flat columnar file, not a SQLite database. The app rebuilds the
database from it on first launch (a few seconds), which is worth doing because
a shipped SQLite file carries a great deal that is either derivable or pure
container: the FTS index alone was 4.5 MB compressed, and page structure,
record headers and B-tree pointers another 3.7 MB. Measured over the real
export, the same 465,000 products ship in 10.9 MB this way against 19.1 MB as
a SQLite file with its index.

Source data
-----------
Open Food Facts publishes a full CSV export, rebuilt daily:

    https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz

It is ~1.2 GB gzipped / ~10 GB expanded, tab-delimited despite the `.csv` name,
and carries ~200 columns (see `location_share_server/headers.txt`). This script
streams it straight out of the gzip, so the expanded TSV is never written to
disk. The download is cached in ~/.cache/openfoodfacts and resumed on rerun.

Open Food Facts data is ODbL-licensed: attribution and share-alike are required
wherever the app ships this database.

Size
----
The export is far too big to ship as-is, so three things shrink it, none of
which lose anything the app can display:

  1. Only `product_name`, `brands` and the 41 nutrients the app renders are
     kept. `code`, `ingredients_text` and ~150 other columns are dropped.
  2. Products reporting no nutrients at all are dropped entirely - about half
     the export.
  3. Nutrients live in one sparse BLOB rather than 41 REAL columns. Only 8.8
     of the 41 are populated on an average product, but a 41-column row pays
     44 bytes of record header whether or not the values are there, plus 8
     bytes for every REAL it does store. Measured on the full export that was
     93 MB of header and 149 MB of values; the blob replaces both with ~26
     bytes a row. This is by far the largest saving.
  4. Near-duplicate products are collapsed. Open Food Facts holds many
     separate entries for the same thing - only 83% of products with nutrition
     are distinct on (name, brands) - and showing a user five identical
     "Whole milk" rows is a bug as much as it is wasted space.
The app builds its FTS index with `detail=none`, dropping every token position
while keeping the per-row size table. `bm25()` still works and, measured over
real queries, returns the identical ranking: product names are too short for a
term to occur twice, so term frequency is always 1 and positions tell bm25
nothing. The length normalisation it does use lives in the size table, which is
retained. What `detail=none` costs is phrase queries, which the app never
issues - see `escape_fts_query`, which emits one token per quoted term
precisely so it never needs them.

The asset is brotli-compressed rather than gzipped: 26% smaller on this data
for no loss, and the app already ships a brotli decoder.

Asset format
------------
Little-endian throughout. A 29-byte header, then five sections laid out one
after another - columnar, not row-interleaved, which costs nothing to write
and is 1.2 MB smaller compressed because it puts like bytes next to like:

    magic       4   b"FDB1"
    version     1   FORMAT_VERSION
    rowCount    4   uint32
    idsLen      4   uint32     } byte length of each section, so a reader can
    scoresLen   4   uint32     } bounds-check and a truncated asset fails
    namesLen    4   uint32     } loudly instead of decoding into nonsense
    brandsLen   4   uint32
    blobsLen    4   uint32

    ids         rowCount LEB128 varints, delta-encoded (ids ascend, so the
                deltas are almost all tiny)
    scores      rowCount bytes, one per product
    names       rowCount x (LEB128 byte length, then UTF-8)
    brands      rowCount x (LEB128 byte length, then UTF-8)
    blobs       rowCount x (LEB128 byte length, then the nutrient blob)

Score
-----
One byte per product, supplementing bm25 rather than replacing it:

    bit 7    set when the product reports any of protein/carbs/fat/energy
    bits 0-4 floor(log2(scans + 1)), capped at 31

The app sorts on the macro bit first, then bm25 relevance, then this score as a
popularity tie-break. Popularity is a coarse log tier rather than a raw count
so it only separates products bm25 already considers comparable. The 128
boundary is load-bearing: the app tests `score >= 128`.

`MAX_PRODUCTS` optionally caps the result to the most-scanned products, which
is the lever to reach a given APK budget - see the constant below.

Blob format
-----------
    bytes 0-5   little-endian bitmap, one bit per NUTRIENTS slot, set when the
                product reports that nutrient
    then        one LEB128 varint per set bit, in ascending slot order:
                    (mantissa << 4) | exponent,  value = mantissa * 10^-exponent

Nutrients span nine orders of magnitude - 900 kcal down to 20 ug of vitamin D
per 100 g - so no single scale factor covers them; a per-nutrient scale chosen
for typical values silently rounds that nutrient's smaller readings to zero.
A per-value decimal exponent is the only encoding measured that stays bounded
(<=0.5% by construction, 0.017% median) across the whole range.

It is also the smallest, because it happens to suit both the data and the
compressor. Nutrition labels carry two or three significant digits, so
stripping trailing zeros leaves mantissas of 1-3 digits that repeat heavily
across products - 34, 100, 250 - which brotli models far better than float16's
effectively random mantissa bits. Measured over the real export it is 5.88 MB
compressed against float16's 6.79 MB, and 11.80 MB raw against 14.93 MB.

float16 was the previous encoding. Besides compressing worse it silently lost
values below its ~6e-8 subnormal floor: 0.51% of all readings, mostly trace
micronutrients, were stored as 0.0 and became indistinguishable from absent.

Product ids
-----------
Ids must stay identical to the ones `location_share_server/build_db.py` assigns,
because two things already depend on them: `Ingredient` rows saved on users'
devices store the product id, and the `/api/food/*` endpoints kept for older
app builds resolve that same id. `build_db.py` inserts every CSV row without an
explicit id, so SQLite assigns rowids 1..N in file order. This script therefore
numbers rows the same way - counting *every* row, including the ones it drops -
and inserts kept rows under their original ordinal.

Usage
-----
    python3 scripts/generate_food_db.py [--csv PATH] [--max-products N] [--keep-db]
"""

import argparse
import csv
import gzip
import json
import os
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

CSV_URL = "https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz"
CACHE_DIR = Path.home() / ".cache" / "openfoodfacts"
CACHED_CSV = CACHE_DIR / "en.openfoodfacts.org.products.csv.gz"

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSET_DIR = REPO_ROOT / "health" / "src" / "main" / "assets"
ASSET_BIN = ASSET_DIR / "food.bin.br"
ASSET_META = ASSET_DIR / "food.bin.meta.json"

MAGIC = b"FDB1"

# Keep only the N most-scanned products, or None for every product with
# nutrition data. This is the APK-size dial: Open Food Facts is dominated by
# products that are scanned once and never again, so a cap here cuts the asset
# hard while barely touching what a real search finds.
#
# Be aware of what the cut selects for. Open Food Facts began in France and its
# scan counts still lean that way, so a popularity cap pulls the database
# towards European - especially French - products, and away from the long tail
# of regional ones everywhere.
MAX_PRODUCTS = 465_000

# Bumped in lockstep with FoodDatabase.SUPPORTED_SCHEMA_VERSION in the app.
# 2: nutrients moved into a sparse blob; bm25 replaced by a stored score.
# 3: FTS index restored to detail=full so the app can rank with bm25 again.
# 4: detail=none (bm25 is unaffected by it) and brotli instead of gzip.
# 5: nutrient blob switched from float16 to a presence bitmap plus per-value
#    decimal floats - smaller, better compressed, and no subnormal underflow.
# 6: asset is a columnar binary the app turns into SQLite, not a SQLite file.
SCHEMA_VERSION = 6

BATCH_SIZE = 50_000

# (database column, CSV header). Order matches NutritionData's declaration
# order in the app and the SELECT in the server's handlers/food.rs - the app
# reads these positionally.
NUTRIENTS = [
    ("proteins_100g", "proteins_100g"),
    ("carbohydrates_100g", "carbohydrates_100g"),
    ("fat_100g", "fat_100g"),
    ("fiber_100g", "fiber_100g"),
    ("sugars_100g", "sugars_100g"),
    ("sodium_100g", "sodium_100g"),
    ("biotin_100g", "biotin_100g"),
    ("caffeine_100g", "caffeine_100g"),
    ("calcium_100g", "calcium_100g"),
    ("chloride_100g", "chloride_100g"),
    ("cholesterol_100g", "cholesterol_100g"),
    ("chromium_100g", "chromium_100g"),
    ("copper_100g", "copper_100g"),
    ("folates_100g", "folates_100g"),
    ("vitamin_b9_100g", "vitamin-b9_100g"),
    ("iodine_100g", "iodine_100g"),
    ("iron_100g", "iron_100g"),
    ("magnesium_100g", "magnesium_100g"),
    ("manganese_100g", "manganese_100g"),
    ("molybdenum_100g", "molybdenum_100g"),
    ("monounsaturated_fat_100g", "monounsaturated-fat_100g"),
    ("vitamin_pp_100g", "vitamin-pp_100g"),
    ("pantothenic_acid_100g", "pantothenic-acid_100g"),
    ("phosphorus_100g", "phosphorus_100g"),
    ("polyunsaturated_fat_100g", "polyunsaturated-fat_100g"),
    ("potassium_100g", "potassium_100g"),
    ("vitamin_b2_100g", "vitamin-b2_100g"),
    ("saturated_fat_100g", "saturated-fat_100g"),
    ("selenium_100g", "selenium_100g"),
    ("vitamin_b1_100g", "vitamin-b1_100g"),
    ("trans_fat_100g", "trans-fat_100g"),
    ("unsaturated_fat_100g", "unsaturated-fat_100g"),
    ("vitamin_a_100g", "vitamin-a_100g"),
    ("vitamin_b12_100g", "vitamin-b12_100g"),
    ("vitamin_b6_100g", "vitamin-b6_100g"),
    ("vitamin_c_100g", "vitamin-c_100g"),
    ("vitamin_d_100g", "vitamin-d_100g"),
    ("vitamin_e_100g", "vitamin-e_100g"),
    ("vitamin_k_100g", "vitamin-k_100g"),
    ("zinc_100g", "zinc_100g"),
    ("energy_kcal_100g", "energy-kcal_100g"),
]

# Open Food Facts stores some wildly out-of-range values (per-package figures
# filed as per-100g, unit mix-ups). Anything a food cannot physically contain
# per 100 g is treated as unreported rather than shown to the user.
MAX_PLAUSIBLE_PER_100G = 10_000.0

# Largest relative error the blob encoding may introduce. Nutrition labels
# carry two or three significant figures, so half a percent is far below the
# precision of the source data, and allowing it keeps mantissas short.
QUANTISATION_TOLERANCE = 0.005

# Indices into NUTRIENTS of protein, carbohydrates, fat and energy. A product
# reporting none of them is real but close to useless in a recipe, so it sorts
# below everything that does - the same demotion the server's ORDER BY applies.
MACRO_INDICES = (0, 1, 2, 40)


def mb(n: int) -> str:
    return f"{n / (1024 ** 2):,.1f} MB"


def ensure_csv(path: Path) -> Path:
    """Download the export if it isn't cached yet, resuming a partial file."""
    if path.exists() and path.stat().st_size > 0:
        print(f"Using cached export: {path} ({mb(path.stat().st_size)})")
        return path

    path.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {CSV_URL}\n  -> {path}")
    # curl rather than urllib: the export is >1 GB and redirects to S3, and
    # -C - resumes a partial file instead of restarting.
    subprocess.run(
        ["curl", "-fL", "-C", "-", "--retry", "5", "--retry-delay", "5",
         "-o", str(path), CSV_URL],
        check=True,
    )
    return path


def encode_varint(n: int) -> bytes:
    """LEB128, the length and delta encoding used throughout the asset."""
    out = bytearray()
    while True:
        seven, n = n & 0x7F, n >> 7
        out.append(seven | (0x80 if n else 0))
        if not n:
            return bytes(out)


def encode_value(value: float) -> bytes:
    """Encode one reading as varint((mantissa << 4) | exponent).

    Picks the smallest exponent whose mantissa still round-trips within
    QUANTISATION_TOLERANCE, which strips trailing zeros: 3.4 becomes 34e-1
    rather than 3400e-3. Short mantissas are what makes the blob compress.
    """
    for exponent in range(16):
        mantissa = round(value * 10 ** exponent)
        if mantissa and abs(mantissa / 10 ** exponent - value) <= QUANTISATION_TOLERANCE * value:
            break
    else:
        mantissa, exponent = round(value * 10 ** 15), 15

    return encode_varint((mantissa << 4) | exponent)


def parse_nutrient(raw: str):
    """CSV cell -> float, or None when absent, unparseable, zero or absurd."""
    if not raw:
        return None
    try:
        value = float(raw)
    except ValueError:
        return None
    # NaN fails every comparison, which also rules it out here.
    if not (0.0 < value <= MAX_PLAUSIBLE_PER_100G):
        return None
    return value


def build(csv_path: Path, db_path: Path, max_products):
    if db_path.exists():
        db_path.unlink()

    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.executescript("""
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = 0;
        PRAGMA cache_size = -262144;
        PRAGMA temp_store = MEMORY;
    """)

    # nutrient_count and popularity only survive long enough to pick a winner
    # among duplicates; both are dropped before the database ships.
    cur.execute("""
        CREATE TABLE products (
            id INTEGER PRIMARY KEY,
            product_name TEXT,
            brands TEXT,
            nutrients BLOB,
            score INTEGER,
            nutrient_count INTEGER,
            popularity INTEGER
        )
    """)
    insert_sql = ("INSERT INTO products (id, product_name, brands, nutrients, score, "
                  "nutrient_count, popularity) VALUES (?, ?, ?, ?, ?, ?, ?)")

    csv.field_size_limit(sys.maxsize)
    started = time.time()
    total_rows = 0
    kept = 0
    batch = []

    print("Streaming export (nothing is expanded to disk)...")
    with gzip.open(csv_path, "rt", encoding="utf-8", errors="replace", newline="") as fh:
        reader = csv.reader(fh, delimiter="\t")
        header = next(reader)
        index = {name: i for i, name in enumerate(header)}

        missing = [h for _, h in NUTRIENTS if h not in index] + \
                  [h for h in ("product_name", "brands", "unique_scans_n") if h not in index]
        if missing:
            raise SystemExit(f"Export is missing expected columns: {missing}")

        # Resolve column positions once; per-row dict building is the single
        # biggest cost at this row count.
        name_i = index["product_name"]
        brands_i = index["brands"]
        scans_i = index["unique_scans_n"]
        nutrient_i = [index[h] for _, h in NUTRIENTS]
        widest = max([name_i, brands_i, scans_i] + nutrient_i)

        for row in reader:
            # Ordinal over EVERY row, kept or not - this is the product id
            # build_db.py would assign. Must not be filtered.
            total_rows += 1

            if len(row) <= widest:
                continue

            name = row[name_i].strip()
            if not name:
                # Unfindable in a name search, so it is pure weight.
                continue

            present = 0
            body = bytearray()
            has_macro = False
            count = 0
            for slot, col in enumerate(nutrient_i):
                value = parse_nutrient(row[col])
                if value is None:
                    continue
                present |= 1 << slot
                body += encode_value(value)
                count += 1
                if slot in MACRO_INDICES:
                    has_macro = True

            if not count:
                continue
            blob = present.to_bytes(6, "little") + body

            try:
                scans = int(row[scans_i]) if row[scans_i] else 0
            except ValueError:
                scans = 0

            # Coarse log2 popularity tier, so that near-equally-popular
            # products are separated by name relevance rather than by a
            # handful of extra scans. Whole score fits in one byte, and 0
            # costs no payload bytes at all in SQLite's record format.
            tier = min(max(scans + 1, 1).bit_length() - 1, 31)
            score = (128 if has_macro else 0) + tier

            batch.append((total_rows, name, row[brands_i].strip(), bytes(blob),
                          score, count, scans))
            kept += 1

            if len(batch) >= BATCH_SIZE:
                cur.executemany(insert_sql, batch)
                batch.clear()
                conn.commit()
                elapsed = time.time() - started
                print(f"  {total_rows:>9,} read  {kept:>9,} kept  "
                      f"{elapsed:>6.0f}s  {total_rows / max(elapsed, 1):>8,.0f} rows/s")

    if batch:
        cur.executemany(insert_sql, batch)
        conn.commit()

    print(f"Read {total_rows:,} products, kept {kept:,} with nutrition data "
          f"({total_rows - kept:,} dropped) in {time.time() - started:.0f}s")

    # --- Collapse duplicates -------------------------------------------------
    # Open Food Facts carries the same product many times over. Keep the entry
    # with the most nutrients, breaking ties by popularity then by lowest id so
    # the choice is deterministic across rebuilds. NOCASE because "Whole Milk"
    # and "Whole milk" are the same thing to anyone searching.
    print("Collapsing duplicate products...")
    started = time.time()
    cur.execute("""
        DELETE FROM products WHERE id IN (
            SELECT id FROM (
                SELECT id, ROW_NUMBER() OVER (
                    PARTITION BY product_name COLLATE NOCASE, brands COLLATE NOCASE
                    ORDER BY nutrient_count DESC, popularity DESC, id ASC
                ) AS rn
                FROM products
            ) WHERE rn > 1
        )
    """)
    removed = cur.rowcount
    conn.commit()
    kept -= removed
    print(f"  removed {removed:,} duplicates, {kept:,} remain "
          f"({time.time() - started:.0f}s)")

    if max_products and kept > max_products:
        # Cut on the raw scan count, not the log2 tier in `score`: whole tiers
        # hold hundreds of thousands of products, so a tier-based cut would
        # land mid-tier and keep whichever happened to have the lowest id.
        cur.execute("""
            DELETE FROM products WHERE id NOT IN (
                SELECT id FROM products
                ORDER BY (score >= 128) DESC, popularity DESC, id ASC
                LIMIT ?
            )
        """, (max_products,))
        conn.commit()
        print(f"Capped to the {max_products:,} highest-ranked products "
              f"({kept - max_products:,} removed)")
        kept = max_products

    cur.execute("ALTER TABLE products DROP COLUMN nutrient_count")
    cur.execute("ALTER TABLE products DROP COLUMN popularity")
    conn.commit()

    # No FTS index and no VACUUM: the app builds the index itself, and this
    # database is never shipped - only the columnar export below is.
    return conn, {"rows": kept, "source_rows": total_rows, "duplicates": removed}


def export_binary(conn, out_path: Path) -> dict:
    """Write the columnar asset described in the module docstring."""
    cur = conn.cursor()
    ids = bytearray()
    scores = bytearray()
    names = bytearray()
    brands = bytearray()
    blobs = bytearray()

    previous_id = 0
    rows = 0
    for pid, name, brand, nutrients, score in cur.execute(
        "SELECT id, product_name, COALESCE(brands, ''), nutrients, score "
        "FROM products ORDER BY id"
    ):
        ids += encode_varint(pid - previous_id)
        previous_id = pid
        scores.append(score & 0xFF)
        encoded = name.encode("utf-8")
        names += encode_varint(len(encoded)) + encoded
        encoded = brand.encode("utf-8")
        brands += encode_varint(len(encoded)) + encoded
        blobs += encode_varint(len(nutrients)) + nutrients
        rows += 1

    header = (
        MAGIC
        + bytes([SCHEMA_VERSION])
        + rows.to_bytes(4, "little")
        + len(ids).to_bytes(4, "little")
        + len(scores).to_bytes(4, "little")
        + len(names).to_bytes(4, "little")
        + len(brands).to_bytes(4, "little")
        + len(blobs).to_bytes(4, "little")
    )
    with open(out_path, "wb") as f:
        f.write(header)
        for section in (ids, scores, names, brands, blobs):
            f.write(section)

    print(f"  sections: ids {mb(len(ids))}  scores {mb(len(scores))}  names {mb(len(names))}"
          f"  brands {mb(len(brands))}  blobs {mb(len(blobs))}")
    return {"rows": rows}


def compress(src_path: Path, out_path: Path) -> None:
    """Brotli-compress the export into the asset the APK ships.

    Brotli rather than gzip because it is ~26% smaller on this data at no cost:
    the app decodes it with org.brotli:dec, which `:library:network` already
    pulls in for HTTP content-encoding.
    """
    out_path.parent.mkdir(parents=True, exist_ok=True)
    print("Compressing asset (brotli -q 11)...")
    started = time.time()
    if out_path.exists():
        out_path.unlink()
    try:
        subprocess.run(["brotli", "-q", "11", "-o", str(out_path), str(src_path)], check=True)
    except FileNotFoundError:
        raise SystemExit("brotli not found - install it (brew install brotli / apt install brotli)")
    print(f"  {out_path.name}: {mb(out_path.stat().st_size)} in {time.time() - started:.0f}s")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", type=Path, default=CACHED_CSV,
                    help="Path to the gzipped Open Food Facts export (downloaded if absent)")
    ap.add_argument("--max-products", type=int, default=MAX_PRODUCTS,
                    help="Keep only the N highest-ranked products (default: %(default)s)")
    ap.add_argument("--keep-db", action="store_true",
                    help="Leave the staging SQLite database behind for inspection")
    args = ap.parse_args()

    csv_path = ensure_csv(args.csv)
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    staging = ASSET_DIR / "staging.db"

    conn, stats = build(csv_path, staging, args.max_products)

    raw_asset = ASSET_DIR / "food.bin"
    print("Writing columnar export...")
    export_binary(conn, raw_asset)
    conn.close()
    raw_bytes = raw_asset.stat().st_size
    print(f"  food.bin: {mb(raw_bytes)} for {stats['rows']:,} products")

    compress(raw_asset, ASSET_BIN)
    raw_asset.unlink()
    if not args.keep_db:
        staging.unlink()

    ASSET_META.write_text(json.dumps({
        "schemaVersion": SCHEMA_VERSION,
        "rows": stats["rows"],
        "bytes": raw_bytes,
        "compressedBytes": ASSET_BIN.stat().st_size,
    }, indent=2) + "\n")

    print("\nAsset summary")
    print(f"  products      {stats['rows']:,} of {stats['source_rows']:,} in the export "
          f"({stats['duplicates']:,} duplicates collapsed)")
    print(f"  in APK        {mb(ASSET_BIN.stat().st_size)}  ({ASSET_BIN.relative_to(REPO_ROOT)})")
    print(f"  unpacks to    {mb(raw_bytes)}, which the app turns into a database on first launch")


if __name__ == "__main__":
    main()
