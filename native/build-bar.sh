#!/bin/sh
# Builds UnichatBar.app, the native macOS helper: the menu-bar item (narrower than Java's TrayIcon, real NSMenu;
# same design as InstaTray's helper) and system notifications, which macOS only lets a bundled app post (it shows
# and asks permission as "Unichat"). Unichat falls back to the Java tray icon and its own cards when it's missing.
# Usage: native/build-bar.sh [out .app]   (default: ~/Library/Caches/unichat-build/UnichatBar.app)
# Prints the path of the executable inside the bundle.
set -e
cd "$(dirname "$0")"
OUT="${1:-$HOME/Library/Caches/unichat-build/UnichatBar.app}"
rm -rf "$OUT"
mkdir -p "$OUT/Contents/MacOS" "$OUT/Contents/Resources"
# Built for the app's minimum, not this Mac's version: by default swiftc targets the macOS it runs on.
MACOS_MIN="${MACOS_MIN:-13.3}"
swiftc -O -target "arm64-apple-macos$MACOS_MIN" UnichatBar.swift -o "$OUT/Contents/MacOS/UnichatBar"

# The app icon, so notifications show Unichat's.
ICONSET="$(mktemp -d)/Unichat.iconset"
mkdir -p "$ICONSET"
R=../src/main/resources/app/unichat/icons
for s in 16 32 128 256 512; do
  cp "$R/app-$s.png" "$ICONSET/icon_${s}x${s}.png"
  d=$((s * 2)); [ -f "$R/app-$d.png" ] && cp "$R/app-$d.png" "$ICONSET/icon_${s}x${s}@2x.png"
done
iconutil -c icns "$ICONSET" -o "$OUT/Contents/Resources/Unichat.icns"
rm -rf "$(dirname "$ICONSET")"

cat > "$OUT/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>app.unichat.helper</string>
  <key>CFBundleName</key><string>Unichat</string>
  <key>CFBundleDisplayName</key><string>Unichat</string>
  <key>CFBundleExecutable</key><string>UnichatBar</string>
  <key>CFBundleIconFile</key><string>Unichat</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>$MACOS_MIN</string>
  <!-- No Dock icon, no menu bar of its own: Unichat is the app the person sees. -->
  <key>LSUIElement</key><true/>
</dict>
</plist>
PLIST
codesign --force --deep -s - "$OUT" 2>/dev/null || true
echo "$OUT/Contents/MacOS/UnichatBar"
