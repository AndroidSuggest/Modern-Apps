#!/bin/bash

# release.sh - Local script to prepare release, build, tag, and push (Headless mode)
set -e

# 0. Environment check: Ensure working directory is clean
if [[ -n $(git status --porcelain) ]]; then
    echo "❌ Error: Your working directory is not clean."
    echo "Please commit or stash your changes before running the release script."
    exit 1
fi

# Check for GitHub CLI (gh)
if ! command -v gh &> /dev/null; then
    echo "⚠️  Warning: 'gh' (GitHub CLI) is not installed. Release creation will be skipped."
    echo "   Install it with: sudo apt install gh (on Debian/Ubuntu)"
    HAS_GH=false
else
    HAS_GH=true
fi

ORIGINAL_COMMIT=$(git rev-parse HEAD)
VERSION_FILE="version.txt"

if [ ! -f "$VERSION_FILE" ]; then
    echo "Error: $VERSION_FILE not found!"
    exit 1
fi

VERSION_CODE=$(sed -n '1p' "$VERSION_FILE" | tr -d '\r' | xargs)
VERSION_NAME=$(sed -n '2p' "$VERSION_FILE" | tr -d '\r' | xargs)

export SOURCE_DATE_EPOCH=$(git log -1 --pretty=%ct)
echo "🕒 SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH for reproducible builds"

echo "🚀 Preparing headless release $VERSION_NAME ($VERSION_CODE)..."

# 1. Inject version into build.gradle.kts files
echo "📝 Injecting versions into sub-module build.gradle.kts files..."
find . -mindepth 2 -name "build.gradle.kts" | while read file; do
    if [[ "$file" == *"build-logic"* ]]; then continue; fi
    sed -i '/versionCode[[:space:]]*=[[:space:]]*/d' "$file"
    sed -i '/versionName[[:space:]]*=[[:space:]]*/d' "$file"
    sed -i "/defaultConfig[[:space:]]*{/a \        versionCode = $VERSION_CODE\n        versionName = \"$VERSION_NAME\"" "$file"
done

# 2. Prepare F-Droid Metadata
echo "📦 Preparing F-Droid metadata..."
MODULE_DIRS=$(find . -mindepth 2 -name "build.gradle.kts" | sed 's|^\./||; s|/build.gradle.kts$||' | sort -u)

