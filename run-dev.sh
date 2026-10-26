#!/bin/sh
# Runs Unichat from sources. Extra JVM flags go in JAVA_OPTS, e.g.
#   JAVA_OPTS="-Dunichat.dataDir=/tmp/unichat-demo" ./run-dev.sh
set -e
cd "$(dirname "$0")"
./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
# Local server: use the tuwunel built from source (packaged apps carry their own; see docs/SERVIDOR-NATIVO.md).
TUWUNEL_BIN="${TUWUNEL_BIN:-$HOME/Library/Caches/unichat-build/tuwunel/target/release/tuwunel}"
[ -x "$TUWUNEL_BIN" ] && JAVA_OPTS="$JAVA_OPTS -Dunichat.tuwunelBin=$TUWUNEL_BIN"
exec java $JAVA_OPTS -cp "target/classes:$(cat target/classpath.txt)" app.unichat.Launcher "$@"
