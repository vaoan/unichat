# Folders and portable preferences: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chat folders in the rail, and preferences that live in files that can be copied and hold in a new installation.

**Architecture:** `service/PreferencesFile` is the source of truth, without JavaFX, indexed by the chat's identity on its network. `SyncProcessor` hands it what the server says and applies to the `Chat` what it answers. The UI only knows the new `ChatFolders` role.

**Tech Stack:** Java 21, JavaFX 23, Jackson, JUnit 5, TestFX.

**Spec:** `docs/superpowers/specs/2026-11-06-folders-and-preferences-design.md`

## Global Constraints

- UI text and docs in Spanish (as the repo was then); code, comments and commits in English.
- Java files < 800 lines and methods < 100 (Checkstyle). `ChatListPane` already has 723: what's new goes in classes of its own.
- The layers of `docs/ARCHITECTURE.md`: `service` may use `model`, `settings`, `login`; JavaFX only in `ui`.
- It must keep working on Windows: no POSIX permissions without checking that the file system supports them.
- No secret in `settings.properties` nor `preferences.json`; never print tokens in logs or tests.
- Tests never touch the real data dir (surefire sets `unichat.dataDir`).
- New controls: accessible text, reachable by keyboard, and they must hold at 160 % and high contrast.
- **No commits during the run unless the user asks.** There are 8 uncommitted files from other work (the LinkedIn login, `CLAUDE.md` included): don't mix them in.

## Review Focus

1. A folder name that's empty, only spaces, repeated or very long: empty is refused, repeated is allowed, long is cut with an ellipsis without breaking the column at 160 %.
2. The person is inside a folder and deletes it, or leaves a chat that was in it: back to the folder list without errors; the chat left stops showing, and its entry is kept.
3. A reminder already fired whose value is still on the server: it doesn't fire again after a restart.
4. A copied `preferences.json` with chats that don't exist yet, and then those chats arrive in a later `/sync` without `m.tag`: they get their preferences when they appear.
5. A full disk or no permission when saving: the app keeps working with what's in memory and the previous file stays intact.

---

### Task 1: Split session and secrets out of `settings.properties`

**Files:**
- Modify: `src/main/java/app/unichat/settings/Settings.java`
- Test: `src/test/java/app/unichat/settings/SettingsSplitTest.java`

**Interfaces:**
- Produces: `Settings` keeps its whole API. `static boolean Settings.staysOnThisMachine(String key)` (package).

- [ ] **Step 1: Write the failing tests** (the `Settings(Path)` constructor is package-private)

```java
@Test void machineKeysLiveApart() {
    var s = new Settings(dir);
    s.setMatrixSession("https://h", "@a:h", "TOKEN", "DEV");
    s.set("ui.theme", "DARK");
    s.set("gif.tenor.key", "K");
    s.set("discord.guilds.paused", "1");
    assertFalse(read("settings.properties").contains("TOKEN"));
    assertTrue(read("settings.properties").contains("ui.theme"));
    assertTrue(read("session.properties").contains("TOKEN"));
    assertTrue(read("session.properties").contains("gif.tenor.key"));
    assertEquals("TOKEN", new Settings(dir).accessToken());
}
@Test void anOldFileIsMigratedOnceAndTwiceIsHarmless() { /* settings.properties with matrix.accessToken and ui.theme; open twice:
    token only in session.properties, theme only in settings.properties, accessToken() right; settings.properties.docker-backup deleted */ }
@Test void aCrashBetweenWritesKeepsTheSession() { /* both files exist with the token in both: session.properties wins and the other is cleaned */ }
@Test void aFailedSaveKeepsThePreviousFile() { /* read-only dir (assumeTrue if it doesn't apply): set() doesn't throw, the file stays the same */ }
@Test void clearSessionEmptiesTheSessionFile() { }
```

- [ ] **Step 2: Run and watch them fail.** `./mvnw -q test -Dtest=SettingsSplitTest` → FAIL.

- [ ] **Step 3: Implement.** Two `Properties`; `getString` and `set` choose by key. `staysOnThisMachine`: the prefixes `matrix.`, `session.`, `server.`, `window.`, `discord.guilds.`; or it starts with `gif.` and ends with `.key`. Confirm the real names of those keys with `grep` (`DiscordServers`, `GifProviders`). Migration in the constructor: write `session.properties` first, then rewrite `settings.properties`. `session.properties` with 0600 if `PosixFileAttributeView` is available. Delete `settings.properties.docker-backup`.

