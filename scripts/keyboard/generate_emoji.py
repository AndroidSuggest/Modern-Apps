#!/usr/bin/env python3
"""Offline generator: Unicode emoji data -> keyboard/src/main/assets/emoji.txt

The keyboard shipped with ~135 hardcoded emoji and no names, which is enough to browse
and impossible to search. This builds the full RGI set with the CLDR short name and
search keywords for each one, so the emoji page can offer a search box.

Output format is `emoji<TAB>group<TAB>name<TAB>keyword|keyword|...`, in the source
file's CLDR order (which is the order emoji palettes are meant to use), one entry per
line. EmojiData.kt parses it.

Only fully-qualified sequences are kept: minimally-qualified and unqualified entries are
the same emoji missing a variation selector, so keeping them would show visual duplicates.

Skin-tone and hair-component variants are dropped. They multiply the set roughly fivefold
without adding anything searchable -- every one of them has the same CLDR keywords as its
base emoji, so they would only push real matches out of the results.

Sources (downloaded at generation time, pinned by version so the asset is reproducible):
  https://unicode.org/Public/emoji/15.1/emoji-test.txt
      the ordered, grouped, fully-qualified emoji list with CLDR short names
  https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotations/en.xml
      the English search keywords

Usage:  python3 scripts/keyboard/generate_emoji.py
"""

import re
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OUTPUT = REPO / "keyboard" / "src" / "main" / "assets" / "emoji.txt"

EMOJI_TEST_URL = "https://unicode.org/Public/emoji/15.1/emoji-test.txt"
CLDR_ANNOTATIONS_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotations/en.xml"
)

# Skin-tone modifiers (U+1F3FB..U+1F3FF) and the hair components. An emoji carrying any of
# these is a variant of one already in the list.
VARIANT_CODEPOINTS = {
    "1F3FB", "1F3FC", "1F3FD", "1F3FE", "1F3FF",  # skin tones
    "1F9B0", "1F9B1", "1F9B2", "1F9B3",           # red/curly/bald/white hair
}

VARIATION_SELECTOR = "\ufe0f"

# `1F600 ; fully-qualified # 😀 E1.0 grinning face`
DATA_LINE = re.compile(
    r"^(?P<codes>[0-9A-F ]+?)\s*;\s*(?P<status>[a-z-]+)\s*#\s*(?P<emoji>\S+)\s+E[\d.]+\s+(?P<name>.+)$"
)

HEADER = """\
# Emoji palette + search index for the offline keyboard.
# Format: one entry per line, "emoji<TAB>group<TAB>name<TAB>keyword|keyword|...".
# Order is CLDR palette order; groups appear in the order their first entry does.
#
# GENERATED FILE -- do not edit by hand.
# Regenerate with: python3 scripts/keyboard/generate_emoji.py
# Sources: unicode.org emoji-test.txt 15.1 (list + names), CLDR common/annotations/en.xml
# (keywords). Fully-qualified sequences only; skin-tone and hair variants dropped.
"""


def fetch(url: str) -> str:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read().decode("utf-8")


def load_keywords() -> dict[str, list[str]]:
    """CLDR search keywords per emoji. Keys are stripped of variation selectors."""
    root = ET.fromstring(fetch(CLDR_ANNOTATIONS_URL))
    keywords: dict[str, list[str]] = {}
    for annotation in root.iter("annotation"):
        # type="tts" is the spoken name, which emoji-test.txt already gives us.
        if annotation.get("type") == "tts":
            continue
        cp = (annotation.get("cp") or "").replace(VARIATION_SELECTOR, "")
        text = annotation.text or ""
        if not cp or not text:
            continue
        keywords[cp] = [w.strip() for w in text.split("|") if w.strip()]
    return keywords


def main() -> None:
    keywords = load_keywords()

    group = ""
    lines = []
    groups: list[str] = []
    for raw in fetch(EMOJI_TEST_URL).splitlines():
        line = raw.strip()
        if line.startswith("# group:"):
            group = line.removeprefix("# group:").strip()
            continue
        if not line or line.startswith("#"):
            continue
        match = DATA_LINE.match(line)
        if not match or match["status"] != "fully-qualified":
            continue
        codes = match["codes"].split()
        if VARIANT_CODEPOINTS.intersection(codes):
            continue

        emoji = match["emoji"]
        name = match["name"]
        # CLDR keys have variation selectors stripped; the palette entries do not.
        words = keywords.get(emoji.replace(VARIATION_SELECTOR, ""), [])
        # The name is already searchable on its own, so it is not repeated as a keyword.
        words = [w for w in words if w != name]

        if group not in groups:
            groups.append(group)
        lines.append(f"{emoji}\t{group}\t{name}\t{'|'.join(words)}")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(HEADER + "\n".join(lines) + "\n", encoding="utf-8")

    print(f"wrote {OUTPUT.relative_to(REPO)}")
    print(f"  {len(lines)} emoji across {len(groups)} groups: {', '.join(groups)}")


if __name__ == "__main__":
    main()
