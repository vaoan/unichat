---
paths:
  - "src/main/java/app/unichat/UnichatApp.java"
  - "src/main/java/app/unichat/ui/DockIcon.java"
  - "src/main/java/app/unichat/ui/AppWindows.java"
  - "src/main/java/app/unichat/ui/MenuDismiss.java"
  - "src/main/java/app/unichat/ui/MacQuit.java"
  - "src/main/java/app/unichat/ui/TrayReopen.java"
  - "src/main/java/app/unichat/ui/Notifier.java"
  - "src/main/java/app/unichat/ui/NotificationPolicy.java"
  - "src/main/java/app/unichat/ui/NativeBar.java"
  - "src/main/java/app/unichat/ui/DesktopToast.java"
  - "native/UnichatBar.swift"
---
# Desktop integration: Dock, tray, menu bar, notifications, quitting

- macOS Dock: the package declares `LSUIElement` (package.sh), so by default there's no Dock icon, ⌘Tab or app menu; Ajustes → Notificaciones `app.showInDock` adds one while the window is open (`DockIcon`), also forced if the menu bar icon is missing. ⌘Tab only lists apps with a Dock icon (macOS has no way around it), so any window besides the main one (photo, video, settings) also brings the Dock icon while open (`DockIcon.regular`), and ⌘` / ⌘⇧` (also ⌘< on a Spanish keyboard) moves between Unichat's windows (`AppWindows`; macOS gives that only to apps with a Window menu).
- A menu that opens activates Unichat and focuses its window (`MenuDismiss`): a right-click on a window of a background app shows the menu without activating it, and then neither a click outside nor Esc reached JavaFX (`MenuDismissUiTest`, the real mouse and keyboard); so `native/UnichatBar.swift` registers a global ⌃⌥U (`app.globalShortcut`, Carbon: no Accessibility permission) that brings the window back. `UnichatApp` handles ⌘Q itself, and activation with no window brings it back (`TrayReopen`).
- The menu-bar helper and system notifications: `native/CLAUDE.md`.

## Lessons
- AWT on the FX thread, or `System.exit` on the FX thread, hung the app on macOS. All `TrayIcon`/`SystemTray` calls go through `EventQueue.invokeLater`; exit happens on a separate thread. Tray/balloon callbacks run on the AWT thread: hop to FX with `Platform.runLater`.
- macOS drops AWT tray notifications from a plain `java` process (usernoted: "dropping msg … app not found"); osascript ones show but clicking opens Script Editor. macOS uses the helper (or Unichat's own card, `DesktopToast`); Windows keeps the native balloon.
- ⌘Q / "Salir" never quit: JavaFX turns the quit action into "close every window" and our close handler hides to the tray; `Desktop.setQuitHandler` is never called with JavaFX owning the app. `MacQuit` wraps JavaFX's glass `Application.EventHandler.handleQuitAction`. Test by AXPress on the app menu's Quit item (an `osascript 'quit app'` to a dev `java` process doesn't reach it).
- A JavaFX window shown while someone types takes the keyboard: the notification card (a Button) got the next space/Enter and opened its chat. The fallback card hands focus back and ignores keys; snapshot runs are headless for the same reason (they once stole the user's typing).
