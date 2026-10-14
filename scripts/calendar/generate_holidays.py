#!/usr/bin/env python3
"""Generates the calendar app's bundled holiday data from Google's public
iCal holiday feeds (en.<slug>#holiday@group.v.calendar.google.com).

Python port of the former :tools:holidaygen HolidayGen.kt — identical
runtime behavior and output layout.

Usage:
    python generate_holidays.py [start-slug]

Writes to calendar/src/main/assets/holidays/ (relative to the repo root):
- languages.json           67 supported language prefixes
- index.json               canonical country list (code + English name)
- en/<CODE>.json           English holidays per country
- <lang>/<CODE>.json       localized holidays, only where they differ from English
- <lang>/index.json        per-language country index
- <CODE>.json              flat English copies (backward compat)
- country_languages.json   map of country code -> available language codes

If a start slug is given and the output dir already exists, resumes from
that slug instead of wiping the output. Stdlib only.
"""

import json
import pathlib
import re
import shutil
import sys
import urllib.request

URL_TEMPLATE = (
    "https://calendar.google.com/calendar/ical/%s.%s%%23holiday%%40group.v.calendar.google.com/public/basic.ics"
)
USER_AGENT = "Mozilla/5.0 (holidaygen)"
TIMEOUT_S = 60

# 67 Google-supported language prefixes with display names
LANGS = [
    ("en", "English"), ("es", "Spanish"), ("fr", "French"),
    ("de", "German"), ("it", "Italian"), ("pt", "Portuguese"),
    ("ru", "Russian"), ("zh", "Chinese"), ("ja", "Japanese"),
    ("ko", "Korean"), ("ar", "Arabic"), ("hi", "Hindi"),
    ("nl", "Dutch"), ("pl", "Polish"), ("tr", "Turkish"),
    ("sv", "Swedish"), ("da", "Danish"), ("no", "Norwegian"),
    ("fi", "Finnish"), ("cs", "Czech"), ("hu", "Hungarian"),
    ("el", "Greek"), ("he", "Hebrew"), ("th", "Thai"),
    ("vi", "Vietnamese"), ("id", "Indonesian"), ("ms", "Malay"),
    ("uk", "Ukrainian"), ("ro", "Romanian"), ("bg", "Bulgarian"),
    ("hr", "Croatian"), ("sk", "Slovak"), ("sl", "Slovenian"),
    ("lt", "Lithuanian"), ("lv", "Latvian"), ("et", "Estonian"),
    ("sr", "Serbian"), ("ca", "Catalan"), ("eu", "Basque"),
    ("gl", "Galician"), ("is", "Icelandic"), ("ga", "Irish"),
    ("mt", "Maltese"), ("cy", "Welsh"), ("af", "Afrikaans"),
    ("sq", "Albanian"), ("hy", "Armenian"), ("az", "Azerbaijani"),
    ("be", "Belarusian"), ("bn", "Bengali"), ("bs", "Bosnian"),
    ("ka", "Georgian"), ("km", "Khmer"), ("kn", "Kannada"),
    ("ky", "Kyrgyz"), ("lo", "Lao"), ("mk", "Macedonian"),
    ("ml", "Malayalam"), ("mn", "Mongolian"), ("my", "Burmese"),
    ("ne", "Nepali"), ("pa", "Punjabi"), ("si", "Sinhala"),
    ("ta", "Tamil"), ("te", "Telugu"), ("ur", "Urdu"),
    ("uz", "Uzbek"),
]

