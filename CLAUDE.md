# Unichat — notes for Claude

Java 21 + JavaFX 23 desktop clone of Beeper: unified inbox over Matrix + mautrix bridges. Maven via wrapper
(`./mvnw`); use `JAVA_HOME=/opt/homebrew/opt/openjdk@21` on this Mac. User-facing text and README/docs in
**Spanish**; code, comments, commits in English. Must keep working on **Windows** (user's target).

## Layout
- `model/` — mutable `Chat`, `Message`, records `Member`, `Account`; `Network` maps each network to its bridge
  bot localpart / puppet prefix / protocol id (must match `server/setup.*`).
- `service/ChatService` — the only API the UI uses. Live model objects, listeners on any thread (UI marshals with
  `Platform.runLater`, coalesced in `MainView`). `MockChatService` = demo mode.
- `matrix/` — `MatrixChatService` (sync loop, sending, media cache), `SyncProcessor` (pure /sync → model, unit
  tested), `MatrixClient` (HTTP). `MatrixLiveIT` = end-to-end test, needs `UNICHAT_IT_HOMESERVER`.
- `ui/` — `MainView` (rail | `ChatListPane` | `ThreadPane` + `InfoPane`, overlays, shortcuts), `Composer`,
  `CommandBar`, `AddAccountPane`, `SettingsWindow`, `Notifier` (AWT tray), `Theme`. Pure logic with tests:
  `ChatFilter`, `ThreadRows`, `RichText`, `Formats`.
- `css/app.css` — single stylesheet; light palette on `.root`, dark on `.root.dark`; `-accent` and font size
  set inline by `Theme`.
- `server/` — Synapse + Postgres + bridges docker-compose, `setup.sh` / `setup.ps1`. `docs/BRIDGES.md`.
- `package.sh` / `package.ps1` — jpackage; `.github/workflows/build.yml` builds Windows zip/msi + macOS dmg.

## Checking UI changes
`-Dunichat.snapshot=out.png` writes the window to a PNG and exits; combine with `-Dunichat.dataDir=<dir>` (put
`session.backend=demo` and optionally `ui.theme=LIGHT` in `<dir>/settings.properties`),
`-Dunichat.openChat=<id>` (demo ids like `!familia:demo`), `-Dunichat.info=true`, `-Dunichat.overlay=command|add`.
Run via `JAVA_OPTS=... ./run-dev.sh`, then read the PNG. Do this after every visual change.

## Rules
- Run `./mvnw test` before committing. Never point tests at the real data dir (surefire sets `unichat.dataDir`).
- Don't copy anything from Beeper's app bundle (proprietary). Design tokens were only used as reference.
- See `.claude/mistakes.md` before touching CSS sizing, icons, AWT/tray or Javadoc.
