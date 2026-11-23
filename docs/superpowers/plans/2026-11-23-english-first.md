# English first — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** everything Unichat stores, sends, logs or tells a program is in English. Spanish shows only on screen, through I18n.

**Architecture:** one class owns the cloud's names, old and new, and migrates in place every round:
- `Storage.move` on every service;
- the vault is re-sealed under its new paths with `VaultReseal`'s walk.

The other text moves out of `messages*.properties` into English constants. A source test keeps Spanish out of non-UI literals.

**Spec:** `docs/superpowers/specs/2026-11-23-english-first-design.md`. Read it whole: the name table, the anti-goals and the phases.

## Global Constraints
- The name table is in the spec. Use exactly: `Unichat/Media`, `Unichat/Settings`, `Unichat/Texts`, `Unichat/Log/senders.json`, `Unichat/Vault`, `keys`, `vault.json`, `Settings/vault-off.json`.
- **No data lost or duplicated:**
  - moves happen on the server;
  - the migration is idempotent and safe to interrupt at any step;
  - a computer on the old version keeps working, and its new files are moved in each round.
- **The vault keeps opening** at every step of its migration: under the old paths, mid-move, and under the new ones.
- **Repo rules** (`CLAUDE.md`, `src/test/CLAUDE.md`):
  - every change has a test that fails first;
  - no waits on time;
  - files stay under 800 lines;
  - user texts go through I18n in both languages.
- **Commands:** run in the foreground. Before committing, run `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -q verify -DskipTests` and the affected tests. The controller runs the full verify.

## Review Focus
1. **Old and new at once.** An old-version computer writes `Ajustes/unichat-settings.json` after the migration. The newer copy wins in `Settings`, and nothing loops. Task 1, `anOldComputerWritingTheOldFolderIsMovedInEachRound`.
2. **Interrupted vault migration.** A migration stopped after the move but before every file is re-sealed still opens, and finishes on the next round. Task 1, `aVaultMigrationInterruptedHalfwayStillOpensAndFinishes`.
3. **Media in both folders.** A file in both `Multimedia` and `Media` isn't uploaded again or lost. Task 1, `mediaInBothFoldersKeepsOneCopy`.
4. **AI reply language.** A Spanish chat still gets a Spanish reply instruction. Task 3, `aSpanishChatIsAskedForASpanishReply`.
5. **Old speech command.** A saved `stt.command` with `{archivo}`/`{idioma}` still runs. Task 2, `anOldSpeechCommandStillRuns`.

### Task 1: The cloud's names, migrated (vault included)
**Files:**
- Create `cloud/CloudNames.java`, the old/new table plus `migrate(Storage, …)`.
- Modify:
  - `Storage` (`move`), `WebDav`, `DropboxApi`, `PCloudApi`;
  - `CloudAccount`, `TextSync`, `VaultMove`, `Vault`, `VaultOff`, `SealedStorage`, `VaultReseal`, `Cloud` (call it at the round's start), `CloudVault` (the vault part under `vaultPart`);
  - the fakes: `FakeDav` and the services' fakes.
- Update the ~125 hard-coded Spanish cloud paths in `cloud/*Test`.

Steps:
- [ ] **`Storage.move(String from, String to)`.** Write a test per service with its fake, then implement it: WebDAV `MOVE` with `Overwrite: F`, Dropbox `files/move_v2`, pCloud `renamefolder`/`renamefile`.
- [ ] **Write the migration tests first and watch each fail:**
  - `aFreshAccountGetsOnlyTheNewNames`
  - `theOldFoldersAreMovedOnce`
  - `anOldComputerWritingTheOldFolderIsMovedInEachRound`
  - `mediaInBothFoldersKeepsOneCopy`
  - `settingsInBothKeepTheNewest`
  - `theVaultIsMovedAndResealedAndStillOpens`
  - `aVaultMigrationInterruptedHalfwayStillOpensAndFinishes`
  - `aComputerStillOnTheOldNamesIsNamedInTheUi` (through the existing plain-senders mechanism)
- [ ] **Implement it.**
  - The vault opens under either name until `vault.json` says `names: 2`. Re-sealing decrypts with the old logical path's AAD and re-encrypts with the new one. Reuse `VaultReseal`'s walk with a path mapping rather than a key change.
  - Update `docs/PRIVACY.md`, `docs/PRIVACIDAD.md`, `cloud/CLAUDE.md`, the vault design's folder names, and the `messages*.properties` lines that name folders.
- [ ] **Commit** with the message `feat(cloud): English names in the cloud, migrated in place (the vault re-sealed)`.

### Task 2: Files and text are English
**Files:**
- `autosave/FileNames.java`, `autosave/TextCopy.java`, `ui/timeline/ForwardNote.java`
- `stt/Transcriber.java`, `support/Redactor.java`, `server/LocalServer.java:166`, `ui/login/LoginWizard.java:172,182,183`
- `messages*.properties`: remove the keys that are no longer UI, and fix the Spanish in the English file at lines 226 and 233.

Steps:
- [ ] **Write the tests first and watch each fail:**
  - `savedFilesAreNamedInEnglishWhateverTheUiLanguage` (with the UI in es)
  - `theSavedChatTextIsInEnglish`
  - `theForwardNoteIsEnglish`
  - `anOldSpeechCommandStillRuns` (plus the setting rewritten to `{file}`/`{language}`)
  - `redactionWritesEnglishPlaceholders`
  - `loginRepliesAreReadByTheirEnglishTokens`
- [ ] **Implement them, then commit** with `refactor: what Unichat writes, sends and logs is English`.

### Task 3: AI prompts in English
**Files:** `assist/ReplyPrompt.java`, `StylePrompt.java`, `FixPrompt.java`, and their tests.

- [ ] **Write the tests first:**
  - `aSpanishChatIsAskedForASpanishReply`
  - `thePersonsInstructionsAndExamplesStayVerbatim`
  - `theStyleNotesAreWrittenInThePersonsLanguage`
  - `timePhrasesAreEnglish`

  Then translate, in natural English, keeping every rule the Spanish text gave.
- [ ] **Commit** with `refactor(assist): the AI's instructions are English, its answers in the chat's language`.

### Task 4: UI through I18n, docs, and the guard
**Files:**
- `service/DemoChats.java`, `MockChatService.java`, and the resource `demo/nota-de-voz.ogg` renamed to `voice-note.ogg`
- `ui/timeline/ThreadPane.java:412-413`, `ui/settings/SettingsWindow.java:61-73`, `ui/MainView.java:679`
- the 10 Spanish docs: translated, then renamed with `git mv`
- `.claude/rules/i18n.md`, `docs/ARCHITECTURE.md`

Steps:
- [ ] **Write `EnglishFirstTest` (source scan) and watch it fail.** It scans string literals under `src/main/java` and fails on Spanish (accented letters and a short word list), except for:
  - comments;
  - `support/I18n`;
  - an explicit allow list, each entry with its reason.
- [ ] **Fix what it finds.** The demo names become `demo.*` keys. The settings page ids become English. Then translate the docs.
- [ ] **Commit** with `refactor: UI text through I18n, docs in English, and a test that keeps it so`.