# Verified Google holiday-feed slugs
SLUGS = [
    "usa", "uk", "canadian", "australian", "indian", "irish", "french", "german",
    "italian", "spain", "portuguese", "dutch", "danish", "finnish", "norwegian", "swedish",
    "polish", "russian", "ukrainian", "austrian", "bulgarian", "croatian", "czech", "greek",
    "hungarian", "latvian", "lithuanian", "romanian", "slovak", "slovenian", "turkish", "japanese",
    "china", "taiwan", "hong_kong", "south_korea", "singapore", "indonesian", "malaysia", "philippines",
    "vietnamese", "brazilian", "mexican", "new_zealand", "jewish", "christian", "islamic", "judaism",
    "hinduism", "orthodox_christianity", "sa", "ar", "cl", "co", "pe", "th",
    "be", "ch", "lu", "is", "rs", "eg", "pk", "bd",
    "af", "al", "dz", "ad", "ao", "ag", "am", "az",
    "bs", "bh", "bb", "by", "bz", "bj", "bt", "bo",
    "ba", "bw", "bn", "bf", "bi", "cv", "kh", "cm",
    "cf", "td", "km", "cg", "cd", "cr", "ci", "cu",
    "cy", "dj", "dm", "do", "ec", "sv", "gq", "er",
    "ee", "sz", "et", "fj", "ga", "gm", "ge", "gh",
    "gd", "gt", "gn", "gw", "gy", "ht", "hn", "ir",
    "iq", "jm", "jo", "kz", "ke", "ki", "kw", "kg",
    "la", "lb", "ls", "lr", "ly", "li", "mg", "mw",
    "mv", "ml", "mt", "mh", "mr", "mu", "fm", "md",
    "mc", "mn", "me", "ma", "mz", "mm", "na", "nr",
    "np", "ni", "ne", "ng", "mk", "om", "pw", "pa",
    "pg", "py", "qa", "rw", "kn", "lc", "vc", "ws",
    "sm", "st", "sn", "sc", "sl", "sb", "so", "ss",
    "lk", "sd", "sr", "sy", "tj", "tz", "tl", "tg",
    "to", "tt", "tn", "tm", "tv", "ug", "ae", "uy",
    "uz", "vu", "ve", "ye", "zm", "zw", "mo", "pr",
    "gu", "vi",
]

BASE_DIR = pathlib.Path(__file__).resolve().parents[2] / "calendar/src/main/assets/holidays"


def print_progress(label, current, total):
    width = 30
    filled = int(current / total * width)
    bar = "\r%s [%s] %d/%d (%d%%)" % (
        label, "\u2588" * filled + "\u2591" * (width - filled),
        current, total, round(current / total * 100),
    )
    print(bar, end="", flush=True)
    if current == total:
        print()


def holidays_equal(a, b):
    return a == b


