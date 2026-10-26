#!/bin/sh
# Builds libolm 3.2.16 as a shared library for the current Mac (Apple Silicon).
# The official mautrix bridge binaries for macOS link @rpath/libolm.3.dylib and look for it next to themselves;
# Homebrew dropped its libolm formula, so Unichat ships this one (Contents/app/server/libolm.3.dylib) and copies it
# into <server>/bin. Bridge encryption is off in Unichat's config, so olm is loaded but not used.
# Usage: native/build-libolm.sh [out dir]   (default: ~/Library/Caches/unichat-build/olm)   Needs cmake.
set -e
VERSION=3.2.16
SHA256=1e90f9891009965fd064be747616da46b232086fe270b77605ec9bda34272a68
OUT="${1:-$HOME/Library/Caches/unichat-build/olm}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

curl -fsSL -o "$WORK/olm.tar.gz" "https://gitlab.matrix.org/matrix-org/olm/-/archive/$VERSION/olm-$VERSION.tar.gz"
echo "$SHA256  $WORK/olm.tar.gz" | shasum -a 256 -c -
tar xzf "$WORK/olm.tar.gz" -C "$WORK"
SRC="$WORK/olm-$VERSION"
# Newer clang rejects list.hh's "T * const other_pos" that is then incremented (same fix distributions apply).
sed -i '' 's/T \* const other_pos = other._data;/T * other_pos = other._data;/' "$SRC/include/olm/list.hh"
cmake -S "$SRC" -B "$WORK/build" -DCMAKE_BUILD_TYPE=Release -DOLM_TESTS=OFF \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=12.0 -DCMAKE_POLICY_VERSION_MINIMUM=3.5 >/dev/null
cmake --build "$WORK/build" -j8 >/dev/null
mkdir -p "$OUT"
cp "$WORK/build/libolm.$VERSION.dylib" "$OUT/libolm.3.dylib"
# Bridges ask for @rpath/libolm.3.dylib; make the library's own id match.
install_name_tool -id @rpath/libolm.3.dylib "$OUT/libolm.3.dylib"
codesign --force -s - "$OUT/libolm.3.dylib"
echo "$OUT/libolm.3.dylib"
