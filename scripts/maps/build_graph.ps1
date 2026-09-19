# build_graph.ps1 -- build one .mamaps archive from one extract, end to end.
#
# The single full builder: OSM extract (+ coastline, world-transit feeds, DEM)
# in, final .mamaps out. One command does the whole pipeline:
#
#   [1] check every input exists + the free-space preflight
#   [2] cargo-build the host tools (road_graph, poi_extract, transit_shapes,
#       mamaps_build, mamaps_dump, mamaps_pack)
#   [3] build the road graph into a TEMP dir (never the final output)
#   [4] derive the transit-routes layer from the world-transit feeds manifest
#       (build_world_transit.sh --work DIR leaves feeds.manifest + gtfs/ there;
#       -WorldTransit defaults to the manifest beside the pack)
#   [5] run mamaps_build with pbf + coastline + temp graph + routes + dem into a
#       TEMP tiles-only archive ($tmp/tiles.mamaps) -- NO sidecar footer yet
#   [6] extract the POI sidecars from the pbf into a TEMP dir, then mamaps_pack
#       the graph + POI + transit sidecar sections onto the temp tiles archive
#       to produce the FINAL $Out (this is what adds the MAMA8 sidecar footer
#       the app's router, departure boards and POI tags read)
#   [7] header check via mamaps_dump on the FINAL $Out (rings_validated, 16 KiB
#       prefix budget, build_id printed) + TIME/PER-ZOOM/PEAK-MEMORY summary,
#   then delete every intermediate (the temp dir is always removed on success).
#
# Nothing here is optional content except under -NoTransit: every input is
# mandatory and every build is all 12 layers at z0-14. -Threads/-Verify are
# operational (how), not content options -- there is no flag that skips
# the coastline, the graph, the DEM, a layer, or a zoom. -NoTransit is the
# one content exception: it skips every GTFS feed (empty transit-routes +
# no --transit sidecar, as if 0 feeds).
#
# Spill backends: by default the feature spill, tile chunks and ways spill
# stage as temp files beside the output. `MAPS_ANON_SPILL=1` stages them in
# pagefile-backed anonymous memory instead — same bytes, no `.tmp` files,
# nothing stranded on kill. The pagefile cap is then the bound, not free disk
# (system-managed pagefile is what a world build wants); without it the preflight
# below warns against a larger multiple.
#
# This folds in the old measure_build.ps1 (live progress + cost report) and the
# old harden_mamaps.sh (header checks; its full 1/3/32-thread matrix lives on
# as the -Verify spot-check, off by default because it doubles build time).
#
# Usage:
#   .\build_graph.ps1 [-Region california|na|world|caonly|naonly] [-NoTransit] [-Verify] [-Threads N]
#
# -Region filters roads/POIs/buildings to the region's bbox. Everything else
# (water, earth, boundaries, landuse, transit, traffic, DEM) is always
# included regardless. The input is ALWAYS the planet -- except caonly, which
# takes inputs/california.osm.pbf directly with NO coordinate filtering, and
# naonly, which takes inputs/north-america.osm.pbf directly the same way
# (downstream tools get --region world): a california build is
# a world build with only CA roads/POIs/buildings, so borders, coastlines
# and country names still render everywhere.
#
# Inputs are fixed (no file-location options):
#   inputs/planet.osm.pbf -> inputs/california.mamaps | inputs/na.mamaps | inputs/world.mamaps
#   inputs/california.osm.pbf -> inputs/caonly.mamaps (only with -Region caonly)
#   inputs/north-america.osm.pbf -> inputs/naonly.mamaps (only with -Region naonly)
#   plus inputs/coastline.shp, inputs/world.mdem.
#   inputs/world.transit + world_transit_work/feeds.manifest are required
#   unless -NoTransit is passed, which skips GTFS entirely (empty routes +
#   no --transit sidecar, as if 0 feeds).
#
# Requires: cargo (https://rustup.rs). A state-sized extract needs roughly
# 10 GB of RAM; any build wants ~6x the .pbf in free disk (checked up front),
# or ~2x under MAPS_ANON_SPILL=1 (archive + sidecars only — the spills live
# in the pagefile, so the pagefile cap is the bound instead).
#[CmdletBinding()]
param(
    # Which region to build: california filters roads/POIs/buildings to the
    # California bbox, na to the North America bbox, world builds everything.
    # Everything else (water, earth, boundaries, landuse, transit, traffic,
    # DEM) is always included.
    [ValidateSet("california", "na", "world", "caonly", "naonly")]
    [string] $Region = "world",
    # Skip every GTFS feed: no transit_shapes run, an empty transit-routes
    # file for mamaps_build, and no --transit sidecar on the pack -- the
    # archive builds as if 0 feeds existed.
    [switch] $NoTransit,
    # Worker threads (via MAPS_THREADS). 0 = the tools' default (max).
    [int] $Threads = 0,
    # Determinism spot-check: rebuild at 1 thread into temp and require a
    # byte-identical sha256. Off by default; it doubles the build.
    [switch] $Verify
)

