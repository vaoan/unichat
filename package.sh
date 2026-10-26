#!/bin/sh
# Builds a native Unichat app for the current OS with jpackage (JDK 21+).
#   macOS: release/Unichat.app (+ .dmg)   Linux: release/unichat (+ .deb when dpkg is available)
set -e
cd "$(dirname "$0")"
VERSION=1.0.0
./mvnw -q -DskipTests package
rm -rf target/pkg release && mkdir -p target/pkg release
cp target/unichat.jar target/pkg/

COMMON="--name Unichat --app-version $VERSION --vendor Unichat --input target/pkg --main-jar unichat.jar
  --main-class app.unichat.Launcher --dest release --java-options -Xmx512m"

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
