# Light When Away Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unichat uses as little memory and CPU as it can, above all while its window is closed to the menu bar, and still gets every notification.

**Architecture:** While the window has been hidden a while, the whole window (`MainView`) and the pictures it decoded are let go; the service (sync, notifications, menu bar, autosave, cloud) keeps running; showing the window builds it again (as a language change does), with the same chat open. JavaFX keeps fewer textures, the local server keeps smaller caches, and the Go bridges collect sooner.

**Tech Stack:** Java 25, JavaFX 27, jpackage options, tuwunel's TOML, Go runtime env (`GOGC`, `GOMEMLIMIT`).

**Spec:** this plan (small project; the person chose, 2026-11-26: "un segundo está bien" for reopening; "muy fluido, muy bajo en memoria, muy bajo en todo; que apenas gaste memoria cuando la UI no esté pero que siga obteniendo todas las notificaciones").

## Measured (2026-11-26, installed app, 10 min after start, window visible)
- Unichat physical footprint 1.1 GB (peak 1.7 GB; what Activity Monitor shows): IOAccelerator (graphics) 340 MB + IOSurface 255 MB, Java heap ~217 MB used / 232 MB committed, metaspace 67 MB, 97 threads, MALLOC ~60 MB.
- tuwunel 173 MB; bridges: discord 113, telegram 35, whatsapp 34, instagram 31, facebook 25, twitter 22, linkedin 19 MB. Total ~912 MB RSS.