$ErrorActionPreference = "Stop"

# Fixed inputs -- no file-location options. ALWAYS the planet: a california
# build keeps only CA roads/POIs/buildings but renders borders, coastlines
# and country names everywhere, so it needs the whole world as input.
# `na` is the same with the North America bbox. `caonly`/`naonly` are the
# exception: their extracts ARE the region, so they build unfiltered.
$Pbf = Join-Path $PSScriptRoot "inputs/planet.osm.pbf"
if ($Region -eq "california") {
    $Out = Join-Path $PSScriptRoot "inputs/california.mamaps"
} elseif ($Region -eq "na") {
    $Out = Join-Path $PSScriptRoot "inputs/na.mamaps"
} elseif ($Region -eq "caonly") {
    # No coordinate filtering: the extract IS California, so downstream tools
    # build it as world.
    $Pbf = Join-Path $PSScriptRoot "inputs/california.osm.pbf"
    $Out = Join-Path $PSScriptRoot "inputs/caonly.mamaps"
} elseif ($Region -eq "naonly") {
    # No coordinate filtering: the extract IS North America, so downstream
    # tools build it as world. Distinct from na.mamaps, which filters the
    # planet to the NA bbox.
    $Pbf = Join-Path $PSScriptRoot "inputs/north-america.osm.pbf"
    $Out = Join-Path $PSScriptRoot "inputs/naonly.mamaps"
} else {
    $Out = Join-Path $PSScriptRoot "inputs/world.mamaps"
}
# What --region the tools see. caonly/naonly build unfiltered (world) from
# their extracts; every other region passes through.
$BuildRegion = if ($Region -eq "caonly" -or $Region -eq "naonly") { "world" } else { $Region }
$Coastline    = Join-Path $PSScriptRoot "inputs/coastline.shp"
$WorldTransit = Join-Path $PSScriptRoot "inputs/world.transit"
$Dem          = Join-Path $PSScriptRoot "inputs/world.mdem"
# build_world_transit.sh leaves the feeds manifest + unzipped gtfs/ in its fixed
# work dir (world_transit_work), not in inputs/. Step [3/7] re-runs transit_shapes
# over those to build the transit-line geometry layer.
$TransitManifest = Join-Path $PSScriptRoot "world_transit_work/feeds.manifest"

function Find-Built([string] $crate, [string] $name) {
    $built = Join-Path $PSScriptRoot "$crate\target\release\$name.exe"
    if (-not (Test-Path $built)) {
        throw "no $name.exe after building $crate -- this is a builder bug, not your input"
    }
    return (Resolve-Path $built).Path
}

# Adaptive, because these span a 128-byte header and a 30 GB spill; a fixed
# unit makes one end of that unreadable.
function Size([double] $b) {
    if ($b -ge 1GB) { return "{0,9:N2} GB" -f ($b / 1GB) }
    if ($b -ge 1MB) { return "{0,9:N1} MB" -f ($b / 1MB) }
    if ($b -ge 1KB) { return "{0,9:N1} KB" -f ($b / 1KB) }
    return "{0,9:N0} B " -f $b
}

