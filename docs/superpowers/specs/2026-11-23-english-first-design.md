# English first — design

## Intent
The person's rule (2026-11-23): "todo lo de sistema debe estar en inglés; el español solo se muestra al usuario en el UI por la traducción".

Everything Unichat stores, sends, logs, names or tells a program is in English. Spanish, and any other language, appears only on Unichat's own screens, through `support/I18n`.

## Goals
1. **Cloud names, migrated in place.** No file is lost, and nothing is uploaded again; moves happen on the server.

   | Old | New |
   |---|---|
   | `Unichat/Multimedia` | `Unichat/Media` |
   | `Unichat/Ajustes` | `Unichat/Settings` |
   | `Unichat/Textos` | `Unichat/Texts` |
   | `Unichat/Registro/remitentes.json` | `Unichat/Log/senders.json` |
   | `Unichat/Bóveda` | `Unichat/Vault` |
   | `llaves` | `keys` |
   | `boveda.json` | `vault.json` |
   | `Ajustes/boveda-apagada.json` | `Settings/vault-off.json` |

2. **Files on disk and text sent to others are in English, whatever the UI language:**
   - autosave and "Guardar como…" names: `image-`, `video-`, `audio-`, `sticker-`, `file-`;
   - the saved chat-text file's header and marks ("edited", "written before AI correction", "corrected with AI");
   - the forward note: "Forwarded from {0}" and "Forwarded".

   These leave `messages*.properties`, because they are not UI.
3. **AI prompts are in English.** They tell the model to answer in the conversation's language, and to write the style notes in the person's language as today. The time phrases ("a moment ago", "3 hours ago") and the "Me" and "(voice note without words)" labels are English.
4. **The rest of the non-UI text:**
   - **Logs and redaction:** log lines are English, and redaction writes `<email>` and `<number>`.
   - **Bridge-reply parsing:** keep only the English tokens the bridges actually send.
   - **The speech command's placeholders:** `{file}`, `{language}`, `{original}`. A saved command with the old `{archivo}` or `{idioma}` still works and is rewritten to the new names.
5. **Text that is UI goes through I18n:**
   - the demo's contact and chat names;
   - the snapshot drafts;
   - the Whisper example path;
   - the settings page ids, which become English ids (`accounts`, …); `MainView` uses them.
6. **Docs:** the 10 Spanish design and plan files are translated to English and renamed. `README.es.md` and `docs/PRIVACIDAD.md` stay, as the allowed translations. Docs that name the cloud folders are updated.
7. **Kept from now on:** a test fails when Spanish appears in a string literal under `src/main/java`, outside an allowed list. The allowed list:
   - quotes of UI text inside comments;
   - `support/I18n`;
   - the emoji search data;
   - test fixtures that stand for what people write in chats, which may be in any language.

   `.claude/rules/i18n.md` and `docs/ARCHITECTURE.md` state the rule.

## Non-goals
- Translating what people write: chat contents and chat names, used as folder names.
- The UI's Spanish (`messages_es.properties`).

## Anti-goals
- **Losing or duplicating data in the cloud while folders move,** or a vault that no longer opens.
- **A computer still on an old Unichat breaking the account.** While it still writes the old folders, the new version keeps reading them and moves what it finds into the new ones, the same way plain copies are moved into the vault today. *Superseded 2026-11-23: the person ruled no computer runs an older Unichat; old-computer support was removed (see the plan's ledger).*
- **The AI answering in English to a Spanish chat** because its instructions are now English. Tests cover it.

## Approaches
1. **Rename with an in-place migration that runs every round** (chosen). The old folder is moved on the server when the new one doesn't exist, and merged when both do. Rejected alternatives:
   - Keep the old names and only change the code: that goes against the rule.
   - New names with no migration: data would be lost.
2. **The vault, which only exists in the person's own account:**
   - Its folder and slots are moved.
   - Every sealed file is re-sealed, because each file's path is part of its authentication data (AAD).
   - This uses the re-seal machinery that "Cambiar la llave" already has (`VaultReseal`), so no new crypto is needed.
   - Opened under either path name during the migration, then only the new one.

## How (decided)
- **Moving folders:** `Storage` gains `move(from, to)`:
  - WebDAV: `MOVE`;
  - Dropbox: `files/move_v2`;
  - pCloud: `renamefolder` / `renamefile`.
  - Each one is tested with the service's fake.
- **Migration steps** (`CloudNames`, one class that holds both name sets):
  - It runs at the start of the round, before anything reads a folder, and is idempotent.
  - When both the old and the new folder exist:
    - **Media:** files missing from the new folder are moved into it.
    - **Settings and AI:** the newest copy wins, as parts already merge.
    - The old folder is deleted once it's empty.
  - **Vault:** under `vaultPart`, `Bóveda` is moved to `Vault` and `llaves` to `keys`, then everything is re-sealed under the new paths. The migration is marked done in `vault.json` (`names: 2`).
- **Old computers:** a computer on an old version keeps writing the old names. Each round, the new version moves new files from the old folders into the new ones, and the UI says which computer still needs updating, as the plain-senders list does now. *Superseded 2026-11-23: the person ruled no computer runs an older Unichat; old-computer support was removed (see the plan's ledger).*
- **AI prompts:** they become English text. `ReplyPromptTest`, `StylePromptTest` and `FixPromptTest` check two things:
  - the reply-language instruction is present;
  - the person's own instructions and examples are kept verbatim.

## Phases
1. The cloud names and their migration, the vault included. This is the most delicate phase.
2. Files and text: autosave names, the saved text, the forward note, the logs, redaction and speech placeholders.
3. AI prompts.
4. The UI through I18n, the docs, and the test that keeps it so.

## Left to the builder
- Class names, and the exact English wording of the prompts (written as a native speaker would).
