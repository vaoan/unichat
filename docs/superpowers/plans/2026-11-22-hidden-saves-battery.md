# Window closed saves battery — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While nobody can see Unichat, everything that receives messages and notifies keeps running as today, and the rest pauses and catches up when it is seen again. "Nobody can see it" covers the window in the tray, minimised, the screen locked or the display asleep. The bridges' logs cost less all the time.

**Architecture:** There is one signal, `support/Seen`: a boolean with listeners, outside `ui`. It is true when a Unichat window that isn't a popup is showing and not minimised, and the screen isn't away. Two sources feed it:
- `UnichatApp`, which watches the windows;
- a `ScreenAway` source: the menu-bar helper on macOS, JNA on Windows, nothing on Linux and in tests.

These parts listen to `Seen`:
- `Showing.whileShowing` (animations);
- `FxWatch`;
- `Cloud`'s media step;
- `StyleLearning`;
- `UpdateChecker`.

Separately, the bridges log at `warn`, and `Supervisor.pump` stops checking the file's size and flushing on every line.

**Tech Stack:** Java 25, JavaFX 27, JNA 5.14.0 (already a dependency), Swift (`native/UnichatBar.swift`), JUnit 5 headless.

**Spec:** `docs/superpowers/specs/2026-11-22-hidden-saves-battery-design.md`. Read it whole, including "Builder's questions, settled".

## Global Constraints
- **Notifications are never late or missing while not seen.** These are untouched:
  - `matrix-sync`
  - the reminders timer (`matrix-timer`)
  - the bridges and their log pumps
  - `Watchdog`
  - `WakeWatch`
  - `Notifier`
  - the menu-bar count
