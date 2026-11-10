#!/usr/bin/env bash
# Gives every later shell command of the session Java 25 (JavaFX 27 needs it), so ./mvnw needs no JAVA_HOME prefix.
[ -n "$CLAUDE_ENV_FILE" ] && [ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@25 ] \
  && echo 'export JAVA_HOME=/opt/homebrew/opt/openjdk@25' >> "$CLAUDE_ENV_FILE"
exit 0
