# settings — notes for Claude

`Settings`: `settings.properties` (portable: travels with the person and through their cloud) and `session.properties` (this machine: `Settings.staysOnThisMachine`). `SyncedFile.replace` writes a file safely (temp, fsync, rename): use it instead of another.

- A key that holds a secret, a path of this computer, or anything that is run (a command, an engine that runs one) stays on this machine: from the cloud, whoever can write there would choose it for every computer (`SettingsSplitTest`).
- The settings window and a new key's proof that it applies live: `.claude/rules/ui-settings.md`.
