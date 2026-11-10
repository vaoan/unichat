# Architecture

Unichat is a desktop app (Java 25 + JavaFX 27). The UI only knows `ChatService`; behind it is Matrix with the
mautrix bridges, or demo mode.

## Layers

Each package may only depend on the ones below it. `ArchitectureTest` checks this on every `./mvnw test`.

| Package | What it does | May use |
|---|---|---|
| root (`app.unichat`) | Startup: creates and wires everything. Nothing depends on it | everything |
| `ui` | JavaFX screens. The only package (besides the root) that uses JavaFX | what's below, except `matrix` |
| `matrix` | `ChatService` over Matrix | `service`, `bridge`, `gif`, `links`, `login`, `model`, `settings`, `support`, `video` (converting a photo before sending it) |
| `service` | The API the UI uses (`ChatService`, `BridgeAccess`) and demo mode | `login`, `model`, `settings` |
| `server` | Local server: installs, starts and watches tuwunel and the bridges | `bridge`, `settings`, `support` |
| `stt` | Speech to text | `settings`, `support`, `video` |
| `assist` | Reply drafts with the person's AI CLI | `model`, `settings`, `support` |
| `video` | ffmpeg, yt-dlp and the conversion of WebM stickers | `settings`, `support` |
| `voice` | Recording voice notes, cleaning them up and encoding them | `model`, `settings`, `support`, `video` |
| `autosave` | Saving everything others send in a chat that has it on (media, and the video behind links with yt-dlp) | `service`, `model`, `settings`, `support`, `video` |
| `cloak` | Protecting a picture from AI training before sending it (Mist's textural attack, in `cloak.py`, run with a Python it installs) | `settings`, `support` |
| `links` | Opens the links in messages to find out whether they have video or audio, never reaching this machine or its network | `model`, `support` |
| `gif` | GIF search providers and frame reading | `model`, `settings`, `support` |
| `login` | Logging in to the bridges and browser cookies | `model`, `support` |
| `update` | Asks GitHub whether there is a new version of Unichat (`UpdateChecker`; the rules without network in `UpdatePolicy`) | `settings`, `support` |
| `settings` | Preferences | `support` |
| `model`, `support`, `bridge` | Domain data; utilities (`Os`, `Hashes`, `Redactor`, `BundledTools`, `Urls`, `Errors`, `Folders`, `Processes`, `Threads`, `ConvertedFiles`, `AppVersion`, `ErrorReport`); what `matrix` and `server` share about the bridges (`BridgeStates`, `BridgePorts`) | nothing in the project |

Accepted exceptions:

- `ui/LoginView` uses `MatrixChatService`: logging in is what creates the service.
- `ui` uses `server` for what isn't about chats (Telegram keys, logs, local server).

What exists only with bridges goes in `BridgeAccess`, which the demo doesn't implement. The UI asks
`svc instanceof BridgeAccess` instead of naming `MatrixChatService`.

## `service`: small interfaces

`ChatService` brings together interfaces by role: `ChatSession`, `ChatReader`, `MediaSource`, `ChatMessaging`, `ChatPreferences`,
`NetworkAccounts`, `ContactDirectory`, `GroupDirectory` and `ChatFolders` (folders). A new class asks only for the role it needs: `ImageCache`
receives `MediaSource`, not the whole service.

## `matrix`

`MatrixChatService` is the facade: it keeps the sync loop and hands the rest to its parts.

| Class | Responsibility |
|---|---|
| `MatrixChatService` | Lifecycle, sync and queries |
| `MatrixCore` | What the parts share: connection, rooms, threads and listeners |
| `MatrixLogin` | Logging in and restoring a saved session |
| `SyncCache` | The last sync on disk, without the content of chats with bots |
| `SyncProcessor`, `RoomFields`, `SystemMessages`, `SyncEvents` | `/sync` → model (logic without network) |
| `MessageSender` | What goes out in a chat |
| `ChatActions` | What you change about a chat |
| `BridgeAccounts` | Bridges and accounts |
| `BotChat`, `BridgeProtocol` | The chat with each bridge's bot; commands and replies |
| `DiscordServers`, `ContactActions`, `MediaDownloader` | Discord servers, contacts, downloads |
| `RoomPreferences` | What the server says versus `PreferencesFile`, which decides; the mute rule that goes back to the server |
| `MatrixClient` | HTTP |

## `ui`

A large window is split by what each piece does, not by screens:

| Class | Responsibility |
|---|---|
| `MainView`, `WindowShortcuts`, `Throttle` | The window and its layers; the keys of the whole window, F6 and zoom; how many times per second it redraws |
| `ChatListPane`, `ChatRowCell`, `SpaceChannelsView` | The chat column; how each row is drawn; a server's channels |
| `ThreadPane`, `TimelineScroll`, `MessageRowView`, `MessageLine` | The open chat; where the list looks; how a message is drawn; its buttons on mouse hover |
| `A11yText` | What the screen reader and the voice say (pure text, with tests) |
| `SettingsWindow`, `SettingsForm` and the pages (`NetworksPage`, `AiReplyPage`…) | The settings window; the pieces of a form; each family of pages |

## `server`

`LocalServer` starts and watches the processes. `BridgeInstaller` leaves a bridge ready (download, configuration and setup commands) and `ServerSecrets` holds the secrets file.

## Saving preferences

`PreferencesFile` doesn't write on the thread of whoever changes something: it copies the state under its lock and writes on a daemon thread, once for many changes in a row. `ChatSession.stop()` waits for that write, and `stop()` is reached on quitting (`UnichatApp.quit`, also from `MacQuit`), on logging out and from a shutdown hook.

## Code limits

There's no official number: Checkstyle defaults to 2,000 lines per file and 150 per method. Unichat is stricter
and checks it on every build:

| Rule | Limit | Tool |
|---|---|---|
| Lines per Java file | 800 | Checkstyle (`checkstyle.xml`) |
| Lines per method | 100 (goal: 60) | Checkstyle |
| Copied code | 100 tokens | PMD CPD (`./mvnw verify`) |

The limit isn't the goal. A file is split by responsibility, not to get under a number.

## Principles

- **KISS:** the simplest solution that works; no layers "just in case".
- **DRY:** what repeats three times gets extracted; twice, if it's the same rule and not a coincidence.
- **SOLID:** one responsibility per class; depend on interfaces (`ChatService`, `BridgeAccess`, `BridgeProvisioner`);
  small interfaces. When SOLID and KISS clash, KISS wins: no interface is created with a single implementation unless
  there's a dependency to invert.

## Known debt

Left alone because fixing it changes behaviour or needs tests that don't exist. In order of usefulness:

1. **Methods of 60 to 100 lines** (see `./mvnw checkstyle:check` with a lower limit): mostly UI constructors.
   **`Network`** is queried with `switch` in several places: a new network touches several files.
2. **Logs:** written with `System.err`, without levels, and there's no `UncaughtExceptionHandler`, so an error on
   one of our own threads can get lost. Migrating to `System.Logger` changes the log format, and `AppLog` and `Redactor`
   must keep filtering.
3. **`catch` blocks that ignore errors:** they have to be reviewed one by one; some are intentional.
4. **Global state:** `Settings.get()`, `LocalServer.get()` and the static `provisioner` of `BridgeAccounts` force
   the tests to share state.
5. **Spanish texts outside `ui`** (errors from `matrix`, `server`, etc.): this is the first step if it's ever translated.
6. **CI:** the GitHub actions are pinned by tag, not by SHA.
