#!/usr/bin/env bash
# Native (no-Docker) Unichat server on an Apple Silicon Mac: tuwunel homeserver + mautrix bridges + SQLite.
# Reference implementation of what Unichat's built-in installer does at startup (see docs/SERVIDOR-NATIVO.md).
#
# Usage: native/setup-mac.sh [bridge ...]     (default: every bridge in versions.env)
# Env:   UNICHAT_SERVER_DIR  data dir (default: ~/Library/Application Support/Unichat/server)
#        TUWUNEL_BIN         prebuilt tuwunel binary to install (otherwise it must already be in bin/)
#        TELEGRAM_API_ID / TELEGRAM_API_HASH (Telegram is skipped without them; read from server/.env too)
# Safe to re-run: binaries are only downloaded when missing or at another version; configs keep their
# secrets and only Unichat's keys are (re)applied. Start/stop with native/run-mac.sh.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DIR="${UNICHAT_SERVER_DIR:-$HOME/Library/Application Support/Unichat/server}"
DOMAIN=localhost
HS_PORT=8008
ADMIN_USER="${ADMIN_USER:-yo}"
OS=darwin ARCH=arm64

[ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] || { echo "Solo Mac con Apple Silicon." >&2; exit 1; }
command -v yq >/dev/null || { echo "Falta yq (brew install yq)." >&2; exit 1; }

rand() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-32}" || true; }
mkdir -p "$DIR/bin" "$DIR/appservices"
umask 077

# Secrets survive re-runs.
SECRETS="$DIR/secrets.env"
if [ ! -f "$SECRETS" ]; then
  { echo "REGISTRATION_TOKEN=$(rand 32)"; echo "DOUBLE_PUPPET_TOKEN=$(rand 48)"; } > "$SECRETS"
fi
# shellcheck disable=SC1090
. "$SECRETS"
if [ -z "${TELEGRAM_API_ID:-}" ] && [ -f "$HERE/../server/.env" ]; then
  TELEGRAM_API_ID="$(grep '^TELEGRAM_API_ID=' "$HERE/../server/.env" | cut -d= -f2- || true)"
  TELEGRAM_API_HASH="$(grep '^TELEGRAM_API_HASH=' "$HERE/../server/.env" | cut -d= -f2- || true)"
fi

bridge_port() {
  case "$1" in
    whatsapp) echo 29318 ;; telegram) echo 29317 ;; signal) echo 29328 ;; instagram) echo 29319 ;;
    facebook) echo 29320 ;; discord) echo 29334 ;; slack) echo 29335 ;; gmessages) echo 29336 ;;
    twitter) echo 29327 ;; linkedin) echo 29341 ;; *) echo "Bridge desconocido: $1" >&2; exit 1 ;;
  esac
}
manifest() { grep "^$1=" "$HERE/../src/main/resources/app/unichat/server/versions.env" | cut -d= -f2-; }

# --- Homeserver binary -----------------------------------------------------------------------
TUWUNEL_VERSION="$(manifest TUWUNEL_VERSION)"
if [ -n "${TUWUNEL_BIN:-}" ]; then
  cp "$TUWUNEL_BIN" "$DIR/bin/tuwunel" && chmod 755 "$DIR/bin/tuwunel"
  echo "$TUWUNEL_VERSION" > "$DIR/bin/tuwunel.version"
fi
[ -x "$DIR/bin/tuwunel" ] || { echo "Falta bin/tuwunel (pasa TUWUNEL_BIN=<ruta>)." >&2; exit 1; }

