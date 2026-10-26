#!/bin/sh
# Downloads libwebp's official gif2webp (static, no dependencies) for this Mac and copies it to $1.
# Unichat uses it to send animated stickers to chats that only take WebP (WhatsApp). The release and its
# checksum are pinned here; package.ps1 pins the Windows build of the same release.
set -e
DEST="$1"
[ -n "$DEST" ] || { echo "usage: $0 <destination file>" >&2; exit 1; }
VERSION=1.6.0
ASSET=libwebp-$VERSION-mac-arm64.tar.gz
SHA256=bc6bf84cc70f3f8574fba797d1e4a7dea4feebe9fa4be919f202413ea2b3b8f2
CACHE="$HOME/Library/Caches/unichat-build/libwebp"
mkdir -p "$CACHE"
if [ ! -f "$CACHE/$ASSET" ] || ! echo "$SHA256  $CACHE/$ASSET" | shasum -a 256 -c - >/dev/null 2>&1; then
  curl -fsSL -o "$CACHE/$ASSET.part" "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/$ASSET"
  echo "$SHA256  $CACHE/$ASSET.part" | shasum -a 256 -c - >/dev/null || { rm -f "$CACHE/$ASSET.part"; echo "libwebp checksum mismatch" >&2; exit 1; }
  mv "$CACHE/$ASSET.part" "$CACHE/$ASSET"
fi
tar xzf "$CACHE/$ASSET" -C "$CACHE"
cp "$CACHE/libwebp-$VERSION-mac-arm64/bin/gif2webp" "$DEST"
chmod 755 "$DEST"
xattr -c "$DEST" 2>/dev/null || true
