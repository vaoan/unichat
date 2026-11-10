#!/usr/bin/env bash
# Before Claude says it's done: when Java or CSS changed since the last check, compile (tests too), run Checkstyle and
# the lessons on every changed file. A failure goes back to Claude (exit 2) to fix; a bug fix without its lesson
# written down gets one reminder. Each state of the changes is checked once (target/.claude-checked).
input=$(cat)
case "$input" in *'"stop_hook_active":true'*|*'"stop_hook_active": true'*) exit 0 ;; esac
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
changed=$( { git diff --name-only HEAD; git ls-files --others --exclude-standard; } | grep -E '\.(java|css|tsv)$' | sort -u)
[ -z "$changed" ] && exit 0
stamp=$( { git diff HEAD; echo "$changed" | while read -r f; do [ -f "$f" ] && cat "$f"; done; } | git hash-object --stdin)
[ "$(cat target/.claude-checked 2>/dev/null)" = "$stamp" ] && exit 0
[ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@25 ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@25
if ! out=$(./mvnw -q test-compile checkstyle:check 2>&1); then
  { echo "The build doesn't compile or breaks Checkstyle (fix before finishing):"; echo "$out" | grep -E 'ERROR|WARN' | head -30; } >&2
  exit 2
fi
# shellcheck disable=SC2086
lessons=$(bash .claude/hooks/java.sh Lessons $changed 2>&1) || { echo "$lessons" >&2; exit 2; }
mkdir -p target && echo "$stamp" > target/.claude-checked
if echo "$input" | grep -qiE '"last_assistant_message"[^}]*(fix|bug|arregl|corrig)' \
   && ! echo "$changed" | grep -qE '(CLAUDE\.md|\.claude/rules/|lessons\.tsv)$' \
   && ! git diff --name-only HEAD | grep -qE '(CLAUDE\.md|\.claude/rules/|lessons\.tsv)$'; then
  echo "If this fixed a bug: keep the lesson (the learn skill): a line in the folder's CLAUDE.md or rule, and a row in .claude/lessons.tsv if a pattern would have caught it. If it wasn't a bug fix, just finish." >&2
  exit 2
fi
exit 0
