# Window closed: save battery, notifications unchanged — design

## Intent
The person keeps Unichat running in the tray all day on a MacBook. With the window closed, measured on 2026-11-22:
- **The app's JVM:** ~11 % of a core and ~8 700 wakeups per sample.
- **The JavaFX thread:** ~3 %, with no window at all.
- **The `log-discord` thread:** ~5 %, copying the Discord bridge's log.

The battery drains faster than it should. With the window closed, Unichat should do only what receiving messages and notifying needs, and pick everything else up again when the window shows.

## Goals
- One signal, "the window is seen". It is false when the main window is closed (to the tray) or minimised.
- While it is false, these pause and resume by themselves when it turns true:
  - **Cloud media:** the upload and the daily verify (`MediaUpload.run`/`verify` inside `Cloud.round`).
  - **Writing-style learning:** the `StyleLearning` queue keeps what was asked and runs it on show.
  - **Update checks:** `UpdateChecker`, hourly. A check that falls due while hidden runs on show.
  - **`FxWatch`:** its 5 s `runLater` to the FX thread is skipped. Nobody can see a frozen window, and it starts again on show.
  - **Every JavaFX animation or timeline:** `WorkingLine`, `AiReplyButton`, `UploadBar`, `InlineVideo`'s visibility timeline, and any other `INDEFINITE` timeline. Everything goes through `Showing.whileShowing`, which follows the same signal (minimised counts as not seen).
- **Always, not only while hidden:**
  - Bridges log at `warn` instead of `info` (`BridgeConfig`, both layouts). The full level comes back with `-Dunichat.bridgeLogs=info` for debugging.
  - `Supervisor.pump` stops checking the file's size on every line. It counts the bytes it writes and flushes at most about once a second, or when the line is a warning or worse.
- **On show:** a cloud round at once (`syncNow`), then the paused work catches up.
- **Measured before and after**, with the same commands as above (`top -stats power,idlew` on the JVM, and per-thread CPU from two `jcmd Thread.print` taken 20 s apart). The numbers go in the change's report.

## Non-goals
- Anything about bridges disconnecting and the watchdog restarting them. That is the next, separate investigation.
- Changing how often messages are fetched.

## Anti-goals (a failure even if it saves battery)
- **A notification late or missing while hidden.** These keep running exactly as today:
  - `matrix-sync`
  - the reminders timer
  - the bridges and their log pumps
  - the watchdog
  - `WakeWatch`
  - the Notifier
  - the menu-bar count
- **Losing work.** Paused media, learning and update checks resume. Nothing that was queued is dropped.
- **The small cloud parts stopping.** Settings, AI-learned data, texts and the vault keep syncing while hidden, because they are small and are what restoring on another computer needs. Only media pauses.

## Approaches considered
1. **One "window seen" signal that each background part listens to.** Chosen by the person. It is written once (DRY), and anything new joins by listening to it.
2. Each part checks visibility on its own. Rejected: the same logic would be duplicated, and it is easy to forget to resume.
3. Lowering the process's priority while hidden. Rejected: it does the same work more slowly and saves little energy.

## How (decided)
- **The signal lives outside `ui`:** `support/Seen` (name is the builder's call), a boolean with listeners, so `cloud`, `assist`, `update` and the root can use it without JavaFX (`ArchitectureTest`).
  - `UnichatApp` sets it from the main stage: `showing && !iconified`.
  - That replaces the copies at `UnichatApp.java:95` (`notifier.setWindowShown`) and `AnimatedImage.java:117`.
  - `Showing.whileShowing` treats a minimised window as not showing too.
- **Defaults:** true at start when the window opens, and false when the app starts to the tray. Headless runs, snapshots and tests set it themselves.
- **The cloud:** the round asks the signal before the media step. A round that is mid-file when the window hides finishes that file and stops before the next one. Turning back to seen calls `syncNow()`.

## Tests (each part)
- The signal follows show, hide, minimise and restore (headless).
- **Cloud:**
  - Hidden: a round syncs settings, texts and the vault, and does not upload media.
  - Shown again: the media goes up.
  - Hiding mid-upload stops after the current file.
- **Style learning:** a request while hidden runs on show.
- **Update checks:** a check due while hidden runs on show.
- **`FxWatch`:** hidden, it posts nothing; shown, it posts again.
- **Animations:** each timeline listed above stops on hide and minimise and restarts on show (headless, like `AnimationShowingUiTest`).
- **Notifications:** with the window hidden, an arriving message still notifies (Notifier, through `ScriptedChatService` or the existing notification tests).
- **Bridge logs:**
  - `BridgeConfig` writes `warn` by default and `info` with the flag.
  - `pump` doesn't stat per line, still rotates at 5 MB, and flushes a warning at once.

## Left to the builder
- Names.
- How each part subscribes to the signal.
- The exact flush cadence in `pump`.
- Whether to put `ui/desktop/AppWindows` on the signal.

## Builder's questions, settled (my calls, marked so they can be checked)
1. **"Seen":** any Unichat window that isn't a popup is showing and not minimised (`AppWindows`' count). A photo viewer or Ajustes open with the main window in the tray counts as seen, so nothing the person looks at freezes. `Showing.whileShowing` keeps following its own node's window as well (a closed viewer stops its own video), and also stops everything when nothing is seen.
2. **"Media":** only `MediaUpload.run` and `verify`. Settings, AI-learned data, the vault and the texts, including the texts' readable copies, keep syncing.
   - The tray's cloud line says that media is paused while the window is closed (a new text, through `CloudTexts`).
   - A verify that falls due stays due and runs in the first round after show.