- [ ] **Step 4: Run.** `./mvnw -q test -Dtest=SettingsSplitTest` → PASS.

### Task 2: `PreferencesFile`: rules, folders and disk

**Files:**
- Create: `src/main/java/app/unichat/service/PreferencesFile.java`, `src/main/java/app/unichat/service/ChatKeys.java`
- Test: `src/test/java/app/unichat/service/PreferencesFileTest.java`

**Interfaces:**
- Produces:

```java
public final class ChatKeys {
    /** "<protocol>:<channel>" when both are known, else "matrix:<roomId>". */
    public static String of(String protocol, String channelId, String roomId);
}
public final class PreferencesFile {
    public enum Flag { ARCHIVED, MUTED, FAVORITE, LOW_PRIORITY, PINNED }
    /** What the server says; null = nothing heard about it yet. */
    public record ServerValues(Boolean archived, Boolean muted, Boolean favorite, Boolean lowPriority, Boolean pinned,
                               Instant reminder, boolean reminderKnown) {}
    public record Effective(boolean archived, boolean muted, boolean favorite, boolean lowPriority, boolean pinned,
                            Instant reminder, boolean muteChosenHere) {}
    public record Folder(String id, String name, List<String> chatKeys) {}

    public PreferencesFile(Path preferences, Path serverSeen);
    public static PreferencesFile inMemory();
    /** The name the unreadable file was set aside under, or null. */
    public String setAside();
    public synchronized Effective reconcile(String key, ServerValues server);
    public synchronized Effective current(String key);
    public synchronized void choose(String key, Flag flag, boolean value);
    /** Set by a rule, not the person (a mute from the phone archives): the person hasn't touched it. */
    public synchronized void follow(String key, Flag flag, boolean value);
    public synchronized void chooseReminder(String key, Instant at);
    public synchronized void rekey(String oldKey, String newKey);
    /** Writes what reconcile changed, if anything. */
    public synchronized void flush();
    public synchronized List<Folder> folders();
    public synchronized String createFolder(String name);
    public synchronized void renameFolder(String id, String name);
    public synchronized void deleteFolder(String id);
    public synchronized void setInFolder(String folderId, String key, boolean in);
}
```

- [ ] **Step 1: Write the failing tests**, with these names and assertions:

```java
// k = "telegram:1"; on(...) = ServerValues with only that flag true and the rest false; unknown = all null
serverValueEntersWhenTheFileHasNothing:        reconcile(k, on(ARCHIVED)).archived() == true; the saved file has fromServer ["archived"]
followsThePhoneBothWaysUntilTouched:           on → true; then off (same server) → false
aNewServerDoesNotEraseWhatWasCopied:           A: reconcile on, flush. B: copy only preferences.json, a new seen. reconcile(off).archived() == true
aPersonsChoiceBeatsTheServer:                  choose(k, ARCHIVED, false); reconcile(on).archived() == false; and the same with true and off
choosingRemovesFromServerMarker:               on, choose(k, ARCHIVED, true): the file doesn't list fromServer; reconcile(off) stays true
explicitFalseSurvivesARestart:                 choose false; reload; reconcile(on).archived() == false
nothingHeardChangesNothing:                    choose true; reconcile(unknown).archived() == true; and with no entry, no entry created
followedValueIsNotAChoice:                     follow(k, ARCHIVED, true); reconcile(on) and then (off) → false
muteChosenHereIsReported:                      choose(k, MUTED, true): muteChosenHere true; on(MUTED): false
reminderImportedOnlyWhenAbsent:                reconcile with ts → that ts; chooseReminder(k, null); reconcile with ts → null, after a reload too
unknownChatEntriesAreKept:                     load a file with "whatsapp:9"; choose on another; save: "whatsapp:9" is still there
rekeyMovesEntryAndFolders:                     choose + setInFolder with "matrix:!a"; rekey to "telegram:1": current and the folder show it; rekey onto an existing key doesn't overwrite fields already chosen
aChatCanBeInSeveralFolders:                    two folders hold it; removing it from one leaves the other
folderNamesMustNotBeBlank:                     createFolder("  ") throws IllegalArgumentException; "A" twice gives two folders; the name is saved without spaces at the ends
deletingAFolderKeepsTheChatsPrefs:             choose + setInFolder + deleteFolder: current doesn't change
damagedFileIsSetAsideNotOverwritten:           "{broken" → setAside() not null, that file keeps its contents; starts empty; a change writes a valid preferences.json
unknownVersionIsSetAside:                      {"version":99} → the same
aFailedWriteKeepsTheOldFileAndMemory:          read-only folder (assumeTrue): choose doesn't throw; current shows the change; the file stays the same
flushWritesOnlyWhenSomethingChanged:           two flushes with no changes don't alter the file's date
chatKeys:                                      of("telegram","1","!r") == "telegram:1"; of(null,"1","!r") == "matrix:!r"; of("telegram",null,"!r") == "matrix:!r"
```

