#!/bin/sh
# Builds a native Unichat app for the current OS with jpackage (JDK 21+).
#   macOS: release/Unichat.app (+ .dmg)   Linux: release/unichat (+ .deb when dpkg is available)
set -e
cd "$(dirname "$0")"
# The version is the pom's (also the app's: support/AppVersion), so a release changes it in one place.
# The oldest macOS the app runs on (whisper-cli's Metal needs 13.3): every binary inside is built for it, and
# native/check-macos-min.sh refuses a package where one needs more.
export MACOS_MIN=13.3
VERSION="$(sed -n 's:^  <version>\(.*\)</version>:\1:p' pom.xml | head -1)"
./mvnw -q -DskipTests package
rm -rf target/pkg release && mkdir -p target/pkg release
cp target/unichat.jar LICENSE THIRD-PARTY.md target/pkg/
# The local server's homeserver ships inside the app (Contents/app/server/tuwunel; see LocalServer): the one
# natives.yml built (native/fetch-natives.sh), or TUWUNEL_BIN=<path to a tuwunel built at the versions.env tag>.
if [ -n "${TUWUNEL_BIN:-}" ]; then
  # Separate lines: set -e lets a failure in the middle of an && list through, which shipped an app without it.
  mkdir -p target/pkg/server
  cp "$TUWUNEL_BIN" target/pkg/server/tuwunel
  chmod 755 target/pkg/server/tuwunel
elif [ "$(uname -s)" = Darwin ]; then
  native/fetch-natives.sh tuwunel-darwin-arm64 target/pkg/server/tuwunel \
    || echo "Warning: without tuwunel the app has no local server (external servers only)." >&2
fi
# macOS menu-bar item and system notifications: native helper (Contents/app/native/UnichatBar.app); without swiftc
# the Java tray icon and Unichat's own notification cards are used.
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null; then
  mkdir -p target/pkg/native && native/build-bar.sh "$PWD/target/pkg/native/UnichatBar.app" >/dev/null
fi
# macOS bridges link libolm (Homebrew dropped it): ship ours (native/build-libolm.sh).
if [ "$(uname -s)" = Darwin ]; then
  LIBOLM="${LIBOLM:-$HOME/Library/Caches/unichat-build/olm/libolm.3.dylib}"
  [ -f "$LIBOLM" ] || ! command -v cmake >/dev/null || native/build-libolm.sh "$(dirname "$LIBOLM")" >/dev/null
  if [ -f "$LIBOLM" ]; then
    mkdir -p target/pkg/server && cp "$LIBOLM" target/pkg/server/libolm.3.dylib
  else
    echo "Warning: libolm is missing (native/build-libolm.sh); the bridges won't start." >&2
  fi
fi
# Animated stickers for chats that only take WebP (WhatsApp): libwebp's gif2webp (see StickerConverter).
if [ "$(uname -s)" = Darwin ]; then
  mkdir -p target/pkg/server
  native/build-gif2webp.sh "$PWD/target/pkg/server/gif2webp" "$PWD/target/pkg/server/anim_dump" || echo "Warning: without gif2webp, animated stickers go to WhatsApp as still images." >&2
  # Voice notes to text (stt/Transcriber); the model is downloaded by the app on first use.
  mkdir -p target/pkg/server/whisper
  native/build-whisper.sh "$PWD/target/pkg/server/whisper/whisper-cli" \
    || echo "Warning: without whisper, voice notes can't be transcribed (except with your own command)." >&2
fi

COMMON="--name Unichat --app-version $VERSION --vendor Unichat --input target/pkg --main-jar unichat.jar
  --main-class app.unichat.Launcher --dest release --java-options -Xmx1g"

case "$(uname -s)" in
  Darwin)
    ICONSET=target/Unichat.iconset && mkdir -p "$ICONSET"
    R=src/main/resources/app/unichat/icons
    for s in 16 32 128 256 512; do
      cp "$R/app-$s.png" "$ICONSET/icon_${s}x${s}.png"
      d=$((s * 2)); [ -f "$R/app-$d.png" ] && cp "$R/app-$d.png" "$ICONSET/icon_${s}x${s}@2x.png"
    done
    iconutil -c icns "$ICONSET" -o target/Unichat.icns
    jpackage $COMMON --type app-image --icon target/Unichat.icns --mac-package-identifier app.unichat
    # A menu bar app: no Dock icon at launch nor among the Dock's recent apps (Ajustes can show one, see ui/DockIcon).
    plutil -insert LSUIElement -bool true release/Unichat.app/Contents/Info.plist
    # The oldest macOS it runs on is the newest any binary inside asks for (whisper-cli: 13.3); jpackage says 10.11.
    plutil -replace LSMinimumSystemVersion -string "$MACOS_MIN" release/Unichat.app/Contents/Info.plist
    native/check-macos-min.sh release/Unichat.app "$MACOS_MIN"
    codesign --force --deep -s - release/Unichat.app
    jpackage --type dmg --name Unichat --app-version $VERSION --vendor Unichat --dest release --app-image release/Unichat.app || echo "dmg skipped"
    ;;
  *)
    jpackage $COMMON --type app-image --icon src/main/resources/app/unichat/icons/app-256.png
    command -v dpkg >/dev/null && jpackage $COMMON --type deb --icon src/main/resources/app/unichat/icons/app-256.png \
      --linux-shortcut --linux-menu-group Network || true
    ;;
esac
ls -la release
