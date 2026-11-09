# Native server (no Docker)

Unichat needs a Matrix server and a "bridge" for each network (WhatsApp, Telegram, …). That used to run in
Docker (`server/`). The native server does the same with ordinary system programs, without Docker:

| Piece | What it is | Where it comes from |
|---|---|---|
| **tuwunel** | Matrix server (a single executable, in Rust) | Built in GitHub Actions and **ships inside the Unichat installer** |
| **mautrix bridges** | One executable per network | **Downloaded** from mautrix's official releases, verified with `sha256sums.txt` |
| **SQLite** | Database (one file per component) | Built into the executables; nothing to install |
| **libolm** (Mac only) | Library the official Mac bridges need in order to start | Built with `native/build-libolm.sh` and **ships inside Unichat**; copied next to the bridges |
| **gif2webp** (libwebp 1.6.0, BSD license) | Converts animated GIF stickers to animated WebP for chats that only accept WebP (WhatsApp) | `package.sh` builds it with `native/build-gif2webp.sh` from the pinned source (with GIFLib; Google's binary requires macOS 15) and `package.ps1` downloads Google's official Windows binary, with its SHA-256 pinned, and it **ships inside Unichat**; without it, the sticker is sent still (PNG) |
| **anim_dump** (libwebp 1.6.0, BSD license) | Decodes animated WebP stickers frame by frame, as a browser does (the Java decoder gets some lossy frames wrong) | Built and shipped alongside gif2webp (`native/build-gif2webp.sh`, the same release in `package.ps1`); without it, Unichat falls back to the Java decoder |

> The official mautrix bridges for Mac link `libolm.3.dylib`, which Homebrew no longer offers. Without it they don't start
> (`Library not loaded: @rpath/libolm.3.dylib`). Unichat carries it; the bridge's encryption is off, so it is
> only loaded. Going forward: build the bridges with `-tags goolm` (pure Go, no libolm), as on Windows.

## Goal: it installs itself

When Unichat starts, it checks what's missing and installs it without asking:

1. **Server:** if `bin/tuwunel` doesn't exist or is a different version, the one shipped with the app is copied.
2. **Bridges:** only those for the networks the user added. When a new network is added, its bridge is downloaded at
   that moment (with a progress bar). If a bridge pinned in `versions.env` changed version, the new one is
   downloaded and restarted.
3. **Configuration:** generated the first time, and on each start only Unichat's keys are applied again
   (passwords and tokens are kept).
4. **Account:** the first time the user is created and Unichat signs in by itself; the user never sees a
   password or a server URL.
5. **Startup and supervision:** Unichat starts the server, waits for it to answer, starts the bridges and
   restarts them if they crash. When Unichat quits (tray menu → Quit Unichat) they are stopped.

With that, **updating Unichat = getting everything**: new versions of the bridges, of the server and configuration
changes come with the app.

> Status (26-Oct-2026): the logic exists as reference scripts for Mac (`native/setup-mac.sh` and
> `native/run-mac.sh`). The next step is to move it into Java inside Unichat so it runs at startup.

## Where everything lives

- macOS: `~/Library/Application Support/Unichat/server/`
- Windows: `%LOCALAPPDATA%\Unichat\server\`

```
server/
  bin/                 tuwunel and one executable per bridge (+ a .version file with the installed version)
  secrets.env          registration token and double puppeting token (private)
  CREDENCIALES.txt     the account's username and password (private)
  tuwunel/             tuwunel.toml + database (db/)
  appservices/         each bridge's registration (tuwunel reads them from here at startup)
  <network>/           each bridge's config.yaml, registration.yaml, <network>.db (SQLite) and bridge.log (filtered)
  run/                 PID of each running process
  bridges.active       active bridges
```

## Pinned versions: `native/versions.env`

One line per component: `<network>=<repo>@<release>:<executable prefix>`. The file downloaded is
`<prefix>-<os>-<architecture>` (for example `mautrix-whatsapp-darwin-arm64`) and it's checked against the
`sha256sums.txt` of the same release; if it doesn't match, it isn't installed.

**Updating a bridge = changing its version here** (and, if its changelog changed the configuration format,
adjusting the keys the installer applies). The bridge maintenance skill will do that work:
read official changelogs, bump versions, migrate configuration, test and publish.

## Configuration Unichat applies to each bridge

The same as the Docker stack (`server/setup.sh`), with SQLite instead of Postgres and `127.0.0.1` instead of
container names:

- Command prefix `!<network>` (Unichat uses it to talk to each bot; see `Network.protocolId()`).
- Permissions: the own domain is `user`, `@yo:localhost` is `admin`.
- Bridge encryption off (everything is local).
- **Double puppeting** with the `doublepuppet` appservice: what you send from your phone shows up as yours.
- **History ("backfill") on from the start**: 50 messages when a chat is created, up to 500 when
  catching up. Important: a chat created with backfill off isn't filled in later (it happened with Telegram
  in Docker); that's why it's on before the first login.
- WhatsApp: only the 50 most recent chats when connecting; those archived on the phone go to "Archived"
  (tag `u.archived`, one way only).
- Telegram: each person uses their own `api_id`/`api_hash` from my.telegram.org; Unichat ships none. It asks for them when
  connecting Telegram and they can be changed in Settings → Networks (`TelegramKeys`, saved in `session.properties`). Without valid
  keys Telegram doesn't start, but the server and the other networks do.
- Instagram: mautrix-meta v0.2607.0 in instagram mode (the last version that works for Instagram).
- **Media on demand (`direct_media`)**: the bridges no longer copy photos, videos or files to the server. Each
  message carries an `mxc://media-<network>.localhost/…` and Unichat asks the bridge itself for it
  (`127.0.0.1:<port>/_matrix/client/v1/media/download/…`, see `LocalServer.directMediaBase`) only when opening it;
  the bridge fetches it from the network at that moment. No federation needed. Before, every attachment of every channel and every
  Telegram resync was stored whole (11 GB in 4 days). Messages from before the change still
  point to their local copy. Drawback: a file the network already deleted (WhatsApp deletes them after a while)
  only comes back if the phone still has it (`direct_media_auto_request`).
- **Live status** (`status_endpoint`): each bridge reports its accounts' connection changes to
  `127.0.0.1:<free port>/status/<bridge>` (served by Unichat, `StatusEndpoint`; it only accepts that bridge's
  `as_token`). `BridgeStates` keeps them and the account list uses them instead of asking `list-logins`.
- **Watchdog** (`Watchdog`): every 30 s it asks the server and each bridge whether they answer. It restarts one that doesn't
  answer 3 times in a row, whose accounts have been disconnected for 10 min, or that disconnected 5 times in 10 min
  (mautrix-discord piles up server subscriptions on each reconnection until Discord cuts it off with 4008). Between
  restarts it waits 5 min, twice as long each time, up to 1 h. A logged-out session isn't restarted: only linking
  again fixes it. Time is measured without counting the Mac asleep. **Without internet** it doesn't restart a disconnected bridge (only
  a hung one): mautrix-discord, if it starts without a network, fails once and doesn't retry. To find out, it tries to open a connection to
  `discord.com`, `web.whatsapp.com` or `telegram.org`, without sending anything. **When the internet returns** it restarts the bridges at once, without waiting, and each one catches up; Facebook and Instagram not, because a restart can cost them the session.
