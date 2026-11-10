#!/bin/sh
# Pins the SHA-256 of every file Unichat or its scripts may download for the native server: writes
# src/main/resources/app/unichat/server/sha256sums.txt from versions.env and each release's own sha256sums.txt.
# Run it whenever versions.env changes (and after natives.yml has published the NATIVES release it names), review
# the diff and commit it: from then on the app, setup-mac.sh, fetch-natives.sh and package.ps1 trust only these
# hashes, so a release edited later (binary and sha256sums.txt swapped together) is refused. ManifestTest fails the
# build while something versions.env can install has no pin here.
# Usage: native/pin-hashes.sh
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
RES="$HERE/../src/main/resources/app/unichat/server"
VERSIONS="$RES/versions.env"
OUT="$RES/sha256sums.txt"
# The platforms in server/Platform.java: mautrix publishes official builds for the first, Unichat builds the second.
OFFICIAL="darwin-arm64"
NATIVE="windows-amd64"
TUWUNEL_PLATFORMS="darwin-arm64 windows-amd64"

setting() { grep "^$1=" "$VERSIONS" | cut -d= -f2- || true; }
NATIVES="$(setting NATIVES)"
UNBUILT=" $(setting NATIVES_UNBUILT | tr ',' ' ') "
[ -n "$NATIVES" ] || { echo "versions.env has no NATIVES" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Prints "<sha>  <release>/<asset>": the asset's line from that release's sha256sums.txt (downloaded once).
pin() {
  release="$1" asset="$2"
  sums="$WORK/$(printf '%s' "$release" | tr '/@' '__').txt"
  if [ ! -f "$sums" ]; then
    curl -fsSL --retry 3 -o "$sums" "https://github.com/${release%@*}/releases/download/${release#*@}/sha256sums.txt" \
      || { echo "$release: no sha256sums.txt" >&2; exit 1; }
  fi
  sha="$(awk -v a="$asset" '$2 == a || $2 == "*"a {print tolower($1)}' "$sums")"
  printf '%s' "$sha" | grep -Eq '^[0-9a-f]{64}$' || { echo "$release: no SHA-256 for $asset" >&2; exit 1; }
  echo "$sha  $release/$asset"
}

{
  grep -E '^[a-z][a-z0-9_]*=' "$VERSIONS" | while IFS='=' read -r name spec; do
    release="${spec%:*}" prefix="${spec##*:}"
    for p in $OFFICIAL; do pin "$release" "$prefix-$p"; done
    case "$UNBUILT" in *" $name "*) ;; *) for p in $NATIVE; do pin "$NATIVES" "$prefix-$p"; done ;; esac
  done
  for p in $TUWUNEL_PLATFORMS; do pin "$NATIVES" "tuwunel-$p"; done
} > "$WORK/pins"

{
  echo "# SHA-256 of every file the native server may download: <sha256>  <owner/repo>@<tag>/<asset>."
  echo "# Written by native/pin-hashes.sh from versions.env; run it again whenever versions.env changes."
  sort -k2 "$WORK/pins"
} > "$WORK/out"
mv "$WORK/out" "$OUT"
echo "Pinned $(wc -l < "$WORK/pins" | tr -d ' ') files in $OUT"