3. **Stopping:** `MediaUpload.run` and `verify` take a "may go on" check, asked before each file. The file in progress finishes, even a long video. Stopping mid-file would throw away the bytes already sent, and resuming would need upload sessions. (Callers of `MediaUpload` change: say so in the plan.)
4. **On show:** one `syncNow`, coalesced. If a round is running, at most one more is queued. Turning seen and unseen quickly gives at most one extra round. The rest (style learning, the update check, animations) starts at once; each is small.
   - A round that is in its vault part when the window hides goes on: only media pauses.
5. **Start state and threads:**
   - **Start state:** `UnichatApp` sets the signal from the same decision it uses to start to the tray, before anything listens. Headless runs, snapshots and tests set it themselves.
   - **Threads:** listeners are called on the setter's thread (the FX thread) and must not block: each hands off to its own executor (the cloud to its worker, and so on).
6. **What waits:**
   - **Style learning:** keeps only the latest request per chat while hidden.
   - **Update checks:** missed hourly checks become one check on show.
   - **Manual check:** "Buscar actualizaciones" in Ajustes is unaffected, since it needs a window.
7. **`FxWatch`:** while hidden it asks every 60 s instead of every 5 s, so a UI thread that hangs while hidden still restarts the app (notifications must not die with it). On show its last question is forgotten, as `WakeWatch` does after waking.
8. **`pump`:**
   - **Warnings flush at once:** a line flushes at once when its JSON `"level"` is `warn`, `error` or `fatal` (all the bridges and tuwunel write JSON; a line that isn't JSON flushes at once too). Others flush at most once a second.
   - **Rotation:** the byte count starts from the file's size when it is opened, so rotation stays at 5 MB.
9. **Bridge logs at `warn`:** nothing in the app reads bridges' info lines (only a person reading `bridge.log` does). Existing installs get it on the next start, since `BridgeConfig.rewrite` runs then.
   - **Kept for the next task:** before this ships, the current `bridge.log` files are copied aside (they still have the `info` history needed for the disconnection investigation that follows).
10. **No numeric target.** A part stays only when it measurably saves, or when it is needed for correctness (animations stopping). A part that shows no gain is dropped and said so in the report.
11. **Screen locked or display asleep counts as not seen** (the person's answer, 2026-11-22).
    - **macOS:** the menu-bar helper (`native/UnichatBar.swift`, which already talks to the app) watches `com.apple.screenIsLocked`/`screenIsUnlocked` (distributed notifications) and `NSWorkspace.screensDidSleep`/`screensDidWake`, and tells the app over its existing channel.
    - **Windows** (the person's target), with JNA, already a dependency (`jna` 5.14.0):
      - A message-only window on its own daemon thread, with a `GetMessage` loop, so nothing polls.
      - `WTSRegisterSessionNotification` reports lock and unlock (`WM_WTSSESSION_CHANGE`: `WTS_SESSION_LOCK`/`UNLOCK`).
      - `RegisterPowerSettingNotification(GUID_CONSOLE_DISPLAY_STATE)` reports display off and on (`WM_POWERBROADCAST`/`PBT_POWERSETTINGCHANGE`).
      - If it fails to start, the app goes on with the window-based signal alone and logs why.
      - `jna-platform` is added only if the bindings are needed, at the same version.
    - **Both sides, one interface:** both feed the same "screen away" source (in `support/`), chosen at start by OS. On Linux, CI and tests it is a no-op, and tests give a fake one.
    - **Everything else works the same on both:** the signal, the pauses, `Showing`, the cloud, the logs and `pump` are plain Java. A minimised window counts on both, and so does the tray, since Windows closes to the tray as the Mac does.
    - **Windows checks:**
      - The handling of each message (lock, unlock, display off/on) is a pure function, tested on every OS with the message codes.
      - The real JNA registration is tested in a `@EnabledOnOs(WINDOWS)` test, so CI's `windows-latest` job runs it. CI's Windows job runs `package.ps1`: add the test run there if it only packages.
      - A Windows build goes to the person for a check on their machine before it is called done.
    - "Seen" = (a window as in 1) and not (locked or display asleep). Unlocking or waking the display turns it back true, with the same on-show catch-up as in 4.
    - **Tests:** the Java side with a fake helper message (locked → not seen, unlocked → seen again); the helper's parsing as the existing helper tests do.
