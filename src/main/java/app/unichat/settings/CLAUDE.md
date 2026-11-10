# settings — notes for Claude

`Settings`: `settings.properties` (portable: travels with the person and through their cloud) and `session.properties` (this machine: `Settings.staysOnThisMachine`). Both are written by `support/SafeFile` (temp, fsync, rename), on a daemon thread of their own (`support/BackgroundWriter`): `set()` never waits for the disk, and `Settings.flush()` waits for it (quitting does, `Shutdown` and the shutdown hook; a test reading the files does).

- A key that holds a secret, a path of this computer, or anything that is run (a command, an engine that runs one) stays on this machine: from the cloud, whoever can write there would choose it for every computer (`SettingsSplitTest`).
- The settings window and a new key's proof that it applies live: `ui/settings/CLAUDE.md`.

## Lessons
- A settings file that couldn't be read at start (a lock by antivirus or OneDrive on Windows) was taken as empty, and the first `set()` wrote that over the session and every setting. `Settings.save` never writes a file that failed to load: it reads it again first and keeps both, or leaves it alone (`SettingsSplitTest.aFileThatCouldntBeReadIsNeverWrittenOver`).
- `Settings.save` wrote and synced both files under the `Settings` lock on every `set()`: the UI thread waited for the disk and every reader stalled behind it (window geometry, each toggle). It now asks a `BackgroundWriter` (`SettingsSplitTest.aChangeIsWrittenOffTheCallersThreadAndFlushWaitsForIt`). A daemon thread dies with the JVM: anything that ends the app flushes first (`ShutdownTest.theSettingsAreOnDiskBeforeTheEnd`), and a test that reads the files or builds a second `Settings` over the same folder calls `flush()` first.
- Since `set()` writes in the background, a test that made a `Settings` over a `@TempDir` must `flush()` it before the test ends (`made(…)` + `@AfterEach` in the settings tests): JUnit deleted the folder while `settings-save` was still writing in it and the test errored ("Failed to delete temp directory"), now and then.
