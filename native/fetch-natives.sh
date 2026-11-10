#!/bin/sh
# Downloads one file of Unichat's own build release (NATIVES in versions.env, made by .github/workflows/natives.yml)
# to $2, checked against the SHA-256 pinned for it in the app (sha256sums.txt beside versions.env, written by
# native/pin-hashes.sh), not the release's own. Used by package.sh for the homeserver it ships.
# Usage: native/fetch-natives.sh <asset> <destination file>     e.g. tuwunel-darwin-arm64 target/pkg/server/tuwunel
# With gh signed in, or GH_TOKEN set, it works while the repository is private; otherwise plain HTTPS.
set -e
ASSET="$1" DEST="$2"
[ -n "$ASSET" ] && [ -n "$DEST" ] || { echo "usage: $0 <asset> <destination file>" >&2; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
RES="$HERE/../src/main/resources/app/unichat/server"
NATIVES="$(grep '^NATIVES=' "$RES/versions.env" | cut -d= -f2-)"
REPO="${NATIVES%@*}" TAG="${NATIVES#*@}"
WANT="$(awk -v k="$NATIVES/$ASSET" '$2 == k {print $1}' "$RES/sha256sums.txt")"
[ -n "$WANT" ] || { echo "$NATIVES/$ASSET has no pinned SHA-256: run native/pin-hashes.sh" >&2; exit 1; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
download() {
  if command -v gh >/dev/null && { [ -n "${GH_TOKEN:-}" ] || gh auth status >/dev/null 2>&1; }; then
    gh release download "$TAG" -R "$REPO" -D "$WORK" -p "$ASSET" --clobber
  else
    curl -fsSL -o "$WORK/$ASSET" "https://github.com/$REPO/releases/download/$TAG/$ASSET"
  fi
}
# GitHub answers an occasional 500: try again before giving up.
download || { sleep 5; download; } || { sleep 15; download; }
[ "$(shasum -a 256 "$WORK/$ASSET" | awk '{print $1}')" = "$WANT" ] || { echo "$ASSET: checksum mismatch" >&2; exit 1; }
mkdir -p "$(dirname "$DEST")"
cp "$WORK/$ASSET" "$DEST"
chmod 755 "$DEST"
xattr -c "$DEST" 2>/dev/null || true
