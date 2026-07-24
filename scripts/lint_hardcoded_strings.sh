#!/usr/bin/env bash
# Verification script: detect hardcoded UI strings that should be in strings.xml
# Excludes contentDescription (out-of-scope per plan), symbolic single-char, and
# already-migrated stringResource / R.string usages.
# Exit 0 = clean, non-zero = violations found.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "=== Hardcoded string audit (excluding contentDescription, single-char symbols) ==="

# 1) Text("...") with alphabetic content
echo ""
echo "--- Text literals ---"
TEXT_HITS=$(grep -R 'Text\s*(\s*"' --include="*.kt" -n \
    --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git \
    . 2>/dev/null \
  | grep -v -E 'stringResource|R\.string|Log\.|//.*Text\(|contentDescription|newPlainText|ClipData|ClipEntry' \
  | grep -v -E 'androidTest|/test/|build/|origbackups|/.git/|/extractor/' \
  | python3 scripts/filter_text.py \
  || true)

if [[ -n "$TEXT_HITS" ]]; then
  echo "$TEXT_HITS"
  TEXT_COUNT=$(echo "$TEXT_HITS" | wc -l)
  echo "Found $TEXT_COUNT Text() violations"
else
  echo "No Text() violations"
  TEXT_COUNT=0
fi

# 2) Toast.makeText with literal
echo ""
echo "--- Toast.makeText literals ---"
TOAST_HITS=$(grep -R 'Toast\.makeText' --include="*.kt" -n \
    --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git \
    . 2>/dev/null \
  | grep -v -E 'androidTest|/test/|origbackups|/.git/|/extractor/' \
  | grep '"' \
  | grep -v -E 'R\.string|getString|stringResource' \
  || true)

if [[ -n "$TOAST_HITS" ]]; then
  echo "$TOAST_HITS"
  TOAST_COUNT=$(echo "$TOAST_HITS" | wc -l)
  echo "Found $TOAST_COUNT Toast violations"
else
  echo "No Toast violations"
  TOAST_COUNT=0
fi

# 3) label = { Text("...") } / placeholder with literals
echo ""
echo "--- label / placeholder / title literals ---"
LABEL_HITS=$(grep -R -E '(label\s*=\s*\{\s*Text\s*\(\s*"|placeholder\s*=\s*\{\s*Text\s*\(\s*")' \
    --include="*.kt" -n --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git \
    . 2>/dev/null \
  | grep -v -E 'stringResource|R\.string' \
  | grep -v -E 'androidTest|origbackups|/.git/|/extractor/' \
  || true)

if [[ -n "$LABEL_HITS" ]]; then
  echo "$LABEL_HITS"
  LABEL_COUNT=$(echo "$LABEL_HITS" | wc -l)
  echo "Found $LABEL_COUNT label/placeholder violations"
else
  echo "No label/placeholder violations"
  LABEL_COUNT=0
fi

# 4) contentDescription = "literal" (accessibility text, screen-reader visible)
echo ""
echo "--- contentDescription literals ---"
CD_HITS=$(grep -R -E 'contentDescription\s*=\s*"' --include="*.kt" -n \
    --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git \
    . 2>/dev/null \
  | grep -v -E 'stringResource|R\.string' \
  | grep -v -E 'androidTest|origbackups|/.git/|/extractor/' \
  | grep -E 'contentDescription\s*=\s*"[^"]*[A-Za-z]{3,}' \
  || true)

if [[ -n "$CD_HITS" ]]; then
  echo "$CD_HITS"
  CD_COUNT=$(echo "$CD_HITS" | wc -l)
  echo "Found $CD_COUNT contentDescription violations"
else
  echo "No contentDescription violations"
  CD_COUNT=0
fi

# 5) Plural string-gluing (e.g. if (n == 1) "" else "s") — must use <plurals>
echo ""
echo "--- plural string-gluing ---"
PLURAL_HITS=$(grep -R -E 'if \([^)]*==\s*1[lL]?\)\s*"[a-z]*"\s*else\s*"[a-z]+"' \
    --include="*.kt" -n --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git \
    . 2>/dev/null \
  | grep -v -E 'androidTest|origbackups|/.git/|/extractor/' \
  || true)

if [[ -n "$PLURAL_HITS" ]]; then
  echo "$PLURAL_HITS"
  PLURAL_COUNT=$(echo "$PLURAL_HITS" | wc -l)
  echo "Found $PLURAL_COUNT plural-gluing violations"
else
  echo "No plural-gluing violations"
  PLURAL_COUNT=0
fi

echo ""
echo "=== Summary ==="
TOTAL=$((TEXT_COUNT + TOAST_COUNT + LABEL_COUNT + CD_COUNT + PLURAL_COUNT))
echo "Total violations: $TOTAL"

if [[ $TOTAL -gt 0 ]]; then
  echo "FAIL: hardcoded strings remain"
  exit 1
else
  echo "PASS: no hardcoded UI strings found"
  exit 0
fi
