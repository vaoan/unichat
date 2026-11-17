#!/bin/sh
# Builds LottieConverter (lottieconverter: Samsung's rlottie with libpng and GIFLib, all static) and copies it to the
# destination. Telegram's animated stickers are Lottie (.tgs, gzipped JSON) that no other network nor JavaFX reads:
# Unichat draws them with it and sends them on as animated WebP (video/LottieStickers). Built from pinned sources: there
# is no official build. On this Mac by default; with --windows, cross-built for Windows (x64) with MinGW-w64, as
# build.yml does on Linux for package.ps1 (zlib built in too: Windows has none).
# Usage: native/build-lottie.sh [--windows] <lottieconverter destination>
set -e
WINDOWS=
[ "$1" = "--windows" ] && { WINDOWS=1; shift; }
DEST="$1"
[ -n "$DEST" ] || { echo "usage: $0 [--windows] <lottieconverter destination>" >&2; exit 1; }
LC_COMMIT=181e4b6ad780801bc74fd8e28a880b130554ac2a
LC_SHA256=e1b1a19d3b1d20d977b0b23a49c2fe01ed5afde7a175e12aa934ffad19381fcd
RLOTTIE_COMMIT=ea06d2f29ba01b8d06c00a838d107f5e484ae59b
RLOTTIE_SHA256=a89e071a3455ccbf8f61af67aad0657259ae9aabdf3afe3a0670954370cdd37a
LIBPNG_VERSION=1.6.59
LIBPNG_SHA256=2540302a1844ad2b2b501977abecfa850f265f97b78f065a712ab4074a89f5b5
GIFLIB_VERSION=5.2.2
GIFLIB_SHA256=be7ffbd057cadebe2aa144542fd90c6838c6a083b5e8a9048b8ee3b66b29d5fb
ZLIB_VERSION=1.3.1
ZLIB_SHA256=9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23
MACOS_MIN="${MACOS_MIN:-13.3}"
CACHE="$HOME/Library/Caches/unichat-build/lottie"
NAME="lottieconverter-$(echo "$LC_COMMIT" | cut -c1-12)-$(echo "$RLOTTIE_COMMIT" | cut -c1-12)"
if [ -n "$WINDOWS" ]; then
  # The posix thread model: rlottie's header includes <future>. Everything static, so the .exe needs no DLL beside it.
  CC=x86_64-w64-mingw32-gcc-posix CXX=x86_64-w64-mingw32-g++-posix AR=x86_64-w64-mingw32-ar
  BIN="$CACHE/$NAME-windows-x64.exe"
  CMAKE_TARGET="-DCMAKE_SYSTEM_NAME=Windows -DCMAKE_C_COMPILER=$CC -DCMAKE_CXX_COMPILER=$CXX"
  TARGET_FLAGS=""
  # rlottie adds MSVC's /MT to a static Windows Release build, whatever the compiler: MinSizeRel has none of it.
  RLOTTIE_BUILD_TYPE=MinSizeRel
else
  CC=cc CXX=c++ AR=ar
  BIN="$CACHE/$NAME-macos$MACOS_MIN"
  CMAKE_TARGET="-DCMAKE_OSX_DEPLOYMENT_TARGET=$MACOS_MIN"
  TARGET_FLAGS="-mmacosx-version-min=$MACOS_MIN"
  RLOTTIE_BUILD_TYPE=Release
