#!/usr/bin/env bash
# Generates Synapse + mautrix bridge configs and registrations for Unichat, then starts the stack.
# Usage: ./setup.sh [domain]       (default domain: localhost)
# Env:   ADMIN_USER (default admin), BRIDGES (space-separated subset),
#        TELEGRAM_API_ID / TELEGRAM_API_HASH (from https://my.telegram.org).
# Safe to re-run: existing files are kept and only the Unichat-relevant keys are (re)applied.
set -euo pipefail
cd "$(dirname "$0")"

YQ_IMAGE="mikefarah/yq:4"
SYNAPSE_IMAGE="matrixdotorg/synapse:latest"
BRIDGES="${BRIDGES:-whatsapp telegram signal instagram facebook discord slack gmessages twitter}"
ADMIN_USER="${ADMIN_USER:-admin}"

command -v docker >/dev/null || { echo "Docker no está instalado." >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Falta 'docker compose' (Docker Compose v2)." >&2; exit 1; }

rand() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-32}" || true; }

mkdir -p data
if [ ! -f .env ]; then
  DOMAIN="${1:-${DOMAIN:-}}"
  if [ -z "$DOMAIN" ]; then
    read -r -p "Nombre del servidor Matrix (dominio) [localhost]: " DOMAIN
    DOMAIN="${DOMAIN:-localhost}"
  fi
  {
    echo "DOMAIN=$DOMAIN"
    echo "POSTGRES_PASSWORD=$(rand 32)"
    echo "DOUBLE_PUPPET_TOKEN=$(rand 48)"
  } > .env
  echo "Creado server/.env (contiene contraseñas; no lo compartas)."
fi
# shellcheck disable=SC1091
set -a; . ./.env; set +a
export ADMIN_ID="@${ADMIN_USER}:${DOMAIN}" PG_PASS="$POSTGRES_PASSWORD" DP_TOKEN="$DOUBLE_PUPPET_TOKEN"

# Runs yq (in Docker, as root so bridge-owned files are writable) on a file under ./data.
yq_edit() {
  local file="$1" expr="$2"
  docker run --rm --user root -v "$PWD/data:/data" \
    -e DOMAIN -e ADMIN_ID -e PG_PASS -e DP_TOKEN -e ADDR -e PORT -e ID -e BOT -e PUPPET -e DB_URI \
    -e META_MODE -e TG_API_ID -e TG_API_HASH -e APPSERVICES \
    "$YQ_IMAGE" -i "$expr" "/data/$file"
}
yq_get() { docker run --rm --user root -v "$PWD/data:/data" "$YQ_IMAGE" "$2" "/data/$1"; }

bridge_port() {
  case "$1" in
    whatsapp) echo 29318 ;; telegram) echo 29317 ;; signal) echo 29328 ;; instagram) echo 29319 ;;
    facebook) echo 29320 ;; discord) echo 29334 ;; slack) echo 29335 ;; gmessages) echo 29336 ;;
    twitter) echo 29327 ;; linkedin) echo 29341 ;; *) echo "Bridge desconocido: $1" >&2; exit 1 ;;
  esac
}

# --- Synapse -------------------------------------------------------------------------------
if [ ! -f data/synapse/homeserver.yaml ]; then
  echo "Generando configuración de Synapse para $DOMAIN..."
  mkdir -p data/synapse
  docker run --rm -v "$PWD/data/synapse:/data" -e SYNAPSE_SERVER_NAME="$DOMAIN" -e SYNAPSE_REPORT_STATS=no \
    "$SYNAPSE_IMAGE" generate
fi

# --- Double puppeting (messages you send from your phone appear as sent by you) ---------------
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

