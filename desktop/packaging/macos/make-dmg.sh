#!/usr/bin/env bash
# Makes the Mac DMG from the app made by `./gradlew :desktop:createDistributable`.
# Run it on a Mac:
#
#   ./gradlew :desktop:createDistributable
#   desktop/packaging/macos/make-dmg.sh NestSync-mac.dmg
#
# What it does:
#   1. Signs NestSync.app "ad-hoc" (free; Apple Silicon Macs refuse to run code
#      with no signature at all), then checks the signature.
#   2. Puts NestSync.app, "Open NestSync.command" and a shortcut to Applications
#      in one folder.
#   3. Packs that folder into a compressed DMG at the path you give
#      (default: NestSync-mac.dmg in the repo's top folder).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
APP="$ROOT/desktop/build/compose/binaries/main/app/NestSync.app"
HELPER="$ROOT/desktop/packaging/macos/Open NestSync.command"
STAGE="$ROOT/desktop/build/dmg-stage"
OUT="${1:-$ROOT/NestSync-mac.dmg}"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac

if [ ! -d "$APP" ]; then
    echo "Can't find $APP. Run ./gradlew :desktop:createDistributable first." >&2
    exit 1
fi

# 1. Ad-hoc signature for the whole app, Java runtime included.
codesign --force --deep --sign - "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"

# 2. The folder that becomes the DMG.
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/NestSync.app"
cp "$HELPER" "$STAGE/Open NestSync.command"
chmod +x "$STAGE/Open NestSync.command"
ln -s /Applications "$STAGE/Applications"

# 3. Pack it.
rm -f "$OUT"
hdiutil create -volname NestSync -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov "$OUT"

echo "Made $OUT"
