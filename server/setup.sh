#!/usr/bin/env bash
# Generates Synapse + mautrix bridge configs and registrations for Unichat, then starts the stack.
# Usage: ./setup.sh [domain]       (default domain: localhost)
# Env:   ADMIN_USER (default admin), BRIDGES (space-separated subset),
#        TELEGRAM_API_ID / TELEGRAM_API_HASH (from https://my.telegram.org).
#        ADMIN_USER and the Telegram keys are saved to .env, so later runs reuse them.
# Safe to re-run: existing files are kept and only the Unichat-relevant keys are (re)applied.
set -euo pipefail
cd "$(dirname "$0")"

YQ_IMAGE="mikefarah/yq:4"
SYNAPSE_IMAGE="matrixdotorg/synapse:latest"
ALL_BRIDGES="whatsapp telegram signal instagram facebook discord slack gmessages twitter linkedin"
BRIDGES="${BRIDGES:-$ALL_BRIDGES}"

command -v docker >/dev/null || { echo "Docker no está instalado." >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Falta 'docker compose' (Docker Compose v2)." >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker no está en marcha (abre Docker Desktop)." >&2; exit 1; }

rand() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-32}" || true; }

# Sets (or with an empty value removes) KEY in .env.
env_set() {
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  grep -v "^${key}=" .env > "$tmp" || true
  [ -n "$value" ] && echo "${key}=${value}" >> "$tmp"
  cat "$tmp" > .env && rm -f "$tmp"
}

# Values given on the command line win over .env and are persisted.
CLI_ADMIN_USER="${ADMIN_USER:-}" CLI_TG_ID="${TELEGRAM_API_ID:-}" CLI_TG_HASH="${TELEGRAM_API_HASH:-}"

mkdir -p data
if [ ! -f .env ]; then
  DOMAIN="${1:-${DOMAIN:-}}"
  if [ -z "$DOMAIN" ]; then
    if [ -t 0 ]; then read -r -p "Nombre del servidor Matrix (dominio) [localhost]: " DOMAIN; fi
    DOMAIN="${DOMAIN:-localhost}"
  fi
  {
    echo "DOMAIN=$DOMAIN"
    echo "POSTGRES_PASSWORD=$(rand 32)"
    echo "DOUBLE_PUPPET_TOKEN=$(rand 48)"
  } > .env
  echo "Creado server/.env (contiene contraseñas; no lo compartas)."
fi
[ -n "$CLI_ADMIN_USER" ] && env_set ADMIN_USER "$CLI_ADMIN_USER"
[ -n "$CLI_TG_ID" ] && env_set TELEGRAM_API_ID "$CLI_TG_ID"
[ -n "$CLI_TG_HASH" ] && env_set TELEGRAM_API_HASH "$CLI_TG_HASH"
# shellcheck disable=SC1091
set -a; . ./.env; set +a
ADMIN_USER="${ADMIN_USER:-admin}"
export ADMIN_ID="@${ADMIN_USER}:${DOMAIN}" PG_PASS="$POSTGRES_PASSWORD" DP_TOKEN="$DOUBLE_PUPPET_TOKEN"

# Telegram refuses to start without the user's own API keys: leave it out until they are set.
TELEGRAM_ON=no
if [ -n "${TELEGRAM_API_ID:-}" ] && [ -n "${TELEGRAM_API_HASH:-}" ]; then TELEGRAM_ON=yes; fi
ACTIVE=""
for b in $BRIDGES; do
  if [ "$b" = telegram ] && [ "$TELEGRAM_ON" = no ]; then
    echo "Telegram queda desactivado: falta TELEGRAM_API_ID / TELEGRAM_API_HASH (ver docs/BRIDGES.md)."
    continue
  fi
  ACTIVE="$ACTIVE $b"
done
# The telegram service has the "telegram" compose profile; COMPOSE_PROFILES in .env enables it.
if [ "$TELEGRAM_ON" = yes ]; then env_set COMPOSE_PROFILES telegram; else env_set COMPOSE_PROFILES ""; fi
unset COMPOSE_PROFILES
if [ "$TELEGRAM_ON" = yes ]; then export COMPOSE_PROFILES=telegram; fi