# --- Bridge binaries (official releases, checked against sha256sums.txt) ----------------------
fetch_bridge() {
  local b="$1" spec repo tag prefix asset out
  spec="$(manifest "$b")"; repo="${spec%@*}"; tag="${spec#*@}"; tag="${tag%%:*}"; prefix="${spec##*:}"
  asset="$prefix-$OS-$ARCH"; out="$DIR/bin/$b"
  if [ -x "$out" ] && [ "$(cat "$out.version" 2>/dev/null)" = "$tag" ]; then return; fi
  echo "Descargando $asset $tag..."
  local base="https://github.com/$repo/releases/download/$tag"
  curl -fsSL -o "$out.part" "$base/$asset"
  local want got
  want="$(curl -fsSL "$base/sha256sums.txt" | awk -v a="$asset" '$2 == a || $2 == "*"a {print $1}')"
  got="$(shasum -a 256 "$out.part" | awk '{print $1}')"
  [ -n "$want" ] && [ "$want" = "$got" ] || { rm -f "$out.part"; echo "Checksum inválido para $asset" >&2; exit 1; }
  chmod 755 "$out.part"; xattr -c "$out.part" 2>/dev/null || true
  mv "$out.part" "$out"; echo "$tag" > "$out.version"
}

BRIDGES="${*:-whatsapp telegram signal instagram facebook discord slack gmessages twitter linkedin}"
ACTIVE=""
for b in $BRIDGES; do
  if [ "$b" = telegram ] && { [ -z "${TELEGRAM_API_ID:-}" ] || [ -z "${TELEGRAM_API_HASH:-}" ]; }; then
    echo "Telegram queda desactivado: falta TELEGRAM_API_ID / TELEGRAM_API_HASH."; continue
  fi
  ACTIVE="$ACTIVE $b"
done
for b in $ACTIVE; do fetch_bridge "$b"; done

# --- Double puppeting ------------------------------------------------------------------------
# Written once (new random tokens on every run would make every bridge re-register).
if [ ! -f "$DIR/appservices/doublepuppet.yaml" ]; then
  cat > "$DIR/appservices/doublepuppet.yaml" <<EOF
id: doublepuppet
url:
as_token: "$DOUBLE_PUPPET_TOKEN"
hs_token: "$(rand 48)"
sender_localpart: doublepuppet
rate_limited: false
namespaces:
  users:
    - regex: '@.*:${DOMAIN}'
      exclusive: false
EOF
fi