# --- Bridges -------------------------------------------------------------------------------
APPSERVICES="/data/appservices/doublepuppet.yaml"
for b in $BRIDGES; do
  echo "== $b =="
  mkdir -p "data/$b"
  if [ ! -f "data/$b/config.yaml" ]; then
    docker compose run --rm --no-deps "$b" >/dev/null 2>&1 || true
  fi
  [ -f "data/$b/config.yaml" ] || { echo "No se pudo generar data/$b/config.yaml" >&2; exit 1; }

  PORT="$(bridge_port "$b")"
  export ID="$b" BOT="${b}bot" PORT
  export ADDR="http://$b:$PORT"
  export DB_URI="postgres://unichat:${PG_PASS}@postgres/$b?sslmode=disable"
  common='.homeserver.address = "http://synapse:8008" | .homeserver.domain = strenv(DOMAIN)
    | .appservice.address = strenv(ADDR) | .appservice.hostname = "0.0.0.0" | .appservice.port = env(PORT)
    | .appservice.id = strenv(ID)'

  if [ "$(yq_get "$b/config.yaml" 'has("network")')" = "true" ]; then
    # bridgev2 ("megabridge") config layout
    export PUPPET="${b}_{{.}}"
    expr="$common"' | .appservice.bot.username = strenv(BOT) | .appservice.username_template = strenv(PUPPET)
      | .database.type = "postgres" | .database.uri = strenv(DB_URI)
      | .bridge.permissions = {"*": "relay"} | .bridge.permissions[strenv(DOMAIN)] = "user"
      | .bridge.permissions[strenv(ADMIN_ID)] = "admin"
      | .encryption.allow = false | .encryption.default = false | .encryption.require = false
      | .double_puppet.secrets = {} | .double_puppet.secrets[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)'
    case "$b" in
      instagram) export META_MODE=instagram; expr="$expr"' | .network.mode = strenv(META_MODE)' ;;
      facebook)  export META_MODE=facebook;  expr="$expr"' | .network.mode = strenv(META_MODE)' ;;
      telegram)
        if [ -n "${TELEGRAM_API_ID:-}" ]; then
          export TG_API_ID="$TELEGRAM_API_ID" TG_API_HASH="${TELEGRAM_API_HASH:-}"
          expr="$expr"' | .network.api_id = env(TG_API_ID) | .network.api_hash = strenv(TG_API_HASH)'
        fi ;;
    esac
  elif [ "$(yq_get "$b/config.yaml" '.appservice | has("bot_username")')" = "true" ]; then
    # Legacy Python bridge (mautrix-telegram)
    export PUPPET="${b}_{userid}" DB_URI="postgres://unichat:${PG_PASS}@postgres/$b"
    expr="$common"' | .appservice.bot_username = strenv(BOT) | .bridge.username_template = strenv(PUPPET)
      | .appservice.database = strenv(DB_URI)
      | .bridge.permissions = {"*": "relaybot"} | .bridge.permissions[strenv(DOMAIN)] = "full"
      | .bridge.permissions[strenv(ADMIN_ID)] = "admin"
      | .bridge.encryption.allow = false | .bridge.encryption.default = false
      | .bridge.login_shared_secret_map = {} | .bridge.login_shared_secret_map[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)'
    if [ -n "${TELEGRAM_API_ID:-}" ]; then
      export TG_API_ID="$TELEGRAM_API_ID" TG_API_HASH="${TELEGRAM_API_HASH:-}"
      expr="$expr"' | .telegram.api_id = env(TG_API_ID) | .telegram.api_hash = strenv(TG_API_HASH)'
    fi
  else
    # Legacy Go bridge layout (mautrix-discord)
    export PUPPET="${b}_{{.}}"
    expr="$common"' | .appservice.bot.username = strenv(BOT) | .bridge.username_template = strenv(PUPPET)
      | .appservice.database.type = "postgres" | .appservice.database.uri = strenv(DB_URI)
      | .bridge.permissions = {"*": "relay"} | .bridge.permissions[strenv(DOMAIN)] = "user"
      | .bridge.permissions[strenv(ADMIN_ID)] = "admin"
      | .bridge.encryption.allow = false | .bridge.encryption.default = false
      | .bridge.login_shared_secret_map = {} | .bridge.login_shared_secret_map[strenv(DOMAIN)] = "as_token:" + strenv(DP_TOKEN)'
  fi
  yq_edit "$b/config.yaml" "$expr"

  # The registration must be regenerated after namespace changes; delete it to force that.
  if [ ! -f "data/$b/registration.yaml" ]; then
    docker compose run --rm --no-deps "$b" >/dev/null 2>&1 || true
  fi
  [ -f "data/$b/registration.yaml" ] || { echo "No se pudo generar data/$b/registration.yaml" >&2; exit 1; }
  APPSERVICES="$APPSERVICES,/data/appservices/$b.yaml"
done

# Copy registrations where Synapse can read them (bridge files may be 0600 and owned by the bridge uid).
docker run --rm --user root -v "$PWD/data:/data" --entrypoint sh "$YQ_IMAGE" -c '
  mkdir -p /data/synapse/appservices
  cp /data/doublepuppet.yaml /data/synapse/appservices/doublepuppet.yaml
  for b in '"$BRIDGES"'; do cp "/data/$b/registration.yaml" "/data/synapse/appservices/$b.yaml"; done
  chmod 644 /data/synapse/appservices/*.yaml'

export APPSERVICES
yq_edit synapse/homeserver.yaml '.database = {"name": "psycopg2", "args": {"user": "unichat",
    "password": strenv(PG_PASS), "database": "synapse", "host": "postgres", "cp_min": 5, "cp_max": 10}}
  | .enable_registration = false
  | .app_service_config_files = (strenv(APPSERVICES) | split(","))'

# --- Start -----------------------------------------------------------------------------------
PROFILE_ARGS=()
case " $BRIDGES " in *" linkedin "*) PROFILE_ARGS=(--profile linkedin) ;; esac
docker compose ${PROFILE_ARGS[@]+"${PROFILE_ARGS[@]}"} up -d postgres synapse $BRIDGES

echo "Esperando a Synapse..."
for _ in $(seq 1 60); do
  if curl -fsS http://localhost:8008/health >/dev/null 2>&1; then break; fi
  sleep 2
done

if [ -t 0 ]; then
  read -r -p "¿Crear ahora el usuario $ADMIN_ID? [S/n] " ans
  if [ "${ans:-S}" != "n" ] && [ "${ans:-S}" != "N" ]; then
    docker compose exec synapse register_new_matrix_user -c /data/homeserver.yaml -u "$ADMIN_USER" -a \
      http://localhost:8008 || echo "(Si el usuario ya existía, ignora este error.)"
  fi
fi

cat <<EOF

Listo. En Unichat inicia sesión con:
  Servidor: http://localhost:8008   (o el dominio público si lo publicaste)
  Usuario:  $ADMIN_USER
Luego ve a Ajustes > Cuentas para conectar cada red. Más detalles en docs/BRIDGES.md.
EOF