- [ ] **Step 2: Run and watch them fail.** `./mvnw -q test -Dtest=PreferencesFileTest` → FAIL.

- [ ] **Step 3: Implement.** The spec's exact format, section `preferences.json`; JSON fields `archived, muted, favorite, lowPriority, pinned, reminderAt, fromServer`. `"reminderAt": null` means "touched". The `reconcile` rule, for each flag with a non-null server value:

```text
if server == true: seen.add(key, flag)
touched = the field is in the file and not in fromServer
if touched: ignore
if server == true: value = true, in fromServer
if server == false and seen.contains(key, flag): value = false, in fromServer; seen.remove(key, flag)
```

Atomic write (a temporary file and `ATOMIC_MOVE`); disk errors are logged with `System.err` without throwing. `choose`, `chooseReminder` and the folders save at once; `reconcile` only marks pending until `flush`. `rekey` keeps what was already chosen at the target. `inMemory()` doesn't touch the disk.

- [ ] **Step 4: Run.** `./mvnw -q test -Dtest=PreferencesFileTest` → PASS.

### Task 3: Connect Matrix to the file

**Files:**
- Modify: `matrix/MatrixRoom.java`, `matrix/SyncProcessor.java`, `matrix/ChatActions.java`, `matrix/MatrixChatService.java`, `model/Chat.java`
- Test: `src/test/java/app/unichat/matrix/PreferencesSyncTest.java`

**Interfaces:**
- Consumes: `PreferencesFile`, `ChatKeys` (Task 2).
- Produces: `Chat.prefKey()` / `setPrefKey(String)`. `SyncProcessor.setPreferences(PreferencesFile)` (`inMemory()` by default, so the existing tests don't change). `SyncProcessor.mutesToApply()` returns a `Map<String, Boolean>` of room to mute.

- [ ] **Step 1: Write the failing tests**, with `/sync` in JSON as in `SyncProcessorTest`:

```java
tagsOnlyEnterThroughTheFile:           m.tag with m.favourite → chat.favorite(); after choose(key, FAVORITE, false) and another identical m.tag → false
prefKeyUsesTheBridgeIdentity:          a room with uk.half-shot.bridge protocol telegram, channel 7 → prefKey "telegram:7"
sameChatOnAnotherServerGetsItsPrefs:   a file with "telegram:7" archived; a new proc with a room of another id and channel 7 → chat.archived()
aChatThatArrivesLaterGetsItsPrefs:     the first /sync without that room; the next brings it without m.tag → archived
bridgeStateArrivingLaterRekeys:        a room without m.bridge + choose; the next /sync with m.bridge → the entry is already under the new key
noAccountDataChangesNothing:           a file with archived, a /sync without the room's account_data → still archived
aMuteChosenHereIsAppliedToTheServer:   the file's mute chosen, no rule on the server → mutesToApply has room → true; with the rule already there → empty
aMuteFromThePhoneIsNotPushedBack:      a rule in m.push_rules with no entry → chat.muted() and mutesToApply empty
```

- [ ] **Step 2: Run and watch them fail.** `./mvnw -q test -Dtest=PreferencesSyncTest` → FAIL.

- [ ] **Step 3: `SyncProcessor`.** `applyRoomAccountData` and `m.push_rules` stop writing to `Chat`: they keep the raw values in `MatrixRoom` (null until heard, and `lastPrefKey`). At the end of `process`, for each room: work out the key with `ChatKeys.of(room.bridgeProtocol, room.bridgeChannelId, room.id)`, `rekey` if it changed, `reconcile`, apply `Effective` to `Chat` and `setPrefKey`, then `flush` once. `setMutedLocal` stops being a way to change a preference. `mutesToApply`: rooms with `muteChosenHere` whose value differs from the rule heard; a room is left out while its write is under way.

- [ ] **Step 4: `ChatActions`.** `setFavorite`, `setLowPriority`, `setPinned`, `archiveOnly`, `muteOnly`, `setReminder` no longer call `setTag`/`putRoomAccountData`: `prefs.choose…`, apply to `Chat`, `core.fire(chatsChanged)`, a completed future. `muteOnly` also writes the rule to the server; if that fails, it doesn't revert (it's retried through `mutesToApply`). Remove `optimistic` if it's left unused; `setMarkedUnread` and `setGuildPaused` don't change.

