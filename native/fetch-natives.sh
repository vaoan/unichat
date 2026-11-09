#!/bin/sh
# Downloads one file of Unichat's own build release (NATIVES in versions.env, made by .github/workflows/natives.yml)
# to $2, checked against that release's sha256sums.txt. Used by package.sh for the homeserver it ships.
# Usage: native/fetch-natives.sh <asset> <destination file>     e.g. tuwunel-darwin-arm64 target/pkg/server/tuwunel
# With gh signed in, or GH_TOKEN set, it works while the repository is private; otherwise plain HTTPS.
set -e
ASSET="$1" DEST="$2"
[ -n "$ASSET" ] && [ -n "$DEST" ] || { echo "usage: $0 <asset> <destination file>" >&2; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
NATIVES="$(grep '^NATIVES=' "$HERE/../src/main/resources/app/unichat/server/versions.env" | cut -d= -f2-)"
REPO="${NATIVES%@*}" TAG="${NATIVES#*@}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
download() {
  if command -v gh >/dev/null && { [ -n "${GH_TOKEN:-}" ] || gh auth status >/dev/null 2>&1; }; then
    gh release download "$TAG" -R "$REPO" -D "$WORK" -p "$ASSET" -p sha256sums.txt --clobber
  else
    for f in "$ASSET" sha256sums.txt; do curl -fsSL -o "$WORK/$f" "https://github.com/$REPO/releases/download/$TAG/$f" || return 1; done
  fi
}
# GitHub answers an occasional 500: try again before giving up.
download || { sleep 5; download; } || { sleep 15; download; }
(cd "$WORK" && grep " \*\{0,1\}$ASSET\$" sha256sums.txt | shasum -a 256 -c - >/dev/null) || { echo "$ASSET: checksum mismatch" >&2; exit 1; }
mkdir -p "$(dirname "$DEST")"
cp "$WORK/$ASSET" "$DEST"
chmod 755 "$DEST"
xattr -c "$DEST" 2>/dev/null || true