# --- inputs -------------------------------------------------------------
# -NoTransit drops the GTFS inputs entirely (no world.transit, no manifest).
$pairs = @(@($Pbf, "pbf"), @($Coastline, "coastline"), @($Dem, "dem"))
if (-not $NoTransit) { $pairs += ,@($WorldTransit, "world.transit") }
foreach ($pair in $pairs) {
    if (-not (Test-Path $pair[0])) { throw "$($pair[1]) not found: $($pair[0])" }
}
$Pbf        = (Resolve-Path $Pbf).Path
$Coastline  = (Resolve-Path $Coastline).Path
$Dem        = (Resolve-Path $Dem).Path
$Out        = [System.IO.Path]::GetFullPath($Out)
if (-not $NoTransit) {
    $WorldTransit = (Resolve-Path $WorldTransit).Path
    if (-not (Test-Path $TransitManifest)) { throw "transit manifest not found: $TransitManifest" }
    $TransitManifest = (Resolve-Path $TransitManifest).Path
} else {
    $WorldTransit = $null
    $TransitManifest = $null
}

# Two things are on disk at once at the deepest zoom: one zoom's tile chunks
# (file backend only) and the archive being assembled — plus the feature spill
# on the file path. On north-america that was about 29 + 23 + 17 GB against a
# 14 GB .pbf, so six times the source is the rule of thumb. Under
# MAPS_ANON_SPILL=1 the spills live in the pagefile, leaving the archive plus
# sidecars (~2x), and running out an hour into z14, after stage A has already
# been paid for, is the failure this warning exists to flag.
#
# It is a warning, not a hard stop: the multiples are north-america-derived
# upper estimates, and a build may well fit in less. Proceeding on a smaller disk
# is allowed -- you accept the risk of a late z14 out-of-space.
$pbfBytes = (Get-Item $Pbf).Length
$anon = ($env:MAPS_ANON_SPILL -eq "1") -or ($env:MAPS_ANON_SPILL -ieq "true") -or ($env:MAPS_ANON_SPILL -ieq "yes")
$wanted = if ($anon) { $pbfBytes * 2 } else { $pbfBytes * 6 }
$root = [System.IO.Path]::GetPathRoot($Out)
$free = (New-Object System.IO.DriveInfo $root).AvailableFreeSpace
if ($free -lt $wanted) {
    $what = if ($anon) { "(archive + sidecars; spills are in the pagefile - check its cap too)" } else { "(feature spill + one zoom's tile chunks + the archive)" }
    Write-Warning (("{0} has {1:N1} GB free; this build may want up to about {2:N1} GB " -f `
            $root, ($free / 1GB), ($wanted / 1GB)) +
          "$what. Proceeding anyway -- " +
          "a large planet run could still hit an out-of-space at z14.")
}

$tmp = "$Out.buildtmp"
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$graphDir = Join-Path $tmp "graph"
$routes   = Join-Path $tmp "transit-routes.geojsonseq"
$log      = Join-Path $tmp "build.log"
# mamaps_build now emits a TILES-ONLY archive here; mamaps_pack folds the
# graph/POI/transit sidecars onto it to produce the final $Out.
$tilesTmp = Join-Path $tmp "tiles.mamaps"
$poiDir   = Join-Path $tmp "poi"
if (Test-Path $Out) { Remove-Item $Out -Force }

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "cargo not found. Install Rust from https://rustup.rs"
}

# Canonical thread behaviour: no inherited pool settings. MAPS_THREADS is the
# one knob both crates honour (see tile_build's par docs); RAYON_NUM_THREADS
# is adopted only when it is unset, so it is cleared rather than left to
# silently shrink a build someone asked for at full width.
if ($Threads -gt 0) { $env:MAPS_THREADS = "$Threads" } else { Remove-Item Env:MAPS_THREADS -ErrorAction SilentlyContinue }
Remove-Item Env:RAYON_NUM_THREADS -ErrorAction SilentlyContinue
Remove-Item Env:MAPS_TIMING -ErrorAction SilentlyContinue
Remove-Item Env:MAPS_PREFETCH_LANES -ErrorAction SilentlyContinue

# --- [1/7] build the host tools ------------------------------------------
# One crate builds all three PBF consumers now: road_graph, poi_extract and
# mamaps_build share osm_ingest, one pool, and one blob scan via the graph's
# sidecar. transit_shapes stays separate (it reads GTFS, not the PBF).
Write-Host "[1/7] Building the host tools ($Region)"
$crates = @(@("osm_ingest", "road_graph"), @("osm_ingest", "poi_extract"), @("mamaps_build", "mamaps_build"))
if (-not $NoTransit) { $crates += ,@("gtfs_ingest", "transit_shapes") }
foreach ($crate in $crates) {
    cargo build --release --manifest-path (Join-Path $PSScriptRoot "$($crate[0])\Cargo.toml") --bin $($crate[1])
    if ($LASTEXITCODE -ne 0) { throw "cargo build $($crate[0])/$($crate[1]) failed with exit code $LASTEXITCODE" }
}
foreach ($bin in @("mamaps_dump", "mamaps_pack")) {
    cargo build --release --manifest-path (Join-Path $PSScriptRoot "tile_build\Cargo.toml") --bin $bin
    if ($LASTEXITCODE -ne 0) { throw "cargo build tile_build/$bin failed with exit code $LASTEXITCODE" }
}
$roadGraph  = Find-Built "osm_ingest" "road_graph"
$poiExtract = Find-Built "osm_ingest" "poi_extract"
if (-not $NoTransit) { $shapes = Find-Built "gtfs_ingest" "transit_shapes" } else { $shapes = $null }
$build      = Find-Built "mamaps_build" "mamaps_build"
$dump       = Find-Built "tile_build" "mamaps_dump"
$pack       = Find-Built "tile_build" "mamaps_pack"

# --- [2/7] road graph into temp ------------------------------------------
# The region filter lives here too: a california graph keeps only ways
# touching the state bbox, so the traffic/junction layers and the pack agree
# with the tiles. mamaps_build re-validates the dir before stage A — an empty
# or stale graph used to cost 43 minutes of stage A before failing.
Write-Host "[2/7] Building the routing graph ($Region) -> $graphDir"
# caonly/naonly pass world: the extract IS the region, so no bbox filtering.
& $roadGraph $Pbf --out $graphDir --region $BuildRegion
if ($LASTEXITCODE -ne 0) { throw "road_graph failed with exit code $LASTEXITCODE" }
# Fail HERE, not 43 minutes into stage A: the world build died on a missing
# metadata.bin after paying the whole of stage A first.
if (-not (Test-Path (Join-Path $graphDir "metadata.bin"))) {
    throw "graph build produced no metadata.bin at $graphDir -- refusing to run stage A against an empty graph"
}

# --- [3/7] transit routes from the world-transit feeds --------------------
# -NoTransit skips every feed: an empty routes file, as if 0 feeds existed.
if ($NoTransit) {
    Write-Host "[3/7] NoTransit: skipping all GTFS feeds (empty transit-routes)"
    [System.IO.File]::WriteAllText($routes, "")
} else {
Write-Host "[3/7] Deriving transit routes from $TransitManifest"
$manifestDir = Split-Path $TransitManifest -Parent
# build_world_transit.sh runs under WSL and writes absolute /mnt/<drive>/... feed
# paths. This is a Windows build, so translate those back to <drive>:\... . If the
# stored path is gone, fall back to the feed's dir under the manifest's own gtfs/.
function Resolve-FeedDir([string] $dir) {
    if ($dir -match '^/mnt/([a-zA-Z])/(.*)$') {
        $dir = "$($Matches[1].ToUpper()):\" + ($Matches[2] -replace '/', '\')
    }
    if (-not [System.IO.Path]::IsPathRooted($dir)) { $dir = Join-Path $manifestDir $dir }
    if (-not (Test-Path (Join-Path $dir "stops.txt"))) {
        $local = Join-Path (Join-Path $manifestDir "gtfs") (Split-Path $dir -Leaf)
        if (Test-Path (Join-Path $local "stops.txt")) { return $local }
    }
    return $dir
}
$resolvedLines = foreach ($line in (Get-Content $TransitManifest)) {
    if ($line -notmatch '\S') { continue }
    $parts = $line -split '=', 3
    if ($parts.Count -lt 2 -or -not $parts[0] -or -not $parts[1]) { throw "unparsable manifest line: $line" }
    $dir = Resolve-FeedDir $parts[1]
    if (-not (Test-Path (Join-Path $dir "stops.txt"))) {
        throw "feed dir has no stops.txt (stale manifest?): $dir"
    }
    if ($parts.Count -ge 3 -and $parts[2]) { "$($parts[0])=$dir=$($parts[2])" } else { "$($parts[0])=$dir" }
}
if (-not $resolvedLines -or $resolvedLines.Count -eq 0) { throw "no usable feeds in $TransitManifest" }
[System.IO.File]::WriteAllLines((Join-Path $tmp "feeds.resolved.manifest"), $resolvedLines)
& $shapes $routes --manifest (Join-Path $tmp "feeds.resolved.manifest")
if ($LASTEXITCODE -ne 0) { throw "transit_shapes failed with exit code $LASTEXITCODE" }
}

# --- [4/7] the tiles-only archive (temp) ----------------------------------
# Started before the build, so the first spike cannot be missed. Under
# MAPS_ANON_SPILL the feature spill has no file: the sampler then tracks only
# the process high-water (commit charge is visible in the OS, not in a path).
$spill = [System.IO.Path]::ChangeExtension($tilesTmp, "features.tmp")
$sampler = Start-Job -ArgumentList "mamaps_build", $spill, $anon -ScriptBlock {
    param($name, $spill, $anon)
    $peakWs = 0L; $peakSpill = 0L
    while ($true) {
        $p = Get-Process -Name $name -ErrorAction SilentlyContinue
        if ($p) {
            $peakWs = [Math]::Max($peakWs, ($p | Measure-Object PeakWorkingSet64 -Maximum).Maximum)
        }
        if (-not $anon -and (Test-Path $spill)) {
            $len = (Get-Item $spill -ErrorAction SilentlyContinue).Length
            if ($len -gt $peakSpill) { $peakSpill = $len }
        }
        [pscustomobject]@{ Ws = $peakWs; Spill = $peakSpill }
        Start-Sleep -Milliseconds 250
    }
}

Write-Host "[4/7] Building $(Split-Path $tilesTmp -Leaf) (tiles only)   z0..z14$(if ($Threads -gt 0) { ", $Threads thread(s)" })"
Write-Host ""
# Tee-Object keeps the bars live on the console AND captures stdout to the log,
# which the summary below parses. No redirect: the bars need the console to redraw on.
$sw = [System.Diagnostics.Stopwatch]::StartNew()
& $build --input $Pbf --out $tilesTmp `
    --coastline $Coastline `
    --graph $graphDir `
    --transit-routes $routes `
    --dem $Dem `
    --region $BuildRegion | Tee-Object -FilePath $log
$exit = $LASTEXITCODE
$sw.Stop()

Start-Sleep -Milliseconds 400
$s = Receive-Job $sampler
Stop-Job $sampler; Remove-Job $sampler -Force
$peakWs    = ($s | Measure-Object Ws    -Maximum).Maximum
$peakSpill = ($s | Measure-Object Spill -Maximum).Maximum

Write-Host ""
if ($exit -ne 0) {
    Write-Host ("FAILED, exit {0}, after {1:N1} s (temp kept at {2})" -f $exit, $sw.Elapsed.TotalSeconds, $tmp)
    exit $exit
}

# --- [5/7] POI sidecars ---------------------------------------------------
# The offline POI / restaurant tags come from here: poi_extract bakes the five
# poi_*.bin side files the packer folds in. --index fixes the dir; --attrs,
# --spatial and --name-index default to their poi_*.bin names beside it, which
# is exactly the set mamaps_pack --poi expects. The --geojson output is the
# baked-vector feed and is not consumed by the pack, but poi_extract requires it.
Write-Host "[5/7] Extracting POI sidecars ($Region) -> $poiDir"
New-Item -ItemType Directory -Force -Path $poiDir | Out-Null
& $poiExtract $Pbf `
    --geojson (Join-Path $poiDir "poi.geojsonseq") `
    --names (Join-Path $poiDir "poi_names.bin") `
    --index (Join-Path $poiDir "poi_index.bin") `
    --region $BuildRegion
if ($LASTEXITCODE -ne 0) { throw "poi_extract failed with exit code $LASTEXITCODE" }

# --- [6/7] pack the sidecars onto the tiles archive -> FINAL $Out ----------
# mamaps_build emits tiles only (no MAMA8 footer); the graph / transit / POI
# sections the app's router, departure boards and POI tags need are appended
# here. This is the step that turns a tiles-only archive into a complete pack.
Write-Host "[6/7] Packing graph + POI + transit sidecars -> $(Split-Path $Out -Leaf)"
if ($NoTransit) {
    & $pack --tiles $tilesTmp --graph $graphDir --poi $poiDir --out $Out
} else {
    & $pack --tiles $tilesTmp --graph $graphDir --poi $poiDir --transit $WorldTransit --out $Out
}
if ($LASTEXITCODE -ne 0) { throw "mamaps_pack failed with exit code $LASTEXITCODE" }

# --- [7/7] header check + report ------------------------------------------
# The binary prints its own per-zoom table (`z0 ...`, `z1 ...`) plus the
# `classified ...` and `wrote ... build_id ...` lines. Parsed here rather than
# re-derived.
$logLines = Get-Content $log
$zooms = @()
foreach ($line in $logLines) {
    # Per-zoom row: `z` + zoom, tiles, features, points, dropped, bytes, map/merge/enc/app seconds.
    if ($line -match '^z(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)') {
        $zooms += [pscustomobject]@{
            zoom = [int]$Matches[1]; tiles = [long]$Matches[2]; features = [long]$Matches[3]
            points = [long]$Matches[4]; dropped = [long]$Matches[5]; bytes = [long]$Matches[6]
            map_ms = [long]([double]$Matches[7] * 1000); merge_ms = [long]([double]$Matches[8] * 1000)
            encode_ms = [long]([double]$Matches[9] * 1000); append_ms = [long]([double]$Matches[10] * 1000)
        }
    }
}
if ($zooms.Count -eq 0) { throw "no per-zoom rows parsed from $log -- the build printed no zN table" }

$h = @{}
foreach ($line in (& $dump $Out --mode header)) {
    $f = $line -split "`t"
    if ($f.Count -ge 2) { $h[$f[0]] = $f[1] }
}
# The renderer skips its repair pass on the strength of this claim.
if ($h["rings_validated"] -ne "true") { throw "rings_validated is '$($h["rings_validated"])' -- the archive is unshippable" }
# Header plus dictionary plus root index inside 16 KiB, so a cold open is one
# range request and not two.
$prefix = [long]$h["prefix_bytes"]
if ($prefix -gt 16384) { throw "prefix is $prefix bytes of 16384 -- a cold open costs two requests" }
Write-Host "[7/7] Header ok: rings_validated true, prefix $prefix/16384 B, build_id $($h["build_id"])"

$sha = (Get-FileHash $Out -Algorithm SHA256).Hash
if ($Verify) {
    # harden_mamaps.sh's full 1/3/32-thread matrix, folded in cheaply: one
    # single-threaded rebuild must be byte-identical. A differing hash means an
    # emit path whose order comes from a pool (e.g. a HashMap iterated on emit).
    Write-Host "Verifying determinism: rebuilding at 1 thread + repacking"
    $verifyTiles = Join-Path $tmp "verify-tiles.mamaps"
    $verifyOut = Join-Path $tmp "verify.mamaps"
    $env:RAYON_NUM_THREADS = "1"
    Remove-Item Env:MAPS_THREADS -ErrorAction SilentlyContinue
    & $build --input $Pbf --out $verifyTiles `
        --coastline $Coastline `
        --graph $graphDir `
        --transit-routes $routes `
        --dem $Dem `
        --region $BuildRegion | Tee-Object -FilePath (Join-Path $tmp "verify.log") | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "verify rebuild failed with exit code $LASTEXITCODE" }
    if ($NoTransit) {
        & $pack --tiles $verifyTiles --graph $graphDir --poi $poiDir --out $verifyOut
    } else {
        & $pack --tiles $verifyTiles --graph $graphDir --poi $poiDir --transit $WorldTransit --out $verifyOut
    }
    if ($LASTEXITCODE -ne 0) { throw "verify pack failed with exit code $LASTEXITCODE" }
    $vsha = (Get-FileHash $verifyOut -Algorithm SHA256).Hash
    if ($vsha -ne $sha) { throw "determinism FAIL: 1-thread rebuild differs ($vsha vs $sha)" }
    Write-Host "  ok: 1-thread rebuild is byte-identical"
    if ($Threads -gt 0) { $env:MAPS_THREADS = "$Threads" }
    Remove-Item Env:RAYON_NUM_THREADS -ErrorAction SilentlyContinue
}

$archive = (Get-Item $Out).Length
$map = ($zooms | Measure-Object map_ms    -Sum).Sum
$mrg = ($zooms | Measure-Object merge_ms  -Sum).Sum
$enc = ($zooms | Measure-Object encode_ms -Sum).Sum
$app = ($zooms | Measure-Object append_ms -Sum).Sum
$tiling = ($map + $mrg + $enc + $app) / 1000.0
$stageA = $sw.Elapsed.TotalSeconds - $tiling
Write-Host ""
Write-Host "======================== RESULT ========================"
Write-Host ""
Write-Host "TIME"
Write-Host ("  {0,-24} {1,8:N1} s  {2,5:N1}%   {3}" -f `
    "stage A", $stageA, (100.0 * $stageA / $sw.Elapsed.TotalSeconds), "parse, resolve, materialise, spill")
foreach ($p in @(@("map (clip)", $map), @("merge", $mrg), @("encode", $enc), @("append", $app))) {
    Write-Host ("  {0,-24} {1,8:N1} s  {2,5:N1}%" -f `
        $p[0], ($p[1] / 1000.0), (100.0 * $p[1] / 1000.0 / $sw.Elapsed.TotalSeconds))
}
Write-Host ("  {0,-24} {1,8:N1} s" -f "TOTAL WALL", $sw.Elapsed.TotalSeconds)
Write-Host ""
Write-Host "PER ZOOM"
Write-Host ("  {0,-5}{1,12}{2,14}{3,16}{4,10}{5,9}{6,9}{7,9}" -f `
    "zoom", "tiles", "features", "body bytes", "map_s", "merge_s", "enc_s", "app_s")
foreach ($z in $zooms) {
    Write-Host ("  z{0,-4}{1,12:N0}{2,14:N0}{3,16:N0}{4,10:N1}{5,9:N1}{6,9:N1}{7,9:N1}" -f `
        $z.zoom, $z.tiles, $z.features, $z.bytes,
        ($z.map_ms / 1000.0), ($z.merge_ms / 1000.0), ($z.encode_ms / 1000.0), ($z.append_ms / 1000.0))
}
Write-Host ""
Write-Host "PEAK MEMORY"
Write-Host ("  {0,-24} {1}   kernel high-water, cannot be missed" -f "resident", (Size $peakWs))
if ($anon) {
    Write-Host ("  {0,-24} {1}   anon spill: no file; commit charge is in the pagefile" -f "feature spill, peak", "n/a")
} else {
    Write-Host ("  {0,-24} {1}   scratch, deleted" -f "feature spill, peak", (Size $peakSpill))
}
Write-Host ""
Write-Host ("  archive           {0}   {1}" -f (Size $archive), $Out)
Write-Host ("  build id          {0,14}" -f $h["build_id"])
Write-Host ("  sha256            {0}" -f $sha)

# Temp is always removed on success now (no -Keep): the graph/POI/routes/tiles
# intermediates are reproducible from the inputs, and anon spills vanish with
# the process. A failed build keeps $tmp as evidence (see the FAILED branch).
Remove-Item $tmp -Recurse -Force
Write-Host ""
