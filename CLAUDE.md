# Unichat — notes for Claude

Java 25 + JavaFX 27 desktop unified inbox over Matrix + mautrix bridges, inspired by Beeper (no code or assets of theirs). Maven via wrapper
(`./mvnw`); use `JAVA_HOME=/opt/homebrew/opt/openjdk@25` on this Mac (JavaFX 27 needs Java 25). User-facing text through `support/I18n` (`I18n.t("key")`, `I18n.n` for counts): `src/main/resources/app/unichat/i18n/messages.properties` (English, the fallback) and
`messages_es.properties`; a new language is one more `messages_<code>.properties` (found by `I18n.available`, offered in Ajustes → Apariencia; `I18nTest` demands the same keys and placeholders in every file).
The language changes live (`UnichatApp.relocalize` rebuilds the window), so never keep a text in a `static final` or anything built once. Tests run in Spanish (`unichat.language=es` in the pom).
Everything in the repo is in **English** (README, docs, scripts, code, commits); `README.es.md` and `docs/PRIVACIDAD.md` are the Spanish translations of the README and `docs/PRIVACY.md` (SignPath's application links the latter), keep them in step. The app itself is bilingual. Must keep working on **Windows** (user's target).


## Commands
- Build and check everything: `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw verify` (tests, architecture, copied code, style).
- UI tests, headless: `./mvnw verify -Pheadless-ui` (one: `-Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`).
- Install on this Mac: `./install-mac.sh` (with `JAVA_HOME` exported), then `open /Applications/Unichat.app`.
- See a screen: a snapshot run (`src/main/java/app/unichat/ui/CLAUDE.md`), headless by itself.

## Rules
- Nothing ever runs on the person's screen: no window, focus, mouse or keyboard of theirs while they work. UI tests run with `-Pheadless-ui`, snapshots are headless by themselves. A test that needs the real display (`MenuDismissUiTest`: the real mouse, macOS activating the app) sits behind its own flag (`-Dunichat.realInputTests=true`) and only the person runs it.
- All code is SOLID, DRY and KISS (`docs/ARCHITECTURE.md`): one responsibility per class, nothing written twice (reuse or extract what exists), the simplest thing that works. Java files stay under 800 lines, methods under 100 (Checkstyle fails the build); split by responsibility.
- Every fix comes with a test that fails without it (watch it fail first), and every feature with tests of what it does.
- Tests never wait for a length of time nor carry a timeout of their own: no `Thread.sleep`, no `await(5, SECONDS)`, `poll(200, MILLISECONDS)`, `join(5000)`, `waitFor(30, …)` or `get(30, …)`. They wait on what the code actually does: a latch or future the code completes, a listener, `FxWait.until` / `FxWait.settled` on screen, the code's own `flush()`. JUnit's global timeout (junit-platform.properties) is the only limit. To check that something did *not* happen, first wait for the point where it would have (flush, settled, the call that would have caused it returned). If the code gives no way to know when it's done, change the code so it does (a listener, a returned future, a flush) rather than guess a time. A time the code under test takes as input is given as zero or as no limit (`Duration.ZERO` with a program that never ends shows the timeout path; `NO_LIMIT` when it ends by itself), never a guess of how long something takes. The one timed pause allowed is a profiler's sampling rate (`ScrollPerfUiTest`'s sampler): it measures, it doesn't wait.
- A flaky test is a bug, never tolerated nor retried away: find the race and fix it (usually a wait on time, or work still running when the test ends: wait for it).
- Run `./mvnw verify` before committing (tests, architecture rules, copied code). Never point tests at the real data dir (surefire sets `unichat.dataDir`).
- Nothing from Beeper's app (proprietary): no code, icons, texts or other assets.
- See `.claude/mistakes.md` before touching CSS sizing, icons, AWT/tray or Javadoc.
- Accessibility is a user requirement: every control reachable by keyboard, with an accessible text, and fine at 160 % text and high contrast (`src/main/java/app/unichat/ui/CLAUDE.md`).

## Packages
Package rules: `docs/ARCHITECTURE.md`, enforced by `ArchitectureTest` (no cycles, JavaFX only in `ui`, `ui` talks to `service`). Read it before adding a dependency between packages.
- `model/` — mutable `Chat`, `Message`, records `Member`, `Account`; `Network` maps each network to its bridge
  bot localpart / puppet prefix / protocol id (must match `server/setup.*`).
- `support/` — leaf utilities (`Os`, `Hashes`, `Redactor`, `BundledTools`, `Processes`, `Folders`, `Urls`, `Errors`). Use them instead of writing another: process timeouts, folder deletion and URL encoding had each drifted apart.
- `bridge/` — what `matrix` and `server` share about bridges (`BridgeStates`, `BridgePorts`).

## Where the details are
Each area has its own `CLAUDE.md`, read when its files are: read it before working there.
- `src/main/java/app/unichat/ui/` — screens, lists, timeline, media viewers, emojis, menus, the Dock; **checking UI changes (snapshots)** and **accessibility**.
- `src/main/java/app/unichat/matrix/` — Matrix: sync, sending (files, photos, uploads), read receipts, formatting, send status, the media cache, waking up.
- `src/main/java/app/unichat/service/` — `ChatService` and its roles, demo mode, the data folder's files, preferences and folders.
- `src/main/java/app/unichat/server/` — the local server: processes, bridge installs, account states, watchdog.
- `native/` — the no-Docker stack (`versions.env`), direct media, native builds, the macOS menu-bar helper.
- `src/main/java/app/unichat/video/`, `voice/`, `stt/`, `assist/`, `cloak/`, `autosave/`, `cloud/`, `login/` — stickers and conversions; voice notes; transcripts; AI drafts; protected photos; saving what chats receive; the person's own storage (settings and media kept in sync); bridge logins.
- `packaging/` — packages, releases, versions and updates, code signing, macOS minimum, THIRD-PARTY.
