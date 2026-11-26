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
| `cloud` | The person's own storage account over WebDAV (pCloud, Icedrive, Nextcloud…): settings and what the AI learned kept the same on every computer, applied live; what chats save goes up | `settings`, `support` |
| `cloak` | Protecting a picture from AI training before sending it (Mist's textural attack, in `cloak.py`, run with a Python it installs) | `settings`, `support` |
| `links` | Opens the links in messages to find out whether they have video or audio, never reaching this machine or its network | `model`, `support` |
| `gif` | GIF search providers and frame reading | `model`, `settings`, `support` |
| `login` | Logging in to the bridges and browser cookies | `model`, `support` |
| `update` | Asks GitHub whether there is a new version of Unichat (`UpdateChecker`; the rules without network in `UpdatePolicy`) | `settings`, `support` |
| `settings` | Preferences | `support` |
| `model`, `support`, `bridge` | Domain data; utilities (`Os`, `Hashes`, `Redactor`, `BundledTools`, `Urls`, `Errors`, `Folders`, `Processes`, `Threads`, `ConvertedFiles`, `AppVersion`, `ErrorReport`, `PathParts`, `BoundedMap`); what `matrix` and `server` share about the bridges (`BridgeStates`, `BridgePorts`, `LoginsCache`, `ChatLimits`: how many chats each bridge was asked for) | nothing in the project |

Accepted exceptions:

- `ui/login/LoginView` uses `MatrixChatService`: logging in is what creates the service.
- `ui` uses `server` for what isn't about chats (Telegram keys, logs, local server).

What exists only with bridges goes in `BridgeAccess`, which the demo doesn't implement. The UI asks
`svc instanceof BridgeAccess` instead of naming `MatrixChatService`.

## `service`: small interfaces

`ChatService` brings together interfaces by role: `ChatSession`, `ChatReader`, `MediaSource`, `ChatMessaging`, `ChatPreferences`,
`NetworkAccounts`, `ContactDirectory`, `GroupDirectory`, `ChatFolders` (folders) `ArchiveWaking` (what rests while the archive is closed; nothing in the demo) and `OlderChats` ("Load older chats"; only with bridges, and in the demo only for snapshots). A new class asks only for the role it needs: `ImageCache`
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
| `OlderChatSteps`, `OlderChatLoader`, `RoomArrivals`, `LoadsOlderChats` | "Load older chats", only on demand: what each network's next step is (pure), running them one network at a time, counting the rooms that arrive, and the `service/OlderChats` role |
| `ArchiveRest`, `ArchivedServers`, `SyncFilter` | Archived Discord servers and channels left out of the sync while the archive is closed, and woken (filter, catch-up) while it's open; what each server brings at the bridge (an archived one opened in the archive comes back until leaving); the sync's filter, posted once and named by id |
| `DiscordServers`, `ContactActions`, `MediaDownloader` | Discord servers (a pause the bridge refused is retried with `Retry`, like mute rules), contacts, downloads |
| `Profiles`, `Invites`, `FetchedMessages` | Names and pictures the sync left out (a few at a time); joining invites; messages fetched on their own (a reply's original) |
| `RoomPreferences`, `Retry` | What the server says versus `PreferencesFile`, which decides; the mute rule that goes back to the server; when a failed write is tried again |
| `MatrixClient` | HTTP |

## `ui`

One subpackage per area, each with its own `CLAUDE.md`. `ui` itself holds the window and what every area shares;
the areas use it, and `MainView` puts them together. A member is `public` only when another package uses it.
`ArchitectureTest` treats `ui` and all its subpackages as the UI layer.

| Package | What it holds |
|---|---|
| `ui` | `MainView` (the window and its layers), `WindowShortcuts` (keys of the whole window, F6 and zoom), `Throttle`, `Redraws`, `FxCoalescer`; shared bits: `Nodes`, `Icons`, `Labels`, `Chips`, `Theme`, `Formats`, `A11yText` (what the screen reader and the voice say), `ListRows`, `SearchFields`, `WorkingLine`, `Browser` |
| `ui.chats` | The chat column (`ChatListPane`, `ChatRowCell`, `SpaceChannelsView`), rail, folders, favorites, new chat, avatars; the info and side panels |
| `ui.timeline` | The open chat (`ThreadPane`, `TimelineScroll`, `MessageRowView`, `MessageLine`), message HTML, the composer and its parts, message actions |
| `ui.media` | Photos, video, audio and animations: viewers, players, caches, tiles, the profile's media |
| `ui.emoji` | Emoji picker, search and inline pictures, reactions, sticker and GIF grids, the media library |
| `ui.settings` | `SettingsWindow`, `SettingsForm` and the pages (`NetworksPage`, `AiReplyPage`…); settings applied live from the cloud |
| `ui.desktop` | Dock, tray, menu bar, notifications, window switching, quitting |
| `ui.login` | Signing in, bridge logins, accounts, Telegram keys, Discord servers |

## `server`

`LocalServer` starts and watches the processes. `BridgeInstaller` leaves a bridge ready (download, configuration and setup commands) and `ServerSecrets` holds the secrets file.

## Saving preferences

`PreferencesFile` and `Settings` don't write on the thread of whoever changes something: each copies its state under its lock and writes on a daemon thread, once for many changes in a row (`support/BackgroundWriter`). `ChatSession.stop()` waits for the preferences' write, and `stop()` is reached on quitting (`UnichatApp.quit`, also from `MacQuit`), on logging out and from a shutdown hook. `Settings.flush()` is the last thing `Shutdown.stopThenEnd` does before the end, and the shutdown hook calls it too. Every file is put in place by `support/SafeFile` (temp beside it, fsync, rename; the temp removed on failure).

## Code limits

There's no official number: Checkstyle defaults to 2,000 lines per file and 150 per method. Unichat is stricter
and checks it on every build:

| Rule | Limit | Tool |
|---|---|---|
| Lines per Java file | 800 | Checkstyle (`checkstyle.xml`) |
| Lines per method | 100 (goal: 60) | Checkstyle |
| Naming, imports, `equals`/`hashCode`, `switch`, modifiers, hidden fields | no finding | Checkstyle (no layout rule) |
| Copied code | 100 tokens | PMD CPD (`./mvnw verify`) |

The limit isn't the goal. A file is split by responsibility, not to get under a number.

## Languages

What Unichat stores, sends, logs or tells a program is English (file and folder names, cloud paths, ids, log lines,
AI prompts). The person reads their own language only through `support/I18n` and `messages_<lang>.properties`, from
any package. `EnglishFirstTest` fails on a Spanish string literal under `src/main/java`, except for `support/I18n`,
comments and its explicit allow list, each entry with its reason (`.claude/rules/i18n.md`).

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
2. **Logs:** written with `System.err`, without levels. An uncaught error is logged by `Recovery`'s handler (and
   restarts Unichat when it's fatal), but one inside an executor's task still ends in its `Future`. Migrating to `System.Logger` changes the log format, and `AppLog` and `Redactor`
   must keep filtering.
3. **`catch` blocks that ignore errors:** they have to be reviewed one by one; some are intentional.
4. **Global state:** `Settings.get()`, `LocalServer.get()` and the static `provisioner` of `BridgeAccounts` force
   the tests to share state.
