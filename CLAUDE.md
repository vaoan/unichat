# Unichat — notes for Claude

Java 25 + JavaFX 27 desktop unified inbox over Matrix + mautrix bridges, inspired by Beeper (no code or assets of theirs).
Maven via wrapper (`./mvnw`); use `JAVA_HOME=/opt/homebrew/opt/openjdk@25` on this Mac (JavaFX 27 needs Java 25).
Everything in the repo is in **English** (README, docs, scripts, code, commits); `README.es.md` and `docs/PRIVACIDAD.md` are the Spanish translations of the README and `docs/PRIVACY.md` (SignPath's application links the latter), keep them in step. The app itself is bilingual: every user-facing text goes through `support/I18n` (details in `.claude/rules/i18n.md`, loaded with the files). Must keep working on **Windows** (user's target).

This file holds only what applies everywhere. Each folder's own notes are in its `CLAUDE.md` (loaded when its files are read) and topics that cut across folders in `.claude/rules/*.md` (loaded by the files they name in `paths:`). Put a new note where the next person touching those files will see it, never here unless it applies to the whole repo.

## Commands
- Build and check everything: `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw verify` (tests, architecture, copied code, style, lessons).
- UI tests, headless: `./mvnw verify -Pheadless-ui` (one: `-Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`).
- One file against the lessons: `bash .claude/hooks/java.sh Lessons <file>…` (the edit hook runs it by itself).
- Install on this Mac: `./install-mac.sh` (with `JAVA_HOME` exported), then `open /Applications/Unichat.app`.
- See a screen: the `snapshot` skill (`.claude/skills/snapshot/`), headless by itself.

## Rules
- Nothing ever runs on the person's screen: no window, focus, mouse or keyboard of theirs while they work. UI tests run with `-Pheadless-ui`, snapshots are headless by themselves. A test that needs the real display (`MenuDismissUiTest`: the real mouse, macOS activating the app) sits behind its own flag (`-Dunichat.realInputTests=true`) and only the person runs it.
- All code is SOLID, DRY and KISS (`docs/ARCHITECTURE.md`): one responsibility per class, nothing written twice (reuse or extract what exists), the simplest thing that works. Java files stay under 800 lines, methods under 100 (Checkstyle fails the build); split by responsibility.
- Package rules: `docs/ARCHITECTURE.md`, enforced by `ArchitectureTest` (no cycles, JavaFX only in `ui`, `ui` talks to `service`). Read it before adding a dependency between packages.
- Every fix comes with a test that fails without it (watch it fail first), and every feature with tests of what it does. How tests wait: `src/test/CLAUDE.md`.
- Run `./mvnw verify` before committing. Never point tests at the real data dir (surefire sets `unichat.dataDir`).
- Nothing from Beeper's app (proprietary): no code, icons, texts or other assets.
- Accessibility is a user requirement: every control reachable by keyboard, with an accessible text, and fine at 160 % text and high contrast (`src/main/java/app/unichat/ui/CLAUDE.md`).
- What arrives in a chat is written by strangers: names, links, HTML, files and text never reach a shell, a tool's options, a path outside its folder or the AI's tools without the checks the area's notes describe. `.claude/agents/security-reviewer.md` lists what to look at.

## Learning from mistakes
A bug fixed is a lesson; keep it where it will be seen next time, in the same change:
1. The regression test (above).
2. One line in the notes of the folder or topic it happened in (a `Lessons` section in that `CLAUDE.md` or rule): what went wrong and what to do instead, in the code's words.
3. If a pattern in the text would have caught it, a check in `.claude/lessons.tsv` (regex, which files, why). The edit hook shows it the moment the pattern is written, and `LessonsTest` fails the build on it.
The `learn` skill walks through it. Don't keep a lesson here, nor one that the code already makes impossible.

## Where the details are
- `src/main/java/app/unichat/<package>/CLAUDE.md` — each package's notes (ui, matrix, service, server, model, support, bridge, video, voice, stt, assist, cloak, autosave, cloud, login, links, gif, update, settings).
- `src/test/CLAUDE.md` — how tests wait, UI tests headless, CI on Linux.
- `.claude/rules/` — topics across folders: i18n, CSS and icons, emoji, media viewers, the timeline, macOS window/tray/Dock, settings pages, Java comments.
- `native/`, `server/`, `packaging/`, `.github/` — the no-Docker stack and native builds; the Docker server; packages, releases and signing; CI.
- `.claude/agents/` (reviewer, security-reviewer), `.claude/skills/` (snapshot, learn, health), `.claude/settings.json` (hooks) — how Claude works here.
- `docs/REVIEW.md` — open findings of the last whole-app review: check it before working on an area.