- [ ] **Step 5: `MatrixChatService`.** Create `PreferencesFile(dataDir/preferences.json, dataDir/server-seen.json)` and give it to `proc` and `chatActions`. After each `proc.process`, apply `mutesToApply`. The `archiveOnly(id, true)` of `mutedElsewhere` becomes `prefs.follow(key, ARCHIVED, true)`. Confirm with `grep` that `Notifier` and `NotificationPolicy` decide by `Chat.muted`/`archived`.

- [ ] **Step 6: Run.** `./mvnw -q test -Dtest='PreferencesSyncTest,SyncProcessorTest,SpaceSyncTest,RoomFeaturesSyncTest'` → PASS. If an existing test asserted writes to Matrix, update it and say so in the task's report.

### Task 4: The `ChatFolders` role in both services

**Files:**
- Create: `service/ChatFolders.java`, `service/FolderActions.java`
- Modify: `service/ChatService.java`, `service/MockChatService.java`, `matrix/MatrixChatService.java`
- Test: `src/test/java/app/unichat/service/FoldersServiceTest.java`

**Interfaces:**
- Consumes: `PreferencesFile`, `Chat.prefKey()`.
- Produces:

```java
public interface ChatFolders {
    List<PreferencesFile.Folder> folders();
    String createFolder(String name);
    void renameFolder(String id, String name);
    void deleteFolder(String id);
    void setInFolder(String folderId, Chat chat, boolean in);
    /** Message for the person if the preferences file was unreadable and set aside; else null. */
    String preferencesWarning();
}
```

`FolderActions`: `new FolderActions(PreferencesFile, Runnable fireChatsChanged)`, implements `ChatFolders`; each service delegates to it. Each change tells with `chatsChanged`.

- [ ] **Step 1: Write the failing tests** on `MockChatService` with a temporary `unichat.dataDir`: `createFolder` tells a `Listener`; `setInFolder` leaves the chat's key; a folder survives re-creating the service; `setFavorite` in the demo is kept in the file and survives re-creating the service; `preferencesWarning()` is not null with a damaged file and contains its name.

- [ ] **Step 2: Run and watch them fail.** `./mvnw -q test -Dtest=FoldersServiceTest` → FAIL.

- [ ] **Step 3: Implement.** `MockChatService` uses the same `PreferencesFile` and sets `prefKey` = `"matrix:" + id`; its setters go through `choose` and apply `current` when loading the demo chats. `ChatService` adds the `ChatFolders` role.

- [ ] **Step 4: Run.** `./mvnw -q test -Dtest=FoldersServiceTest` → PASS, then `./mvnw -q test -Dtest='ArchitectureTest'` → PASS.

### Task 5: The folders UI

**Files:**
- Create: `ui/FolderView.java`, `ui/FolderRows.java`, `ui/FolderDialogs.java`
- Modify: `ui/Rail.java`, `ui/ChatListPane.java`, `ui/ChatMenus.java`, `ui/Icons.java`, `ui/MainView.java`, `css/app.css`
- Test: `src/test/java/app/unichat/ui/FolderRowsTest.java`, `src/test/java/app/unichat/ui/FoldersUiTest.java`

**Interfaces:**
- Consumes: `ChatFolders` (Task 4).
- Produces: `ChatListPane.showFolders()`, `ChatListPane.foldersOpen()`. `FolderRows.chatsIn(Folder, List<Chat>) -> List<Chat>`, `FolderRows.unread(Folder, List<Chat>) -> long`. `FolderView(ChatFolders, Supplier<ListCell<Chat>> cells, Consumer<Chat> open)`, because `ChatCell` is internal to `ChatListPane`.

