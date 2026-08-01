#!/usr/bin/env python3
"""Offline generator: common_words_list.txt -> keyboard/src/main/assets/dict/words_en.txt

The keyboard shipped with a 330-word "starter" list whose own header asked for it to
be replaced before production. Autocorrect over 330 words does not just underperform,
it actively rewrites correct words into one of the few it knows, so this builds the
real list from the 60k-entry frequency-ordered word list already in the repo (the one
:games:wordmaker uses).

Output format is the `word<TAB>frequency` form Dictionary.kt already parses, sorted
alphabetically so the asset diffs cleanly.

Frequency is derived from rank in the source file (which is ordered most-common
first), EXCEPT for profanity, which is emitted with frequency 0. Dictionary.kt treats
frequency 0 as "known word, never offered": typing it is not flagged as a misspelling
and it is not autocorrected away, but it can never be produced as a suggestion or as
an autocorrection of something else. That is the standard IME behaviour and it is the
reason this cannot just be a filtered word list -- dropping the words entirely would
make the keyboard silently "correct" them into something else.

Only /^[a-z]+$/ entries survive. KeyboardService only appends `Char.isLetter()`
characters to the composing buffer (see the text.length == 1 && text[0].isLetter()
guard), so hyphenated and apostrophised entries could never be matched as one token
and would only be dead weight in the binary-search arrays.

Usage:  python3 scripts/keyboard/generate_dictionary.py
"""

from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "scripts" / "wordmaker" / "common_words_list.txt"
BAD_WORDS = REPO / "scripts" / "wordmaker" / "bad-words.txt"
OUTPUT = REPO / "keyboard" / "src" / "main" / "assets" / "dict" / "words_en.txt"

# Single letters are not words and make terrible autocorrect targets ("m" -> every
# two-letter word is within edit distance 1). Only the two real ones survive.
ALLOWED_SINGLE_LETTERS = {"a", "i"}

HEADER = """\
# English word list for the offline keyboard.
# Format: one entry per line, "word<TAB>frequency". Higher frequency ranks higher.
#
# Frequency 0 means "known word, never offered": contains() accepts it so the word is
# not treated as a misspelling and is not autocorrected away, but it is never produced
# as a suggestion or as an autocorrection. Profanity is filed this way.
#
# GENERATED FILE -- do not edit by hand.
# Regenerate with: python3 scripts/keyboard/generate_dictionary.py
# Source: scripts/wordmaker/common_words_list.txt (frequency-ordered), filtered to
# /^[a-z]+$/ because KeyboardService only composes Char.isLetter() characters.
"""


def load_profanity() -> set[str]:
    """Single-token lowercase a-z entries from the shared bad-words list."""
    words = set()
    for raw in BAD_WORDS.read_text(encoding="utf-8", errors="replace").splitlines():
        w = raw.strip().lower()
        if w and w.isalpha() and w.isascii():
            words.add(w)
    return words


def main() -> None:
    profanity = load_profanity()

    # Rank is position in the source file; first occurrence wins on duplicates.
    ranked: dict[str, int] = {}
    for rank, raw in enumerate(
        SOURCE.read_text(encoding="utf-8", errors="replace").splitlines()
    ):
        word = raw.strip().lower()
        if not word or not word.isascii() or not word.isalpha():
            continue
        if len(word) == 1 and word not in ALLOWED_SINGLE_LETTERS:
            continue
        if word not in ranked:
            ranked[word] = rank

    # Re-rank densely over the words that survived filtering. Ranks from the source file
    # are sparse here (60k lines in, 42k words out), and using them directly would push
    # every word past the cutoff to a negative frequency -- which then reads as the
    # "never offered" sentinel and would silently mute a third of the dictionary.
    dense = {word: rank for rank, word in enumerate(ranked)}
    total = len(dense)

    suppressed = 0
    lines = []
    for word in sorted(dense):
        if word in profanity:
            freq = 0
            suppressed += 1
        else:
            # Rank 0 (most common) gets the highest frequency. Offset by 1 so no
            # ordinary word can collide with the 0 sentinel.
            freq = total - dense[word] + 1
        lines.append(f"{word}\t{freq}")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(HEADER + "\n".join(lines) + "\n", encoding="utf-8")

    print(f"wrote {OUTPUT.relative_to(REPO)}")
    print(f"  {total} words ({suppressed} profanity entries at frequency 0)")


if __name__ == "__main__":
    main()
