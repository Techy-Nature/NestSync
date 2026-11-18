#!/bin/bash
# Open NestSync.command
#
# Double-click this file to open NestSync on a Mac.
#
# NestSync isn't signed with a paid Apple Developer ID, so macOS puts a
# "downloaded from the internet" mark on it and won't open it with a plain
# double-click. This script does the same three things you could type in
# Terminal yourself:
#   1. removes that mark from NestSync.app (xattr),
#   2. re-signs the app "ad-hoc" on this Mac if its signature doesn't check out
#      (free, no Apple account needed),
#   3. opens it.
# It only touches NestSync.app. Nothing is sent anywhere.

say() { printf '%s\n' "$*"; }
finish() {
    say ""
    read -r -p "Press Enter to close this window." _
    exit "$1"
}

HERE="$(cd "$(dirname "$0")" && pwd)"

# Find NestSync.app: Applications first, then next to this file.
APP=""
for candidate in "/Applications/NestSync.app" "$HOME/Applications/NestSync.app" "$HERE/NestSync.app"; do
    if [ -d "$candidate" ]; then
        APP="$candidate"
        break
    fi
done

if [ -z "$APP" ]; then
    say "Can't find NestSync.app."
    say "Drag NestSync into your Applications folder first, then double-click this file again."
    finish 1
fi

# A DMG is read-only, so the app has to be copied out of it first.
if [ ! -w "$APP" ]; then
    say "NestSync is still inside the disk image, which can't be changed."
    say "Drag NestSync into your Applications folder first, then double-click this file again."
    finish 1
fi

say "Getting NestSync ready: $APP"

# 1. Remove the "downloaded from the internet" mark.
xattr -cr "$APP" 2>/dev/null

# 2. Re-sign ad-hoc only if the signature doesn't check out.
if ! /usr/bin/codesign --verify --deep --strict "$APP" >/dev/null 2>&1; then
    say "Re-signing NestSync on this Mac (ad-hoc, no Apple account needed)..."
    if ! /usr/bin/codesign --force --deep --sign - "$APP" >/dev/null 2>&1; then
        say "Couldn't re-sign NestSync. Try moving it to Applications and running this again."
        finish 1
    fi
fi

# 3. Open it.
if open "$APP"; then
    say "Opening NestSync. You can close this window."
    say "From now on you can open NestSync from Applications like any other app."
    exit 0
fi

say "macOS still wouldn't open NestSync."
say "Open System Settings > Privacy & Security, scroll down, and click Open Anyway next to NestSync."
finish 1