- **Meta sessions** (`MetaSessions`): on some networks (Claro, in Colombia) `www.facebook.com` redirects to `web.facebook.com`. mautrix-meta sends its cookies as a header, Go drops them on that redirect, Facebook answers with the login page and the bridge **erases the cookies**, even though they were still valid. So: a private copy of the cookies is kept (`<bridge>/session-backup.json`, readable only by you, like the database); one of these bridges isn't restarted while that redirect is happening; and if the bridge erased the cookies of an account that is still listed, they're put back as soon as the network allows it, up to 2 times (if the session really was closed, Facebook rejects them and you have to link again). An account unlinked on purpose is no longer listed, and its copy is deleted.
- What Unichat downloads (`cache/media`) has a limit (Settings → Privacy, 1 GB by default): what hasn't been seen for
  the longest is deleted first (`MediaCache`).

The server (`tuwunel.toml`): listens only on `127.0.0.1:8008`, no federation, no presence ("online"),
registration only with a secret token, and reads the bridges' registrations from `appservices/`.

## Platforms

| | Mac Apple Silicon | Windows x64 | Mac Intel |
|---|---|---|---|
| tuwunel server | ✅ built by `natives.yml` (no changes, ~26 min) | ✅ built by `natives.yml`, without the Linux features (`--no-default-features` without `jemalloc`/`io_uring`/`systemd`; ~82 min, a 123 MB `tuwunel.exe`) | ❌ out of scope |
| Go bridges (WhatsApp, Telegram, Meta, Slack, Google Messages, X, LinkedIn, Discord) | ✅ official binaries | ✅ built by `natives.yml` from Linux (`go build -tags goolm`, cgo with MinGW; there are no official binaries) | ❌ |
| Signal | ✅ official binary | ❌ the Rust part builds; linking with Go fails (pending; `NATIVES_UNBUILT`, Unichat doesn't offer it) | ❌ |

- **Mac Intel is left out**: GitHub retired its `macos-13` machines (Dec-2025) and will remove the remaining Intel ones in
  2027; Apple no longer supports Intel.

## Own builds

What has no official binaries is built by `.github/workflows/natives.yml` and published as the release that
`NATIVES` in `versions.env` names (`natives-<n>`, pre-release, with `sha256sums.txt` and the link to the exact source
of each file, which the bridges' AGPL asks for):

- `tuwunel-darwin-arm64` and `tuwunel-windows-amd64`: `package.sh` and `package.ps1` put them in the app
  (`native/fetch-natives.sh`; `TUWUNEL_BIN` wins).
- `mautrix-<bridge>-windows-amd64`: Unichat downloads them on Windows when each network is connected (`Manifest.releaseUrl`),
  verified with `sha256sums.txt` like the official ones.

To update a bridge or tuwunel: change its version in `versions.env`, bump `NATIVES` (`natives-2`…) and push to
`main`; the workflow only runs if that release doesn't exist. A new bridge for Windows needs nothing else: the list
comes from `versions.env`.

What was learned while trying it (on a test branch, since deleted):

- WhatsApp on Windows: `go tool maubuild` fails ("not supported by windows"), but `go build -tags goolm
  ./cmd/mautrix-whatsapp` with cgo and MinGW works: a ~70 MB `.exe` (33 MB without symbols), `--version` and `-e`
  (example configuration) run fine. The other bridges build the same way (`cmd/<name>`; Discord, the legacy
  bridge, at the root). From a Mac too: `CGO_ENABLED=1 GOOS=windows GOARCH=amd64 CC=x86_64-w64-mingw32-gcc`
  (`brew install go mingw-w64`).
- Signal needs `protoc` (libsignal's `spqr`); on Windows what fails is linking Rust with Go.
- tuwunel on Windows needs LLVM/clang for the rocksdb bindings.

## Moving from Docker to the native server

Chats aren't migrated from Synapse. When switching, you have to log in to each network again once; the chats
are created again, already with history. What was marked only in Unichat (favorites, archived, low priority) is
lost; the messages aren't, because they're on each network.

## Manual use (development, Mac)

```sh
# Once: build tuwunel (or use the one the app ships)
cargo build --release --locked          # in a tuwunel clone at the version in versions.env
TUWUNEL_BIN=<path>/target/release/tuwunel native/setup-mac.sh     # downloads bridges and generates configs
native/run-mac.sh start                 # starts everything; creates the account the first time
native/run-mac.sh status
native/run-mac.sh stop
```
Requires `yq` (`brew install yq`). Telegram takes the keys from `server/.env` if they exist.

## Logs and reinstalling (Mac)

The installed app has no console; everything goes to files:

| What | File |
|---|---|
| Unichat | `~/Library/Application Support/Unichat/logs/unichat.log` (the previous one: `unichat.log.1`, after 5 MB) |
| Server | `~/Library/Application Support/Unichat/server/tuwunel/tuwunel.log` |
| Each bridge | `~/Library/Application Support/Unichat/server/<network>/bridge.log` (JSON) |

**No personal data:** the bridges and the server write to their standard output and Unichat filters it before
saving it (`Redactor`): it removes Matrix, room and event IDs, file links, phone numbers and long numeric
IDs, emails, tokens and the values of fields such as name, text, title or user. Unichat's log
goes through the same filter. The bridges log at `info` level (not `debug`).

**Cleanup:** each file rotates after 5 MB (a `.1` copy is kept). When Unichat opens and then once a
day, logs not written in the last N days are deleted (Settings → Privacy → Delete logs after: 1, 7 or
30 days; 7 by default). "Delete logs now" empties those in use and deletes the rest. Databases and
configurations are never touched.

Follow the log live: `tail -f "$HOME/Library/Application Support/Unichat/logs/unichat.log"`.

Fix and reinstall: `TUWUNEL_BIN=<path to tuwunel> ./install-mac.sh` packages, closes Unichat if it's
open and replaces `/Applications/Unichat.app`. The data (session, server, chats) isn't touched.
