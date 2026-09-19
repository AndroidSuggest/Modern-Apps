#!/usr/bin/env python3
"""diff_kinds.py — does this archive carry the kinds the schema promises?

Decodes a `.mamaps` archive with `tile_build`'s `mamaps_dump` and checks the kind
vocabulary per layer:

  * The archive has 9 layers with `landtype` first (v8 merge of the four wash layers).
  * The v8 kinds are present: `orchard`, `vineyard`, `quarry`, `swimming_pool`
    (the first three are new ids; `swimming_pool` predates v8 but was never emitted).
  * Zero unknown-kind errors: every kind on every feature resolves through the
    archive's own dictionary.

Usage:
  ./diff_kinds.py after.mamaps
  ./diff_kinds.py before.mamaps after.mamaps   (also reports which kinds appeared)

Exit code 0 when the vocabulary holds, 1 otherwise.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CRATE = ROOT / "tile_build"

EXPECTED_LAYERS = [
    "landtype",
    "roads",
    "boundaries",
    "buildings",
    "places",
    "poi",
    "transit",
    "traffic",
    "junction",
]

# v8: what the wash used to drop (`swimming_pool` already had an id — paint only).
EXPECTED_NEW_KINDS = ["orchard", "vineyard", "quarry", "swimming_pool"]


def dump_command(explicit: Path | None) -> list[str]:
    if explicit:
        return [str(explicit)]
    for name in ("mamaps_dump", "mamaps_dump.exe"):
        built = CRATE / "target" / "release" / name
        if built.exists():
            return [str(built)]
    return [
        "cargo",
        "run",
        "--release",
        "--quiet",
        "--manifest-path",
        str(CRATE / "Cargo.toml"),
        "--bin",
        "mamaps_dump",
        "--",
    ]


def run_dump(cmd: list[str], archive: Path, *extra: str) -> str:
    proc = subprocess.run(cmd + [str(archive), *extra], capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit(f"mamaps_dump failed on {archive}")
    if proc.stderr.strip():
        sys.stderr.write(proc.stderr)
    return proc.stdout


def dict_layers(cmd: list[str], archive: Path) -> list[str]:
    layers: list[str] = []
    for line in run_dump(cmd, archive, "--mode", "dict").splitlines():
        # `layer\t{id}\t{name}` lines; kinds and details follow their own prefixes.
        parts = line.split("\t")
        if len(parts) == 3 and parts[0] == "layer":
            layers.append(parts[2])
    return layers


def dict_kinds(cmd: list[str], archive: Path) -> list[str]:
    kinds: list[str] = []
    for line in run_dump(cmd, archive, "--mode", "dict").splitlines():
        parts = line.split("\t")
        if len(parts) == 3 and parts[0] == "kind":
            kinds.append(parts[2])
    return kinds


def summary_kinds(cmd: list[str], archive: Path) -> dict[str, set[str]]:
    """layer -> set of kind names, from `--mode summary` (has a `kinds=` column)."""
    out: dict[str, set[str]] = {}
    for line in run_dump(cmd, archive, "--mode", "summary").splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        layer = parts[1]
        kinds: set[str] = set()
        for p in parts[2:]:
            k, _, v = p.partition("=")
            if k == "kinds" and v:
                # `kind_label` appends `:detail` when a feature carries one; the kind
                # is the head. Landtype features never carry details, but strip anyway.
                kinds.update(name.partition(":")[0] for name in v.split(","))
        out.setdefault(layer, set()).update(kinds)
    return out


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--dump")]
    dump = None
    for i, a in enumerate(sys.argv[1:]):
        if a == "--dump":
            dump = Path(sys.argv[1:][i + 1])
    if not args:
        print("usage: diff_kinds.py after.mamaps [before.mamaps] [--dump BIN]", file=sys.stderr)
        return 2
    cmd = dump_command(dump)
    after = Path(args[0])
    before = Path(args[1]) if len(args) > 1 else None

    failures: list[str] = []

    layers = dict_layers(cmd, after)
    if layers != EXPECTED_LAYERS:
        failures.append(f"layers {layers} != expected {EXPECTED_LAYERS}")

    kinds = summary_kinds(cmd, after)
    landtype_kinds = kinds.get("landtype", set())
    for kind in EXPECTED_NEW_KINDS:
        if kind not in landtype_kinds:
            failures.append(f"landtype carries no `{kind}`")
    # The dictionary itself must carry the new ids: a kind on a feature that the
    # dict lacks prints as `-` in the dump.
    dict_kind_names = dict_kinds(cmd, after)
    for kind in EXPECTED_NEW_KINDS:
        if kind not in dict_kind_names:
            failures.append(f"dictionary carries no kind `{kind}`")
    # Unknown kinds surface as `-` in the dump rather than names.
    for layer, names in sorted(kinds.items()):
        if "-" in names:
            failures.append(f"{layer} carries features with an unknown kind")

    print(f"layers: {layers}")
    print(f"landtype kinds ({len(landtype_kinds)}): {sorted(landtype_kinds)}")
    if before is not None:
        before_kinds = summary_kinds(cmd, before)
        appeared = {
            layer: sorted(names - before_kinds.get(layer, set()))
            for layer, names in kinds.items()
            if names - before_kinds.get(layer, set())
        }
        print(f"kinds appearing vs {before}: {appeared or 'none'}")

    print()
    if not failures:
        print("OK: 9 layers with landtype first, 4 v8 kinds present, no unknown kinds")
        return 0
    print(f"FAIL ({len(failures)}):")
    for f in failures:
        print(f"  {f}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
