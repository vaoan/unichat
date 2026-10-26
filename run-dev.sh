#!/bin/sh
# Runs Unichat from sources. Extra JVM flags go in JAVA_OPTS, e.g.
#   JAVA_OPTS="-Dunichat.dataDir=/tmp/unichat-demo" ./run-dev.sh
set -e
cd "$(dirname "$0")"
./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
# Local server: use the tuwunel built from source (packaged apps carry their own; see docs/SERVIDOR-NATIVO.md).
TUWUNEL_BIN="${TUWUNEL_BIN:-$HOME/Library/Caches/unichat-build/tuwunel/target/release/tuwunel}"
[ -x "$TUWUNEL_BIN" ] && JAVA_OPTS="$JAVA_OPTS -Dunichat.tuwunelBin=$TUWUNEL_BIN"
# macOS bridges need libolm next to them (native/build-libolm.sh).
LIBOLM="${LIBOLM:-$HOME/Library/Caches/unichat-build/olm/libolm.3.dylib}"
[ -f "$LIBOLM" ] && JAVA_OPTS="$JAVA_OPTS -Dunichat.libolm=$LIBOLM"
# macOS menu-bar item (native/build-bar.sh); rebuilt when the Swift source changes.
if [ "$(uname -s)" = Darwin ] && command -v swiftc >/dev/null; then
  BAR="$HOME/Library/Caches/unichat-build/UnichatBar"
  [ -x "$BAR" ] && [ "$BAR" -nt native/UnichatBar.swift ] || native/build-bar.sh "$BAR" >/dev/null
  [ -x "$BAR" ] && JAVA_OPTS="$JAVA_OPTS -Dunichat.barHelper=$BAR"
fi
exec java $JAVA_OPTS -cp "target/classes:$(cat target/classpath.txt)" app.unichat.Launcher "$@"
