#!/usr/bin/env python3
"""Offline generator: dictionary.csv -> words.dawg + definitions.br

Replaces the 16.4 MB dictionary.csv asset (176k rows: Word,Count,POS,Definition)
with two compact assets:

  words.dawg      A minimized DAWG (deterministic acyclic word graph) encoding the
                  set of valid words. Used for membership tests (contains()).
  definitions.br  A brotli-compressed, tab/newline delimited store mapping each
                  word to its list of definitions. Decoded lazily at runtime with
                  org.brotli.dec.BrotliInputStream for getDefinition().

The word set and definitions are parsed with the EXACT same logic the old
Dictionary.kt used, so runtime behaviour is preserved:

    for each line:
        parts = line.split(",", limit=4)
        if len(parts) < 4: skip
        word = parts[0].lower()
        definition = parts[3].strip('"')
        defs[word].append(definition)

The DAWG is built over the UTF-8 BYTES of each word (one edge per byte), so any
word the old naive CSV parse produced -- including non-ASCII garbage -- is
representable. contains() in Kotlin traverses the query word's UTF-8 bytes.

words.dawg binary format (big-endian, java.io.DataInputStream friendly):
    magic      : 4 bytes  = b"WDG1"
    edgeCount  : int32
    rootOffset : int32            (edge index where the root node's edges begin)
    repeat edgeCount:
        edge   : int32 packed:
            bits 24..31 (8)  byte value (one UTF-8 byte of the word)
            bit  23     (1)  lastEdgeOfNode
            bit  22     (1)  targetIsFinal (a word ends at the target node)
            bits 0..21  (22) targetOffset (edge index of target node, 0 = leaf)

Edge index 0 is a reserved sentinel (an "empty node"); leaf nodes point there so
a scan of an empty node terminates immediately without matching.

definitions.br: brotli stream of UTF-8 text. One record per word:
    word \t def1 \t def2 \t ... \n
Tabs/newlines inside words or definitions are replaced with spaces (none exist in
the source data; the guard keeps the format unambiguous).
"""

import struct
import subprocess
import sys
import tempfile
from pathlib import Path

MAGIC = b"WDG1"
OFFSET_BITS = 22
MAX_OFFSET = (1 << OFFSET_BITS) - 1
FINAL_BIT = 1 << 22
LAST_BIT = 1 << 23


class DawgNode:
    __slots__ = ("id", "final", "edges")
    _next_id = 0

    def __init__(self):
        self.id = DawgNode._next_id
        DawgNode._next_id += 1
        self.final = False
        self.edges = {}  # int byte -> DawgNode

    def key(self):
        # Children are already minimized (bottom-up), so their ids are stable.
        return (self.final, tuple(sorted((b, n.id) for b, n in self.edges.items())))


class Dawg:
    """Incremental minimal-DAWG construction (Daciuk et al.). Insert in sorted order."""

    def __init__(self):
        self.previous = b""
        self.root = DawgNode()
        self.unchecked = []       # list of (parent, char, child)
        self.minimized = {}       # key -> node

    def insert(self, word):
        # word is a bytes object; edges are keyed by int byte values.
        if word < self.previous:
            raise ValueError(f"words must be inserted in sorted order: {word!r} < {self.previous!r}")
        common = 0
        for a, b in zip(word, self.previous):
            if a != b:
                break
            common += 1
        self._minimize(common)
        node = self.root if not self.unchecked else self.unchecked[-1][2]
        for byte in word[common:]:
            nxt = DawgNode()
            node.edges[byte] = nxt
            self.unchecked.append((node, byte, nxt))
            node = nxt
        node.final = True
        self.previous = word

    def finish(self):
        self._minimize(0)

    def _minimize(self, down_to):
        for i in range(len(self.unchecked) - 1, down_to - 1, -1):
            parent, letter, child = self.unchecked[i]
            k = child.key()
            existing = self.minimized.get(k)
            if existing is not None:
                parent.edges[letter] = existing
            else:
                self.minimized[k] = child
            self.unchecked.pop()


def parse_csv(csv_path: Path):
    """Reproduce old Dictionary.kt parsing exactly. Returns dict[word] -> [defs]."""
    defs = {}
    order = []
    with csv_path.open("r", encoding="utf-8", errors="replace", newline="") as f:
        # BufferedReader.lines() splits on \n / \r / \r\n; Python universal newlines match.
        first = True
        for raw in f:
            if first:
                first = False
                # header row "Word,Count,POS,Definition" -> parts[0]="word" would be
                # inserted by the old code too, but header word "word" split has 4 parts
                # ("Word","Count","POS","Definition") so the OLD code DID include it.
                # Preserve that behaviour: do not skip the header.
                pass
            line = raw.rstrip("\n").rstrip("\r")
            parts = line.split(",", 3)
            if len(parts) < 4:
                continue
            w = parts[0].lower()
            definition = parts[3].strip('"')
            if w not in defs:
                defs[w] = []
                order.append(w)
            defs[w].append(definition)
    return defs, order


def build_dawg(words):
    dawg = Dawg()
    encoded = sorted(w.encode("utf-8") for w in words if w != "")
    for wb in encoded:
        dawg.insert(wb)
    dawg.finish()
    return dawg


