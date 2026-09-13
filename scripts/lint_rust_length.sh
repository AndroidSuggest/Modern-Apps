#!/usr/bin/env bash
# Verification script: flag Rust files longer than 500 lines.
# Mirrors lint-rules FileLengthDetector (350 ui / 800 elsewhere for Kotlin),
# which cannot see Rust — this covers *.rs instead. Advisory, like the other
# 6 non-fatal custom rules: exit 0 always unless --strict is passed.
# Excludes: target/ build output, generated files, vendored third-party,
# test fixtures (long by nature, same rationale as the Kotlin rule).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LIMIT=500
STRICT=0
if [[ "${1:-}" == "--strict" ]]; then
  STRICT=1
fi

echo "=== Rust file length audit (limit $LIMIT lines) ==="

HITS=$(find library maps scripts -name '*.rs' -not -path '*/target/*' -not -path '*/build/*' \
  | while read -r f; do
      # Skip generated + vendored + test fixtures.
      case "$f" in
        */generated/*|*/third_party/*|*/tests/*|*/test_fixtures/*|*/examples/*) continue;;
      esac
      n=$(wc -l < "$f")
      if [[ "$n" -gt "$LIMIT" ]]; then
        echo "$n $f"
      fi
    done | sort -rn || true)

if [[ -n "$HITS" ]]; then
  echo "$HITS"
  COUNT=$(echo "$HITS" | wc -l)
  echo "Found $COUNT file(s) over $LIMIT lines — split along module lines so each file stays reviewable."
else
  echo "No Rust files over $LIMIT lines"
  COUNT=0
fi

if [[ "$STRICT" -eq 1 && "$COUNT" -gt 0 ]]; then
  echo "FAIL: Rust files exceed the length limit"
  exit 1
fi
echo "PASS (advisory)"
exit 0