# Runs yq (in Docker, as root so bridge-owned files are writable) on a file under ./data.
yq_edit() {
  local file="$1" expr="$2"
  docker run --rm --user root -v "$PWD/data:/data" \
    -e DOMAIN -e ADMIN_ID -e PG_PASS -e DP_TOKEN -e ADDR -e PORT -e ID -e BOT -e PUPPET -e DB_URI \
    -e TG_API_ID -e TG_API_HASH -e APPSERVICES \
    "$YQ_IMAGE" -i "$expr" "/data/$file"
}
yq_get() { docker run --rm --user root -v "$PWD/data:/data" "$YQ_IMAGE" "$2" "/data/$1"; }
# Checksum of files under ./data (read as root: bridge files may be 0600 and owned by another uid).
# Paths are container paths and may contain globs (expanded inside the container).
data_sum() {
  docker run --rm --user root -v "$PWD/data:/data" --entrypoint sh "$YQ_IMAGE" -c "cat $* 2>/dev/null | md5sum"
}
# Checksum of a YAML file's content, ignoring comments and formatting (bridges rewrite their config on start).
conf_sum() {
  docker run --rm --user root -v "$PWD/data:/data" --entrypoint sh "$YQ_IMAGE" -c "yq -o=json . /data/$1 | md5sum"
}

bridge_port() {
  case "$1" in
    whatsapp) echo 29318 ;; telegram) echo 29317 ;; signal) echo 29328 ;; instagram) echo 29319 ;;
    facebook) echo 29320 ;; discord) echo 29334 ;; slack) echo 29335 ;; gmessages) echo 29336 ;;
    twitter) echo 29327 ;; linkedin) echo 29341 ;; *) echo "Bridge desconocido: $1" >&2; exit 1 ;;
  esac
}
for b in $ACTIVE; do bridge_port "$b" >/dev/null; done

# --- Synapse -------------------------------------------------------------------------------
if [ ! -f data/synapse/homeserver.yaml ]; then
  echo "Generando configuración de Synapse para $DOMAIN..."
  mkdir -p data/synapse
  docker run --rm -v "$PWD/data/synapse:/data" -e SYNAPSE_SERVER_NAME="$DOMAIN" -e SYNAPSE_REPORT_STATS=no \
    "$SYNAPSE_IMAGE" generate
