# Your own server and bridges

Unichat is a Matrix client. The networks (WhatsApp, Telegram, Signal, Instagram, Messenger, Discord,
Slack, Google Messages, X and LinkedIn) arrive through **mautrix bridges** installed on your own
Matrix server. The `server/` folder has everything needed to bring it up with Docker.

## What's included

| Service | Image | Bot | "Puppet" users |
|---|---|---|---|
| Synapse (Matrix server) | `matrixdotorg/synapse` | — | — |
| Postgres | `postgres:16-alpine` | — | — |
| WhatsApp | `dock.mau.dev/mautrix/whatsapp` | `@whatsappbot` | `@whatsapp_…` |
| Telegram | `dock.mau.dev/mautrix/telegram` | `@telegrambot` | `@telegram_…` |
| Signal | `dock.mau.dev/mautrix/signal` | `@signalbot` | `@signal_…` |
| Instagram | `dock.mau.dev/mautrix/meta:v26.07` (instagram mode) | `@instagrambot` | `@instagram_…` |
| Messenger | `dock.mau.dev/mautrix/meta` | `@facebookbot` | `@facebook_…` |
| Discord | `dock.mau.dev/mautrix/discord` | `@discordbot` | `@discord_…` |
| Slack | `dock.mau.dev/mautrix/slack` | `@slackbot` | `@slack_…` |
| Google Messages | `dock.mau.dev/mautrix/gmessages` | `@gmessagesbot` | `@gmessages_…` |
| X (Twitter) | `dock.mau.dev/mautrix/twitter` | `@twitterbot` | `@twitter_…` |
| LinkedIn | `dock.mau.dev/mautrix/linkedin` | `@linkedinbot` | `@linkedin_…` |

Unichat recognizes each network by these names (see `Network.java`), so **don't change them**.
Each bridge also uses the command prefix `!<network>` (`!whatsapp`, `!facebook`, `!twitter`,
`!gmessages`…): Unichat puts it in front of the commands it sends so they work in any chat with the bot.

**Instagram**: mautrix-meta stopped supporting Instagram after version v26.07, so that
container is pinned to `v26.07`. Messenger uses the latest version.

## Requirements

- Docker Desktop (Windows/macOS) or Docker Engine + Compose v2 (Linux), **running**.
- ~2–3 GB of free RAM.
- For Telegram (optional): your own `api_id` and `api_hash` (see below).

## Getting started

macOS / Linux:

```bash
cd server
ADMIN_USER=yo ./setup.sh localhost
```

Windows (PowerShell):

```powershell
cd server
.\setup.ps1 -Domain localhost -AdminUser yo
```

