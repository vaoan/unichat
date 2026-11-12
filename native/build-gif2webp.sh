#!/bin/sh
# Builds libwebp's gif2webp for this Mac (static: no dependencies) and copies it to $1. Unichat uses it to send
# animated stickers to chats that only take WebP (WhatsApp). Built from the pinned sources rather than Google's
# binary, which needs macOS 15: this one runs from MACOS_MIN on (the app's minimum, see package.sh).
# GIFLib, which gif2webp reads GIFs with, is built into it. package.ps1 pins Google's Windows build of the same release.
# With a second path it also builds anim_dump there: animated stickers are decoded with it (AnimDump), since the Java
# decoder gets some lossy frames' colours wrong. With a third, img2webp: a GIF that came as an MP4 (Telegram's) is
# forwarded as an animated WebP made from its frames (video/GifVideos; it reads them as PAM, no PNG library needed).
# Usage: native/build-gif2webp.sh <gif2webp destination> [anim_dump destination] [img2webp destination]
set -e
DEST="$1" DUMP_DEST="$2" IMG_DEST="$3"
[ -n "$DEST" ] || { echo "usage: $0 <gif2webp destination> [anim_dump destination]" >&2; exit 1; }
VERSION=1.6.0
SHA256=e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564
GIFLIB_VERSION=5.2.2
GIFLIB_SHA256=be7ffbd057cadebe2aa144542fd90c6838c6a083b5e8a9048b8ee3b66b29d5fb
MACOS_MIN="${MACOS_MIN:-13.3}"
CACHE="$HOME/Library/Caches/unichat-build/libwebp"
BIN="$CACHE/gif2webp-$VERSION-macos$MACOS_MIN"
DUMP_BIN="$CACHE/anim_dump-$VERSION-macos$MACOS_MIN"
IMG_BIN="$CACHE/img2webp-$VERSION-macos$MACOS_MIN"
mkdir -p "$CACHE"
fetch() { # url file sha256
  if [ ! -f "$2" ] || ! echo "$3  $2" | shasum -a 256 -c - >/dev/null 2>&1; then
    curl -fsSL -o "$2.part" "$1"
    echo "$3  $2.part" | shasum -a 256 -c - >/dev/null || { rm -f "$2.part"; echo "$(basename "$2"): checksum mismatch" >&2; exit 1; }
    mv "$2.part" "$2"
  fi
}
if [ ! -x "$BIN" ] || { [ -n "$DUMP_DEST" ] && [ ! -x "$DUMP_BIN" ]; } || { [ -n "$IMG_DEST" ] && [ ! -x "$IMG_BIN" ]; }; then
  fetch "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-$VERSION.tar.gz" "$CACHE/libwebp-$VERSION.tar.gz" "$SHA256"
  fetch "https://sourceforge.net/projects/giflib/files/giflib-$GIFLIB_VERSION.tar.gz/download" "$CACHE/giflib-$GIFLIB_VERSION.tar.gz" "$GIFLIB_SHA256"
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  tar xzf "$CACHE/libwebp-$VERSION.tar.gz" -C "$WORK"
  tar xzf "$CACHE/giflib-$GIFLIB_VERSION.tar.gz" -C "$WORK"
  GIF="$WORK/giflib-$GIFLIB_VERSION"
  for f in dgif_lib egif_lib gifalloc gif_err gif_font gif_hash openbsd-reallocarray quantize; do
    cc -O2 -mmacosx-version-min="$MACOS_MIN" -c "$GIF/$f.c" -o "$GIF/$f.o"
  done
  ar rcs "$GIF/libgif.a" "$GIF"/*.o
  cmake -S "$WORK/libwebp-$VERSION" -B "$WORK/build" -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_DEPLOYMENT_TARGET="$MACOS_MIN" \
    -DBUILD_SHARED_LIBS=OFF -DWEBP_BUILD_GIF2WEBP=ON -DWEBP_BUILD_ANIM_UTILS=ON -DWEBP_BUILD_CWEBP=OFF \
    -DWEBP_BUILD_DWEBP=OFF -DWEBP_BUILD_IMG2WEBP=ON -DWEBP_BUILD_VWEBP=OFF -DWEBP_BUILD_WEBPINFO=OFF \
    -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF -DGIF_INCLUDE_DIR="$GIF" -DGIF_LIBRARY="$GIF/libgif.a" \
    -DCMAKE_DISABLE_FIND_PACKAGE_PNG=ON -DCMAKE_DISABLE_FIND_PACKAGE_JPEG=ON >/dev/null
  cmake --build "$WORK/build" -j8 --target gif2webp anim_dump img2webp >/dev/null 2>&1
  cp "$WORK/build/gif2webp" "$BIN"
  cp "$WORK/build/anim_dump" "$DUMP_BIN"
  cp "$WORK/build/img2webp" "$IMG_BIN"
fi
for pair in "$BIN:$DEST" "$DUMP_BIN:$DUMP_DEST" "$IMG_BIN:$IMG_DEST"; do
  from="${pair%%:*}" to="${pair#*:}"
  [ -n "$to" ] || continue
  cp "$from" "$to"
  chmod 755 "$to"
  xattr -c "$to" 2>/dev/null || true
done