# --- Bridges: config + registration ----------------------------------------------------------
export DOMAIN ADMIN_ID="@${ADMIN_USER}:${DOMAIN}" DP_TOKEN="$DOUBLE_PUPPET_TOKEN"
for b in $ACTIVE; do
  echo "== $b =="
  BD="$DIR/$b"; mkdir -p "$BD"
  [ -f "$BD/config.yaml" ] || (cd "$BD" && "$DIR/bin/$b" -e -c config.yaml >/dev/null)
  PORT="$(bridge_port "$b")"
  export ID="$b" BOT="${b}bot" PORT ADDR="http://127.0.0.1:$PORT" PUPPET="${b}_{{.}}"
  export DB_URI="file:$BD/$b.db?_txlock=immediate"
  # Same keys as server/setup.sh (Docker), with SQLite and localhost addresses.
  common='.homeserver.address = "http://127.0.0.1:'"$HS_PORT"'" | .homeserver.domain = strenv(DOMAIN)
    | .homeserver.software = "standard"
    | .appservice.address = strenv(ADDR) | .appservice.hostname = "127.0.0.1" | .appservice.port = env(PORT)
    | .appservice.id = strenv(ID) | .bridge.command_prefix = "!" + strenv(ID)'
  if [ "$(yq 'has("network")' "$BD/config.yaml")" = true ]; then
    expr="$common"' | .appservice.bot.username = strenv(BOT) | .appservice.username_template = strenv(PUPPET)
      | .database.type = "sqlite3-fk-wal" | .database.uri = strenv(DB_URI)
      | .bridge.permissions = {"*": "relay"} | .bridge.permissions[strenv(DOMAIN)] = "user"
      | .bridge.permissions[strenv(ADMIN_ID)] = "admin"
      | .encryption.allow = false | .encryption.default = false | .encryption.require = false
      | .double_puppet.secrets = {} | .double_puppet.secrets[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)
      | .backfill.enabled = true | .backfill.max_initial_messages = 50 | .backfill.max_catchup_messages = 500
      | .logging.writers = [{"type": "file", "format": "json", "filename": strenv(LOGFILE), "max_size": 20, "max_backups": 3}]'
    export LOGFILE="$BD/bridge.log"
    if [ "$b" = instagram ]; then
      expr="$expr"' | .network.mode = "instagram" | .appservice.bot.displayname = "Instagram bridge bot"'
    fi
    if [ "$b" = whatsapp ]; then
      expr="$expr"' | .network.history_sync.max_initial_conversations = 50
        | .network.archive_tag = "u.archived"
        | .bridge.only_bridge_tags = ["m.favourite", "m.lowpriority", "u.archived"] | .bridge.tag_only_on_create = false'
    fi
    if [ "$b" = telegram ]; then
      export TG_API_ID="$TELEGRAM_API_ID" TG_API_HASH="$TELEGRAM_API_HASH"
      expr="$expr"' | .network.api_id = env(TG_API_ID) | .network.api_hash = strenv(TG_API_HASH)
        | .network.animated_sticker.target = "gif" | .network.animated_sticker.convert_from_webm = true'
    fi
  else
    # Legacy layout (mautrix-discord)
    export LOGFILE="$BD/bridge.log"
    expr="$common"' | .appservice.bot.username = strenv(BOT) | .bridge.username_template = strenv(PUPPET)
      | .appservice.database.type = "sqlite3-fk-wal" | .appservice.database.uri = strenv(DB_URI)
      | .bridge.permissions = {"*": "relay"} | .bridge.permissions[strenv(DOMAIN)] = "user"
      | .bridge.permissions[strenv(ADMIN_ID)] = "admin"
      | .bridge.encryption.allow = false | .bridge.encryption.default = false
      | .bridge.login_shared_secret_map = {} | .bridge.login_shared_secret_map[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)
      | .bridge.backfill.forward_limits.initial.dm = 50 | .bridge.backfill.forward_limits.initial.channel = 50
      | .bridge.backfill.forward_limits.missed.dm = 500 | .bridge.backfill.forward_limits.missed.channel = 500
      | .logging.writers = [{"type": "file", "format": "json", "filename": strenv(LOGFILE), "max_size": 20, "max_backups": 3}]'
  fi
  yq -i "$expr" "$BD/config.yaml"
  [ -f "$BD/registration.yaml" ] || (cd "$BD" && "$DIR/bin/$b" -g -c config.yaml -r registration.yaml >/dev/null)
  cp "$BD/registration.yaml" "$DIR/appservices/$b.yaml"
done
# Registrations of bridges that are no longer active are removed so the server stops queueing events for them.
for f in "$DIR"/appservices/*.yaml; do
  n="$(basename "$f" .yaml)"; [ "$n" = doublepuppet ] && continue
  case " $ACTIVE " in *" $n "*) ;; *) rm -f "$f" ;; esac
done

# --- Homeserver config -----------------------------------------------------------------------
mkdir -p "$DIR/tuwunel/db"
cat > "$DIR/tuwunel/tuwunel.toml" <<EOF
# Generated by setup-mac.sh; edits are overwritten.
[global]
server_name = "$DOMAIN"
database_path = "$DIR/tuwunel/db"
address = ["127.0.0.1"]
port = $HS_PORT
# Personal server: no federation, no presence (main background load with many bridged contacts).
allow_federation = false
allow_local_presence = false
allow_incoming_presence = false
allow_outgoing_presence = false
# Fixed resolvers: tuwunel won't start if /etc/resolv.conf has a scoped IPv6 nameserver (fe80::1%en0).
dns_servers = ["1.1.1.1", "9.9.9.9"]
# Only someone with the token can create accounts; the first account becomes server admin.
allow_registration = true
registration_token = "$REGISTRATION_TOKEN"
new_user_displayname_suffix = ""
appservice_dir = "$DIR/appservices"
max_request_size = 104857600
log = "info"
EOF
echo "$ACTIVE" | xargs > "$DIR/bridges.active"
echo "Listo. Arranca con: native/run-mac.sh start"
