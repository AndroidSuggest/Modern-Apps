#!/usr/bin/env python3
"""Offline generator: cities.csv -> cities.bin (packed binary).

Reads the full geonames-style cities.csv and emits a compact binary asset
containing only (name -> timezone) pairs for cities with population > 100k,
with an interned timezone string table.

Binary format (all integers big-endian to match java.io.DataInputStream):
    magic       : 4 bytes  = b"CTB1"
    tzCount     : int32
    repeat tzCount:
        tzLen   : uint8         (timezone id length in UTF-8 bytes)
        tzBytes : UTF-8
    cityCount   : int32
    repeat cityCount:
        nameLen : uint16        (city name length in UTF-8 bytes)
        nameBytes : UTF-8
        tzIndex : uint16        (index into the timezone table)

City records are emitted in the SAME order as the source CSV so that duplicate
city names resolve identically to the original `associate { name to tz }`
(last occurrence wins).
"""

import csv
import struct
import sys
from pathlib import Path

NAME_COL = 1
POP_COL = 14
TZ_COL = 15
POP_THRESHOLD = 100_000


def generate(csv_path: Path, bin_path: Path) -> None:
    tz_table: list[str] = []
    tz_index: dict[str, int] = {}
    cities: list[tuple[str, int]] = []

    with csv_path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        next(reader, None)  # drop header
        for row in reader:
            if len(row) <= TZ_COL:
                continue
            pop_raw = row[POP_COL]
            try:
                pop = float(pop_raw)
            except (ValueError, TypeError):
                continue
            if pop <= POP_THRESHOLD:
                continue
            name = row[NAME_COL]
            tz = row[TZ_COL]
            idx = tz_index.get(tz)
            if idx is None:
                idx = len(tz_table)
                tz_index[tz] = idx
                tz_table.append(tz)
            cities.append((name, idx))

    with bin_path.open("wb") as out:
        out.write(b"CTB1")
        out.write(struct.pack(">i", len(tz_table)))
        for tz in tz_table:
            b = tz.encode("utf-8")
            if len(b) > 0xFF:
                raise ValueError(f"timezone too long: {tz!r}")
            out.write(struct.pack(">B", len(b)))
            out.write(b)
        out.write(struct.pack(">i", len(cities)))
        for name, idx in cities:
            b = name.encode("utf-8")
            if len(b) > 0xFFFF:
                raise ValueError(f"name too long: {name!r}")
            out.write(struct.pack(">H", len(b)))
            out.write(b)
            out.write(struct.pack(">H", idx))

    print(f"cities: {len(cities)}  timezones: {len(tz_table)}")
    print(f"wrote {bin_path} ({bin_path.stat().st_size} bytes)")


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    assets = root / "clock" / "src" / "main" / "assets"
    csv_path = Path(sys.argv[1]) if len(sys.argv) > 1 else assets / "cities.csv"
    bin_path = Path(sys.argv[2]) if len(sys.argv) > 2 else assets / "cities.bin"
    generate(csv_path, bin_path)


if __name__ == "__main__":
    main()