- [ ] **Step 1: Write the failing tests.** `FolderRowsTest`: keeps archived chats; includes neither absent chats nor bridge bots; order pinned first and then activity, as `InboxRows:53`; `unread` uses `ChatFilter.inboxUnread`. `FoldersUiTest` (`-Dunichat.uiTests=true`, runs with `-Pheadless-ui`):

```java
theFoldersButtonSitsRightAboveSettings:   in Rail, the index of the "Folders" button == the index of "Settings" - 1
clickingShowsNamesAndClickingANameGoesIn: create folders, press: the names show; press one: its chats and the title show; the folder list no longer shows
goingBackReturnsToTheNames:               the back button and Esc too
openingAChatStaysInTheFolder:             open a chat: the thread shows it and we're still inside
aChatCanBeInTwoFolders:                   setInFolder on two; it shows in both
deletingTheOpenFolderGoesBackToTheNames:  with no exception
leavingAChatInsideAFolderJustHidesIt:     leaveChat: it stops showing; the key stays in the folder
searchingLeavesTheFolder:                 typing in the search shows the normal results
theInboxButtonLeavesFolders:              pressing Inbox closes folders; Folders marked active only inside
menuTogglesMembership:                    ChatMenus has "Folders" with each one checked; bots and the personal space don't offer it
```

- [ ] **Step 2: Run and watch them fail.** `./mvnw -q verify -Pheadless-ui -Dtest='FolderRowsTest,FoldersUiTest' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implement.** As the spec's UI section says, with these texts: tooltip `Folders`; button `New folder`; dialog `Folder name`; empty `You have no folders yet. Make one and add chats with a right click.`; actions `Rename`, `Delete`; confirmation `Delete the folder "X"? The chats aren't deleted.`; submenu `Folders` with `New folder…`; inside a folder `Remove from this folder`. A `CheckMenuItem` for each folder. The button goes after `Rail`'s `grow` and before `settings`, with no count. `FolderView` is added to `ChatListPane` like the `spaceView`, with the same visibility switch. When opening a space from a folder, use the existing `spaceView`. Long names with an ellipsis. `MainView`: a `Show folders` command in `CommandBar`, and at start, if `preferencesWarning()` isn't null, show it with `toast`. Add a `-Dunichat.panel`-style `-Dunichat.openFolders=true` for the snapshots.

- [ ] **Step 4: Run.** The command of Step 2 → PASS.

- [ ] **Step 5: Check visually.** In a temporary directory with `session.backend=demo` and a `preferences.json` with two folders: `JAVA_OPTS="-Dunichat.dataDir=<dir> -Dunichat.openFolders=true -Dunichat.snapshot=out.png" ./run-dev.sh`; read the image. Again in the light theme, dark, 160 % and high contrast, and inside a folder.

### Task 6: Document, verify and install

**Files:**
- Modify: `CLAUDE.md`, `docs/ARCHITECTURE.md`, `README.md` if it describes where the preferences live

- [ ] **Step 1: Document.** In `CLAUDE.md`, which file keeps what, what is copied and what isn't, the `server-seen.json` rule and the `ChatFolders` role.

- [ ] **Step 2: Full verification.** `./mvnw -q verify -Pheadless-ui` → exit 0. Quote the real result; `AutoScrollUiTest` and other known flaky tests are reported as such, not hidden.

- [ ] **Step 3: A backup before migrating the real data.** The migration touches the person's token: copy `settings.properties` and `media-library.json` to `~/Library/Caches/unichat-prefs-backup-<date>/` with 0700 permissions, without printing their contents.

- [ ] **Step 4: Install.** `TUWUNEL_BIN=$HOME/Library/Caches/unichat-build/tuwunel/target/release/tuwunel ./install-mac.sh`, then `open /Applications/Unichat.app` (the installer doesn't start it) and turn the dump back on with `jinfo`. Installing without `TUWUNEL_BIN` leaves the app without a server.

- [ ] **Step 5: Check with the real data, without printing values.** Bridges running; window visible; the session still active; `grep -c accessToken settings.properties` gives 0; `preferences.json` was created; the number of chats with `"archived": true` matches the ones archived before; no archived chat came back to the inbox. Ask the person to make a folder and confirm it survives a restart.
