#!/bin/sh
# Builds LottieConverter (lottieconverter: Samsung's rlottie with libpng and GIFLib, all static) for this Mac and copies
# it to $1. Telegram's animated stickers are Lottie (.tgs, gzipped JSON) that no other network nor JavaFX reads: the
# Telegram bridge runs it to make them GIFs as they arrive (it finds it on its PATH, server/Supervisor), and Unichat
# runs it on the ones that came before (video/LottieStickers). Built from pinned sources; no official macOS build.
# Usage: native/build-lottie.sh <lottieconverter destination>
set -e
DEST="$1"
[ -n "$DEST" ] || { echo "usage: $0 <lottieconverter destination>" >&2; exit 1; }
LC_COMMIT=181e4b6ad780801bc74fd8e28a880b130554ac2a
LC_SHA256=e1b1a19d3b1d20d977b0b23a49c2fe01ed5afde7a175e12aa934ffad19381fcd
RLOTTIE_COMMIT=ea06d2f29ba01b8d06c00a838d107f5e484ae59b
RLOTTIE_SHA256=a89e071a3455ccbf8f61af67aad0657259ae9aabdf3afe3a0670954370cdd37a
LIBPNG_VERSION=1.6.59
LIBPNG_SHA256=2540302a1844ad2b2b501977abecfa850f265f97b78f065a712ab4074a89f5b5
GIFLIB_VERSION=5.2.2
GIFLIB_SHA256=be7ffbd057cadebe2aa144542fd90c6838c6a083b5e8a9048b8ee3b66b29d5fb
MACOS_MIN="${MACOS_MIN:-13.3}"
CACHE="$HOME/Library/Caches/unichat-build/lottie"
BIN="$CACHE/lottieconverter-$(echo "$LC_COMMIT" | cut -c1-12)-$(echo "$RLOTTIE_COMMIT" | cut -c1-12)-macos$MACOS_MIN"
mkdir -p "$CACHE"
fetch() { # url file sha256
  if [ ! -f "$2" ] || ! echo "$3  $2" | shasum -a 256 -c - >/dev/null 2>&1; then
    curl -fsSL -o "$2.part" "$1"
    echo "$3  $2.part" | shasum -a 256 -c - >/dev/null || { rm -f "$2.part"; echo "$(basename "$2"): checksum mismatch" >&2; exit 1; }
    mv "$2.part" "$2"
  fi
}
if [ ! -x "$BIN" ]; then
  fetch "https://github.com/sot-tech/LottieConverter/archive/$LC_COMMIT.tar.gz" "$CACHE/LottieConverter-$LC_COMMIT.tar.gz" "$LC_SHA256"
  fetch "https://github.com/Samsung/rlottie/archive/$RLOTTIE_COMMIT.tar.gz" "$CACHE/rlottie-$RLOTTIE_COMMIT.tar.gz" "$RLOTTIE_SHA256"
  fetch "https://github.com/pnggroup/libpng/archive/refs/tags/v$LIBPNG_VERSION.tar.gz" "$CACHE/libpng-$LIBPNG_VERSION.tar.gz" "$LIBPNG_SHA256"
  fetch "https://sourceforge.net/projects/giflib/files/giflib-$GIFLIB_VERSION.tar.gz/download" "$CACHE/giflib-$GIFLIB_VERSION.tar.gz" "$GIFLIB_SHA256"
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  for t in "LottieConverter-$LC_COMMIT" "rlottie-$RLOTTIE_COMMIT" "libpng-$LIBPNG_VERSION" "giflib-$GIFLIB_VERSION"; do
    tar xzf "$CACHE/$t.tar.gz" -C "$WORK"
  done
  GIF="$WORK/giflib-$GIFLIB_VERSION"
  for f in dgif_lib egif_lib gifalloc gif_err gif_font gif_hash openbsd-reallocarray quantize; do
    cc -O2 -mmacosx-version-min="$MACOS_MIN" -c "$GIF/$f.c" -o "$GIF/$f.o"
  done
  ar rcs "$GIF/libgif.a" "$GIF"/*.o
  cmake -S "$WORK/libpng-$LIBPNG_VERSION" -B "$WORK/png" -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_DEPLOYMENT_TARGET="$MACOS_MIN" \
    -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_TESTS=OFF -DPNG_TOOLS=OFF -DPNG_FRAMEWORK=OFF >/dev/null
  cmake --build "$WORK/png" -j8 >/dev/null
  # Its NEON drawing is 32-bit ARM assembly: on an arm64 Mac the plain C path (the arm64 compiler still defines
  # __ARM_NEON__). Only the library: its examples need what this build leaves out.
  cmake -S "$WORK/rlottie-$RLOTTIE_COMMIT" -B "$WORK/rlottie" -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_DEPLOYMENT_TARGET="$MACOS_MIN" \
    -DBUILD_SHARED_LIBS=OFF -DLOTTIE_MODULE=OFF -DLOTTIE_TEST=OFF -DLOTTIE_EXAMPLE=OFF -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_CXX_FLAGS="-U__ARM_NEON__" >/dev/null
  cmake --build "$WORK/rlottie" -j8 --target rlottie >/dev/null
  LC="$WORK/LottieConverter-$LC_COMMIT"
  c++ -O2 -std=c++11 -mmacosx-version-min="$MACOS_MIN" "$LC/lottie_export.cpp" -o "$WORK/lottieconverter" \
    -I"$WORK/rlottie-$RLOTTIE_COMMIT/inc" -I"$WORK/libpng-$LIBPNG_VERSION" -I"$WORK/png" -I"$GIF" \
    "$WORK/rlottie/librlottie.a" "$(ls "$WORK/png"/libpng*.a | head -1)" "$GIF/libgif.a" -lz -lpthread
  cp "$WORK/lottieconverter" "$BIN"
fi
cp "$BIN" "$DEST"
chmod 755 "$DEST"
xattr -c "$DEST" 2>/dev/null || true
