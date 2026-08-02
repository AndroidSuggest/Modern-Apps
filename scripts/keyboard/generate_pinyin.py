#!/usr/bin/env python3
"""Offline generator: ICU Han-Latin readings -> keyboard/src/main/assets/dict/pinyin_*.txt

The Chinese layouts need a pinyin-syllable -> characters index, and there is no such
table in the repo. The readings come from ICU's `Han-Latin` transliterator (i.e. from
Unicode's Unihan `kMandarin` data), read here through the system ICU library so the
generated asset is reproducible from Unicode data alone rather than from a scraped
word list.

Ranking is the hard part: Unihan knows *which* characters read "zhong" but not which
one a person means. Three tiers, in order:

  1. HAND_FREQ below -- the most common characters, in rough frequency order, written
     out by hand. It contains both simplified and traditional forms; each output table
     keeps only the ones its own encoding can represent, which is what makes 这 rank
     first for simplified and 這 first for traditional from a single list.
  2. Level 1 of the table's own legacy encoding (GB2312 for simplified, Big5 for
     traditional) -- the ~5k characters those standards considered common.
  3. Level 2, then the other script's characters, then everything else by codepoint.

Two files are emitted rather than one shared list, because a traditional-script user
offered 国 before 國 has to skip past a character they will never want.

Format: `syllable<TAB>characters`, one syllable per line, sorted; characters in rank
order. Parsed by PinyinDictionary.kt.

Usage:  python3 scripts/keyboard/generate_pinyin.py
"""

import ctypes
import unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OUT_DIR = REPO / "keyboard" / "src" / "main" / "assets" / "dict"

# CJK Unified Ideographs. Ext-A and beyond are rare enough that including them only
# lengthens candidate lists with characters nobody is reaching for.
FIRST, LAST = 0x4E00, 0x9FFF

# Rough frequency order, simplified and traditional interleaved. Only ordering depends
# on this -- an omission costs a character some rank, it never removes it from the
# candidate list, which comes from Unihan.
HAND_FREQ = (
    "的一是了不我人在有他这這个個中大来來上国國说說们們为為子和你地出道时時年得就那要下以生会會自着"
    "着去之过過家学學对對可她里裡後后小么麼心多天而能好都然没沒日于於起还還发發成事只作当當想看文无無"
    "开開手十用主行方又如前所本见見经經头頭面公同三已老从從动動两兩长長知民样樣与與定十点點种種间間"
    "情感话話回位分爱愛给給名法期它信问問身者被高其此常正真明力全比第水白话世界者九八七六五四百千万萬"
    "太少几幾次使身车車走空东東南西北先生活工作学校北京上海中国朝夜早晚昨今明天气氣候雨雪风風云雲山水火"
    "土木金石田米茶饭飯菜肉鱼魚鸡雞蛋奶果树樹花草虫蟲鸟鳥马馬牛羊猪豬狗猫貓门門窗床桌椅书書笔筆纸紙"
    "电電话話视視脑腦机機车車船飞飛路桥橋店市场場院房屋家庭父母兄弟姐妹儿兒女男朋友同事老师師医醫生病"
    "药藥钱錢买買卖賣贵貴便宜快慢新旧舊高低长长短远遠近深浅淺重轻輕难難易忙闲閒累饿餓渴冷热熱疼痛喜怒哀乐樂"
)

# Syllables ICU can produce that are not pinyin (it passes some characters through).
VALID_INITIALS = ("b p m f d t n l g k h j q x zh ch sh r z c s y w").split()


