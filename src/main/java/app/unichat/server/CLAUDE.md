# server — notes for Claude

The local server: installs, starts and watches tuwunel and the bridges. The rules, commands and map are in the root `CLAUDE.md`.

- `server/` — Synapse + Postgres + bridges docker-compose, `setup.sh` / `setup.ps1`. `docs/BRIDGES.md`.
- `server/LocalServer` runs the processes; `server/BridgeInstaller` leaves a bridge ready (download, config, setup processes; tests with a temp dir and `FakeService`), `server/ServerSecrets` holds the secrets file.
- `server/StatusEndpoint` + `BridgeStates` (bridges POST their account states, mautrix `status_endpoint`) feed
  `MatrixChatService.accounts()` and `ui/AccountAlerts` + `AccountAlertBar` (foot of the chat list, so chats don't jump); `server/Watchdog` restarts hung,
  stuck or flapping processes (awake time, backoff), and all but Meta's bridges when the internet returns; `server/MetaSessions` keeps Meta cookies safe from the bridge erasing them over a redirect (see docs/NATIVE-SERVER.md). Demo: `-Dunichat.demoAccounts=WHATSAPP:NEEDS_LOGIN,...`.