for dir in $MODULE_DIRS; do
    module_key=$(echo $dir | sed 's|/|-|g')
    target_path="$dir/src/main/play/listings/en-US"
    if [ -f "metadata_data/${module_key}.md" ] || [ -f "$dir/src/main/ic_launcher-playstore.png" ]; then
        mkdir -p "$target_path/graphics/icon"
        mkdir -p "$target_path/graphics/phone-screenshots"
        if [ -f "$dir/src/main/ic_launcher-playstore.png" ]; then cp "$dir/src/main/ic_launcher-playstore.png" "$target_path/graphics/icon/icon.png"; fi
        if [ -d "metadata_data/photos/${module_key}" ]; then
            i=1
            for shot in $(ls "metadata_data/photos/${module_key}"/*.png 2>/dev/null | sort -V); do
                cp "$shot" "$target_path/graphics/phone-screenshots/${i}.png"
                i=$((i + 1))
            done
        elif [ -f "metadata_data/photos/${module_key}.png" ]; then
            cp "metadata_data/photos/${module_key}.png" "$target_path/graphics/phone-screenshots/1.png"
        fi
        if [ -f "metadata_data/${module_key}.md" ]; then
            head -n 1 "metadata_data/${module_key}.md" > "$target_path/short-description.txt"
            cp "metadata_data/${module_key}.md" "$target_path/full-description.txt"
        fi
    fi
done

# 3. Git Commit and Tag (Done BEFORE build so build sees committed state)
echo "💾 Creating temporary commit and tag..."
# Reproducible builds: make commit/tag timestamps deterministic using SOURCE_DATE_EPOCH
# so the tag object doesn't embed wall-clock time and can be verified.
export GIT_AUTHOR_DATE="@${SOURCE_DATE_EPOCH}"
export GIT_COMMITTER_DATE="@${SOURCE_DATE_EPOCH}"
git add .
git commit -m "chore: prepare release $VERSION_NAME" || { echo "No changes to commit"; exit 1; }
git tag -a "$VERSION_NAME" -m "Release $VERSION_NAME"

# 4. Build Apps (Parallel with Worker Limits)
echo "🔨 Building all app modules in parallel (constrained)..."
# Detect modules that apply the app conventions
APP_MODULES=$(grep -r "common-conventions-app" . --include="build.gradle.kts" | cut -d: -f1 | sed 's|^\./||; s|/build.gradle.kts$||' | sort -u)
TASKS=$(echo "$APP_MODULES" | sed 's|^|:|; s|/|:|g; s|$|:assembleRelease|' | tr '\n' ' ')

rm -rf distribution_apks
mkdir -p distribution_apks

# - --parallel: Build modules in parallel
# - --max-workers=2: Strictly limit concurrent tasks to prevent memory spikes
# - --no-daemon: Use a fresh process to ensure memory is released after build
./gradlew $TASKS \
    --parallel \
    --max-workers=2 \
    --no-daemon \
    -x lint \
    -x test \
    -Pandroid.enableResourceOptimizations=true \
    -Pandroid.enableR8.fullMode=true \
    -Dorg.gradle.jvmargs="-Xmx6g -XX:MaxMetaspaceSize=1g"

# 5. Consolidate APKs
echo "📂 Collecting APKs into distribution_apks/..."
find . -path "*/build/outputs/apk/release/*.apk" -exec cp {} distribution_apks/ \;

# 5b. Build index.json for the :appstore "Modern Apps" source.
# The store cannot map an asset filename back to a package name on its own
# (:games:chess builds chess-release.apk but installs as com.vayunmathur.games.chess),
# so the release has to state it. This index is NOT signed and does not need to be:
# :appstore verifies every APK from this source against its own signing certificate,
# so a rewritten index can change which bytes are offered but not which bytes install.
echo "🧾 Generating distribution_apks/index.json..."
AAPT2=$(ls -1 "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
if [ -z "$AAPT2" ]; then
    echo "❌ aapt2 not found under \$ANDROID_HOME/build-tools; cannot generate index.json"
    exit 1
fi

{
    printf '{\n  "versionCode": %s,\n  "versionName": "%s",\n  "apps": [\n' "$VERSION_CODE" "$VERSION_NAME"
    first=true
    for apk in distribution_apks/*.apk; do
        [ -e "$apk" ] || continue
        badging=$("$AAPT2" dump badging "$apk" 2>/dev/null)
        pkg=$(echo "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
        vcode=$(echo "$badging" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p")
        vname=$(echo "$badging" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p")
        tsdk=$(echo "$badging" | sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p")
        label=$(echo "$badging" | sed -n "s/^application-label:'\([^']*\)'.*/\1/p")
        if [ -z "$pkg" ]; then
            echo "⚠️  Skipping $apk (aapt2 could not read a package name)" >&2
            continue
        fi
        [ -n "$label" ] || label="$pkg"
        sha=$(shasum -a 256 "$apk" | cut -d' ' -f1)
        size=$(wc -c < "$apk" | tr -d ' ')
        # Short description = first line of the F-Droid metadata, when there is one.
        module_key=$(grep -rl "applicationId = \"$pkg\"" --include=build.gradle.kts . 2>/dev/null \
            | head -1 | sed 's|^\./||; s|/build.gradle.kts$||; s|/|-|g')
        summary=""
        if [ -n "$module_key" ] && [ -f "metadata_data/${module_key}.md" ]; then
            summary=$(head -n 1 "metadata_data/${module_key}.md" | sed 's/\\/\\\\/g; s/"/\\"/g')
        fi
        $first || printf ',\n'
        first=false
        printf '    {"packageName": "%s", "label": "%s", "apk": "%s", "sha256": "%s", "size": %s, "versionCode": %s, "versionName": "%s", "targetSdk": %s, "summary": "%s"}' \
            "$pkg" "$(echo "$label" | sed 's/\\/\\\\/g; s/"/\\"/g')" "$(basename "$apk")" \
            "$sha" "$size" "${vcode:-0}" "${vname:-$VERSION_NAME}" "${tsdk:-0}" "$summary"
    done
    printf '\n  ]\n}\n'
} > distribution_apks/index.json

echo "   $(grep -c '"packageName"' distribution_apks/index.json) apps indexed"

# 6. Push Tag and Create GitHub Release
echo "📤 Pushing tag $VERSION_NAME to origin..."
git push origin "$VERSION_NAME"

if [ "$HAS_GH" = true ]; then
    echo "🎁 Creating GitHub Release..."
    gh release create "$VERSION_NAME" distribution_apks/*.apk distribution_apks/index.json \
        --title "Release $VERSION_NAME" \
        --notes "Automated multi-module release. Version Code: $VERSION_CODE" \
        --draft
    echo "✅ Release created as a draft."
else
    echo "⚠️  Skipping GitHub Release creation (gh CLI not found)."
fi

# 7. Restore local branch state (Headless effect)
echo "🧹 Restoring local branch to original state ($ORIGINAL_COMMIT)..."
git reset --hard "$ORIGINAL_COMMIT"

echo "✅ Done! All apps built and tag pushed."
