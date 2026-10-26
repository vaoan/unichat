#!/bin/sh
# Packages Unichat and installs it in /Applications, replacing the previous copy (quits it first).
#   TUWUNEL_BIN=<path to tuwunel> ./install-mac.sh
# Logs of the installed app:
#   ~/Library/Application Support/Unichat/logs/unichat.log            (the app)
#   ~/Library/Application Support/Unichat/server/tuwunel/tuwunel.log  (homeserver)
#   ~/Library/Application Support/Unichat/server/<network>/bridge.log (each bridge)
# None of them hold personal data (see Redactor); old ones are deleted (Ajustes > Privacidad > Registros).
set -e
cd "$(dirname "$0")"
./package.sh
if pgrep -f "/Applications/Unichat.app/" >/dev/null; then
  osascript -e 'quit app "Unichat"' 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do pgrep -f "/Applications/Unichat.app/" >/dev/null || break; sleep 1; done
  pkill -f "/Applications/Unichat.app/" 2>/dev/null || true
fi
rm -rf /Applications/Unichat.app
cp -R release/Unichat.app /Applications/
echo "Instalado en /Applications/Unichat.app"
echo "Log: tail -f \"\$HOME/Library/Application Support/Unichat/logs/unichat.log\""
