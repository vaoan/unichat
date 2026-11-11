---
name: snapshot
description: Look at a Unichat screen after a visual change, headless (writes a PNG, nothing on the person's screen). Use after every change to the UI or CSS.
---
# Looking at a screen (headless)

`-Dunichat.snapshot=out.png` (`SnapshotRun`) writes the window to a PNG and exits. A snapshot run is headless by itself (`UnichatApp.main`: JavaFX's Headless platform, AWT headless): no window, menu-bar icon nor notification appears.

1. A demo data folder: `mkdir -p "$TMPDIR/unichat-demo" && printf 'session.backend=demo\n' > "$TMPDIR/unichat-demo/settings.properties"` (add `ui.theme=LIGHT`, `ui.fontScale=1.6` to check the biggest text).
2. Run: `JAVA_OPTS="-Dunichat.snapshot=$TMPDIR/out.png -Dunichat.dataDir=$TMPDIR/unichat-demo <options>" ./run-dev.sh`, then Read the PNG.

Options:
- `-Dunichat.openChat=<id>` (demo ids like `!familia:demo`), `-Dunichat.openSpace=<id>` (a server's channels, e.g. `!servidor:demo`), `-Dunichat.info=true`, `-Dunichat.panel=EMOJIS|STICKERS|GIFS`, `-Dunichat.stickerSet=<link>` (that sticker set open in Stickers; the demo's is `https://t.me/addstickers/unichat_demo`).
- `-Dunichat.overlay=command|newchat|add|folderNew|folderDelete|settings:<Página>` (a page id works too: `settings:ai`; `-Dunichat.settingsFull=true` captures the whole page, top to bottom), `-Dunichat.network=<protocolo>` (settings snapshots the settings window).
- `-Dunichat.openFolders=true` (folder list), `-Dunichat.openFolder=<name>` (inside one).
- `-Dunichat.snapshotScale=2` to check icon alignment; `-Dunichat.demoAccounts=WHATSAPP:NEEDS_LOGIN,...` for account alerts; `-Dunichat.upload=<file>,<file>` (+ `-Dunichat.uploadAs=PHOTO|DOCUMENT`) sends a batch to the open chat.

Check at 100 % and 160 % text, light and dark, and with high contrast when the change touches sizes or colours (`.claude/rules/css-icons.md`).
