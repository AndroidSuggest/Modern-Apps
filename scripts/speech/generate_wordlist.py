#!/usr/bin/env python3
"""
Generate a top-N frequent word list for a given language using `wordfreq`.

Used by fetch_piper_model.sh to build <lang>-word_id.bin dictionaries for
non-English TTS voices. The English path uses CMUdict (125k entries), but for
other languages we use wordfreq's top 100k most common words, which covers
everyday vocabulary and is filtered to simple tokens.

Usage:
    python3 scripts/speech/generate_wordlist.py --lang de --top 100000 --out /tmp/de-words.txt
    python3 scripts/speech/generate_wordlist.py --lang fr --top 50000 --out /tmp/fr-words.txt

Requires: pip install wordfreq
"""

import argparse
import re
import sys
from pathlib import Path

# Keep only words that look like ordinary words (letters, apostrophe, hyphen).
# Allow Unicode letters \w covers ASCII but \p? Python's re with \w + UNICODE works for Latin etc.
# We filter punctuation aggressively since espeak-ng fed one word per line must not contain spaces.
WORD_RE = re.compile(r"^[\w'\-]+$", re.UNICODE)

# Also reject tokens with digits or excessive length
MAX_LEN = 40


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lang", required=True, help="ISO-639-1 code, e.g. de, fr, es")
    parser.add_argument("--top", type=int, default=100_000, help="Top N frequent words (default 100k)")
    parser.add_argument("--out", type=Path, required=True, help="Output file")
    args = parser.parse_args()

    try:
        from wordfreq import top_n_list
    except ImportError:
        sys.exit("wordfreq not installed: pip install wordfreq")

    lang = args.lang.strip().lower()
    # Map Chinese/Japanese/Korean? wordfreq supports them but tokenization is different.
    # Use BCP-47-ish tag? top_n_list expects lang code like 'de', 'fr', 'zh' etc.
    print(f"Fetching top {args.top} words for lang={lang} via wordfreq…", file=sys.stderr)

    try:
        words: list[str] = top_n_list(lang, n=args.top)
    except Exception as e:
        sys.exit(f"wordfreq error for lang={lang}: {e}. Try pip install wordfreq and check language support.")

    # Deduplicate case-insensitively while keeping order, filter
    seen = set()
    out_words: list[str] = []
    for w in words:
        if not w:
            continue
        w = w.strip()
        if not w:
            continue
        if len(w) > MAX_LEN:
            continue
        if any(c.isspace() for c in w):
            continue
        if not WORD_RE.fullmatch(w):
            continue
        # Skip pure digits (added separately by generate_piper_dict.py)
        if w.isdigit():
            continue
        low = w.lower()
        if low in seen:
            continue
        seen.add(low)
        out_words.append(w)

    print(f"Filtered to {len(out_words)} words after dedup/length/regex", file=sys.stderr)

    # Ensure digits + single letters fallback (generate_piper_dict.py also adds them, but keep here)
    # The dict generator needs 0-9a-z to spell unknown words char-by-char.

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(out_words), encoding="utf-8")
    print(f"Wrote {len(out_words)} words to {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
