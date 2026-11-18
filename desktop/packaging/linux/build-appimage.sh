#!/usr/bin/env bash
# Wraps the app folder made by `./gradlew :desktop:createDistributable` into one
# NestSync-x86_64.AppImage file. Run it on an x86_64 Linux machine:
#
#   ./gradlew :desktop:createDistributable
#   desktop/packaging/linux/build-appimage.sh
#
# The AppImage lands in the repo's top folder.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
APP="$ROOT/desktop/build/compose/binaries/main/app/NestSync"
WORK="$ROOT/desktop/build/appimage"
OUT="$ROOT/NestSync-x86_64.AppImage"
APPDIR="$WORK/NestSync.AppDir"

if [ ! -x "$APP/bin/NestSync" ]; then
    echo "Can't find $APP/bin/NestSync. Run ./gradlew :desktop:createDistributable first." >&2
    exit 1
fi

rm -rf "$WORK"
mkdir -p "$WORK"

# 1. The AppDir: the app folder plus the files every AppImage needs at its top.
cp -a "$APP" "$APPDIR"
cp "$ROOT/desktop/packaging/linux/AppRun" "$APPDIR/AppRun"
chmod +x "$APPDIR/AppRun"
cp "$ROOT/desktop/packaging/linux/nestsync.desktop" "$APPDIR/nestsync.desktop"
cp "$ROOT/desktop/icons/NestSync.png" "$APPDIR/nestsync.png"
ln -s nestsync.png "$APPDIR/.DirIcon"

# 2. appimagetool packs the AppDir into one file. Its runtime doesn't need FUSE 2 installed.
TOOL="$WORK/appimagetool"
curl -fsSL -o "$TOOL" \
    https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
chmod +x "$TOOL"

# APPIMAGE_EXTRACT_AND_RUN lets appimagetool run where FUSE isn't available (like CI).
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$APPDIR" "$OUT"

echo "Made $OUT"