fi
SYNAPSE_SUM_BEFORE="$(data_sum "/data/synapse/homeserver.yaml /data/synapse/appservices/*.yaml")"

# --- Double puppeting (messages you send from your phone appear as sent by you) ---------------
# Written once: rewriting it with new random tokens would force a Synapse restart on every run.
if [ ! -f data/doublepuppet.yaml ]; then
  DOMAIN_RE="${DOMAIN//./\\.}"
  cat > data/doublepuppet.yaml <<EOF
id: doublepuppet
url:
as_token: "$DP_TOKEN"
hs_token: "$(rand 48)"
sender_localpart: "doublepuppet_$(rand 8)"
rate_limited: false
namespaces:
  users:
    - regex: '@.*:${DOMAIN_RE}'
      exclusive: false
EOF
fi

# --- Bridges -------------------------------------------------------------------------------
APPSERVICES="/data/appservices/doublepuppet.yaml"
CHANGED=""
for b in $ACTIVE; do
  echo "== $b =="
  mkdir -p "data/$b"
  if [ ! -f "data/$b/config.yaml" ]; then
    docker compose run --rm --no-deps "$b" >/dev/null 2>&1 || true
  fi
  [ -f "data/$b/config.yaml" ] || { echo "No se pudo generar data/$b/config.yaml" >&2; exit 1; }
  before="$(conf_sum "$b/config.yaml")"

  PORT="$(bridge_port "$b")"
  export ID="$b" BOT="${b}bot" PORT
  export ADDR="http://$b:$PORT"
  export DB_URI="postgres://unichat:${PG_PASS}@postgres/$b?sslmode=disable"
  # Command prefix "!<id>" (e.g. !whatsapp): Unichat prefixes every bot command with it, so commands also
  # work in a bot DM that is not the bridge's management room. Keep in sync with Network.protocolId().
  common='.homeserver.address = "http://synapse:8008" | .homeserver.domain = strenv(DOMAIN)
    | .appservice.address = strenv(ADDR) | .appservice.hostname = "0.0.0.0" | .appservice.port = env(PORT)
    | .appservice.id = strenv(ID) | .bridge.command_prefix = "!" + strenv(ID)'

  if [ "$(yq_get "$b/config.yaml" 'has("network")')" = "true" ]; then
    # bridgev2 ("megabridge") config layout: every bridge except discord
    export PUPPET="${b}_{{.}}"
    expr="$common"' | .appservice.bot.username = strenv(BOT) | .appservice.username_template = strenv(PUPPET)
      | .database.type = "postgres" | .database.uri = strenv(DB_URI)
      | .bridge.permissions = {"*": "relay"} | .bridge.permissions[strenv(DOMAIN)] = "user"
      | .bridge.permissions[strenv(ADMIN_ID)] = "admin"
      | .encryption.allow = false | .encryption.default = false | .encryption.require = false
      | .double_puppet.secrets = {} | .double_puppet.secrets[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)
      | .backfill.enabled = true | .backfill.max_initial_messages = 50 | .backfill.max_catchup_messages = 500'
    # Instagram runs the pinned mautrix-meta v26.07 (last release with Instagram) in instagram mode.
    # Current mautrix-meta (facebook) has no mode key: its login flows (facebook, messenger, ...) pick it.
    if [ "$b" = instagram ]; then
      expr="$expr"' | .network.mode = "instagram" | .appservice.bot.displayname = "Instagram bridge bot"'
    fi
    # Create rooms only for the 50 most recent WhatsApp chats; older ones appear when a message arrives
    # (creating every conversation from history overloads Synapse for many minutes).
    # Chats archived on the phone get Unichat's archive tag (u.archived, see SyncProcessor.TAG_ARCHIVED), also
    # when archived later: tags keep syncing instead of only at room creation. One-way: WhatsApp -> Unichat.
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
    # Legacy Go bridge layout (mautrix-discord)
    export PUPPET="${b}_{{.}}"
    expr="$common"' | .appservice.bot.username = strenv(BOT) | .bridge.username_template = strenv(PUPPET)
      | .appservice.database.type = "postgres" | .appservice.database.uri = strenv(DB_URI)
      | .bridge.permissions = {"*": "relay"} | .bridge.permissions[strenv(DOMAIN)] = "user"
      | .bridge.permissions[strenv(ADMIN_ID)] = "admin"
      | .bridge.encryption.allow = false | .bridge.encryption.default = false
      | .bridge.login_shared_secret_map = {} | .bridge.login_shared_secret_map[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)
      | .bridge.backfill.forward_limits.initial.dm = 50 | .bridge.backfill.forward_limits.initial.channel = 50
      | .bridge.backfill.forward_limits.missed.dm = 500 | .bridge.backfill.forward_limits.missed.channel = 500'
  fi
  yq_edit "$b/config.yaml" "$expr"
  [ "$before" = "$(conf_sum "$b/config.yaml")" ] || CHANGED="$CHANGED $b"

  # The registration must be regenerated after namespace changes; delete it to force that.
  if [ ! -f "data/$b/registration.yaml" ]; then
    docker compose run --rm --no-deps "$b" >/dev/null 2>&1 || true
  fi
  [ -f "data/$b/registration.yaml" ] || { echo "No se pudo generar data/$b/registration.yaml" >&2; exit 1; }
  APPSERVICES="$APPSERVICES,/data/appservices/$b.yaml"
done

# Copy registrations where Synapse can read them (bridge files may be 0600 and owned by the bridge uid).
# Registrations of disabled bridges are removed so Synapse does not queue events for them.
docker run --rm --user root -v "$PWD/data:/data" --entrypoint sh "$YQ_IMAGE" -c '
  rm -rf /data/synapse/appservices && mkdir -p /data/synapse/appservices
  cp /data/doublepuppet.yaml /data/synapse/appservices/doublepuppet.yaml
  for b in '"$ACTIVE"'; do cp "/data/$b/registration.yaml" "/data/synapse/appservices/$b.yaml"; done
  chmod 644 /data/synapse/appservices/*.yaml'

# Personal server: the default message/login rate limits are too tight when connecting several bridges
# in a row (createRoom counts as a message). Failed-login limits stay at Synapse's defaults.
export APPSERVICES
# Presence (online/offline of every bridged contact) is the main background load on a small Synapse.
yq_edit synapse/homeserver.yaml '.presence.enabled = false'
yq_edit synapse/homeserver.yaml '.database = {"name": "psycopg2", "args": {"user": "unichat",
    "password": strenv(PG_PASS), "database": "synapse", "host": "postgres", "cp_min": 5, "cp_max": 10}}
  | .enable_registration = false
  | .rc_message = {"per_second": 10, "burst_count": 100}
  | .rc_login.address = {"per_second": 1, "burst_count": 20} | .rc_login.account = {"per_second": 1, "burst_count": 20}
  | .app_service_config_files = (strenv(APPSERVICES) | split(","))'
[ "$SYNAPSE_SUM_BEFORE" = "$(data_sum "/data/synapse/homeserver.yaml /data/synapse/appservices/*.yaml")" ] \
  || CHANGED="synapse $CHANGED"

# --- Start -----------------------------------------------------------------------------------
if [ "$TELEGRAM_ON" = no ]; then
  docker compose --profile telegram rm -sf telegram >/dev/null 2>&1 || true
fi
# Containers that were already running keep the old config until restarted (new ones read it fresh).
RUNNING=" $(docker compose ps --services --status running 2>/dev/null | tr '\n' ' ') "
RESTART=""
for s in $CHANGED; do case "$RUNNING" in *" $s "*) RESTART="$RESTART $s" ;; esac; done
echo "Arrancando contenedores (la primera vez Synapse tarda ~1 min)..."
# shellcheck disable=SC2086
docker compose up -d postgres synapse $ACTIVE
if [ -n "$RESTART" ]; then
  echo "Reiniciando por cambios de configuración:$RESTART"
  # shellcheck disable=SC2086
  docker compose restart $RESTART
fi

echo "Esperando a Synapse..."
for _ in $(seq 1 60); do
  if curl -fsS http://localhost:8008/health >/dev/null 2>&1; then break; fi
  sleep 2
done

# --- User account ----------------------------------------------------------------------------
# Created once with a random password, saved to CREDENCIALES.txt (git-ignored).
if [ ! -f CREDENCIALES.txt ]; then
  PASSWORD="$(rand 24)"
  if docker compose exec -T synapse register_new_matrix_user -c /data/homeserver.yaml \
       -u "$ADMIN_USER" -p "$PASSWORD" -a http://localhost:8008 >/dev/null; then
    umask 077
    cat > CREDENCIALES.txt <<EOF
Unichat / Matrix
Servidor:   http://localhost:8008
Usuario:    $ADMIN_USER   ($ADMIN_ID)
Contraseña: $PASSWORD
EOF
    echo "Usuario $ADMIN_ID creado. Contraseña guardada en server/CREDENCIALES.txt"
  else
    echo "No se pudo crear $ADMIN_ID (¿ya existe?). Crea otro con:" >&2
    echo "  docker compose exec synapse register_new_matrix_user -c /data/homeserver.yaml -a http://localhost:8008" >&2
  fi
fi

cat <<EOF

Listo. En Unichat inicia sesión con:
  Servidor: http://localhost:8008   (o el dominio público si lo publicaste)
  Usuario:  $ADMIN_USER   (contraseña en server/CREDENCIALES.txt)
Luego agrega tus cuentas en Ajustes → Cuentas. Más detalles en docs/BRIDGES.md.
EOF