fi
mkdir -p "$CACHE"
sha256() { if command -v sha256sum >/dev/null; then sha256sum -c - >/dev/null 2>&1; else shasum -a 256 -c - >/dev/null 2>&1; fi; }
fetch() { # url file sha256
  if [ ! -f "$2" ] || ! echo "$3  $2" | sha256; then
    curl -fsSL -o "$2.part" "$1"
    echo "$3  $2.part" | sha256 || { rm -f "$2.part"; echo "$(basename "$2"): checksum mismatch" >&2; exit 1; }
    mv "$2.part" "$2"
  fi
}
if [ ! -f "$BIN" ]; then
  fetch "https://github.com/sot-tech/LottieConverter/archive/$LC_COMMIT.tar.gz" "$CACHE/LottieConverter-$LC_COMMIT.tar.gz" "$LC_SHA256"
  fetch "https://github.com/Samsung/rlottie/archive/$RLOTTIE_COMMIT.tar.gz" "$CACHE/rlottie-$RLOTTIE_COMMIT.tar.gz" "$RLOTTIE_SHA256"
  fetch "https://github.com/pnggroup/libpng/archive/refs/tags/v$LIBPNG_VERSION.tar.gz" "$CACHE/libpng-$LIBPNG_VERSION.tar.gz" "$LIBPNG_SHA256"
  fetch "https://sourceforge.net/projects/giflib/files/giflib-$GIFLIB_VERSION.tar.gz/download" "$CACHE/giflib-$GIFLIB_VERSION.tar.gz" "$GIFLIB_SHA256"
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  for t in "LottieConverter-$LC_COMMIT" "rlottie-$RLOTTIE_COMMIT" "libpng-$LIBPNG_VERSION" "giflib-$GIFLIB_VERSION"; do
    tar xzf "$CACHE/$t.tar.gz" -C "$WORK"
  done
  ZINC="" ZLIB="-lz" PNG_ZLIB=""
  if [ -n "$WINDOWS" ]; then
    fetch "https://github.com/madler/zlib/releases/download/v$ZLIB_VERSION/zlib-$ZLIB_VERSION.tar.gz" "$CACHE/zlib-$ZLIB_VERSION.tar.gz" "$ZLIB_SHA256"
    tar xzf "$CACHE/zlib-$ZLIB_VERSION.tar.gz" -C "$WORK"
    Z="$WORK/zlib-$ZLIB_VERSION"
    make -C "$Z" -f win32/Makefile.gcc PREFIX=x86_64-w64-mingw32- libz.a >/dev/null
    ZINC="-I$Z" ZLIB="$Z/libz.a" PNG_ZLIB="-DZLIB_INCLUDE_DIR=$Z -DZLIB_LIBRARY=$Z/libz.a"
  fi
  GIF="$WORK/giflib-$GIFLIB_VERSION"
  for f in dgif_lib egif_lib gifalloc gif_err gif_font gif_hash openbsd-reallocarray quantize; do
    $CC -O2 $TARGET_FLAGS -c "$GIF/$f.c" -o "$GIF/$f.o"
  done
  $AR rcs "$GIF/libgif.a" "$GIF"/*.o
  # Unquoted on purpose: several flags, written in this file.
  cmake -S "$WORK/libpng-$LIBPNG_VERSION" -B "$WORK/png" -DCMAKE_BUILD_TYPE=Release $CMAKE_TARGET $PNG_ZLIB \
    -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_TESTS=OFF -DPNG_TOOLS=OFF -DPNG_FRAMEWORK=OFF >/dev/null
  cmake --build "$WORK/png" -j8 >/dev/null
  # Its NEON drawing is 32-bit ARM assembly: on an arm64 Mac the plain C path (the arm64 compiler still defines
  # __ARM_NEON__). No threads: frames are drawn one at a time anyway. Only the library: its examples need what this
  # build leaves out.
  cmake -S "$WORK/rlottie-$RLOTTIE_COMMIT" -B "$WORK/rlottie" -DCMAKE_BUILD_TYPE=$RLOTTIE_BUILD_TYPE $CMAKE_TARGET \
    -DBUILD_SHARED_LIBS=OFF -DLOTTIE_MODULE=OFF -DLOTTIE_TEST=OFF -DLOTTIE_EXAMPLE=OFF -DLOTTIE_THREAD=OFF \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_CXX_FLAGS="-U__ARM_NEON__" >/dev/null
  cmake --build "$WORK/rlottie" -j8 --target rlottie >/dev/null
  LC="$WORK/LottieConverter-$LC_COMMIT"
  OUT="$WORK/lottieconverter"
  if [ -n "$WINDOWS" ]; then
    # Its headers import rlottie from a DLL on Windows unless RLOTTIE_BUILD is defined; here it's static.
    $CXX -O2 -std=c++11 -DRLOTTIE_BUILD=0 "$LC/lottie_export.cpp" -o "$OUT.exe" \
      -I"$WORK/rlottie-$RLOTTIE_COMMIT/inc" -I"$WORK/libpng-$LIBPNG_VERSION" -I"$WORK/png" -I"$GIF" $ZINC \
      "$WORK/rlottie/librlottie.a" "$(ls "$WORK/png"/libpng*.a | head -1)" "$GIF/libgif.a" $ZLIB -lshlwapi -static
    OUT="$OUT.exe"
  else
    $CXX -O2 -std=c++11 $TARGET_FLAGS "$LC/lottie_export.cpp" -o "$OUT" \
      -I"$WORK/rlottie-$RLOTTIE_COMMIT/inc" -I"$WORK/libpng-$LIBPNG_VERSION" -I"$WORK/png" -I"$GIF" \
      "$WORK/rlottie/librlottie.a" "$(ls "$WORK/png"/libpng*.a | head -1)" "$GIF/libgif.a" $ZLIB -lpthread
  fi
  cp "$OUT" "$BIN"
fi
cp "$BIN" "$DEST"
chmod 755 "$DEST"
xattr -c "$DEST" 2>/dev/null || true
