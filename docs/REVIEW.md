# Open review findings

What the last whole-app review (2026-11-10: SOLID, DRY, KISS and security, four reviewers, each finding checked in the code) found and is not fixed yet, most severe first. Fix one with a test that fails first, then delete its line. The `health` skill (`.claude/skills/health/`) runs the next review; add to this list what it finds and doesn't fix.

Fixed in that review: `file:` media addresses from messages opened as local files (`LocalFileUrlTest`); `stt.command`/`stt.engine` travelling through the cloud (`SettingsSplitTest`); `natives.yml` gave every job a write token; the Docker homeserver listened on every interface.

## Security
1. **Bridge and tuwunel checksums come from the same release as the binaries** (`server/BinaryStore.java:52`, `native/setup-mac.sh`, `native/fetch-natives.sh`, `package.ps1`): they catch corruption, not tampering. Pin a SHA-256 per asset and platform in `versions.env`.
2. **Windows: autosaved files have no Mark-of-the-Web and keep RTL overrides and runnable extensions** (`autosave/FileNames.java:80`, `AutoSave.java:242`): `invoice‮fdp.exe` shows as a PDF and runs without SmartScreen; a `.library-ms` leaks the NTLM hash. Strip `\p{Cf}`, write `Zone.Identifier` (ZoneId=3), neutralize runnable extensions.
3. **Codex runs with the person's MCP servers and AGENTS.md** (`assist/AiEngine.java:59`): Claude is isolated (`--strict-mcp-config --setting-sources ""`), Codex only has `--sandbox read-only`. Override its MCP servers and profiles.
4. **No deadline on response bodies** (`links/LinkInspector.java:108`, `gif/GifFiles.java:47`): four slow links stop link inspection for the session; GIF downloads also lack a size cap and an https check.
5. **Animated WebP decoded at full size before the frame cap** (`video/AnimDump.java:39`): a small 8000×8000×300 WebP fills the disk or the heap. Refuse by width × height × frames from `parseLayout`.
6. **Unbounded HTML nesting** (`ui/HtmlView.java:118`): `<b>`×10000 fits in an event and likely overflows the stack on the FX thread. Cap the depth.
7. **`Redactor` misses `refresh_token`, cookies, YAML `as_token:` and `?token=`** (`support/Redactor.java:17`): they reach logs and error reports.
8. **ffmpeg probes any format from received files** (`video/VideoTranscoder.java:96`, `BigPictures.java:63`, `stt/Transcriber.java:92`): an HLS playlist with `file:` segments reads local files into the output. Add `-format_whitelist`.
9. **Homeserver address from `well_known` not checked** (`matrix/MatrixLogin.java:99`): can downgrade the session to plain HTTP. Pass it through `isHomeserver`, refuse `http` unless local.
10. Lower: bridge configs and `tuwunel.toml` written 0644 while `secrets.properties` is 0600 (`server/LocalServer.java:268`, `BridgeInstaller.java:67`); pCloud's token left in browser history (`cloud/PCloudLogin.java:75`); OAuth listener on 43117 never times out (`cloud/Loopback.java:27`); `state` compared with `equals` (`PCloudLogin.java:89`, `DropboxLogin.java:92`); Chrome's debugging port open up to 15 min (`login/ChromeLogin.java:106`); some reserved ranges pass `LinkInspector` (198.18/15, 240/4, 64:ff9b::/96, 2002::/16); `${{ matrix.bridge.* }}` inside `run:` in `natives.yml`; Docker images `:latest`; one external-link path (`UnichatApp.java:281`) doesn't go through `Browser.open`, and neither checks the scheme.

