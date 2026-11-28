# native — notes for Claude

Native builds and the no-Docker stack, and the macOS menu-bar helper (the Java side, Dock, tray and quitting: `src/main/java/app/unichat/ui/desktop/CLAUDE.md`; `emoji-list.py` makes the emoji list: `ui/emoji/CLAUDE.md`).

- `native/` — no-Docker stack (tuwunel + mautrix bridge binaries + SQLite): `versions.env` pins every component,
  `setup-mac.sh` / `run-mac.sh` are the reference for the installer Unichat will run at startup (install whatever
  is missing, start and supervise). Read `docs/NATIVE-SERVER.md` before touching it.
  **Bump `versions.env` → run `native/pin-hashes.sh`** and commit the `sha256sums.txt` it writes beside it: the app,
  `setup-mac.sh`, `fetch-natives.sh` and `package.ps1` install only files whose SHA-256 is pinned there (a release's
  own sha256sums.txt would vouch for a swapped binary), and `ManifestTest` fails the build while one is missing.
  `patches/<bridge>/*.patch`: Unichat's changes to a bridge in `NATIVES_ALL` (built by `natives.yml` for both
  platforms, from a tag or a full commit SHA). Made with `git diff` against that exact ref and checked with
  `git apply --check` on a clean checkout; one that no longer applies fails the build, so a bump means remaking it.
  WhatsApp's `sync-history-chats.patch` adds `!whatsapp sync-history-chats <n>` (1–500: rooms for the n newest
  history-sync chats not bridged yet, "Created <k> chats from history sync") for "Load older chats"
  (`matrix/OlderChatSteps`). Until natives-3 is published, pinned and installed, the bridge answers "Unknown command"
  and the person sees "not possible: needs the next Unichat update" (`whatsappWithoutThePatchIsNotPossible`).
  Chats the bridge skips (status broadcast, PSA, a chat-info error) are never marked synced and keep their place among
  the newest, so in rare cases a "Created 0" can end WhatsApp's older chats early (Unichat stores "nothing more").
  Bridges run with `direct_media` (`mxc://media-<bridge>.localhost/…`, nothing copied to the server); Unichat
  downloads those from the bridge's port (`LocalServer.directMediaBase`).
  `server/MediaPrune` deletes Discord attachments older than `discord.mediaDays` (default 30, 0 = never; Ajustes →
  Redes) on each server start; avatars/emoji kept.
- `native/UnichatBar.swift` (`native/build-bar.sh`) — macOS helper, built as `UnichatBar.app` (bundle id
  `app.unichat.helper`, shown as "Unichat"): the menu-bar item (narrower than Java's TrayIcon, real NSMenu; its icon is a template drawn there, `Bar.glyph`, so macOS colours it like its own; as in Telegram, a left click brings the window when it is put away or behind other apps (`Notifier.setWindowFocused`: no Unichat window has the focus) and opens the menu while it is open and focused, a right click or Control-click always opens it: minimize to tray or open, notifications off/on, quit) and
  **system notifications** (UNUserNotificationCenter needs a bundle; clicks come back as `open <chat id>`). It also says when the screen locks or the displays sleep (`screen 1`, and `screen 0` when both are over; `ui/desktop/MacScreen` hands it to `Seen`, and the helper's end counts as the screen back). The Java side is tested (`NativeBarScreenTest`); the helper's own two flags (locked, asleep) and its notifications only by hand, with a real lock and display sleep on a Mac after installing. Driven by
  `ui/desktop/NativeBar` over stdin (the unread count, `ChatFilter.inboxUnread` like the inbox badge, shows beside the icon, with a red dot on the icon's corner, a view of its own since the template icon can't keep a colour; the AWT tray draws it on the icon, `Notifier.withCount`); `Notifier` uses it when macOS allows notifications, else `DesktopToast` (own card, must
  never take keyboard focus), and the AWT TrayIcon if the helper is missing or dies. package.sh ships it as
  `Contents/app/native/UnichatBar.app`.
- `native/build-glass.sh <out>` + `patches/javafx/glass-layer-leak.patch` — JavaFX's macOS `libglass.dylib` rebuilt from openjdk/jfx27u at the pinned tag and commit (clone cached in `~/Library/Caches/unichat-build/javafx`; the script applies every patch in that folder and refuses any other commit). OpenJFX's Glass never freed a closed window's layer and view (GlassLayer.m and GlassView3D.m missing a release after addSublayer:/addSubview:, GlassMTLOffscreen.m skipping `[super dealloc]`): with the window closed the app kept 848 MB of IOSurface (51 regions); with the patched dylib 0.1 MB (2), no crash. `package.sh` ships it as `Contents/app/libglass.dylib`, next to `unichat.jar`, where JavaFX's `NativeLibLoader` loads it before extracting its own; if it can't be built the app just uses JavaFX's. Windows (D3D) isn't affected and uses JavaFX's own natives. **Bumping JavaFX:** `GlassPatchTest` fails while `javafx.version` differs from the script's `JFX_VERSION`; check the new tag's three files still need the patch (upstream may have fixed them: then drop the patch), remake it with `git diff` against the new tag, update `JFX_VERSION`, `JFX_TAG`, `JFX_COMMIT` and the two jar SHA-256s.

## Lessons
- Appending to a generated YAML without a trailing newline glued `rc_message:` to a comment line (Synapse test config). Use `printf '\n...'` when appending.
- A notification's first click often didn't open its chat (no "Notification click" line in the log: it never reached Unichat): each message posted a notification of its own, so a busy chat stacked them, and the first click on a stack only unfolds it. One request per chat now (`identifier: chat`), which replaces the last and alerts again: nothing to unfold. Only a real click can check it.
