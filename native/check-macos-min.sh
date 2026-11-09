#!/bin/sh
# Fails when a binary inside the app needs a newer macOS than the app says it runs on ($2): that binary would
# refuse to start there (the menu-bar helper was once built for macOS 26 because swiftc targets the Mac it runs on).
# Usage: native/check-macos-min.sh <Unichat.app> <minimum, e.g. 13.3>
set -e
APP="$1" MIN="$2"
[ -d "$APP" ] && [ -n "$MIN" ] || { echo "usage: $0 <app> <minimum macOS>" >&2; exit 1; }
bad=0
find "$APP" -type f \( -perm -u+x -o -name '*.dylib' \) | while read -r f; do
  file -b "$f" | grep -q Mach-O || continue
  for v in $(vtool -show-build "$f" 2>/dev/null | awk '/minos/ {print $2}'); do
    newest="$(printf '%s\n%s\n' "$MIN" "$v" | sort -V | tail -1)"
    if [ "$newest" != "$MIN" ]; then echo "macOS $v needed by ${f#"$APP"/} (the app says $MIN)" >&2; echo bad; fi
  done
done | grep -q bad && { echo "Error: some binaries need a newer macOS than the app's minimum." >&2; exit 1; } || true
