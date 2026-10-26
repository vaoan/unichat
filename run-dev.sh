#!/bin/sh
# Runs Unichat from sources. Extra JVM flags go in JAVA_OPTS, e.g.
#   JAVA_OPTS="-Dunichat.dataDir=/tmp/unichat-demo" ./run-dev.sh
set -e
cd "$(dirname "$0")"
./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
exec java $JAVA_OPTS -cp "target/classes:$(cat target/classpath.txt)" app.unichat.Launcher "$@"
