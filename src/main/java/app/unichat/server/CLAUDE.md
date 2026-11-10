# server — notes for Claude

The local server: installs, starts and watches tuwunel and the bridges.

- `server/` — Synapse + Postgres + bridges docker-compose, `setup.sh` / `setup.ps1`. `docs/BRIDGES.md`.
- `server/BinaryStore` downloads bridges (and tuwunel when the package has none) only if they match the SHA-256 pinned in `resources/app/unichat/server/sha256sums.txt` (`Manifest.sha256`, keyed `<owner/repo>@<tag>/<asset>`), never the release's own sums. **Bump `versions.env` → run `native/pin-hashes.sh`** (`ManifestTest` fails until you do).
- `server/LocalServer` runs the processes; `server/BridgeInstaller` leaves a bridge ready (download, config, setup processes; tests with a temp dir and `FakeService`), `server/ServerSecrets` holds the secrets file.
- `server/StatusEndpoint` + `BridgeStates` (bridges POST their account states, mautrix `status_endpoint`) feed
  `MatrixChatService.accounts()` and `ui/AccountAlerts` + `AccountAlertBar` (foot of the chat list, so chats don't jump); `server/Watchdog` restarts hung,
  stuck or flapping processes (awake time, backoff), and all but Meta's bridges when the internet returns; `server/MetaSessions` keeps Meta cookies safe from the bridge erasing them over a redirect (see docs/NATIVE-SERVER.md). Demo: `-Dunichat.demoAccounts=WHATSAPP:NEEDS_LOGIN,...`.

## Lessons
- A thread reading a child process's output must never stop reading: when `Supervisor.pump` died (OOM) the pipe stayed full and the Discord bridge sat in write() for a day, alive but deaf. Use `support/Processes`.
