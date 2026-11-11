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
# Never replace a copy that runs the local server with one that can't (tuwunel missing: no TUWUNEL_BIN, no download).
if [ -e /Applications/Unichat.app/Contents/app/server/tuwunel ] && [ ! -e release/Unichat.app/Contents/app/server/tuwunel ]; then
  echo "Error: this package has no tuwunel and the installed app does; not replacing it (see package.sh)." >&2
  exit 1
fi
if pgrep -f "/Applications/Unichat.app/" >/dev/null; then
  osascript -e 'quit app "Unichat"' 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do pgrep -f "/Applications/Unichat.app/" >/dev/null || break; sleep 1; done
  pkill -f "/Applications/Unichat.app/" 2>/dev/null || true
fi
rm -rf /Applications/Unichat.app
cp -R release/Unichat.app /Applications/
# Every other copy macOS knew showed up beside the installed one in Spotlight and the Apps view (two Unichats and a
# "UnichatBar"): the build's own, jpackage's temporary ones, the menu-bar helper's build cache. All but the installed one
# are unregistered, and the build's copies removed (package.sh makes them again; the .dmg stays).
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister
"$LSREGISTER" -dump 2>/dev/null | sed -n 's/^path: *\(.*\.app\) (0x[0-9a-f]*)$/\1/p' | grep -i unichat | sort -u |
  while IFS= read -r app; do
    case "$app" in
      /Applications/Unichat.app|/Applications/Unichat.app/*) ;;
      *) "$LSREGISTER" -u "$app" 2>/dev/null || true ;;
    esac
  done
rm -rf release/Unichat.app target/pkg
echo "Instalado en /Applications/Unichat.app"
echo "Log: tail -f \"\$HOME/Library/Application Support/Unichat/logs/unichat.log\""