- **Only media pauses in the cloud.** Settings, AI-learned data, the vault and the texts (with their readable copies) keep syncing while not seen.
- **Nothing queued is lost.** Paused media, learning and update checks resume when seen again.
- **Mac and Windows both** (the person's target is Windows). On Linux the screen source is a no-op. CI runs on Linux and Windows.
- **Repo rules** (`CLAUDE.md`):
  - every fix and feature has a test that fails without it (watch it fail);
  - UI tests are headless (`-Pheadless-ui`);
  - no waiting on time in tests (`src/test/CLAUDE.md`);
  - files stay under 800 lines and methods under 100;
  - JavaFX only in `ui` (`ArchitectureTest`);
  - user texts go through `I18n`, in English and Spanish;
  - a lesson goes in the folder's `CLAUDE.md`.
- **Build:** `JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -q verify -Pheadless-ui`. Run it in the foreground, with timeout 600000.
- **Bridge log level:** `warn` by default, `info` with `-Dunichat.bridgeLogs=info`.
- **`FxWatch`:** asks every 5 s while seen and every 60 s while not seen.
- **`pump`:** a line with JSON `"level"` of `warn`, `error` or `fatal`, or a line that isn't JSON, is flushed at once. Other lines are flushed at most once a second. Rotation stays at 5 MB, counting from the file's size when it is opened.

## Review Focus
1. **A message arriving while not seen notifies exactly as when seen.** Task 1, `aMessageWhileNotSeenStillNotifies`.
2. **The screen unlocked while the window is in the tray stays not seen.** Unlocking doesn't count as seen. Task 1, `unlockingWithTheWindowInTheTrayStaysUnseen`.
3. **Hiding and showing quickly queues at most one extra cloud round.** Task 3, `flappingQueuesAtMostOneRound`.
4. **The helper dies, or Windows registration fails.** `Seen` falls back to the windows alone, with no exception and a log line. Task 6 `aHelperThatEndsLeavesTheScreenHere`, Task 7 `aRegistrationThatFailsLeavesTheScreenHere`.
5. **A verify due while not seen runs in the first round after seen.** It isn't skipped for a day. Task 3, `aVerifyDueWhileUnseenRunsWhenSeen`.

---

### Task 1: The `Seen` signal and its window source

**Files:**
- Create: `src/main/java/app/unichat/support/Seen.java`
- Create: `src/main/java/app/unichat/support/ScreenAway.java`
- Modify: `src/main/java/app/unichat/UnichatApp.java` (lines ~95-97, the start-to-tray decision)
- Modify: `src/main/java/app/unichat/ui/desktop/AppWindows.java` (expose the count that `Seen` needs)
- Modify: `src/main/java/app/unichat/ui/desktop/Notifier.java` (`setWindowShown` is fed from the same windows' expression)
- Test: `src/test/java/app/unichat/support/SeenTest.java`, `src/test/java/app/unichat/ui/desktop/SeenWindowsUiTest.java`

**Interfaces:**
- Produces:
  - `public final class Seen`, with:
    - `static Seen get()`
    - `boolean now()`
    - `void addListener(Consumer<Boolean> l)` and `void removeListener(Consumer<Boolean> l)`. Listeners are called on the setter's thread, only on a change, and must not block.
    - `void windows(boolean someWindowShown)` and `void screenAway(boolean away)`
    - a package or test hook `static void reset(boolean seen)` for tests.
  - `now() = someWindowShown && !away`.
  - `public interface ScreenAway`:
    - `void start(Consumer<Boolean> away)`, and `static ScreenAway none()`.
- Consumes: nothing.

- [ ] **Step 1: Write the failing tests.**
  - In `SeenTest`, for `startsAsSet`, `windowsHiddenIsUnseen`, `screenAwayIsUnseen`, `unlockingWithTheWindowInTheTrayStaysUnseen`, assert `now()` follows `windows`/`screenAway` as given.
  - `listenersHearOnlyChanges`: set the same value twice and assert one call.
  - In `SeenWindowsUiTest` (headless), for `showingHidingAndMinimisingFollowsSeen`, a stage shown, hidden, iconified and restored gives true, false, false, true.
    - A photo-viewer-like second `Stage`, shown while the main one is hidden, gives true.
    - A popup doesn't count.
  - `aMessageWhileNotSeenStillNotifies`: with `Seen` false, an arriving message through the existing notification test path, the way `NotificationOpenUiTest` or the Notifier's tests drive one, still posts its notification.
- [ ] **Step 2: Run them and see them fail** (they don't compile):
  ```bash
  ./mvnw -q verify -Pheadless-ui -Dtest='SeenTest,SeenWindowsUiTest' -Dsurefire.failIfNoSpecifiedTests=false
  ```
- [ ] **Step 3: Implement.**
  - `UnichatApp` listens on every window that `AppWindows` counts and calls `Seen.get().windows(...)`. It sets the start value from the same decision that starts it in the tray, before anything listens.
  - `notifier.setWindowShown` keeps its own meaning, the main stage, and is unchanged.
  - Snapshot and headless runs set `Seen` themselves.
- [ ] **Step 4: Run them and see them pass.** Then run `./mvnw -q verify -DskipTests` (Checkstyle and architecture).
- [ ] **Step 5: Commit** with the message `feat(support): one signal for whether anyone can see Unichat`.

### Task 2: JavaFX stops when unseen; `FxWatch` slows down

**Files:**
- Modify: `src/main/java/app/unichat/ui/Showing.java`
- Modify, each timeline that runs on its own so that it goes through `Showing.whileShowing`:
  - `src/main/java/app/unichat/ui/WorkingLine.java:41-43`
  - `ui/timeline/AiReplyButton.java:137-139`
  - `ui/timeline/UploadBar.java:45,67`
  - `ui/media/InlineVideo.java:34,49`
- Modify: `src/main/java/app/unichat/ui/media/AnimatedImage.java:117` (uses `Showing` and `Seen` instead of its own `isShowing && !iconified`)
- Modify: `src/main/java/app/unichat/FxWatch.java` (`EVERY_MS` 5 000 while seen, 60 000 while not; the last question is forgotten when seen again)
- Test: `src/test/java/app/unichat/ui/media/AnimationShowingUiTest.java` (extend), `src/test/java/app/unichat/ui/SeenAnimationsUiTest.java`, `src/test/java/app/unichat/FxWatchTest.java`

**Interfaces:** Consumes `Seen` from Task 1. `Showing.whileShowing(node, start, stop)` keeps its signature. It starts only while the node's window shows and `Seen.now()`, and stops when either ends.

- [ ] **Step 1: Write the failing tests.**
  - `SeenAnimationsUiTest.everyTimelineStopsWhenUnseenAndStartsWhenSeen`: for `WorkingLine`, `AiReplyButton` (working), `UploadBar` (counting), `InlineVideo` and an `AnimatedImage`, assert each `Animation.Status` is `RUNNING`. Then set `Seen.reset(false)` and assert `STOPPED`/`PAUSED`. Then set it to true and assert `RUNNING`.
  - `FxWatchTest`:
    - `unseenAsksEverySixtySeconds`: its interval is read through an injectable clock or sleeper, never a real wait.
    - `seenAgainForgetsTheLastQuestion`: an old unanswered question at the moment `Seen` turns true doesn't count as frozen.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.** Make `FxWatch`'s sleep injectable if it isn't already, so the test needs no time.
- [ ] **Step 4: Run them, plus every `*UiTest` in `ui/media` and `ui/timeline`, and see them pass.**
- [ ] **Step 5: Commit** with the message `feat(ui): animations and the UI watch rest while nobody can see Unichat`.

### Task 3: Cloud media pauses while unseen

**Files:**
- Modify: `src/main/java/app/unichat/cloud/MediaUpload.java`. These signatures become:
  - `int run(Storage dav, Progress progress, BooleanSupplier mayGoOn)`
  - `int verify(Storage dav, Progress progress, BooleanSupplier mayGoOn)`
  - `mayGoOn` is asked before each file, and the file in progress finishes.
- Modify: `src/main/java/app/unichat/cloud/Cloud.java`:
  - the round passes `Seen.get()::now`;
  - a verify that falls due stays due while unseen;
  - a `Seen` listener calls a coalesced `syncNow()` when seen again, with at most one round queued behind a running one;
  - `Status` says media is paused.
- Modify: `src/main/java/app/unichat/ui/settings/CloudTexts.java`, with the new line under the key `cloud.media.pausedUnseen`:
  - EN: "Photos and videos wait while Unichat's window is closed"
  - ES: "Las fotos y videos esperan mientras la ventana de Unichat está cerrada"
- Modify: `messages.properties` and `messages_es.properties`.
- Test: `src/test/java/app/unichat/cloud/CloudUnseenTest.java`.

**Interfaces:** Consumes `Seen`. Produces `Status.mediaPaused()` (boolean), which `CloudTexts.vaultLines`/status lines read.

- [ ] **Step 1: Write the failing tests** in `CloudUnseenTest`, with `FakeDav`/`VaultFixture`:
  - `unseenSyncsTheSmallPartsButNoMedia`: settings, texts and vault files change on the fake, and no media is PUT.
  - `seenAgainSendsTheMedia`
  - `hidingMidUploadStopsAfterTheCurrentFile`: hold the first PUT with `holdOnce`, set unseen, release it, and assert one file went up and the next didn't.
  - `flappingQueuesAtMostOneRound`: while a round is held, set seen false and true five times, then assert at most two rounds ran.
  - `aVerifyDueWhileUnseenRunsWhenSeen`
  - `theStatusSaysMediaWaits`
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.** Update every caller of `MediaUpload.run`/`verify`.
- [ ] **Step 4: Run every test in `cloud/` and `ui/settings/`, plus `I18nTest`, and see them pass.**
- [ ] **Step 5: Commit** with the message `feat(cloud): media waits while nobody can see Unichat; the small parts go on`.

### Task 4: Style learning and update checks wait

**Files:**
- Modify: `src/main/java/app/unichat/assist/StyleLearning.java`. While unseen, `request(chatId, …)` keeps only the latest request per chat and doesn't start it. Seen again starts them, and the futures complete as they would have.
- Modify: `src/main/java/app/unichat/update/UpdateChecker.java`. While unseen, `checkIfDue` doesn't check. Seen again with a check overdue runs exactly one. A manual check is unaffected.
- Test: `src/test/java/app/unichat/assist/StyleLearningUnseenTest.java`, `src/test/java/app/unichat/update/UpdateCheckerUnseenTest.java`.

**Interfaces:** Consumes `Seen`.

- [ ] **Step 1: Write the failing tests.**
  - `requestsWhileUnseenRunWhenSeen`: the program is a fake, and the future completes only after seen.
  - `onlyTheLatestPerChatIsKept`
  - `missedChecksBecomeOneWhenSeen`: a fake clock where three hourly ticks pass while unseen, then one HTTP call on seen.
  - `aManualCheckIsntHeld`
- [ ] **Step 2: Run them and see them fail. Step 3: Implement. Step 4: Run them and see them pass, with `assist/` and `update/`.**
- [ ] **Step 5: Commit** with the message `feat: style learning and update checks wait until Unichat is seen`.

### Task 5: Bridge logs cost less

**Files:**
- Modify: `src/main/java/app/unichat/server/BridgeConfig.java:92,146`. `min_level` is `warn`, or `System.getProperty("unichat.bridgeLogs", "warn")`, checked against `debug|info|warn|error`.
- Modify: `src/main/java/app/unichat/server/Supervisor.java:167-197` (`pump`), following the Global Constraints. Each line is still redacted, and `read` and `afterLine` still run for each line.
- Test: `src/test/java/app/unichat/server/BridgeConfigTest.java` (extend), `src/test/java/app/unichat/server/SupervisorPumpTest.java` (create, or extend the existing pump test).

- [ ] **Step 1: Write the failing tests.**
  - `bridgesLogWarningsByDefault` and `theFlagBringsInfoBack`, for both layouts, one bridgev2 bridge and discord.
  - `aWarningIsOnDiskAtOnce`: after a `"level":"warn"` line is pumped, the file has it before the next line comes.
  - `infoLinesAreFlushedWithinTheNextFlush`: a test-only flush hook or clock, no waits.
  - `rotationCountsTheFileAlreadyThere`: a 5 MB-minus-10-byte file plus a 20-byte line rotates.
  - `noSizeCheckPerLine`: inject the size function and count its calls, at most one per open.
- [ ] **Step 2: Run them and see them fail. Step 3: Implement. Step 4: Run them and see them pass, with `server/`.**
- [ ] **Step 5: Commit** with the message `perf(server): bridges log warnings only; the log pump stops checking and flushing every line`.

### Task 6: macOS — the screen locked or asleep

**Files:**
- Modify: `native/UnichatBar.swift`.
  - Observe `DistributedNotificationCenter` `com.apple.screenIsLocked`/`com.apple.screenIsUnlocked`, and `NSWorkspace.shared.notificationCenter` `screensDidSleepNotification`/`screensDidWakeNotification`.
  - Emit `screen\t1` when locked or asleep, and `screen\t0` when unlocked or awake. It is away while either holds.
  - Document it in the header comment's output list.
- Modify: `src/main/java/app/unichat/ui/desktop/NativeBar.java` (~line 192). Parse `screen\t<0|1>` and feed a `ScreenAway` consumer. When the helper ends, report not away.
- Create: `src/main/java/app/unichat/ui/desktop/MacScreen.java`, implementing `ScreenAway` through `NativeBar`.
- Modify: `UnichatApp`, which chooses the source by OS: macOS `MacScreen`, Windows `WindowsScreen` (Task 7), otherwise `ScreenAway.none()`.
- Test: `src/test/java/app/unichat/ui/desktop/NativeBarScreenTest.java`.

- [ ] **Step 1: Write the failing tests.**
  - `lockedThenUnlockedFollowsSeen`: helper lines fed as the existing `NativeBar` tests feed `notify-auth`.
  - `aHelperThatEndsLeavesTheScreenHere`: the stream closes, the screen is back to not away, and nothing throws.
  - `aBadScreenLineIsIgnored`
- [ ] **Step 2: Run them and see them fail. Step 3: Implement both sides.** Then run `bash native/build-bar.sh` and `bash native/check-macos-min.sh` and see them succeed.
- [ ] **Step 4: Run them and see them pass. Step 5: Commit** with the message `feat(desktop): on macOS a locked or sleeping screen counts as nobody seeing Unichat`.

### Task 7: Windows — the session locked or the display off

**Files:**
- Create: `src/main/java/app/unichat/ui/desktop/WindowsScreen.java`, implementing `ScreenAway` with JNA.
  - A daemon thread creates a message-only window (`HWND_MESSAGE`) and calls `WTSRegisterSessionNotification(hwnd, NOTIFY_FOR_THIS_SESSION)` and `RegisterPowerSettingNotification(hwnd, GUID_CONSOLE_DISPLAY_STATE, DEVICE_NOTIFY_WINDOW_HANDLE)`, then loops on `GetMessage`.
  - Add `jna-platform` at 5.14.0 only if its `User32`/`Wtsapi32` bindings are needed.
- Create: `src/main/java/app/unichat/ui/desktop/ScreenMessages.java`, a pure function `Boolean away(int msg, long wParam, int displayState)`. It returns true or false for:
  - `WM_WTSSESSION_CHANGE` (0x02B1) with `WTS_SESSION_LOCK` (7) or `UNLOCK` (8);
  - `WM_POWERBROADCAST` (0x0218) with `PBT_POWERSETTINGCHANGE` (0x8013) and the display state 0 (off), 1 (on) or 2 (dimmed, counts as on).

  It returns null for anything else. Locked and display off are tracked separately; the screen is away while either holds.
- Modify: `.github/workflows/build.yml`, so the Windows job (`windows-latest`, line ~67) runs `./mvnw -B test -Dtest='WindowsScreen*Test,ScreenMessagesTest' -Dsurefire.failIfNoSpecifiedTests=false` before packaging.
- Test:
  - `src/test/java/app/unichat/ui/desktop/ScreenMessagesTest.java`, which runs on every OS.
  - `src/test/java/app/unichat/ui/desktop/WindowsScreenTest.java`, `@EnabledOnOs(OS.WINDOWS)`.

- [ ] **Step 1: Write the failing tests.**
  - `ScreenMessagesTest`: lock, then unlock, gives true, false. Display 0, 1, 2 gives true, false, false. Locked plus display on stays away until unlocked. Other messages give null.
  - `WindowsScreenTest.itRegistersAndStops`: start, the thread is alive and registered, stop, and the thread ends.
  - `aRegistrationThatFailsLeavesTheScreenHere`: a stubbed failing register gives not away, a log line, and nothing thrown. This one runs on every OS through the factored-out start logic.
- [ ] **Step 2: Run them and see them fail. Step 3: Implement. Step 4: Run them and see them pass** (`ScreenMessagesTest` here; the Windows one runs in CI).
- [ ] **Step 5: Commit** with the message `feat(desktop): on Windows a locked session or a display off counts as nobody seeing Unichat`.

### Task 8: Notes

**Files:**
- Modify: `src/main/java/app/unichat/support/CLAUDE.md` for `Seen`, with what may and may not listen to it. The anti-goals list goes there, verbatim from the spec.
- Modify: `src/main/java/app/unichat/ui/CLAUDE.md`, saying that `Showing` follows `Seen`.
- Modify: `cloud/CLAUDE.md`, saying that media waits while unseen.
- Modify: `server/CLAUDE.md`, for the bridge log level and the flag.
- Modify: `ui/desktop/CLAUDE.md`, for the screen sources on macOS and Windows.

- [ ] **Step 1: Write the notes. Step 2: Run the full verify. Step 3: Commit** with the message `docs: what keeps running while nobody sees Unichat`.

The before and after measurements on the person's Mac are the controller's job, not a task. The "before" numbers were taken on 2026-11-22 with the window closed:
- the JVM at ~11 % of a core;
- the FX thread at 654 ms per 20 s;
- `log-discord` at 927 ms per 20 s.