The script (safe to run again; it only reapplies what's needed and restarts what changed):

1. Creates `server/.env` with random passwords (git ignores it; don't share it).
2. Generates the Synapse configuration (`data/synapse/homeserver.yaml`), connects it to Postgres and
   relaxes the message/login rate limits (it's a personal server).
3. Generates each bridge's configuration, gives it Unichat's bot/puppet names,
   **turns encryption off**, turns on *double puppeting* and creates its `registration.yaml`.
4. Registers the bridges with Synapse and brings everything up with `docker compose up -d`
   (the bridges wait for Postgres and Synapse to be healthy).
5. The first time, it creates your user with a random password and saves it in
   **`server/CREDENCIALES.txt`** (git ignores it).

To choose only some networks: `BRIDGES="whatsapp signal" ./setup.sh` or
`.\setup.ps1 -Bridges whatsapp,signal`.

To create another user later:

```bash
docker compose exec synapse register_new_matrix_user -c /data/homeserver.yaml -a http://localhost:8008
```

### Telegram (turn on later)

Telegram requires your own API keys. Without them the script **leaves Telegram off** (it isn't
registered with Synapse and its container doesn't start) and everything else works normally. To turn it on:

1. Go to <https://my.telegram.org> → *API development tools* and create an app: you get an `api_id`
   (a number) and an `api_hash`.
2. Run the script again with them (they're saved in `server/.env`, so this is only needed once):

   ```bash
   cd server
   TELEGRAM_API_ID=123456 TELEGRAM_API_HASH=abcdef0123... ./setup.sh
   ```

   ```powershell
   cd server
   $env:TELEGRAM_API_ID="123456"; $env:TELEGRAM_API_HASH="abcdef0123..."; .\setup.ps1
   ```

   You can also add `TELEGRAM_API_ID=...` and `TELEGRAM_API_HASH=...` to `server/.env` by hand
   and run `./setup.sh` (or `.\setup.ps1`) with nothing else. The script adds `COMPOSE_PROFILES=telegram`
   to `.env` so that `docker compose up -d` also starts Telegram.

To turn it off again, delete those two lines from `server/.env` and run the script again.

### About the domain

The domain is part of your ID (`@yo:localhost`) and **can't be changed later**.
`localhost` works for using Unichat on the same computer. If you want to use it from other devices,
use a real domain, publish port 8008 behind an HTTPS proxy (Caddy, nginx, Traefik) and serve
`https://tu-dominio/.well-known/matrix/client` with `{"m.homeserver":{"base_url":"https://matrix.tu-dominio"}}`.
With `localhost` the server can't federate, so the bots' avatars (hosted on
`maunium.net`) don't load; it's only cosmetic.

## Signing in to Unichat

On Unichat's start screen enter:

- **Server**: `localhost:8008` (or your domain; Unichat looks up `.well-known` automatically).
- **Username**: `yo` (or the full ID `@yo:localhost`).
- **Password**: the one in `server/CREDENCIALES.txt`.

## Connecting each network

Press **Connect a network** (the **+** button in the sidebar, the command palette or
*Settings → Accounts*) and choose the network. Unichat opens (or creates) the chat with the bridge's bot and sends it
the login command. Follow the bot's instructions **in that chat**:

| Network | Command Unichat sends | What the bot replies / what you do |
|---|---|---|
| WhatsApp | `!whatsapp login qr` | A **QR code**. On your phone: WhatsApp → *Linked devices* → *Link a device* and scan it. Alternative: `!whatsapp login phone` (pairing code). |
| Telegram | `!telegram login qr` | A **QR code**. On your phone: Telegram → *Settings → Devices → Link Desktop Device*. If you have two-step verification, the bot will ask for the password. Alternative: `!telegram login phone`. |
| Signal | `!signal login` | A **QR code**. On your phone: Signal → *Settings → Linked devices* → **+**. |
| Google Messages | `!gmessages login` | Unichat connects your Google account with the cookie assistant (see "Connecting cookie networks" below). Then confirm the **emoji** that shows up in Messages on your phone. QR pairing no longer exists. |
| Instagram | `!instagram login` | Connects with Unichat's cookie assistant (browser session, sign-in window or `cookies.txt`). |
| Messenger | `!facebook login facebook` | Connects with Unichat's cookie assistant (facebook.com). Alternatives: `!facebook login messenger` (messenger.com cookies) or `!facebook login messenger-lite` (username and password). |
| Discord | `!discord login` | A **QR code**: scan it from the Discord mobile app (*Settings → Scan QR Code*). Alternative: `!discord login-token`. |
| Slack | `!slack login email` | Asks for your Slack account's **email** and then the code you receive by email. Alternative: `!slack login token` (token `xoxc-…` + cookie `d` `xoxd-…`). |
| X | `!twitter login cookies` | Connects with Unichat's cookie assistant (`auth_token` and `ct0`). Alternative: `!twitter login password`. |
| LinkedIn | `!linkedin login cookies` | Connects with Unichat's cookie assistant (linkedin.com). Alternative: `!linkedin login password`. |

QR codes expire in a minute or two: if you don't make it in time, press **Connect a network** again.
To abandon a login, type `cancel` in the bot's chat; `help` shows all the commands.

When the bot confirms the login, your chats show up on their own in Unichat's inbox
(the bridges invite you to each room and Unichat accepts the invitations automatically).

**Disconnect**: in *Settings → Accounts → Disconnect* Unichat sends `list-logins` and then
`logout <id>` to each session it finds (or `logout` if the bridge doesn't have that command).
It's "best effort": if the bot asks for something else, finish the process in its chat.

## Limitations

- **No end-to-end encryption (E2EE) in Matrix**: Unichat doesn't implement E2EE. That's why the
  script turns encryption off in every bridge (`encryption.allow: false`). Traffic between
  the original network and the bridge is still encrypted as always; what isn't encrypted is the part between your
  server and Unichat, which is why the server should be yours. Matrix rooms that are already encrypted
  show "🔒 Unsupported encrypted message".
- **Instagram, Messenger, X, LinkedIn, Slack and Google Messages** use web cookies: they can
  expire and the network could block the account if it notices unusual use. Use them at your own risk.
- **Instagram** depends on an old version of mautrix-meta (v26.07); if Meta changes something, it may
  stop working with no fix.
- **WhatsApp and Signal** require the main phone to connect every now and then.
- The account status in Unichat is approximate: it's inferred from the bridge's rooms and the
  bot's messages.
- Public read receipts are only sent if they're turned on in *Settings → Privacy*;
  otherwise Unichat sends a private receipt (`m.read.private`) to clear the unread count.

## Starting, stopping and maintenance

The containers have `restart: unless-stopped`: they come back on their own when Docker Desktop opens.
If Docker restarts, the bridges may restart a couple of times until Postgres and Synapse
are ready; that's normal.

```bash
cd server
docker compose ps                                # status
docker compose stop                              # stop everything (the data stays in server/data)
docker compose start                             # start again
docker compose logs -f whatsapp                  # see a bridge's log
docker compose pull && ./setup.sh                # update images and reapply the configuration
docker compose down                              # stop and remove containers (not the data)
```

Backup: stop the stack and keep the `server/data/` folder, `server/.env` and
`server/CREDENCIALES.txt`.

If you change bot or puppet names in a `config.yaml`, delete that bridge's `registration.yaml`
and run the script again to regenerate it.

## Connecting cookie networks (Instagram, Messenger, X, LinkedIn, Google Messages)

You no longer need to copy cookies by hand or grab a cURL. In **Accounts → Add account → the network** Unichat
offers three ways:

1. **Use your browser session**: Unichat looks in Firefox and Chromium browsers (Chrome, Edge, Brave…)
   for an existing session on that network and shows you a card "Use your <network> session from <browser>".
   On macOS it may ask for permission to read the browser's keychain.
2. **Sign in with another account**: opens a window inside Unichat with the network's page; you sign
   in as usual (two-step verification included) and the window closes on its own when you're done.
3. **Choose cookies.txt file…**: export the cookies while signed in (a "Get cookies.txt"-style extension)
   and choose the file. If cookies are missing, Unichat says which ones.

There's also a discreet **Paste cookies manually** link (JSON or cURL), as before.

Privacy: only the cookies of the network you're connecting are read, they're sent only to your own
Matrix server (to the bridge's bot) and Unichat neither keeps them nor writes them to logs.

## Several accounts on the same network

The new bridges (WhatsApp, Signal, Instagram, Messenger, Google Messages, Slack, X, LinkedIn, Telegram)
support several sessions. In Unichat: **Settings → Accounts → Add account → choose the network** (the sidebar's + button takes you there); an assistant opens
with the QR code, the session options (cookie networks) or the field for codes (the technical chat with the bot stays hidden, with a link
to see it). Also by right-clicking the network in the sidebar. In **Settings → Connected networks** each account has its name
(alias) and its **Disconnect** button, which closes only that session (`logout <id>`).

Unichat reads the list of sessions with `list-logins` (when connecting or disconnecting, and every few hours when
opening the app). Limitation: the bridges don't always say which session each chat belongs to; when it isn't
known, the chats show up together under the network and the per-account filter isn't shown (so as not to hide chats).

## History (backfill)

`setup` turns on backfill in every bridge (the last 50 messages per chat when linking and up to 500 missed
messages after restarts). WhatsApp sends the history **only once, when linking**: if you linked an account
before turning on backfill, disconnect it in **Accounts** and link it again to receive your chats.
