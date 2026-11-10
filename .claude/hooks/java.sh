#!/usr/bin/env bash
# Runs a hook program from src/test/java/app/unichat/claude (Java's source launcher, so it works the same on Windows
# under Git Bash) with Java 25: JAVA_HOME, else Homebrew's openjdk@25, else the PATH's.
root="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
java="$JAVA_HOME/bin/java"
[ -n "$JAVA_HOME" ] && { [ -x "$java" ] || [ -x "$java.exe" ]; } || java=/opt/homebrew/opt/openjdk@25/bin/java
[ -x "$java" ] || java=java
exec "$java" "$root/src/test/java/app/unichat/claude/$1.java" "${@:2}"
