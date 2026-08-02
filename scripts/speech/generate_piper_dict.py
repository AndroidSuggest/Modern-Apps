#!/usr/bin/env python3
"""
Build the `<lang>-word_id.bin` phoneme dictionary that com.vayunmathur.ncnn.Vits uses
instead of a runtime espeak-ng.

espeak-ng is GPL and awkward to vendor into the ncnn AAR (it wants exceptions/RTTI,
which ncnn disables PUBLIC), so phonemisation is done ahead of time here and shipped as
a lookup table. espeak-ng is still needed *on the build machine* — just not on-device.

Format, entries back to back with no header:

    word '\\0' id id id ... '\\xff'

Each id is one byte indexing the voice's `phoneme_id_map` from config.json. Words are
looked up case-insensitively at runtime, so only one case needs storing.

Usage:
    ./scripts/speech/generate_piper_dict.py \\
        --config dist/speech-mirror/piper/config.json \\
        --words cmudict-words.txt \\
        --voice en-us \\
        --out dist/speech-mirror/piper/en-word_id.bin

Word list: one word per line. https://github.com/Alexir/CMUdict `cmudict.0.7a` works
after stripping the pronunciations and the `(2)` variant suffixes; --words - reads stdin.
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

# Terminates an entry's id list, so it can never be a phoneme id itself.
TERMINATOR = 0xFF

# Reserved by the phonemizer and never looked up from the dictionary.
RESERVED_IDS = {0: "PAD", 1: "BOS", 2: "EOS", 3: "SPACE"}

# espeak-ng is slow to start, so words go through in batches on one invocation.
BATCH = 2000


def load_phoneme_map(config_path: Path) -> dict:
    """phoneme_id_map maps an IPA symbol to a list of ids; we only ever use the first."""
    with config_path.open(encoding="utf-8") as f:
        config = json.load(f)

    raw = config.get("phoneme_id_map")
    if not raw:
        sys.exit(f"{config_path} has no phoneme_id_map")

    out = {}
    for symbol, ids in raw.items():
        if not ids:
            continue
        value = ids[0]
        if value == TERMINATOR:
            # Would be indistinguishable from the entry terminator.
            sys.exit(f"phoneme {symbol!r} has id 255, which collides with the terminator")
        if not 0 <= value <= 255:
            sys.exit(f"phoneme {symbol!r} has id {value}, which does not fit in a byte")
        out[symbol] = value
    return out


def phonemize(words: list, voice: str) -> list:
    """One espeak-ng --ipa line per input word, in order."""
    result = []
    for start in range(0, len(words), BATCH):
        chunk = words[start:start + BATCH]
        proc = subprocess.run(
            ["espeak-ng", "-q", "-v", voice, "--ipa", "--stdin"],
            input="\n".join(chunk),
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if proc.returncode != 0:
            sys.exit(f"espeak-ng failed: {proc.stderr.strip()}")

        lines = proc.stdout.split("\n")
        if len(lines) < len(chunk):
            sys.exit(
                f"espeak-ng returned {len(lines)} lines for {len(chunk)} words — "
                "a word probably contained whitespace"
            )
        result.extend(line.strip() for line in lines[:len(chunk)])

        print(f"  phonemized {min(start + BATCH, len(words))}/{len(words)}", file=sys.stderr)

    return result


def to_ids(ipa: str, phoneme_map: dict, longest: int):
    """
    Greedy longest-match over the IPA string. Symbols are multi-codepoint often enough
    (dʒ, aɪ, ɔː) that matching one character at a time would mis-segment.

    Returns None if any part of the string is unmappable — a partial pronunciation is
    worse than falling back to spelling the word out, which is what Vits does on a miss.
    """
    ids = []
    i = 0
    while i < len(ipa):
        for width in range(min(longest, len(ipa) - i), 0, -1):
            symbol = ipa[i:i + width]
            if symbol in phoneme_map:
                ids.append(phoneme_map[symbol])
                i += width
                break
        else:
            return None
    return ids or None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", type=Path, required=True, help="Piper voice config.json")
    parser.add_argument("--words", type=Path, required=True, help="word list, one per line, or -")
    parser.add_argument("--voice", default="en-us", help="espeak-ng voice (default: en-us)")
    parser.add_argument("--out", type=Path, required=True, help="output <lang>-word_id.bin")
    args = parser.parse_args()

    phoneme_map = load_phoneme_map(args.config)
    longest = max(len(s) for s in phoneme_map)
    print(f"{len(phoneme_map)} phonemes, longest symbol {longest} chars", file=sys.stderr)

    if str(args.words) == "-":
        raw = sys.stdin.read()
    else:
        raw = args.words.read_text(encoding="utf-8", errors="replace")

    # Deduplicate case-insensitively (lookup is case-insensitive) but keep input order,
    # and drop anything with whitespace since espeak-ng is fed one word per line.
    seen = set()
    words = []
    for line in raw.splitlines():
        word = line.strip()
        if not word or any(c.isspace() for c in word):
            continue
        key = word.lower()
        if key in seen:
            continue
        seen.add(key)
        words.append(word)

    # Digits and single letters are what Vits falls back to when a word is missing, so
    # they have to be present or the fallback produces nothing at all. CMUdict has
    # neither.
    for extra in list("0123456789abcdefghijklmnopqrstuvwxyz"):
        if extra not in seen:
            seen.add(extra)
            words.append(extra)

    print(f"{len(words)} words to phonemize", file=sys.stderr)
    pronunciations = phonemize(words, args.voice)

    args.out.parent.mkdir(parents=True, exist_ok=True)

    stored = set()
    skipped = []
    with args.out.open("wb") as f:
        for word, ipa in zip(words, pronunciations):
            ids = to_ids(ipa, phoneme_map, longest) if ipa else None
            if ids is None:
                skipped.append(word)
                continue
            f.write(word.encode("utf-8"))
            f.write(b"\x00")
            f.write(bytes(ids))
            f.write(bytes([TERMINATOR]))
            stored.add(word.lower())

    print(f"\nwrote {len(stored)} entries to {args.out} "
          f"({args.out.stat().st_size / 1e6:.1f} MB)", file=sys.stderr)

    if skipped:
        print(f"skipped {len(skipped)} unmappable words, e.g. {skipped[:10]}", file=sys.stderr)

    # Vits spells unknown words out character by character, so a gap here means the
    # fallback silently produces nothing for anything containing that character.
    missing = [c for c in "0123456789abcdefghijklmnopqrstuvwxyz" if c not in stored]
    if missing:
        print(f"WARNING: no entry for {missing} — Vits cannot spell out words "
              "containing them", file=sys.stderr)

    if not stored:
        sys.exit("no entries written — check --config and --voice")


if __name__ == "__main__":
    main()
