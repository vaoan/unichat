---
name: health
description: Check how healthy the code is - build, size limits, copied code, lessons, largest classes - and review an area with the reviewer subagents. Use when asked for a health check, an audit, or a SOLID/DRY/KISS/security review.
---
# Code health

1. Numbers: `bash .claude/skills/health/report.sh` (files near the 800-line limit, the biggest packages, `ProcessBuilder`/`HttpClient`/`URLEncoder` outside their helpers, `catch` blocks that drop the error).
2. Gates: `./mvnw verify` (tests, ArchitectureTest, Checkstyle sizes, PMD CPD, LessonsTest). Then `./mvnw verify -Pheadless-ui` when UI changed.
3. Review: launch the `reviewer` and `security-reviewer` subagents in parallel, one per area (ui; matrix+service+model+settings; server+login+cloud+links+update+support+CI; assist+autosave+video+stt+voice+cloak+gif), each told which folders. Ask for verified findings only.
4. Act: fix what is high severity with a test that fails first (and the `learn` skill); add the rest to `docs/REVIEW.md` (and delete what got fixed), so the next review doesn't rediscover it.
