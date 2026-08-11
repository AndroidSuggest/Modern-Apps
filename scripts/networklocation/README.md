# networklocation geocoder — build scripts (reference)

These are the **ad-hoc orchestration scripts** used to build the offline reverse/forward
geocoder database (`geocoder.geodb`) from a full OpenStreetMap planet dump. They are kept
here **for reference** — they document exactly how the shipped `.geodb` was produced. They
are **machine-specific** (hardcoded WSL paths, ~110 GB heap, ~190 GB disk) and are meant to
run on the Linux/WSL build box, not in CI.

The canonical, parameterized entry point already lives one level up:
`scripts/geocoder_gen.sh` (+ `scripts/geocoder_gen.cpp`). The scripts here are the raw
wrappers that were actually run for the planet-scale build, including the alternate
JVM (`GeocoderGenerator` unit test) generation path.

## Pipeline

```
planet-latest.osm.pbf            (~88 GB, OSM planet — the source)
    │  run-extract.sh → networklocation/tools/extract-osm-addresses.sh (osmium)
    ▼
addr.geojsonseq                  (~101 GB; every object with addr:housenumber,
    │                             one GeoJSON Feature per line — ~289.9 M lines)
    │  gen.sh   (JVM :networklocation GeocoderGenerator test, ~110 GB heap)
    ▼
geocoder.geodb                   (~2.3 GB, 260.8 M records; self-verified)
    │  reencode.sh  (scripts/geocoder_gen.cpp `reencode` → compacter format)
    ▼
geocoder2.geodb / geocoder-final.geodb   (~1.4 GB, the shipped product)
```

`addr.geojsonseq` is an **intermediate** — regenerable from the `.pbf` via `run-extract.sh`,
so it can be deleted to reclaim disk once the `.geodb` is built.

## Scripts

| Script | Role |
| --- | --- |
| `run-extract.sh` | planet `.pbf` → `addr.geojsonseq` via `networklocation/tools/extract-osm-addresses.sh` (osmium). Logs size + line count. |
| `gen.sh` | `addr.geojsonseq` → `geocoder.geodb` via the JVM `GeocoderGenerator` test (`GEOCODER_INPUT`/`GEOCODER_OUTPUT`/`GEOCODER_HEAP` env). Prints output size + sha256. |
| `reencode.sh` | Compiles `scripts/geocoder_gen.cpp` (+ simdjson, libzstd) and re-encodes `geocoder.geodb` → `geocoder2.geodb`. |
| `poll.sh` | Heartbeat monitor for the extraction (osmium PID, output growth); exits on `END` in `extract.log`. |
| `gpoll.sh` | Heartbeat monitor for generation (java RSS, free memory, geodb growth); exits on `GEN END` in `gen.log`. |
| `memest.py` | Estimates peak JVM heap for `GeocoderGenerator` over the whole planet from a sample of `addr.geojsonseq` (used to size `GEOCODER_HEAP`). |

## Dependencies

- **Extraction:** `osmium-tool` (invoked by `networklocation/tools/extract-osm-addresses.sh`).
- **JVM generation (`gen.sh`):** a JDK + the repo's Gradle wrapper; a machine with enough RAM
  for `GEOCODER_HEAP` (~110 GB for the full planet — see `memest.py`).
- **C++ reencode (`reencode.sh`):** `g++` (OpenMP, C++17), `libzstd`, and `simdjson`
  (`build/simdjson.cpp` in the scratch dir).

## Caveats

- Paths like `/home/vayun/geocoder-scratch` and `/mnt/c/Users/Vayun/Documents/code/Modern-Apps`
  are hardcoded — adjust before reuse.
- The full run needs ~190 GB of free disk (88 GB `.pbf` + 101 GB `.geojsonseq`) and a
  large-RAM box; on WSL, deleting the intermediates does not shrink the ext4 vhdx until you
  compact it.
