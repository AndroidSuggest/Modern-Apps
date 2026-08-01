#!/usr/bin/env bash
# Verification script: check the Compose-preview store-screenshot setup is coherent.
#
# None of this needs a compiler, which is the point — it catches the mechanical mistakes
# that are otherwise only found by a full Gradle run:
#
#   - a @Preview that is missing @PreviewTest (renders in Studio, silently not collected,
#     surfaces as the unhelpful "did not discover any tests")
#   - previews declared as top-level functions instead of class members (same symptom)
#   - preview functions not named Preview<N>… (listing order comes from sorting the
#     generated filenames, so a stray name silently reshuffles the store listing)
#   - a leftover src/androidTest MetadataScreenshots.kt from the old on-device generator
#   - a module that opted into previews but has no @Preview at all
#
# On-device capture is gone. A module either renders its listing from previews, or its
# images are hand-captured and committed under metadata_data/photos/ (camera and
# games/voxels: a live viewfinder and a Vulkan surface, neither of which Layoutlib draws).
#
# Exit 0 = clean, non-zero = problems found.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

problems=0

note() { printf '  %s\n' "$1"; }
fail() { printf '  ✗ %s\n' "$1"; problems=$((problems + 1)); }

echo "=== Compose preview metadata audit ==="
echo

# Sapling keeps copies of build files under .git/sl/origbackups; they are not modules.
app_modules() {
    grep -rl "common-conventions-app" --include="build.gradle.kts" . 2>/dev/null \
        | grep -v '/build/' | grep -v '^\./\.git/' | sed 's|^\./||' | sort
}

for gradle_file in $(app_modules); do
    module="${gradle_file%/build.gradle.kts}"
    # Match the id(...) declaration only — a module can legitimately *mention* the other
    # convention in a comment explaining why it is not using it (games/voxels does).
    uses_preview=$(grep -cE '^[[:space:]]*id\("common-conventions-preview-metadata"\)' "$gradle_file" || true)
    preview_dir="$module/src/screenshotTest"
    old_gen=$(find "$module/src/androidTest" -name 'MetadataScreenshots.kt' 2>/dev/null | head -1)

    if [[ "$uses_preview" -eq 0 ]]; then
        # Manual or unpublished. Either way it must not carry preview sources that nothing
        # renders.
        if [[ -d "$preview_dir" ]]; then
            echo "$module"
            fail "has src/screenshotTest but does not apply common-conventions-preview-metadata"
        fi
        continue
    fi

    header_shown=0
    show() { [[ $header_shown -eq 0 ]] && echo "$module" && header_shown=1; }

    if [[ ! -d "$preview_dir" ]]; then
        show; fail "applies preview-metadata but has no src/screenshotTest"
        continue
    fi

    if [[ -n "$old_gen" ]]; then
        show; fail "leftover on-device generator: $old_gen"
    fi

    files=$(find "$preview_dir" -name '*.kt' 2>/dev/null)
    if [[ -z "$files" ]]; then
        show; fail "src/screenshotTest contains no Kotlin sources"
        continue
    fi

    n_preview=0
    n_previewtest=0
    for f in $files; do
        # Count annotations only where they annotate a declaration, not in prose/KDoc.
        p=$(grep -cE '^[[:space:]]*@Preview\(' "$f" || true)
        t=$(grep -cE '^[[:space:]]*@PreviewTest[[:space:]]*$' "$f" || true)
        n_preview=$((n_preview + p))
        n_previewtest=$((n_previewtest + t))

        # Preview functions must be class members; a top-level one lands in a synthetic
        # ...Kt facade the engine does not scan.
        if grep -qE '^fun Preview[0-9]' "$f"; then
            show; fail "top-level preview function in ${f#"$module"/}; must be a class member"
        fi

        # Listing order is filename order, which embeds the function name.
        # BSD sed (macOS) has no \s, so use POSIX classes throughout.
        badly_named=$(grep -oE '^[[:space:]]+fun [A-Za-z0-9_]+\(\)' "$f" \
            | sed -E 's/^[[:space:]]+fun //; s/\(\)//' \
            | grep -vE '^Preview[0-9]' || true)
        for name in $badly_named; do
            # Only complain about functions that are actually previews.
            if grep -B4 -E "fun $name\(\)" "$f" | grep -q '@Preview('; then
                show; fail "preview function '$name' is not named Preview<N>…; listing order will be wrong"
            fi
        done
    done

    if [[ "$n_preview" -eq 0 ]]; then
        show; fail "no @Preview found under src/screenshotTest"
    elif [[ "$n_previewtest" -ne "$n_preview" ]]; then
        show; fail "$n_preview @Preview but $n_previewtest @PreviewTest — every preview needs both"
    fi

    [[ $header_shown -eq 1 ]] || printf '%-24s %s preview(s)\n' "$module" "$n_preview"
done

echo
echo "--- shared infrastructure ---"
for f in build-logic/src/main/kotlin/common-conventions-preview-metadata.gradle.kts \
         gradle/libs.versions.toml gradle.properties .gitignore; do
    [[ -f "$f" ]] || fail "missing $f"
done
grep -q 'android.experimental.enableScreenshotTest=true' gradle.properties \
    || fail "gradle.properties is missing android.experimental.enableScreenshotTest=true"
grep -q 'screenshot-validation-api' gradle/libs.versions.toml \
    || fail "libs.versions.toml is missing screenshot-validation-api (supplies @PreviewTest)"
grep -q 'src/screenshotTest\*/reference/' .gitignore \
    || fail ".gitignore is not ignoring the rendered reference images"

echo
preview_modules=0
while read -r gf; do
    if grep -qE '^[[:space:]]*id\("common-conventions-preview-metadata"\)' "$gf"; then
        preview_modules=$((preview_modules + 1))
    fi
done < <(app_modules)
echo "=== $preview_modules module(s) render their listing from Compose previews ==="
if [[ $problems -gt 0 ]]; then
    echo "=== $problems problem(s) found ==="
    exit 1
fi
echo "=== clean ==="