def serialize_dawg(dawg: Dawg) -> bytes:
    # Collect reachable nodes from root.
    reachable = []
    seen = set()
    stack = [dawg.root]
    while stack:
        n = stack.pop()
        if id(n) in seen:
            continue
        seen.add(id(n))
        reachable.append(n)
        for c in sorted(n.edges):
            stack.append(n.edges[c])

    non_leaf = [n for n in reachable if n.edges]
    # Deterministic order by node id.
    non_leaf.sort(key=lambda n: n.id)

    # Assign edge offsets. Index 0 is the reserved sentinel (empty node).
    offset_of = {}
    idx = 1
    for n in non_leaf:
        offset_of[id(n)] = idx
        idx += len(n.edges)
    edge_count = idx

    if edge_count > MAX_OFFSET:
        raise ValueError(f"edge count {edge_count} exceeds {OFFSET_BITS}-bit offset range")

    edges = [0] * edge_count  # index 0 = sentinel: char 0, lastBit set, target 0
    edges[0] = (0 << 24) | LAST_BIT | 0

    for n in non_leaf:
        base = offset_of[id(n)]
        keys = sorted(n.edges)
        for i, code in enumerate(keys):
            target = n.edges[code]
            if not (0 <= code <= 0xFF):
                raise ValueError(f"byte {code} out of range")
            packed = code << 24
            if i == len(keys) - 1:
                packed |= LAST_BIT
            if target.final:
                packed |= FINAL_BIT
            packed |= offset_of.get(id(target), 0)  # leaf -> 0
            edges[base + i] = packed & 0xFFFFFFFF

    root_offset = offset_of[id(dawg.root)]
    out = bytearray()
    out += MAGIC
    out += struct.pack(">i", edge_count)
    out += struct.pack(">i", root_offset)
    for e in edges:
        out += struct.pack(">I", e)
    return bytes(out)


def build_definitions_blob(defs, order):
    lines = []
    sanitized = 0
    for w in order:
        parts = [w]
        for d in defs[w]:
            if "\t" in d or "\n" in d or "\r" in d:
                d = d.replace("\t", " ").replace("\r", " ").replace("\n", " ")
                sanitized += 1
            parts.append(d)
        clean_w = w.replace("\t", " ").replace("\r", " ").replace("\n", " ")
        parts[0] = clean_w
        lines.append("\t".join(parts))
    if sanitized:
        print(f"  note: sanitized {sanitized} definition(s) containing tab/newline")
    return ("\n".join(lines)).encode("utf-8")


def brotli_compress(data: bytes, out_path: Path):
    with tempfile.NamedTemporaryFile(delete=False) as tmp:
        tmp.write(data)
        tmp_path = Path(tmp.name)
    try:
        subprocess.run(
            ["brotli", "-q", "11", "-f", "-o", str(out_path), str(tmp_path)],
            check=True,
        )
    finally:
        tmp_path.unlink(missing_ok=True)


def main():
    root = Path(__file__).resolve().parents[2]
    assets = root / "games/wordmaker/src/main/assets"
    csv_path = assets / "dictionary.csv"
    dawg_path = assets / "words.dawg"
    defs_path = assets / "definitions.br"

    if not csv_path.exists():
        sys.exit(f"missing {csv_path}")

    print(f"Parsing {csv_path} ...")
    defs, order = parse_csv(csv_path)
    print(f"  {len(order)} unique words, {sum(len(v) for v in defs.values())} definitions")

    print("Building DAWG ...")
    dawg = build_dawg(defs.keys())
    dawg_bytes = serialize_dawg(dawg)
    dawg_path.write_bytes(dawg_bytes)
    print(f"  wrote {dawg_path} ({len(dawg_bytes):,} bytes)")

    print("Building brotli definitions store ...")
    blob = build_definitions_blob(defs, order)
    brotli_compress(blob, defs_path)
    print(f"  wrote {defs_path} ({defs_path.stat().st_size:,} bytes, "
          f"from {len(blob):,} bytes uncompressed)")

    # Round-trip sanity: verify every word is found by re-reading the DAWG.
    verify_dawg(dawg_bytes, defs.keys())
    print("DAWG self-check passed.")


def verify_dawg(data: bytes, words):
    magic = data[:4]
    assert magic == MAGIC, magic
    edge_count = struct.unpack_from(">i", data, 4)[0]
    root_offset = struct.unpack_from(">i", data, 8)[0]
    edges = struct.unpack_from(f">{edge_count}I", data, 12)

    def contains(word):
        wb = word.encode("utf-8")
        node = root_offset
        n = len(wb)
        for j, code in enumerate(wb):
            i = node
            found = -1
            while True:
                e = edges[i]
                ec = (e >> 24) & 0xFF
                if ec == code:
                    found = i
                    break
                if e & LAST_BIT:
                    break
                i += 1
            if found < 0:
                return False
            e = edges[found]
            if j == n - 1:
                return bool(e & FINAL_BIT)
            node = e & MAX_OFFSET
        return False

    checked = 0
    for w in words:
        if w == "":
            continue
        if not contains(w):
            raise AssertionError(f"word not found in DAWG round-trip: {w!r}")
        checked += 1
    # Negative check: a couple of non-words.
    for bad in ("zzzzzq", "qwxzptv"):
        if contains(bad):
            raise AssertionError(f"false positive in DAWG: {bad!r}")
    print(f"  verified {checked} words")


if __name__ == "__main__":
    main()
