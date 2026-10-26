#!/usr/bin/env bash
# Starts/stops the native server stack set up by setup-mac.sh.
# Usage: native/run-mac.sh start|stop|status
# start: tuwunel first, waits until it answers, then every active bridge; creates the user account on the
# first run (password saved to <data dir>/CREDENCIALES.txt). Logs: <data dir>/<component>/*.log.
set -euo pipefail
DIR="${UNICHAT_SERVER_DIR:-$HOME/Library/Application Support/Unichat/server}"
HS="http://127.0.0.1:8008"
ADMIN_USER="${ADMIN_USER:-yo}"
RUN="$DIR/run"; mkdir -p "$RUN"

alive() { [ -f "$RUN/$1.pid" ] && kill -0 "$(cat "$RUN/$1.pid")" 2>/dev/null; }

launch() { # name, workdir, command...
  local name="$1" wd="$2"; shift 2
  if alive "$name"; then echo "$name ya está corriendo"; return; fi
  (cd "$wd" && nohup "$@" >>"$wd/$name.out" 2>&1 & echo $! > "$RUN/$name.pid")
  echo "$name iniciado (pid $(cat "$RUN/$name.pid"))"
}

create_account() {
  [ -f "$DIR/CREDENCIALES.txt" ] && return
  # shellcheck disable=SC1091
  . "$DIR/secrets.env"
  local pass; pass="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24 || true)"
  # Two-step user-interactive auth: the first call returns a session, the second sends the token.
  python3 - "$HS" "$ADMIN_USER" "$pass" "$REGISTRATION_TOKEN" <<'PY'
import json, sys, urllib.request, urllib.error
hs, user, pw, token = sys.argv[1:]
def post(body):
    req = urllib.request.Request(hs + "/_matrix/client/v3/register", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    try:
        return json.load(urllib.request.urlopen(req))
    except urllib.error.HTTPError as e:
        return json.load(e)
first = post({"username": user, "password": pw, "inhibit_login": True})
session = first.get("session")
done = post({"username": user, "password": pw, "inhibit_login": True,
             "auth": {"type": "m.login.registration_token", "token": token, "session": session}})
if "user_id" not in done:
    sys.exit("No se pudo crear la cuenta: " + json.dumps(done))
PY
  (umask 077; cat > "$DIR/CREDENCIALES.txt" <<EOF
Unichat / Matrix (servidor nativo)
Servidor:   $HS
Usuario:    $ADMIN_USER   (@$ADMIN_USER:localhost)
Contraseña: $pass
EOF
)
  echo "Cuenta @$ADMIN_USER:localhost creada. Contraseña en $DIR/CREDENCIALES.txt"
}

case "${1:-}" in
  start)
    launch tuwunel "$DIR/tuwunel" "$DIR/bin/tuwunel" -c "$DIR/tuwunel/tuwunel.toml"
    for _ in $(seq 1 60); do curl -fsS "$HS/_matrix/client/versions" >/dev/null 2>&1 && break; sleep 1; done
    curl -fsS "$HS/_matrix/client/versions" >/dev/null || { echo "tuwunel no responde; ver $DIR/tuwunel/tuwunel.out" >&2; exit 1; }
    create_account
    for b in $(cat "$DIR/bridges.active"); do launch "$b" "$DIR/$b" "$DIR/bin/$b" -c config.yaml; done
    ;;
  stop)
    for f in "$RUN"/*.pid; do
      [ -f "$f" ] || continue
      n="$(basename "$f" .pid)"
      if alive "$n"; then kill "$(cat "$f")"; echo "$n detenido"; fi
      rm -f "$f"
    done
    ;;
  status)
    for n in tuwunel $(cat "$DIR/bridges.active" 2>/dev/null); do
      if alive "$n"; then echo "✓ $n"; else echo "✗ $n"; fi
    done
    ;;
  *) echo "Uso: $0 start|stop|status" >&2; exit 2 ;;
esac