def icu_transliterator(identifier):
    """An ICU transliterator from the system library, as a str -> str function."""
    lib = ctypes.CDLL("/usr/lib/libicucore.dylib")
    lib.utrans_openU.restype = ctypes.c_void_p
    lib.utrans_openU.argtypes = [
        ctypes.c_void_p, ctypes.c_int32, ctypes.c_int, ctypes.c_void_p,
        ctypes.c_int32, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int),
    ]
    err = ctypes.c_int(0)
    ident = identifier.encode("utf-16-le")
    handle = lib.utrans_openU(
        ctypes.create_string_buffer(ident), len(ident) // 2, 0, None, 0, None,
        ctypes.byref(err),
    )
    if not handle or err.value > 0:
        raise SystemExit(f"could not open the {identifier} transliterator (ICU error {err.value})")

    def translate(text):
        data = text.encode("utf-16-le")
        capacity = max(1024, len(data) * 8)
        buf = ctypes.create_string_buffer(capacity * 2)
        ctypes.memmove(buf, data, len(data))
        length = ctypes.c_int32(len(data) // 2)
        limit = ctypes.c_int32(len(data) // 2)
        status = ctypes.c_int(0)
        lib.utrans_transUChars(
            ctypes.c_void_p(handle), buf, ctypes.byref(length), capacity, 0,
            ctypes.byref(limit), ctypes.byref(status),
        )
        if status.value > 0:
            raise SystemExit(f"transliteration failed (ICU error {status.value})")
        return bytes(buf.raw[: length.value * 2]).decode("utf-16-le")

    return translate


def strip_tone(syllable):
    """`zhōng` -> `zhong`; the layouts have no tone keys, so the index is toneless."""
    decomposed = unicodedata.normalize("NFD", syllable)
    return "".join(c for c in decomposed if not unicodedata.combining(c)).lower()


def encodable(char, encoding):
    try:
        char.encode(encoding)
        return True
    except UnicodeEncodeError:
        return False


def level(char, encoding, level1_end):
    """0 for the encoding's level-1 (common) block, 1 for the rest of it, 2 for absent."""
    try:
        raw = char.encode(encoding)
    except UnicodeEncodeError:
        return 2
    return 0 if int.from_bytes(raw, "big") <= level1_end else 1


def build_index(translate):
    """syllable -> [characters], every CJK ideograph ICU can read."""
    chars = [chr(c) for c in range(FIRST, LAST + 1)]
    # One call per 1000 characters: the transliterator is slow to invoke, and its
    # output is space-separated readings, one per input character.
    index = {}
    for start in range(0, len(chars), 1000):
        chunk = chars[start : start + 1000]
        readings = translate("".join(chunk)).split(" ")
        if len(readings) != len(chunk):
            raise SystemExit(f"reading count mismatch at {start}: {len(readings)} vs {len(chunk)}")
        for char, reading in zip(chunk, readings):
            syllable = strip_tone(reading)
            if not syllable.isascii() or not syllable.isalpha():
                continue  # ICU passed the character through unread
            index.setdefault(syllable, []).append(char)
    return index


def write_table(index, path, encoding, level1_end, other_encoding):
    rank_of_hand = {}
    for i, char in enumerate(HAND_FREQ):
        if char not in rank_of_hand and encodable(char, encoding):
            rank_of_hand[char] = i

    def sort_key(char):
        hand = rank_of_hand.get(char)
        if hand is not None:
            return (0, hand, 0)
        own = level(char, encoding, level1_end)
        if own < 2:
            return (1 + own, 0, ord(char))
        # Present in the other script's standard but not this one: still worth
        # ranking above the long tail of characters no standard bothered with.
        return (3 if encodable(char, other_encoding) else 4, 0, ord(char))

    lines = [
        "# Pinyin syllable -> characters, for the Chinese layouts.",
        "# Generated by scripts/keyboard/generate_pinyin.py from Unicode/ICU Han-Latin",
        "# readings. Format: syllable<TAB>characters, most likely candidate first.",
    ]
    for syllable in sorted(index):
        chars = sorted(set(index[syllable]), key=sort_key)
        lines.append(f"{syllable}\t{''.join(chars)}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{path.name}: {len(index)} syllables, {path.stat().st_size // 1024} KiB")


def write_bopomofo(index, translate_bopomofo, path):
    """bopomofo spelling -> pinyin syllable, so 注音 can share the pinyin index."""
    lines = [
        "# Bopomofo (注音) spelling -> pinyin syllable, for the 注音 layout.",
        "# Generated by scripts/keyboard/generate_pinyin.py via ICU Latin-Bopomofo.",
    ]
    seen = {}
    for syllable in sorted(index):
        spelling = "".join(
            c for c in translate_bopomofo(syllable) if 0x3105 <= ord(c) <= 0x3129
        )
        if spelling and spelling not in seen:
            seen[spelling] = syllable
    for spelling in sorted(seen):
        lines.append(f"{spelling}\t{seen[spelling]}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{path.name}: {len(seen)} spellings")


def main():
    index = build_index(icu_transliterator("Han-Latin"))
    dropped = [s for s in index if not any(s.startswith(i) for i in VALID_INITIALS) and s not in ("a", "o", "e", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "er")]
    for syllable in dropped:
        del index[syllable]
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    # GB2312 level 1 ends at 0xD7F9; Big5 level 1 ends at 0xC67E.
    write_table(index, OUT_DIR / "pinyin_sc.txt", "gb2312", 0xD7F9, "big5")
    write_table(index, OUT_DIR / "pinyin_tc.txt", "big5", 0xC67E, "gb2312")
    write_bopomofo(index, icu_transliterator("Latin-Bopomofo"), OUT_DIR / "bopomofo.txt")


if __name__ == "__main__":
    main()
