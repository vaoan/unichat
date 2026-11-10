# app.unichat (root) — notes for Claude

Startup and wiring: `Launcher` (main of the package), `UnichatApp` (the JavaFX application: window, tray, quitting, language changes), `SnapshotRun` (the `snapshot` skill), `AppLog` (stdout/stderr to `<data>/logs/unichat.log`, through `Redactor`), `Shutdown` (quitting off the UI thread).

## Recovery
- `Recovery` starts Unichat again by itself when it can't go on: an `OutOfMemoryError` (any `VirtualMachineError`) in a thread without its own handler, the old generation still ≥ 95 % full right after a collection, or `FxWatch` (the UI thread unanswered for a minute of awake time). It logs why plus every thread's stack, starts the same command (`ProcessHandle.info()`: the app's launcher, or the `java` line from sources) with `--unichat-after=<pid>:<count>`, and exits (halted after 20 s if exiting hangs). The new run waits for the old pid to end (`Recovery.Start.waitForThePrevious`), so two never share the data folder or ports; `Supervisor` cleans the old run's bridges. At most 3 in a row; 10 minutes running resets the count.
- Only `Launcher` installs it: tests and snapshot runs never restart by themselves.
- Exceptions inside an executor's task go to its `Future`, not the handler: an `OutOfMemoryError` there is only caught by the memory threshold.

## Lessons
- Unichat hung for good: a chat full of stickers kept 56 animations (2 520 decoded frames) alive on a 1 GB heap; an `OutOfMemoryError` killed `matrix-sync` (no more messages) and left JavaFX's render thread spinning in `PrEffectHelper.render` (a `do … while (!validate())` over a clip's effect), so the UI thread waited forever in `waitForRenderingToComplete`. Found with `jcmd <pid> Thread.print` (the same frame in two dumps 15 s apart) and a heap dump traced back to `AnimatedImage.play`'s key frames. Now `ui/AnimationBudget` caps what plays at once and `Recovery` restarts if it happens anyway.
