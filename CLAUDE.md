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
- `native/` — no-Docker stack (tuwunel + mautrix bridge binaries + SQLite): `versions.env` pins every component,
  `setup-mac.sh` / `run-mac.sh` are the reference for the installer Unichat will run at startup (install whatever
  is missing, start and supervise). Read `docs/SERVIDOR-NATIVO.md` before touching it.
- `native/UnichatBar.swift` (`native/build-bar.sh`) — macOS menu-bar item drawn natively (narrower than Java's
  TrayIcon, real NSMenu; same design as InstaTray), driven by `ui/NativeBar` over stdin; `Notifier` falls back to the
  AWT TrayIcon when it's missing or dies. run-dev.sh builds it; package.sh ships it as `Contents/app/native/UnichatBar`.
- `native/fetch-gif2webp.sh` — pinned libwebp `gif2webp` shipped in `app/server/` (Windows: pinned in package.ps1);
  `matrix/StickerConverter` uses it when a chat's room_features refuse a sticker's MIME (GIF → WebP on WhatsApp).
  Ajustes → Redes (`<network>.animatedStickers` = STICKER|GIF|VIDEO) can send them as mp4 instead (`GifFrames` +
  JCodec, pure Java).
- `package.sh` / `package.ps1` — jpackage; `.github/workflows/build.yml` builds Windows zip/msi + macOS dmg.

## Checking UI changes
`-Dunichat.snapshot=out.png` writes the window to a PNG and exits; combine with `-Dunichat.dataDir=<dir>` (put
`session.backend=demo` and optionally `ui.theme=LIGHT` in `<dir>/settings.properties`),
`-Dunichat.openChat=<id>` (demo ids like `!familia:demo`), `-Dunichat.openSpace=<id>` (a server's channels, e.g. `!servidor:demo`), `-Dunichat.info=true`, `-Dunichat.panel=EMOJIS|STICKERS|GIFS`,
`-Dunichat.overlay=command|newchat|add|settings:<Página>`, `-Dunichat.network=<protocolo>` (with `list.view.<RED>=GROUPS` in settings for the Grupos tree) (settings snapshots the settings window).
Run via `JAVA_OPTS=... ./run-dev.sh`, then read the PNG. Do this after every visual change.

## Accessibility (user requirement — keep it working)
Settings → Accesibilidad (`a11y.*` keys); `Theme` adds `contrast`, `strong-focus`, `wide-spacing` root classes and
follows OS high contrast / reduced motion. Every new control needs an accessible text (icon buttons get it from
`Composer.iconButton`), keyboard reachability, and must survive 160 % text + high contrast. `KeyboardUiTest`
(`-Dunichat.uiTests=true`, needs a display) checks F6 zones, timeline keys, zoom and screen-reader texts;
`FilterUiTest` (same flag) checks that the rail keeps the chosen chip when switching network.

## Rules
- Run `./mvnw test` before committing. Never point tests at the real data dir (surefire sets `unichat.dataDir`).
- Don't copy anything from Beeper's app bundle (proprietary). Design tokens were only used as reference.
- See `.claude/mistakes.md` before touching CSS sizing, icons, AWT/tray or Javadoc.
