---
paths:
  - "src/main/java/app/unichat/ui/Settings*.java"
  - "src/main/java/app/unichat/ui/*Page.java"
  - "src/main/java/app/unichat/ui/*Pages.java"
  - "src/main/java/app/unichat/ui/ConverterSettings.java"
  - "src/main/java/app/unichat/ui/SyncedSettings.java"
  - "src/main/java/app/unichat/settings/**"
---
# The settings window

- `SettingsWindow`: pages built with `SettingsForm`: each option a `SettingsRow`, its control beside the text while the text keeps 14 em, under it otherwise (a wide one squeezed names to a syllable a line); `SettingsLayoutUiTest` checks every page at 100 % and 160 %; `stacked` always puts it under.
- Redes puts what holds for every network first, then each network by name, with the converter in `ConverterSettings`. Pages: `AppearancePages`, `PrivacyPage`, `NetworksPage`, `SpeechPage`, `AiReplyPage`, `CloudPage`, `AboutPage`.
- A new setting key: `SyncedSettingsUiTest` has a proof for every setting key in the source and fails for a new one without (it travels with the person's cloud, `cloud/CLAUDE.md`); keys that must stay on this machine are `Settings.staysOnThisMachine`'s (`service/CLAUDE.md`).
- A new connection to anything outside the machine is in `docs/PRIVACY.md` and `docs/PRIVACIDAD.md` and can be turned off here (`packaging/CLAUDE.md`, SignPath's terms).
