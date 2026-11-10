# native — notes for Claude

Native builds and the no-Docker stack, and the macOS menu-bar helper.

- `native/` — no-Docker stack (tuwunel + mautrix bridge binaries + SQLite): `versions.env` pins every component,
  `setup-mac.sh` / `run-mac.sh` are the reference for the installer Unichat will run at startup (install whatever
  is missing, start and supervise). Read `docs/NATIVE-SERVER.md` before touching it.
  Bridges run with `direct_media` (`mxc://media-<bridge>.localhost/…`, nothing copied to the server); Unichat
  downloads those from the bridge's port (`LocalServer.directMediaBase`).
  `server/MediaPrune` deletes Discord attachments older than `discord.mediaDays` (default 30, 0 = never; Ajustes →
  Redes) on each server start; avatars/emoji kept.
- `native/UnichatBar.swift` (`native/build-bar.sh`) — macOS helper, built as `UnichatBar.app` (bundle id
  `app.unichat.helper`, shown as "Unichat"): the menu-bar item (narrower than Java's TrayIcon, real NSMenu; its icon is a template drawn there, `Bar.glyph`, so macOS colours it like its own; as in Telegram, a left click brings the window when it is put away or behind other apps (`Notifier.setWindowFocused`: no Unichat window has the focus) and opens the menu while it is open and focused, a right click or Control-click always opens it: minimize to tray or open, notifications off/on, quit) and
  **system notifications** (UNUserNotificationCenter needs a bundle; clicks come back as `open <chat id>`). Driven by
  `ui/NativeBar` over stdin (the unread count, `ChatFilter.inboxUnread` like the inbox badge, shows beside the icon, with a red dot on the icon's corner, a view of its own since the template icon can't keep a colour; the AWT tray draws it on the icon, `Notifier.withCount`); `Notifier` uses it when macOS allows notifications, else `DesktopToast` (own card, must
  never take keyboard focus), and the AWT TrayIcon if the helper is missing or dies. package.sh ships it as
  `Contents/app/native/UnichatBar.app`.

## Lessons
- Appending to a generated YAML without a trailing newline glued `rc_message:` to a comment line (Synapse test config). Use `printf '\n...'` when appending.
