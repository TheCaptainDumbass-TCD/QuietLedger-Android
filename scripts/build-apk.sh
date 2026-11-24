#!/usr/bin/env bash
# Builds and drops artefacts in ~/QuietLedger-APKs with the version and commit
# in the filename, so it is always obvious which build is on the phone.
#
# Any previous artefact is moved into ~/QuietLedger-APKs/archive first, so the
# top level only ever holds the current build. Nothing is deleted.
#
#   ./scripts/build-apk.sh            debug APK, for testing
#   ./scripts/build-apk.sh release    signed release APK and AAB
#
# The release build is minified and resource-shrunk, which takes it from roughly
# 42 MB down to 11 MB, so it needs testing in its own right. R8 removing
# something that is only reached reflectively will not show up in a debug build.
#
# Release signing reads keystore.properties at the repo root. Without it the
# build still succeeds but is unsigned and cannot be installed.

set -euo pipefail

BUILD_TYPE="${1:-debug}"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$HOME/QuietLedger-APKs"
ARCHIVE_DIR="$OUT_DIR/archive"

# A copy goes here as well, which Syncthing carries to the laptop and from there
# to Proton Drive.
#
# This is the only off-site copy of a runnable app. The source lives in Forgejo
# on another host in the same building, so a fire takes both it and this box. A
# backup you can read but have no app to open is a spreadsheet exercise; an APK
# off-site means a new phone can run the ledger with no source anywhere.
#
# Send only, and not shared with the phone: a 45 MB file per build would waste
# its storage for nothing.
OFFSITE_DIR="${QL_APK_OFFSITE_DIR:-$HOME/.local/share/quietledger/apk}"

# How many builds to keep off-site. Enough to fall back if the newest turns out
# to be bad, few enough to stay reasonable in Proton Drive.
OFFSITE_KEEP="${QL_APK_OFFSITE_KEEP:-3}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

cd "$REPO_DIR"
mkdir -p "$OUT_DIR" "$ARCHIVE_DIR" "$OFFSITE_DIR"

case "$BUILD_TYPE" in
    debug)
        TASKS=(":app:assembleDebug")
        ARTEFACTS=("app/build/outputs/apk/debug/app-debug.apk")
        ;;
    release)
        if [ ! -f keystore.properties ]; then
            echo "No keystore.properties, so the release build will be unsigned." >&2
            echo "See the README section on release builds." >&2
        fi
        TASKS=(":app:assembleRelease" ":app:bundleRelease")
        ARTEFACTS=(
            "app/build/outputs/apk/release/app-release.apk"
            "app/build/outputs/bundle/release/app-release.aab"
        )
        ;;
    *)
        echo "Unknown build type: $BUILD_TYPE (expected debug or release)" >&2
        exit 1
        ;;
esac

./gradlew "${TASKS[@]}"

SHA_SHORT="$(git rev-parse --short HEAD)"
DIRTY=""
if ! git diff --quiet || ! git diff --cached --quiet; then
    DIRTY="-dirty"
fi
VERSION="$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)"
BASE="QuietLedger-${VERSION}-${BUILD_TYPE}-${SHA_SHORT}${DIRTY}"

# Retire whatever was there before. Moved, never deleted, so an older build can
# always be reinstalled to check whether something is a regression.
shopt -s nullglob
archived=0
for old in "$OUT_DIR"/*.apk "$OUT_DIR"/*.aab; do
    mv "$old" "$ARCHIVE_DIR/"
    archived=$((archived + 1))
done
shopt -u nullglob

echo
for artefact in "${ARTEFACTS[@]}"; do
    if [ ! -f "$artefact" ]; then
        echo "Expected artefact not found at $artefact" >&2
        exit 1
    fi
    ext="${artefact##*.}"
    dest="$OUT_DIR/${BASE}.${ext}"
    cp "$artefact" "$dest"
    echo "$(printf '%-4s' "$ext"):   $dest"
    echo "        $(du -h "$dest" | cut -f1), sha256 $(sha256sum "$dest" | awk '{print $1}')"
done

if [ "$BUILD_TYPE" = "release" ] && [ -f keystore.properties ]; then
    APKSIGNER="$(ls "$HOME"/Android/sdk/build-tools/*/apksigner 2>/dev/null | tail -1 || true)"
    if [ -n "$APKSIGNER" ]; then
        echo
        echo "Signature: $("$APKSIGNER" verify "$OUT_DIR/${BASE}.apk" >/dev/null 2>&1 && echo "verified" || echo "FAILED TO VERIFY")"
    fi
fi

# Copied off-site, with its checksum, so a corrupted download is detectable
# rather than something you discover when the install fails.
echo
for artefact in "${ARTEFACTS[@]}"; do
    ext="${artefact##*.}"
    [ "$ext" = "apk" ] || continue

    src="$OUT_DIR/${BASE}.${ext}"
    cp "$src" "$OFFSITE_DIR/${BASE}.${ext}"
    (cd "$OFFSITE_DIR" && sha256sum "${BASE}.${ext}" > "${BASE}.${ext}.sha256")

    # Verified after copying rather than assumed. A truncated copy is worse
    # than no copy, because it looks like protection.
    if ! (cd "$OFFSITE_DIR" && sha256sum --quiet -c "${BASE}.${ext}.sha256"); then
        echo "The off-site copy of ${BASE}.${ext} does not match its checksum." >&2
        exit 1
    fi
    echo "offsite: $OFFSITE_DIR/${BASE}.${ext}"
done

# Oldest copies pruned, newest kept. Both the APK and its checksum go together,
# so a stray .sha256 cannot outlive the file it describes.
shopt -s nullglob
mapfile -t offsite_apks < <(ls -1t "$OFFSITE_DIR"/*.apk 2>/dev/null)
if [ "${#offsite_apks[@]}" -gt "$OFFSITE_KEEP" ]; then
    for stale in "${offsite_apks[@]:$OFFSITE_KEEP}"; do
        rm -f "$stale" "$stale.sha256"
        echo "         pruned $(basename "$stale")"
    done
fi
shopt -u nullglob

echo "         $(ls -1 "$OFFSITE_DIR"/*.apk 2>/dev/null | wc -l) build(s) off-site, $(du -sh "$OFFSITE_DIR" | cut -f1), syncing to the laptop"

echo
if [ "$archived" -gt 0 ]; then
    echo "Archived: $archived older artefact(s) moved to $ARCHIVE_DIR"
fi
echo "History:  $(find "$ARCHIVE_DIR" -maxdepth 1 \( -name '*.apk' -o -name '*.aab' \) | wc -l) artefact(s), $(du -sh "$ARCHIVE_DIR" | cut -f1) in archive"
