#!/bin/sh
# Builds a native Unichat app for the current OS with jpackage (JDK 21+).
#   macOS: release/Unichat.app (+ .dmg)   Linux: release/unichat (+ .deb when dpkg is available)
set -e
cd "$(dirname "$0")"
VERSION=1.0.0
./mvnw -q -DskipTests package
rm -rf target/pkg release && mkdir -p target/pkg release
cp target/unichat.jar target/pkg/
# The local server's homeserver ships inside the app (Contents/app/server/tuwunel; see LocalServer).
# CI builds it from source; locally pass TUWUNEL_BIN=<path to a tuwunel built at the versions.env tag>.
if [ -n "${TUWUNEL_BIN:-}" ]; then
  mkdir -p target/pkg/server && cp "$TUWUNEL_BIN" target/pkg/server/tuwunel && chmod 755 target/pkg/server/tuwunel
else
  echo "Aviso: sin TUWUNEL_BIN la app no trae el servidor local (solo servidores externos)." >&2
fi
# macOS menu-bar item and system notifications: native helper (Contents/app/native/UnichatBar.app); without swiftc
# the Java tray icon and Unichat's own notification cards are used.
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null; then
  mkdir -p target/pkg/native && native/build-bar.sh "$PWD/target/pkg/native/UnichatBar.app" >/dev/null
fi
# macOS bridges link libolm (Homebrew dropped it): ship ours (native/build-libolm.sh).
if [ "$(uname -s)" = Darwin ]; then
  LIBOLM="${LIBOLM:-$HOME/Library/Caches/unichat-build/olm/libolm.3.dylib}"
  if [ -f "$LIBOLM" ]; then
    mkdir -p target/pkg/server && cp "$LIBOLM" target/pkg/server/libolm.3.dylib
  else
    echo "Aviso: falta libolm (native/build-libolm.sh); los puentes no arrancarán." >&2
  fi
fi
# Animated stickers for chats that only take WebP (WhatsApp): libwebp's gif2webp (see StickerConverter).
if [ "$(uname -s)" = Darwin ]; then
  mkdir -p target/pkg/server
  native/fetch-gif2webp.sh "$PWD/target/pkg/server/gif2webp" || echo "Aviso: sin gif2webp los stickers animados se envían fijos a WhatsApp." >&2
  # Voice notes to text (stt/Transcriber); the model is downloaded by the app on first use.
  mkdir -p target/pkg/server/whisper
  native/build-whisper.sh "$PWD/target/pkg/server/whisper/whisper-cli" \
    || echo "Aviso: sin whisper no se pueden transcribir notas de voz (salvo con un comando propio)." >&2
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
    jpackage $COMMON --type dmg --icon target/Unichat.icns --mac-package-identifier app.unichat || echo "dmg skipped"
    ;;
  *)
    jpackage $COMMON --type app-image --icon src/main/resources/app/unichat/icons/app-256.png
    command -v dpkg >/dev/null && jpackage $COMMON --type deb --icon src/main/resources/app/unichat/icons/app-256.png \
      --linux-shortcut --linux-menu-group Network || true
    ;;
esac
ls -la release
