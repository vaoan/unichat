---
name: learn
description: Keep the lesson of a bug just fixed (or a mistake the person corrected) where it will be seen next time - the area's notes, and a check when a pattern can catch it. Use after fixing a bug, after the person corrects you twice on the same thing, or when the Stop hook reminds you.
---
# Keeping a lesson

1. **The test.** The fix already has a test that fails without it (root `CLAUDE.md`). If not, write it first.
2. **Where it belongs.** The folder where the mistake was made (`<folder>/CLAUDE.md`, loaded when its files are read) or, if it cuts across folders, the `.claude/rules/<topic>.md` whose `paths:` cover the files (create one if none fits; `paths:` are globs from the repo root). Never the root `CLAUDE.md` unless it applies to the whole repo.
3. **The line.** Under `## Lessons`: what went wrong, how it showed, and what to do instead, in the code's words (class and method names, the test that guards it). One or two sentences. If a lesson already there says the same, sharpen it instead of adding.
4. **A check, when a pattern would have caught it.** A row in `.claude/lessons.tsv` (id, files glob, Java regex per line, why + what instead) and its example in `LessonsTest.CAUGHT`. Run `bash .claude/hooks/java.sh Lessons <file>` on the files it's about: a match that's fine gets `lesson-ok: <id>` in a comment, otherwise the regex is too wide. A dangerous command instead → a row in `.claude/guards.tsv` and its example in `GuardTest.STOPPED`.
5. **Prune.** If the code now makes the mistake impossible (a type, a single helper everyone must use, an ArchitectureTest rule), prefer that and keep the note short. Delete notes that turned out wrong.

Then `./mvnw verify` (LessonsTest checks the whole repository against the new row).