## Correctness
1. **A failed `settings.properties`/`session.properties` read becomes a wiped file** on the next `set()` (`settings/Settings.java:106`): a moment's lock by antivirus or OneDrive logs the person out and erases every setting. Refuse to save over a file that failed to load, as `PreferencesFile` does.
2. **The old `MainView` keeps marking chats read after a language change or logout** (`ui/MainView.java:183`): stage listeners never removed in `dispose()`; the old view also stays in memory. Same for `ThreadPane`'s `Transcripts` listener (`ThreadPane.java:190`).
3. **Quitting stops the services on the FX thread** (`UnichatApp.java:330`): up to 10 s per bridge, or the whole install, of a frozen window.
4. **Autosave marks a failed save as done** (`autosave/AutoSave.java:184`): no space or a failed download loses those files for good.
5. **My own sends can be skipped by autosave** (`matrix/SyncProcessor.java:671`): the confirmed echo keeps the time the upload started, before the other side's newer message.
6. **Saving a link turns still images into one-frame MP4s** (`video/VideoTranscoder.java:70`): ffmpeg calls GIF/WebP "Video".
7. **Any startup catch-up error throws away the cached sync** (`matrix/MatrixChatService.java:254`): a 429 or 502 forces the full multi-MB sync.
8. **`BotChat.addDirect` overwrites `m.direct` after a failed GET** (`matrix/BotChat.java:186`); a failed reply lookup is cached for good (`MatrixChatService.java:483`, `MatrixException` is unchecked).
9. **Leaks:** `.part` files in `cache/media` never deleted (`matrix/MatrixClient.java:122`); `pending` events per room grow without bound (`SyncProcessor.java:470`); `MediaDownloader.watching` keys; a new `HttpClient` per call (`support/Downloads.java:46`, `DropboxLogin`, `UpdateChecker`, `MetaSessions`); cloud repair loads a whole file in memory (`cloud/MediaUpload.java:151`).
10. Lower: `Settings.save` fsyncs twice under the lock on every `set`; the tray is built on the FX thread (`ui/Notifier.java:173`); stopping `ReplyWriter`/`Transcriber` leaves their program running; an old `CloakJob` can write over the new one's status; `Transcripts` can start a note twice; dot-files saved again on each explore; renaming a group splits its folder; `BigPictures` downloads ffmpeg before trying `sips`; Discord server pause fails silently; `config.yaml` rewritten in place (`server/BridgeConfig.java:50`).

## DRY
- Running a program: `ReplyWriter.run`, `Transcriber.run`, `CloakJob.run` re-implement `support/Processes.capture` (and "last line" three times).
- "Run into a `.part`, move into place": five copies in `video/` (`VideoTranscoder` ×2, `WebmStickers`, `BigPictures`, `SendablePhotos`); "temp file then rename": six (`Downloads`, `Archives`, `BinaryStore`, `MediaUpload`, `Cloud`, `UploadQueue`) next to `settings/SyncedFile.replace`.
- Installers: `Ffmpeg.install` / `YtDlp.install`; the CPU/OS pick in `Ffmpeg`, `YtDlp`, `CloakTool`.
- HTTP send + error mapping seven times in `cloud/` (`PCloudApi`, `DropboxApi`, `WebDav`, `DropboxLogin`).
- mxc parsing three times (`MediaDownloader.serverOf`, `.download`, `MessageSender.needsCopy`); `MessageSender.probeMime` repeats `model/MimeTypes`.
- UI: "open file" (`ProfileMedia`, `MessageMedia`), "open video" ×3 (only one pauses audio), the coalesced redraw ×4 (`UploadBar`, `ExploreBar`, `CloakBar`, `ThreadPane`; none counts in `Redraws.PENDING`), drag and drop (`ThreadPane`, `Composer`), `FormatMenu.item`/`ChatMenus.item`, `VideoControls.frameName` vs `FileNames.safe`.
- `ChromiumCookies.readCopy` / `FirefoxCookies.readCopy`; `LocalServer.answers` / `Watchdog.answers`; `StylePrompt.accept` / `problem` (and `StyleLearner` reads a Spanish word from a log line).

## SOLID / KISS
- `ui/Composer` (736 lines): text, layout, voice recording, AI drafts and attachments. Move recording and the AI wiring out.
- `service/PreferencesFile` (725): flags, folders, `server-seen.json`, recovery and the background writer; the writer would also give `Settings` its async save.
- `matrix/MatrixChatService` (624): profiles, invites and the fetched-message cache beyond "lifecycle, sync and queries".
- `autosave/AutoSave` (538): live saving, exploring the past and folder moves.
- `ui/` is one flat package of 131 files: subpackages by area (timeline, media, emoji, settings, desktop) would let each carry its own `CLAUDE.md` instead of path rules.
- Dead code: `YtDlp.canInstall()`.