def write_json(path, obj):
    path.write_text(json.dumps(obj, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
        if resp.status // 100 != 2:
            raise RuntimeError("HTTP %d" % resp.status)
        return resp.read().decode("utf-8")


def unfold(ics):
    out = []
    for raw in re.split(r"\r?\n", ics):
        if (raw.startswith(" ") or raw.startswith("\t")) and out:
            out[-1] = out[-1] + raw[1:]
        else:
            out.append(raw)
    return out


def unescape(s):
    b = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            i += 1
            n = s[i]
            if n in ("n", "N"):
                b.append(" ")
            elif n == ",":
                b.append(",")
            elif n == ";":
                b.append(";")
            elif n == "\\":
                b.append("\\")
            else:
                b.append(n)
        else:
            b.append(c)
        i += 1
    return "".join(b).strip()


def calendar_name(lines):
    for line in lines:
        if line.startswith("X-WR-CALNAME:"):
            name = line[len("X-WR-CALNAME:"):]
            name = re.sub(r"^Holidays and Observances in ", "", name)
            name = re.sub(r"^Holidays in ", "", name)
            return name.strip()
    return None


def country_code(name):
    return re.sub(r"[^A-Za-z0-9]", "", name)


def parse_events(lines):
    out = []
    in_event = False
    summary = None
    date = None
    for line in lines:
        if line == "BEGIN:VEVENT":
            in_event = True
            summary = None
            date = None
        elif line == "END:VEVENT":
            if summary is not None and date is not None:
                out.append((date, summary))
            in_event = False
        elif not in_event:
            continue
        else:
            colon = line.find(":")
            if colon <= 0:
                continue
            left = line[:colon].upper()
            value = line[colon + 1:]
            if left == "SUMMARY":
                summary = unescape(value)
            elif left.startswith("DTSTART"):
                m = re.search(r"([0-9]{4})([0-9]{2})([0-9]{2})", value)
                if m:
                    date = "%s-%s-%s" % m.groups()
    return out


def sort_holidays(holidays):
    return sorted(holidays, key=lambda h: (h[0], h[1].casefold()))


def load_country_languages(path):
    """Parses an existing country_languages.json into {code: [langs]} (ordered)."""
    country_langs = {}
    try:
        text = path.read_text(encoding="utf-8")
        for m in re.finditer(r'"([^"]+)":\[([^\]]*)\]', text):
            langs = [lang.strip().strip('"') for lang in m.group(2).split(",")]
            langs = [lang for lang in langs if lang]
            if langs:
                country_langs[m.group(1)] = langs
    except OSError:
        pass
    return country_langs


def main(argv):
    start_slug = argv[0] if argv else None
    base_dir = BASE_DIR
    resume = start_slug is not None and base_dir.exists()

    if not resume and base_dir.exists():
        shutil.rmtree(base_dir)
    base_dir.mkdir(parents=True, exist_ok=True)
    if resume:
        print("Resuming from slug: %s" % start_slug)

    # Write languages.json at top level
    write_json(
        base_dir / "languages.json",
        [{"code": code, "name": name} for code, name in LANGS],
    )

    # Phase 1: Build canonical slug -> code mapping from English feed
    print("Phase 1/2: Building canonical index from English...")
    slug_to_code = {}
    canonical_index = []

    for i, slug in enumerate(SLUGS):
        print_progress("  Fetching English", i + 1, len(SLUGS))
        try:
            ics = get(URL_TEMPLATE % ("en", slug))
            name = calendar_name(unfold(ics))
            if name is None:
                continue
            code = country_code(name)
            slug_to_code[slug] = code
            canonical_index.append({"code": code, "name": name})
        except Exception as ex:
            print("\n  [en] Skipping %s: %s" % (slug, ex), file=sys.stderr)
    print()
    canonical_index.sort(key=lambda e: e["name"].casefold())
    print("  \u2713 Canonical: %d countries found\n" % len(canonical_index))

    # Write top-level index.json
    write_json(base_dir / "index.json", canonical_index)

    # Phase 2: For each slug, fetch English first, then other langs
    print("Phase 2/2: Fetching localized holidays (comparing to English, only saving differences)...")
    country_langs = {}
    if resume:
        country_langs = load_country_languages(base_dir / "country_languages.json")
        if country_langs:
            print("  Loaded existing progress for %d countries" % len(country_langs))

    total_written = 0
    total_countries = len(slug_to_code)
    country_idx = 0
    total_langs = len(LANGS) - 1
    started = start_slug is None

    for slug in SLUGS:
        if not started:
            if slug == start_slug:
                started = True
            else:
                continue
        code = slug_to_code.get(slug)
        if code is None:
            continue
        country_idx += 1

        # Fetch English baseline
        try:
            en_ics = get(URL_TEMPLATE % ("en", slug))
            en_holidays = sort_holidays(parse_events(unfold(en_ics)))
            if not en_holidays:
                continue
        except Exception as ex:
            print("\n  [en] Skipping %s: %s" % (slug, ex), file=sys.stderr)
            continue

        # Write English
        en_dir = base_dir / "en"
        en_dir.mkdir(parents=True, exist_ok=True)
        write_json(en_dir / ("%s.json" % code), [{"d": d, "n": n} for d, n in en_holidays])
        country_langs.setdefault(code, [])
        if "en" not in country_langs[code]:
            country_langs[code].append("en")
        total_written += 1

        # Fetch other languages, compare to English
        lang_idx = 0
        distinct_count = 1
        for lang, _lang_name in LANGS:
            if lang == "en":
                continue
            lang_idx += 1
            print_progress(
                "  [%d/%d] %s (%d langs)" % (country_idx, total_countries, code, distinct_count),
                lang_idx, total_langs,
            )
            try:
                ics = get(URL_TEMPLATE % (lang, slug))
                holidays = sort_holidays(parse_events(unfold(ics)))
                if not holidays:
                    continue
                if holidays_equal(en_holidays, holidays):
                    continue
                lang_dir = base_dir / lang
                lang_dir.mkdir(parents=True, exist_ok=True)
                write_json(lang_dir / ("%s.json" % code), [{"d": d, "n": n} for d, n in holidays])
                country_langs.setdefault(code, [])
                if lang not in country_langs[code]:
                    country_langs[code].append(lang)
                distinct_count += 1
                total_written += 1
            except Exception:
                # skip silently
                pass
    print()

    # Write per-language index.json
    for lang, _lang_name in LANGS:
        lang_dir = base_dir / lang
        if not lang_dir.exists():
            continue
        index = [e for e in canonical_index if (lang_dir / ("%s.json" % e["code"])).exists()]
        index.sort(key=lambda e: e["name"].casefold())
        write_json(lang_dir / "index.json", index)

    # Copy English to flat structure for backward compat
    en_dir = base_dir / "en"
    for src in sorted(en_dir.glob("*.json")):
        if src.name != "index.json":
            shutil.copyfile(src, base_dir / src.name)

    # Write country_languages.json
    write_json(base_dir / "country_languages.json", country_langs)

    print("  \u2713 Wrote %d files across %d languages to %s" % (total_written, len(LANGS), base_dir))


if __name__ == "__main__":
    main(sys.argv[1:])
