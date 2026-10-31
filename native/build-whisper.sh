#!/bin/sh
# Builds whisper.cpp's whisper-cli for this Mac (static, Metal embedded: no dependencies) and copies it to $1.
# Unichat uses it to transcribe voice notes locally and for free (stt/Transcriber). There is no official macOS
# binary; the source release and its checksum are pinned here, package.ps1 pins the Windows build of the same
# release. Models are downloaded by the app on first use (stt/WhisperModel).
set -e
DEST="$1"
[ -n "$DEST" ] || { echo "usage: $0 <destination file>" >&2; exit 1; }
VERSION=1.9.5
SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
CACHE="$HOME/Library/Caches/unichat-build/whisper"
TARBALL="$CACHE/whisper.cpp-$VERSION.tar.gz"
BIN="$CACHE/whisper-cli-$VERSION"
mkdir -p "$CACHE"
if [ ! -x "$BIN" ]; then
  if [ ! -f "$TARBALL" ] || ! echo "$SHA256  $TARBALL" | shasum -a 256 -c - >/dev/null 2>&1; then
    curl -fsSL -o "$TARBALL.part" "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v$VERSION.tar.gz"
    echo "$SHA256  $TARBALL.part" | shasum -a 256 -c - >/dev/null || { rm -f "$TARBALL.part"; echo "whisper.cpp checksum mismatch" >&2; exit 1; }
    mv "$TARBALL.part" "$TARBALL"
  fi
  SRC="$CACHE/whisper.cpp-$VERSION"
  rm -rf "$SRC" && tar xzf "$TARBALL" -C "$CACHE"
  # GGML_NATIVE=OFF: the binary runs on every Apple Silicon Mac, not only the one that built it.
  cmake -S "$SRC" -B "$SRC/build" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF \
    -DGGML_METAL_EMBED_LIBRARY=ON -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=13.3 >/dev/null
  cmake --build "$SRC/build" -j --config Release --target whisper-cli >/dev/null
  cp "$SRC/build/bin/whisper-cli" "$BIN"
  # The sources and objects are ~300 MB; only the binary is kept.
  rm -rf "$SRC"
fi
cp "$BIN" "$DEST"
chmod 755 "$DEST"
xattr -c "$DEST" 2>/dev/null || true
