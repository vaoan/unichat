# tests — notes for Claude

Tests run in Spanish (`unichat.language=es` in the pom), with `unichat.dataDir`, `unichat.autosaveDir` and `unichat.silent` set by surefire: never the real data dir.

## Waiting
- Wait on a future with `get()`, never `join()`: `join()` can't be interrupted, so JUnit's timeout never ends it and a future that never completes hangs the build for good (two agents lost an hour to it; lesson `test-future-join`).
- Tests never wait for a length of time nor carry a timeout of their own: no `Thread.sleep`, no `await(5, SECONDS)`, `poll(200, MILLISECONDS)`, `join(5000)`, `waitFor(30, …)` or `get(30, …)` (`.claude/lessons.tsv` catches them). They wait on what the code actually does: a latch or future the code completes, a listener, `FxWait.until` / `FxWait.settled` on screen, the code's own `flush()`. JUnit's global timeout (`junit-platform.properties`) is the only limit.
- To check that something did *not* happen, first wait for the point where it would have (flush, settled, the call that would have caused it returned). If the code gives no way to know when it's done, change the code so it does (a listener, a returned future, a flush) rather than guess a time.
- A time the code under test takes as input is given as zero or as no limit (`Duration.ZERO` with a program that never ends shows the timeout path; `NO_LIMIT` when it ends by itself), never a guess of how long something takes. The one timed pause allowed is a profiler's sampling rate (`ScrollPerfUiTest`'s sampler): it measures, it doesn't wait.
- A flaky test is a bug, never tolerated nor retried away: find the race and fix it (usually a wait on time, or work still running when the test ends: wait for it).
- A wait for something that can fail waits for the failure too and says it, never runs to the limit.

## Flags
Off by default, only the person runs them: `-Dunichat.networkTests=true` (real downloads and CLIs), `-Dunichat.chromeTests=true` (real Chrome, off screen), `-Dunichat.perfTests=true` (`ScrollPerfUiTest`, `EmojiScrollPerfUiTest`: the real display), `-Dunichat.realInputTests=true` (`MenuDismissUiTest`: the real mouse). `MatrixLiveIT` needs `UNICHAT_IT_HOMESERVER`.

## UI tests (`*UiTest`, `-Pheadless-ui`)
- They run with no display or windows: JavaFX's own headless platform (`-Dglass.platform=Headless`, since JavaFX 25; Monocle was never published past JavaFX 21). All of them run there. It proves logic, not focus, tray or Dock.
- Its virtual screen is a fixed 1000×1000 and a window drawn outside it (moved off-screen, or taller than the rest) throws on every frame, on the UI thread, into whatever test comes next: keep test windows on it, hidden with opacity 0, and close them when done.
- CI runs them on Linux (`build.yml`, ui-headless): a test mustn't assume the Mac. The shortcut key is Ctrl there (press Ctrl and ⌘ together), the fonts are taller (leave room before a size cap), and there's no sound card (PulseAudio's null sink).
- `KeyboardUiTest` checks F6 zones, timeline keys, zoom and screen-reader texts; `FilterUiTest` that the rail keeps the chosen chip when switching network.

## Lessons
- `Node.isFocused()` needs the window to have OS focus: F6 into the timeline only selected a message via the focused listener, so it silently failed when the window wasn't frontmost. Select explicitly (`focusTimeline()`), and never test through focus listeners.
- A fixed "now" next to code that reads the real clock (`FirefoxCookiesTest`: cookies valid one day after 2026-10-26) starts failing days later. Give fixtures a margin of years.
- Matrix media messages keep the name in `fileName`, not `body` (body = caption): match on both.
- Measure, don't guess: a click simulation (visible→hidden events, cache misses) found the second avatar flash; peak heap with real files (`-Xmx256m` and a folder of them) found the WebP OOM.
- `FxWait.settled` knows only what counts in `Redraws.PENDING`: a `Platform.runLater` the code queues to change the layout later (`Composer`'s stacking after a width change, `MainView.catchUp` when the window shows again) must count there, or a test measures the half-moved screen. `NotificationOpenUiTest` failed one run in three (on `main` too) until both did.
