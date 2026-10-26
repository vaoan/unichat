#!/bin/sh
# Builds UnichatBar, the native macOS menu-bar item (narrower than Java's TrayIcon, real NSMenu; same design as
# InstaTray's helper). Unichat falls back to the Java tray icon when it's missing.
# Usage: native/build-bar.sh [out file]   (default: ~/Library/Caches/unichat-build/UnichatBar)
set -e
cd "$(dirname "$0")"
OUT="${1:-$HOME/Library/Caches/unichat-build/UnichatBar}"
mkdir -p "$(dirname "$OUT")"
swiftc -O UnichatBar.swift -o "$OUT"
codesign --force -s - "$OUT" 2>/dev/null || true
echo "$OUT"
