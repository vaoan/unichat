# Unichat — notes for Claude

Java 21 + JavaFX 23 desktop clone of Beeper: unified inbox over Matrix + mautrix bridges. Maven via wrapper
(`./mvnw`); use `JAVA_HOME=/opt/homebrew/opt/openjdk@21` on this Mac. User-facing text and README/docs in
**Spanish**; code, comments, commits in English. Must keep working on **Windows** (user's target).

## Layout
Package rules: `docs/ARQUITECTURA.md`, enforced by `ArchitectureTest` (no cycles, JavaFX only in `ui`, `ui` talks to `service`). Read it before adding a dependency between packages.
- `model/` — mutable `Chat`, `Message`, records `Member`, `Account`; `Network` maps each network to its bridge
  bot localpart / puppet prefix / protocol id (must match `server/setup.*`).
- `support/` — leaf utilities (`Os`, `Hashes`, `Redactor`, `BundledTools`).
- `bridge/` — what `matrix` and `server` share about bridges (`BridgeStates`, `BridgePorts`).
- `service/ChatService` — the only API the UI uses (made of small role interfaces; take the narrowest one). Live model objects, listeners on any thread (UI marshals with
  `Platform.runLater`, coalesced in `MainView`). `MockChatService` = demo mode. `BridgeAccess` = what only bridge-backed services add.
- Data dir files: `preferences.json` (per-chat archived/muted/favorite/lowPriority/pinned + folders; `service/PreferencesFile`), `settings.properties`,
  `media-library.json` and `media-library/` (a copy of each favourite sticker/GIF, `matrix/SavedMedia`) travel with the person (copy them to a new machine); `session.properties` (token, `matrix.*`, `server.*`, `window.*`; `Settings.staysOnThisMachine`
  decides, older keys migrate out of settings.properties) and `server-seen.json` stay. An older version will ask you to log in again, because the session moved to session.properties; nothing is lost. `preferences.json.bak` is the version before the last save, tried when the main file is damaged. Preference rules (`matrix/RoomPreferences`): the file wins; a field the
  person never touched follows the server; a server false counts only if the same server (identity `homeserver|deviceId`) said true, so a fresh server doesn't
  erase copied choices. Only the mute rule still goes to the server (unread counts come from it). Chat identity across servers: `service/ChatKeys`,
  `<protocol>:<channel.id>`. `service/ChatFolders` is the role for folders (`FolderActions`, `FolderForwarding`); `ui/FolderView` (+ `FolderRows`, `FolderDialogs`) shows them.
- `matrix/` — `MatrixChatService` (facade: lifecycle, sync loop) over `MatrixCore` (shared), `MessageSender`, `ChatActions`,
  `BridgeAccounts`, `DiscordServers`, `ContactActions`, `MediaDownloader`, `SyncCache`, `MatrixLogin`; `SyncProcessor`
  (pure /sync → model, unit tested) with `RoomFields`, `SystemMessages`, `SyncEvents`; `BridgeProtocol` (pure), `BotChat`, `MatrixClient` (HTTP). `MatrixLiveIT` = end-to-end test, needs `UNICHAT_IT_HOMESERVER`.
- `ui/` — `MainView` (rail | `ChatListPane` | `ThreadPane` + `InfoPane`, overlays, shortcuts), `Composer`,
  `CommandBar`, `AddAccountPane`, `SettingsWindow`, `Notifier` (AWT tray), `Theme`. Pure logic with tests:
  `ChatFilter`, `ThreadRows`, `RichText`, `Formats`.
- Timeline updates: `ThreadRows.update` touches only rows that differ (`MessageRow.revision`, from `Message.revision()`: a new drawn field must go in there), since replacing the whole list redraws all that's on screen; `ThreadPane.redraw` is for changes of appearance; `MainView` updates the open chat at most every 250 ms. `ScrollPerfUiTest` (`-Dunichat.perfTests=true`, real display, ffmpeg) prints before/after numbers for a giant chat; compare with the Mac under similar load, which moves them a lot.
- `css/app.css` — single stylesheet; light palette on `.root`, dark on `.root.dark`; `-accent` and font size
  set inline by `Theme`.
- `server/` — Synapse + Postgres + bridges docker-compose, `setup.sh` / `setup.ps1`. `docs/BRIDGES.md`.
- `native/` — no-Docker stack (tuwunel + mautrix bridge binaries + SQLite): `versions.env` pins every component,
  `setup-mac.sh` / `run-mac.sh` are the reference for the installer Unichat will run at startup (install whatever
  is missing, start and supervise). Read `docs/SERVIDOR-NATIVO.md` before touching it.
  Bridges run with `direct_media` (`mxc://media-<bridge>.localhost/…`, nothing copied to the server); Unichat
  downloads those from the bridge's port (`LocalServer.directMediaBase`), and `matrix/MediaCache` caps `cache/media`
  (`cache.mediaMb`, LRU; files over 1 MB go first, saved stickers/GIFs are pinned, `MediaSource.keepCached`; one file per direct-media URL whatever the size). `model/MediaLibrary.same` says when two items are one sticker (bridge id, else format, dimensions and bytes), since direct media gives the same file a new URL in each message. Media grids keep their tiles (`ui/TileCache`) and `MessageMedia.loadMedia` shows what `AnimatedImage` already decoded at once. `server/MediaPrune` deletes Discord attachments older than `discord.mediaDays` (default 30, 0 = never; Ajustes →
  Redes) on each server start; avatars/emoji kept.
- `server/StatusEndpoint` + `BridgeStates` (bridges POST their account states, mautrix `status_endpoint`) feed
  `MatrixChatService.accounts()` and `ui/AccountAlerts` + `AccountAlertBar` (foot of the chat list, so chats don't jump); `server/Watchdog` restarts hung,
  stuck or flapping processes (awake time, backoff), and all but Meta's bridges when the internet returns; `server/MetaSessions` keeps Meta cookies safe from the bridge erasing them over a redirect (see docs/SERVIDOR-NATIVO.md). Demo: `-Dunichat.demoAccounts=WHATSAPP:NEEDS_LOGIN,...`.
- macOS Dock: the package declares `LSUIElement` (package.sh), so by default there's no Dock icon, ⌘Tab or app menu; Ajustes → Notificaciones `app.showInDock` adds one while the window is open (`ui/DockIcon`), also forced if the menu bar icon is missing. ⌘Tab only lists apps with a Dock icon (macOS has no way around it), so `native/UnichatBar.swift` registers a global ⌃⌥U (`app.globalShortcut`, Carbon: no Accessibility permission) that brings the window back. `UnichatApp` handles ⌘Q itself, and activation with no window brings it back (`TrayReopen`). Don't build nodes while the window is hidden: nothing is drawn, so JavaFX never empties `Scene.dirtyNodes` and everything built stays in memory (`MainView.catchUp` brings it up to date on opening; only the menu bar count updates meanwhile).
- `native/UnichatBar.swift` (`native/build-bar.sh`) — macOS helper, built as `UnichatBar.app` (bundle id
  `app.unichat.helper`, shown as "Unichat"): the menu-bar item (narrower than Java's TrayIcon, real NSMenu; a left click brings the window, a right click or Control-click opens the menu) and
  **system notifications** (UNUserNotificationCenter needs a bundle; clicks come back as `open <chat id>`). Driven by
  `ui/NativeBar` over stdin (the unread count, `ChatFilter.inboxUnread` like the inbox badge, shows beside the icon; the AWT tray draws it on the icon, `Notifier.withCount`); `Notifier` uses it when macOS allows notifications, else `DesktopToast` (own card, must
  never take keyboard focus), and the AWT TrayIcon if the helper is missing or dies. package.sh ships it as
  `Contents/app/native/UnichatBar.app`.
- Message HTML: `ui/HtmlView` draws `formatted_body` tag by tag, as a browser would (jsoup, so badly nested HTML reads the same), with no cases per network or bridge: Discord embeds are just HTML, and `matrix/MessageHtml` writes `com.beeper.linkpreviews` as HTML too. Pictures only from `mxc://`, links only http(s), no scripts or frames. `Message.body` stays plain text for lists, replies and editing (`withoutEmbedText` drops the "> " lines the bridge repeats an embed in). Discord's bridge drops the video of a rich embed, so `links/LinkInspector` opens each link when its message shows (`MediaSource.linkMedia`; at most 5 per message) and asks the server what it is, or the page's `og:video`; `MessageMedia.linkPlayers` shows it. Those links come from anyone: no cookies, and every hop refuses this machine and its network; `MediaDownloader` fetches all web addresses through it too. A browser's User-Agent gets redirected by FixupX, hence "compatible".
- Video: `Message.playsInline` decides by what the bridge reports (GIF flag, video stickers, or up to 2 MB and 15 s); `ui/InlineVideo` (muted loop in the timeline, a native player only while on screen, none with reduced motion), `ui/VideoViewer` (window with controls; downloads on opening). javafx-media comes with javafx-web; WebM it can't play goes through `video/WebmStickers` (ffmpeg to animated WebP or, if that ffmpeg lacks the encoder like Homebrew's, GIF; cached in `cache/media`; `video/Ffmpeg` uses the person's own or downloads a pinned build on first use, `video.ffmpegAuto`, Ajustes → Redes), `VideoViewer` converts other unreadable videos to H.264 (`video/VideoTranscoder`), and what still can't play falls back to a file chip and the system player. Trust the file, not its label: a sticker labelled `video/webm` may be delivered as a GIF; `.mov` is cached as `.mp4` because JavaFX refuses that name. Reels that arrive as a picture + `external_url` open their page (`Message.isReelLink`).
- Photos: `ui/MediaViewer` (window and controls) over `ui/PhotoPane` (mouse) and `ui/PhotoZoom` (pure: scale, turn, position). Animated GIF/WebP stay decoded at up to 1600 px, so real size there is the decoded one.
- Audio: `ui/AudioPlayback` (one per window: starting another stops it; survives changing chats), shown by `AudioControls` in the message and in `AudioBar` on top. JavaFX refuses Ogg/Opus: `stt/PlayableAudio` makes a 48 kHz WAV once (`OggOpus.toPlayableWav`; 16 kHz is only for Whisper).
- Sending voice notes: `voice/` records from `Microphone` (Java Sound) and encodes Ogg/Opus in pure Java (`VoiceEncoder`, Concentus; `OggOpusWriter`); `ui/VoiceBar` replaces the composer row while recording. `voice/VoiceEnhancer` (`voice.enhance`) cleans the raw recording before sending with ffmpeg (highpass, RNNoise `arnndn`, speechnorm), downloading ffmpeg and the pinned model on first use; any failure sends it as recorded. Measure changes to the filter with speech (`say`), not tones: RNNoise leaves a tone alone. `VoiceEnhancerTest` does with `-Dunichat.networkTests=true`. `RoomFeatures.voiceSend` says, per chat, the MIME (WhatsApp needs `audio/ogg; codecs=opus`) or null (no mic). A Mac without permission hands over pure silence, not an error: `VoiceBar` detects it.
- Login steps asking for request headers (LinkedIn): `LoginWebView.openCapturing` watches the page's own XHR/fetch headers through a script and builds the Cookie header from the cookie store (`BridgeCookies.captured`); LinkedIn cancels a session used under another browser's identity within seconds (mautrix/linkedin#61: its bridge always says Chrome 141/Linux), so cookies from the person's own browser don't work, and Google refuses embedded windows. So `login/ChromeLogin` opens their real Chrome on a throwaway profile (on a Mac through `open`: a child process would have its permissions charged to Unichat, and passkeys on the phone need Bluetooth), uses a chosen debugging port (port 0 makes `navigator.webdriver` true and Google refuses), gives only the site's tab the bridge's identity over the debugging port (`Emulation.setUserAgentOverride`, client hints included; Google's popup is another tab, unattached, because Google rejects a browser that contradicts itself), reads the cookies once the feed loads and deletes the profile (`-Dunichat.chromeTests=true` runs it for real, off screen); the window remains for email and password, and stops waiting for headers after 8 s (`BridgeCookies.headerFallbacks`).
- `assist/` + `ui/AiReplyButton`, `AiReplyPage` — AI reply drafts, and correcting what's written (`FixPrompt`: only that text goes, no chat, instructions or folder; undoable with ⌘Z), through the person's own `claude` or `codex` (`AiEngine`, prompt on stdin, `ai.*` settings, off by default). Only ever text in the composer, never sent by itself. From anywhere in the window ⌘I writes and ⌘O corrects (`Composer.aiKeyPressed`), leaving the focus in the text box; the info panel is ⌘⇧I. Chats are written by others and can carry instructions: keep the CLI's tools minimal (`AiEngine.command`: Claude reads only the chosen folder, tested by `ReplyWriterTest` with `-Dunichat.networkTests=true`) and never log the prompt.
- `stt/` — voice notes to text: `Transcriber` runs whisper.cpp's `whisper-cli` (`native/build-whisper.sh` on Mac,
  pinned official zip in package.ps1; shipped in `app/server/whisper/`) or the user's own command (`CommandLine`);
  `OggOpus` decodes Opus at 48 kHz then low-passes to 16 kHz (Concentus returns silence for the first CELT frames
  at 16 kHz); `WhisperModel` downloads the pinned model on first use; `Transcripts` caches per media URL.
  Claude Code / Codex CLIs can't take audio (tested), so they aren't engines.
- `native/fetch-gif2webp.sh` — pinned libwebp `gif2webp` shipped in `app/server/` (Windows: pinned in package.ps1);
  `matrix/StickerConverter` uses it when a chat's room_features refuse a sticker's MIME (GIF → WebP on WhatsApp).
  Ajustes → Redes (`<network>.animatedStickers` = STICKER|GIF|VIDEO) can send them as mp4 instead (`GifFrames` +
  JCodec, pure Java).
- `THIRD-PARTY.md` — generated by `native/third-party.py` (rerun when a dependency or pinned version changes); shipped
  with `LICENSE` in the packages. Unichat's own code is 0BSD.
- `package.sh` / `package.ps1` — jpackage; `.github/workflows/build.yml` builds Windows zip/msi + macOS dmg.

## Checking UI changes
`-Dunichat.snapshot=out.png` writes the window to a PNG and exits; combine with `-Dunichat.dataDir=<dir>` (put
`session.backend=demo` and optionally `ui.theme=LIGHT` in `<dir>/settings.properties`),
`-Dunichat.openChat=<id>` (demo ids like `!familia:demo`), `-Dunichat.openSpace=<id>` (a server's channels, e.g. `!servidor:demo`), `-Dunichat.info=true`, `-Dunichat.panel=EMOJIS|STICKERS|GIFS`,
`-Dunichat.overlay=command|newchat|add|folderNew|folderDelete|settings:<Página>`, `-Dunichat.openFolders=true` (folder list), `-Dunichat.openFolder=<name>` (inside one), `-Dunichat.network=<protocolo>` (settings snapshots the settings window).
Run via `JAVA_OPTS=... ./run-dev.sh`, then read the PNG. Do this after every visual change.

## Accessibility (user requirement — keep it working)
Settings → Accesibilidad (`a11y.*` keys); `Theme` adds `contrast`, `strong-focus`, `wide-spacing` root classes and
follows OS high contrast / reduced motion. Every new control needs an accessible text (icon buttons get it from
`Composer.iconButton`), keyboard reachability, and must survive 160 % text + high contrast. `KeyboardUiTest`
(`-Dunichat.uiTests=true`, needs a display) checks F6 zones, timeline keys, zoom and screen-reader texts;
`FilterUiTest` (same flag) checks that the rail keeps the chosen chip when switching network. `./mvnw verify -Pheadless-ui` runs them with no display or windows (Monocle); `AudioUiTest`, `ReactionEmojiUiTest` and `NetworkSettingsUiTest` still need a display (`-Dunichat.uiTests=true`): JavaFX stops under Monocle there. It proves logic, not focus, tray or Dock.

## Rules
- KISS, DRY, SOLID (`docs/ARQUITECTURA.md`). Java files stay under 800 lines, methods under 100 (Checkstyle fails the build); split by responsibility.
- Run `./mvnw verify` before committing (tests, architecture rules, copied code). Never point tests at the real data dir (surefire sets `unichat.dataDir`).
- Don't copy anything from Beeper's app bundle (proprietary). Design tokens were only used as reference.
- See `.claude/mistakes.md` before touching CSS sizing, icons, AWT/tray or Javadoc.