## Global Constraints
- Every notification still arrives while the window is closed: system notifications, the menu bar's unread count and ring, autosave, cloud, sounds — nothing that `Notifier`, the service or `AutoSave` does depends on `MainView` existing.
- Reopening may take about a second (the person's word); it reopens on the chat that was open.
- Nothing on the person's screen in tests (`-Pheadless-ui`); tests never wait on time (inject the delay; `FxWait`); every behaviour has a test that fails without it.
- English in code; I18n for any text (none expected); files < 800 lines, methods < 100; `ArchitectureTest` rules.
- Measure before and after on the installed app (`vmmap -summary <pid>` Physical footprint, `ps -o rss=`), never guess.

## Review Focus
1. A notification clicked while the window was let go opens that chat (the view is rebuilt first).
2. The settings window, a photo/video viewer, or a sheet open when the window hides: nothing is let go under them (or they're closed safely), no exception.
3. Hiding and showing quickly (under the delay) keeps the same view: no rebuild, no flash.
4. A language change or logout while the view is let go builds the right thing on the next show.
5. The old view and its pictures are really collectable (weak references cleared after GC), not only detached.

---

### Task 1: Let the window go while it's closed

**Files:**
- Modify: `src/main/java/app/unichat/UnichatApp.java` (window shown/hidden, `relocalize`, `openFromNotification`, `showWindow`, logout), maybe a new `src/main/java/app/unichat/ui/AwayRelease.java` (or in root) that owns the timer and the rebuild so UnichatApp stays small.
- Modify: picture caches to empty on request: `ui/media/ImageCache`, `ui/media/Stills`, `ui/media/AnimatedImage` (decoded cache), `ui/media/MessageMedia.FILE_PREVIEWS`, anything else static that holds decoded `Image`s (grep `static final .*Map<.*Image` and `BoundedMap` in `ui`). One entry point, e.g. `ui/media/Pictures.forget()`.
- Test: `src/test/java/app/unichat/ui/AwayReleaseUiTest.java` (or in root package), headless.
- Notes: a line in `src/main/java/app/unichat/ui/CLAUDE.md` and in root `src/main/java/app/unichat/CLAUDE.md` if the lifecycle lives there.

**Interfaces:**
- Produces: the window is let go after `Settings`-free constant `AWAY_RELEASE = Duration.ofSeconds(60)` hidden (not merely minimised, not with another Unichat window showing such as Settings or a viewer); a package-visible way for tests to pass `Duration.ZERO` and a way to know it happened (a counter or listener), so tests wait on it, not on time.
- Rebuild = the same path `relocalize` uses (`new MainView(...)`, `scene.setRoot`, `Theme.applyAll`, `openChatId(chat)`), shared, not copied.

- [ ] **Step 1: Tests first** (each fails without the code):
  - `aWindowHiddenLongEnoughLetsItsViewGo`: hide the stage, with the delay at zero → the old `MainView` is disposed and, after a bounded `System.gc()` loop, a `WeakReference` to it is cleared; the scene's root is no longer it.
  - `itsPicturesGoToo`: an avatar in `ImageCache` before hiding → empty after the release.
  - `showingAgainBuildsItWithTheSameChatOpen`: chat X open, hide, release, show → a new `MainView` with X open.
  - `aNotificationClickWhileLetGoOpensItsChat`: release, then the notification-open path for chat Y → window shown, Y open.
  - `theMenuBarCountStillFollowsWhileLetGo`: release, a message arrives (demo/scripted service) → the notifier's unread count changes.
  - `hidingAndShowingQuicklyKeepsTheSameView`: delay > 0, hide then show before it → same `MainView` instance.
  - `nothingIsLetGoUnderAnOpenSettingsWindow`: Settings showing when the main window hides → no release while it shows.
  - `aLanguageChangeWhileLetGoBuildsTheNewLanguageOnShow` (or relocalize when there's no view does nothing and the next show builds in the new language).
- [ ] **Step 2: Run** headless → FAIL.
- [ ] **Step 3: Implement**; reuse `relocalize`'s build; `dispose()` the view (it already unregisters what it put on the stage and singletons, `ui/CLAUDE.md`); empty the picture caches; one `System.gc()` after letting go is acceptable (comment why: give the memory back now, G1 returns it to the system).
- [ ] **Step 4: Run** the new class plus `RelocalizeUiTest`, `NotificationOpenUiTest`, `ThemeSceneUiTest`, `AvatarFlashUiTest`, any `UnichatApp`/tray tests → PASS. Static checks `./mvnw -q -Djacoco.skip=true -DskipTests verify -Pheadless-ui` exit 0.
- [ ] **Step 5: Commit** by path: `perf(app): let the window and its pictures go while it's closed, build it again on opening`.

### Task 2: Smaller caches in the local server and the bridges, fewer textures

**Files:**
- Modify: `src/main/java/app/unichat/server/HomeserverConfig.java` (`toml`): tuwunel's cache settings for a personal server — `cache_capacity_modifier` and the RocksDB caches (`db_cache_capacity_mb`, `db_write_buffer_capacity_mb`); read tuwunel's own example config (its repo, `conduwuit-example.toml`/`tuwunel-example.toml`, the version in `versions.env`) for the exact names and defaults before choosing; keep defaults that matter for correctness.
- Modify: `src/main/java/app/unichat/server/Supervisor.java` (or where a bridge's `ProcessBuilder` is made): for Go bridges only (not tuwunel, not Python ones if any), environment `GOGC=50` and `GOMEMLIMIT=<per bridge>` (soft limit; e.g. 64MiB, Discord 128MiB) — check which bridges are Go (mautrix-* v2 are Go).
- Modify: `package.sh`, `package.ps1`, `PackagingJvmTest`: `-Dprism.maxvram=256m` (JavaFX's texture pool, 512 MB by default; past it JavaFX evicts least-used textures).
- Tests: `HomeserverConfigTest` (the values written), a Supervisor/launcher test that a Go bridge's process gets the env and tuwunel doesn't, `PackagingJvmTest` (the new option on both).

- [ ] **Step 1: Tests first** → FAIL.
- [ ] **Step 2: Implement**, with a comment for each value saying why (measured numbers above).
- [ ] **Step 3: Run** the classes + static checks → PASS.
- [ ] **Step 4: Commit** by path: `perf(server): smaller caches for the local server, Go bridges collect sooner, fewer JavaFX textures`.

### Task 3 (controller): verify, install, measure
- [ ] Full `./mvnw -q clean verify -Pheadless-ui` → exit 0.
- [ ] Merge, push, install, reopen. Measure each process (RSS) and Unichat's physical footprint (`vmmap -summary`) visible after 10 min, and after the window has been closed 2 min; compare with the numbers above. Report to the person.
