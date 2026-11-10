#!/usr/bin/env bash
# Numbers for the health skill: where the code is closest to its limits or drifting from its shared helpers.
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../../..}" || exit 1
main=src/main/java/app/unichat
echo "== Java files over 600 lines (limit 800)"
find src -name '*.java' -exec wc -l {} + | grep -v ' total$' | awk '$1 > 600' | sort -rn
echo "== Lines per package"
for d in "$main"/*/; do printf '%6d %s\n' "$(cat "$d"*.java | wc -l)" "$(basename "$d")"; done | sort -rn
echo "== Processes started outside support/Processes"
grep -rln 'new ProcessBuilder(' "$main" | grep -v support/Processes.java
echo "== HttpClient built outside one shared place"
grep -rn 'HttpClient.newBuilder()\|HttpClient.newHttpClient()' "$main" | wc -l
echo "== URL encoding outside support/Urls"
grep -rln 'URLEncoder\.\|URLDecoder\.' "$main" | grep -v support/Urls.java
echo "== catch blocks that drop the error"
grep -rnE 'catch \([^)]*\) *\{ *\}' "$main" | wc -l
