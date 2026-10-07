#!/usr/bin/env python3
"""Offline generator for the weather app's place-search catalogue.

The city search lets the user pick from real GeoNames places rather than
typing free text, so everything is compiled into the APK and no search
makes a network request at runtime - the downloads below happen here, at
build time, not on the device.

One output, handled like the health catalogue:

  weather/src/main/assets/places.db.br      gitignored, plus a .meta.json

Source: GeoNames `cities1000` (places with population >= 1000, ~170k rows),
plus `admin1CodesASCII` and `countryInfo` for display names. The dump is
CC-BY 4.0 (https://www.geonames.org/about.html); attribution ships in the
app's about screen.

    https://download.geonames.org/export/dump/cities1000.zip
    https://download.geonames.org/export/dump/admin1CodesASCII.txt
    https://download.geonames.org/export/dump/countryInfo.txt

Schema mirrors the server's /wx/v1/search index byte-for-byte (same table
names, same FTS columns, same ranking SQL in GeoDatabase.kt), so results
match whether they come from the device or the server:

  places(id, name, asciiname, latitude, longitude, country_code,
         country_name, admin1, population, timezone)
  places_fts(name, asciiname, alternates) - hidden alias column like the
    health labs table: alternate names ("Bombay", "München" ASCII forms)
    are searchable but the display name stays canonical.

Ranking is exact-name, then prefix, then population, then bm25 - the same
ORDER BY as the server. Kept byte-for-byte in step with GeoDatabase.kt so
both resolve a query the same way.

Unlike `generate_food_db.py`, this ships a finished SQLite database rather
than a columnar file the app rebuilds. At ~170k rows the container and FTS
index cost little more than the data, and shipping the database directly
removes the whole on-device build step. Brotli, because the app already
links a decoder for it.

Usage
-----
    python3 scripts/generate_places_db.py              # everything
    python3 scripts/generate_places_db.py --keep-db    # keep unpacked SQLite
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import subprocess
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS = REPO_ROOT / "weather" / "src" / "main" / "assets"

CITIES_URL = "https://download.geonames.org/export/dump/cities1000.zip"
ADMIN1_URL = "https://download.geonames.org/export/dump/admin1CodesASCII.txt"
COUNTRY_URL = "https://download.geonames.org/export/dump/countryInfo.txt"

CACHE_DIR = Path.home() / ".cache" / "geonames"
CACHED_CITIES = CACHE_DIR / "cities1000.zip"
CACHED_ADMIN1 = CACHE_DIR / "admin1CodesASCII.txt"
CACHED_COUNTRY = CACHE_DIR / "countryInfo.txt"

# Bumped in lockstep with SUPPORTED_SCHEMA_VERSION in GeoDatabase.kt.
SCHEMA_VERSION = 1


def mb(n: int) -> str:
    return f"{n / 1_000_000:.1f} MB"


def download(url: str, dest: Path) -> Path:
    """Fetch `url` to `dest`, skipping the download if it is already cached."""
    if dest.exists() and dest.stat().st_size > 0:
        print(f"  cached  {dest} ({mb(dest.stat().st_size)})")
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"  fetching {url}")
    subprocess.run(["curl", "-sSL", "--fail", "-o", str(dest), url], check=True)
    print(f"  saved   {dest} ({mb(dest.stat().st_size)})")
    return dest


def load_admin1(path: Path) -> dict[str, str]:
    """admin1 code ("US.CA") -> ASCII name ("California")."""
    names: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.split("\t")
        if len(cols) >= 2:
            names[cols[0]] = cols[1]
    return names


def load_countries(path: Path) -> dict[str, str]:
    """ISO code -> country name."""
    names: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.split("\t")
        if len(cols) > 4:
            names[cols[0]] = cols[4]
    return names


def build_places(
    cities_zip: Path,
    admin1_names: dict[str, str],
    country_names: dict[str, str],
    conn: sqlite3.Connection,
) -> int:
    conn.execute(
        """
        CREATE TABLE places (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            asciiname TEXT NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            country_code TEXT NOT NULL DEFAULT '',
            country_name TEXT NOT NULL DEFAULT '',
            admin1 TEXT NOT NULL DEFAULT '',
            population INTEGER NOT NULL DEFAULT 0,
            timezone TEXT NOT NULL DEFAULT ''
        )
        """
    )
    conn.execute(
        """
        CREATE VIRTUAL TABLE places_fts USING fts5(
            name, asciiname, alternates,
            content='places', content_rowid='rowid'
        )
        """
    )
    rows: list[tuple] = []
    with zipfile.ZipFile(cities_zip) as zf:
        name = next(n for n in zf.namelist() if n.endswith(".txt"))
        with zf.open(name) as handle:
            for raw in handle:
                line = raw.decode("utf-8", errors="replace").rstrip("\n")
                if not line.strip():
                    continue
                # geonameid, name, asciiname, alternatenames, lat, lon,
                # feature class/code, country, cc2, admin1..4, population,
                # elevation, dem, timezone, modification.
                c = line.split("\t")
                if len(c) < 18:
                    continue
                try:
                    gid = int(c[0])
                    lat = float(c[4])
                    lon = float(c[5])
                except ValueError:
                    continue
                if gid == 0:
                    continue
                cc = c[8]
                admin1 = admin1_names.get(f"{cc}.{c[10]}", "")
                country = country_names.get(cc, "")
                try:
                    pop = int(c[14])
                except ValueError:
                    pop = 0
                tz = c[17] if len(c) > 17 else ""
                rows.append(
                    (gid, c[1], c[2], lat, lon, cc, country, admin1, pop, tz,
                     c[3].replace(",", " "))
                )
    rows.sort(key=lambda r: (r[1].lower(), -r[8]))
    conn.executemany(
        "INSERT INTO places VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [r[:10] for r in rows],
    )
    conn.executemany(
        "INSERT INTO places_fts(rowid, name, asciiname, alternates) VALUES (?, ?, ?, ?)",
        [(r[0], r[1], r[2], r[10]) for r in rows],
    )
    print(f"  places  {len(rows)} cities")
    return len(rows)


def build_catalog(db_path: Path) -> int:
    cities_zip = download(CITIES_URL, CACHED_CITIES)
    admin1_names = load_admin1(download(ADMIN1_URL, CACHED_ADMIN1))
    country_names = load_countries(download(COUNTRY_URL, CACHED_COUNTRY))
    db_path.parent.mkdir(parents=True, exist_ok=True)
    db_path.unlink(missing_ok=True)
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("PRAGMA journal_mode = OFF")
        conn.execute("PRAGMA synchronous = OFF")
        count = build_places(cities_zip, admin1_names, country_names, conn)
        # DELETE, not OFF: the app reopens the file read-only, which a journal
        # mode of OFF does not survive cleanly.
        conn.execute("PRAGMA journal_mode = DELETE")
        conn.commit()
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()
    print(f"  built   {db_path} ({mb(db_path.stat().st_size)})")
    return count


def compress(src: Path, dest: Path) -> None:
    try:
        import brotli
    except ImportError:
        sys.exit("brotli is required to compress the asset: pip install brotli")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(brotli.compress(src.read_bytes(), quality=11))
    print(f"  wrote   {dest} ({mb(dest.stat().st_size)})")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--keep-db", action="store_true",
                    help="keep the uncompressed SQLite file next to the asset")
    args = ap.parse_args()

    print("Places (GeoNames cities1000)")
    staging = ASSETS / "places.db"
    count = build_catalog(staging)
    compress(staging, ASSETS / "places.db.br")

    meta = {
        "schemaVersion": SCHEMA_VERSION,
        "places": count,
        "bytes": staging.stat().st_size,
        "compressedBytes": (ASSETS / "places.db.br").stat().st_size,
        "sources": {
            "cities": CITIES_URL,
            "admin1": ADMIN1_URL,
            "country": COUNTRY_URL,
        },
    }
    (ASSETS / "places.db.meta.json").write_text(
        json.dumps(meta, indent=1) + "\n", newline="\n"
    )
    print(f"  wrote   {ASSETS / 'places.db.meta.json'}")

    if not args.keep_db:
        staging.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
