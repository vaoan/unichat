# Load older chats — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (or superpowers:executing-plans inline). Steps use checkbox (`- [ ]`) syntax.

**Goal:** a "Load older chats" button at the end of the inbox list. Each click opens a warning sheet, then asks the bridges of the networks in view, one at a time, for older chats they haven't brought into Matrix. Progress shows in a `TaskBar` on top, and the button hides when no network in view has more.

**Architecture:** `bridge/ChatLimits` holds what the person asked for. `server/BridgeConfig` writes it into each bridge's config, and `BridgeProvisioner.reconfigure` restarts one bridge and waits for it. `matrix/OlderChatSteps` decides each network's step (pure). `matrix/OlderChatLoader` runs them one network at a time and counts the new rooms with `matrix/RoomArrivals`. All of it sits behind the `service/OlderChats` role. In `ui/chats`: the button in `ChatListPane`, `OlderChatsSheet` in the overlay, and `OlderChatsBar` on top. WhatsApp's history chats come through a Unichat patch, `native/patches/whatsapp/sync-history-chats.patch`.

**Tech Stack:** Java 25, JavaFX 27, JUnit 5 with headless UI tests (`-Pheadless-ui`), Go (the bridge patch), GitHub Actions (`natives.yml`).

**Spec:** `docs/superpowers/specs/2026-11-24-older-chats-design.md`. Read it whole: its network table and its error table are the contract.

## Global Constraints
- **Only on demand.** Nothing in this feature runs by itself: no step at start, on login, on sync, after a failure or on a timer. Only `OlderChats.load`, which only the sheet's "Load" calls. The existing one-time Telegram resync (`BridgeAccounts.maybeResyncChats`, keyed on `telegram.chatSyncLimit`) is left exactly as it is, and the new settings never trigger it.
- **Each network's step is the spec's table, exactly:**
  - Telegram: +100, with `create_limit` and `update_limit` both raised, then a restart, then `sync-chats`.
  - WhatsApp: `sync groups` once, then `sync-history-chats 100`, with no restart.
  - Discord: +50 `startup_private_channel_create_limit`, a restart, and 10 minutes between steps.
  - LinkedIn: `create_limit` 0, then a restart.
  - Google Messages: `initial_chat_sync_count` 500, once.
  - Instagram: `batch_count` 3, once, only when ticked in the sheet.
  - Facebook and X: nothing more.
  - `max_initial_messages` stays 50.
- **Instagram is off unless ticked** in the sheet on that click. No other way runs it.
- **One bridge at a time.** At most one run of `load`, and inside it one network at a time. A restart touches only that network's bridge.
- **A bridge is never left broken.** A missing key refuses before stopping. A bridge that doesn't come back gets its old setting back and one more restart.
- **No bridge database is written.**
- **Repo rules** (`CLAUDE.md`, `src/test/CLAUDE.md`):
  - every fix and feature has a test that fails without it (watch it fail);
  - tests wait on what the code does, never on time (`.claude/lessons.tsv` catches sleeps and timeouts);
  - no test points at the real data dir;
  - files under 800 lines, methods under 100;
  - `ArchitectureTest`: `ui` → `service` only, with JavaFX only in `ui`, and `bridge` uses nothing in the project;
  - every text through `I18n`, in both `messages*.properties` (`I18nTest`), English in code (`EnglishFirstTest`);
  - accessibility: keyboard reach, accessible texts, 160 %, high contrast.
- **Nothing on the person's screen.** UI tests only with `-Pheadless-ui`. Snapshots only with the `snapshot` skill (headless).
- **Mutations** (breaking code to watch a test fail) only in a throwaway worktree: `git worktree add <scratchpad>/<name> HEAD`, copy the change, break it, run, `git worktree remove`. Never in the working tree.
- **Commands** (foreground, `JAVA_HOME=/opt/homebrew/opt/openjdk@25`):
  - one test class: `./mvnw -q test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false`;
  - UI tests: the same with `-Pheadless-ui`;
  - before each commit: `./mvnw -q verify -DskipTests` and `bash .claude/hooks/java.sh Lessons <changed files>`.
  The controller runs the full `./mvnw verify -Pheadless-ui` at the end.
- **Commits:** one per task, conventional (`fix:`, `feat:`, `build:`, `docs:`), ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus
1. **Instagram never runs unless ticked.** Task 8 `instagramIsOffByDefaultAndRunsOnlyWhenTicked`; Task 5 `instagramRunsOnlyWhenIncluded`.
2. **Nothing runs by itself.** Task 5 `nothingRunsWithoutBeingAsked`; Task 4 `noPlanTriggersTheTelegramResync`.
3. **Telegram past 100 really asks for more.** Task 1 `telegramsUpdateLimitFollowsItsCreateLimit`; Task 2 `telegramsExtraRaisesBothLimits`.
4. **A bridge is never left broken.** Task 3 `aLimitKeyMissingRefusesBeforeStopping`; Task 5 `aBridgeThatDoesntComeBackGetsItsOldLimitBack`.
5. **No notification storm.** Task 6 `aChatLoadedWithItsHistoryDoesntNotify`.
6. **Discord restarts stay rare.** Task 5 `aSecondDiscordStepWithinTenMinutesDoesntRestart`.
7. **One-shot limits go back.** Google Messages and Instagram: Task 2 `oneShotLimitsApplyOnlyWhileSet`; Task 5 `oneShotSettingsAreClearedWhenTheirStepEnds`.
8. **Cancel does nothing.** Task 8 `cancelIsFocusedAndRunsNothing`.

---

### Task 1: Telegram's `update_limit` follows `create_limit` (the bug)
The bug is that only `create_limit` is raised. `update_limit` stays at the bridge's default of 100 and stops the walk, so any limit above 100 does nothing.

**Files:**
- `src/main/java/app/unichat/server/BridgeConfig.java` (the `telegram` case)
- `src/test/java/app/unichat/server/BridgeConfigTest.java`
- `src/main/java/app/unichat/server/CLAUDE.md` (Lessons)

**Interfaces:** none new.

The steps:
- [ ] Write `BridgeConfigTest.telegramsUpdateLimitFollowsItsCreateLimit`: a Telegram template with `network.sync.create_limit: 15`, `update_limit: 50` and `login_sync_limit: 15`, applied, gives `create_limit == update_limit == BridgePorts.TELEGRAM_CREATE_LIMIT` (100). Today `update_limit` stays 50, so the test fails.
- [ ] Run it and watch it fail: `./mvnw -q test -Dtest=BridgeConfigTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Fix: `setExisting(c, "/network/sync/update_limit", …)` with the same value as `create_limit`, and a comment saying why (the walk stops after `update_limit` dialogs).
- [ ] Add a Lessons line to `server/CLAUDE.md`: Telegram's chats past 100 never came, because `create_limit` went up and `update_limit` (default 100) stopped the walk first; raise both, as `BridgeConfigTest.telegramsUpdateLimitFollowsItsCreateLimit` checks. No `lessons.tsv` row: the mistake is a line that's *missing*, which no per-line pattern sees. Say so in the commit body.
- [ ] Commit: `fix(server): Telegram's update_limit follows create_limit, so chats past 100 can come`.

### Task 2: `ChatLimits`, written into each bridge's config
**Files:**
- New: `src/main/java/app/unichat/bridge/ChatLimits.java`
- `src/main/java/app/unichat/server/BridgeConfig.java` (`Settings` gains `ChatLimits limits`; the old constructors pass `ChatLimits.NONE`)
- `src/main/java/app/unichat/server/BridgeInstaller.java` (reads `Settings.get()` → `ChatLimits.parse`)
- `src/main/java/app/unichat/settings/Settings.java` (`staysOnThisMachine`: the `olderChats.` prefix)
- Tests: new `src/test/java/app/unichat/bridge/ChatLimitsTest.java`, `BridgeConfigTest`, `BridgeInstallerTest`, `SettingsTest`

**Interfaces:**
```java
// bridge: uses nothing in the project
public record ChatLimits(int telegramExtra, int discordExtra, boolean linkedinAll,
                         boolean gmessagesOnce, boolean instagramOnce) {
    public static final ChatLimits NONE = new ChatLimits(0, 0, false, false, false);
    public static final int TELEGRAM_STEP = 100, DISCORD_STEP = 50, DISCORD_BASE = 50,
            GMESSAGES_ALL = 500, INSTAGRAM_PAGES = 3;
    /** Keys: olderChats.telegram.extra, olderChats.discord.extra, olderChats.linkedin.all,
     *  olderChats.gmessages.once, olderChats.instagram.once. Unreadable or negative → that field's default. */
    public static ChatLimits parse(Function<String, @Nullable String> get);
    public int telegramLimit();   // TELEGRAM_CREATE_LIMIT + telegramExtra
    public int discordLimit();    // DISCORD_BASE + discordExtra
}
```
`ChatLimits` uses `BridgePorts.TELEGRAM_CREATE_LIMIT`; both are in `bridge`, so the "uses nothing in the project" rule holds.

The tests (write all of them, watch each fail, then implement):
- `ChatLimitsTest.readsWhatWasSaved`: a map holding the five keys → each field. `nothingSavedIsNone`: an empty map → `NONE`. `garbageOrNegativeIsTheDefault`: `"abc"` and `"-5"` → 0, `"maybe"` → false.
- `BridgeConfigTest.telegramsExtraRaisesBothLimits`: `telegramExtra` 200 → `create_limit` and `update_limit` are 300, and `login_sync_limit` stays 100.
- `BridgeConfigTest.discordsPrivateChannelLimitIsFiftyPlusWhatWasAsked`: extra 0 → 50, extra 100 → 150.
- `BridgeConfigTest.linkedInCreatesEveryChatOnceAskedFor`: `linkedinAll` → `network.sync.create_limit` is 0; without it, the template's 10 is untouched.
- `BridgeConfigTest.oneShotLimitsApplyOnlyWhileSet`:
  - `gmessagesOnce` → `network.initial_chat_sync_count` 500; without it, the template's value is untouched;
  - `instagramOnce` → `network.thread_backfill.batch_count` 3; without it, 0.
- `BridgeConfigTest.instagramWalksNoInboxPagesByDefault`: a template with `batch_count: -1` comes out as 0 (spec: the anti-goal of an inbox walk at every login).
- `BridgeConfigTest.aMissingLimitKeyIsReported`: `BridgeConfig.missingLimitKeys(config, "discord")` names `/bridge/startup_private_channel_create_limit` when it's absent, and is empty when it's present. One list per bridge, the same paths `apply` writes.
- `BridgeInstallerTest.theChatLimitsSavedReachTheConfig`: with `olderChats.discord.extra=50` in a temp `Settings`, `prepare("discord", …)` writes 100 into its `config.yaml` (temp dir, `FakeService`).
- `SettingsTest.olderChatsStayOnThisMachine`: `staysOnThisMachine("olderChats.telegram.extra")` is true.

The steps:
- [ ] Write the tests and watch them fail.
- [ ] Implement. Use `setExisting` for every limit, so a renamed key is reported and never added. Add a comment for each key saying why it is set and who raises it.
- [ ] Commit: `feat(server): each bridge's chat limits follow what the person asked for`.

### Task 3: `BridgeProvisioner.reconfigure`: one bridge restarted, and waited for
**Files:**
- `src/main/java/app/unichat/matrix/BridgeProvisioner.java` (a new default method)
- `src/main/java/app/unichat/server/BridgeLauncher.java` (`reconfigure`: missing-key check, `applySettings`, wait for the answer as `open` does)
- `src/main/java/app/unichat/server/LocalServer.java` (the provisioner it hands `matrix`)
- `src/main/java/app/unichat/UnichatApp.java` (or wherever the provisioner is wired, if it needs a change)
- Tests: `BridgeLauncherTest`, `WatchdogTest`

**Interfaces:**
```java
// matrix/BridgeProvisioner
/** Rewrites network's config from the settings and restarts its bridge only; returns once it answers.
 *  Throws before stopping anything when a limit key is missing (IOException, message in the person's words),
 *  and when the bridge doesn't answer within 60 s (BridgeDidntAnswer extends IOException). */
default void reconfigure(Network network, Consumer<String> progress) throws Exception {
    throw new UnsupportedOperationException("reconfigure");
}
```

The tests:
- `BridgeLauncherTest.reconfiguringRestartsOnlyThatBridgeAndWaitsForItsAnswer`: the fake processes record `prepare(discord)`, `stop(discord)`, `restarting(discord)`, `start(discord)`, in that order, and nothing for any other bridge. The call returns only after the fake probe answers (a latch the test releases).
- `BridgeLauncherTest.aLimitKeyMissingRefusesBeforeStopping`: with a config missing the key, `reconfigure` throws and the fake recorded no `stop`.
- `BridgeLauncherTest.aBridgeThatDoesntAnswerAfterReconfiguringIsReported`: the fake probe says "stopped running", so `reconfigure` throws `BridgeDidntAnswer`. Use the "not at all once it stopped running" path, not a 60 s wait.
- `BridgeLauncherTest.aBridgeNotMeantToRunIsntStartedByReconfigure`: a bridge without accounts throws and isn't started.
- `WatchdogTest.aReconfiguredBridgeIsntCountedAsFlapping`: after `restarting(b)`, the bridge's stop and start don't add to its restart backoff. If the watchdog already ignores them, this test passes at once; keep it as a guard and say so.

The steps:
- [ ] Write the tests and watch them fail.
- [ ] Implement them on top of `applySettings`, with no new process code.
- [ ] Commit: `feat(server): reconfigure one bridge and wait for it to answer`.

### Task 4: `service/OlderChats` and `matrix/OlderChatSteps` (what a click would do)
**Files:**
- New: `src/main/java/app/unichat/service/OlderChats.java` (the spec's interface, word for word)
- `src/main/java/app/unichat/service/ChatService.java`: don't add it there. The UI asks `svc instanceof OlderChats`, as with `BridgeAccess`.
- New: `src/main/java/app/unichat/matrix/OlderChatSteps.java` (pure)
- Tests: new `src/test/java/app/unichat/matrix/OlderChatStepsTest.java`

**Interfaces:**
```java
final class OlderChatSteps {
    enum Kind { RAISE_RESTART_COMMAND, COMMAND, RAISE_RESTART, NOTHING }
    /** One network's next step: how to run it, the setting it writes (key → value), the command if any, its Plan. */
    record Step(Network network, Kind kind, Map<String, String> settings, @Nullable String command, Duration commandPatience,
                OlderChats.Plan plan) {}
    /** settings: Settings.get()::getString in production, a map in tests; accounts: networks with a login; now: the clock. */
    OlderChatSteps(Function<String, @Nullable String> settings, Set<Network> withAccounts, Supplier<Instant> now);
    /** The next step of n, or the Answer when there's none (NothingMore, NotPossible). */
    sealed interface Next { record Run(Step step) implements Next {} record Done(OlderChats.Answer answer) implements Next {} }
    Next next(Network n);
    List<Network> inViewOrder(Collection<Network> inView);  // Telegram, WhatsApp, Discord, LinkedIn, Google Messages, Instagram
}
```

The tests (one per row of the spec's table, plus the edges):
- `telegramAsksForAHundredMore`: extra 0 → a step with kind `RAISE_RESTART_COMMAND`, setting `olderChats.telegram.extra=100`, command `sync-chats`, patience 10 min, and plan `chats=100, restarts=true`.
- `whatsappAsksGroupsFirst`: kind `COMMAND`, command `sync groups`, plan `chats=-1, restarts=false`. `whatsappThenAsksAHundredHistoryChats`: with `olderChats.whatsapp.groups=done` → command `sync-history-chats 100`, `chats=100`.
- `discordAsksFiftyMoreAndSaysItReconnectsEveryServer`: setting `extra=50`, plan `chats=50, restarts=true, reconnectsServers=true`.
- `discordWaitsTenMinutesBetweenSteps`: `olderChats.discord.at` set 3 minutes ago → plan `waitFirst=7 min`, and `next` gives no runnable step. At 10 minutes it does.
- `linkedinAndGoogleMessagesAskForEverythingOnce`: plans with `chats=-1`. After `olderChats.<net>.done`, they give `NothingMore`.
- `instagramIsRiskyAndOneShot`: plan `risky=true, chats=-1`, setting `olderChats.instagram.once=true`.
- `facebookAndXHaveNothingMore`: `NothingMore`, and they're left out of the plans.
- `aNetworkWithoutAnAccountIsNotPossible`: not in `withAccounts` → `NotPossible`, left out of the plans.
- `aNetworkThatLoadedNothingHasNothingMore`: `olderChats.telegram.done=true` → `NothingMore`, and `hasMore` is false for `{TELEGRAM}`.
- `hasMoreIsTrueWhileAnyNetworkInViewHasAStep`: `{FACEBOOK, TELEGRAM}` is true; `{FACEBOOK, TWITTER}` is false.
- `theOrderIsFixed`: `inViewOrder({INSTAGRAM, TELEGRAM, DISCORD})` → Telegram, Discord, Instagram.
- `noPlanTriggersTheTelegramResync`: building steps and plans never writes `telegram.chatSyncLimit` (the settings function is read-only; assert no write through a recording map).

The steps:
- [ ] Write the tests and watch them fail.
- [ ] Implement.
- [ ] Commit: `feat(matrix): what a click on "Load older chats" would do for each network`.

### Task 5: `RoomArrivals` and `OlderChatLoader` (running a click)
**Files:**
- New: `src/main/java/app/unichat/matrix/RoomArrivals.java`, `src/main/java/app/unichat/matrix/OlderChatLoader.java`
- `src/main/java/app/unichat/matrix/MatrixChatService.java` (`implements OlderChats`, delegating; and telling `RoomArrivals` about rooms from `dispatch`)
- `src/main/java/app/unichat/matrix/BridgeAccounts.java` (only if `OlderChatLoader` needs its `bots.query` and `requireSuccess`; prefer passing a narrow function in)
- Tests: new `src/test/java/app/unichat/matrix/RoomArrivalsTest.java`, new `src/test/java/app/unichat/matrix/OlderChatLoaderTest.java`, and one wiring test in `MatrixServiceDirectTest` (or the nearest existing one)

**Interfaces:**
```java
final class RoomArrivals {
    /** rooms: the network's room ids now (bridge-bot rooms excluded); later: schedules a check (tests run it by hand). */
    RoomArrivals(Function<Network, Set<String>> rooms, Supplier<Instant> now, BiConsumer<Duration, Runnable> later);
    Counting start(Network n);                 // snapshot of the rooms now
    interface Counting { int newRooms(); CompletableFuture<Integer> whenQuiet(Duration quiet, Duration max); }
    void roomsChanged();                        // from MatrixChatService.dispatch when chatsChanged
}

final class OlderChatLoader {
    interface Bot { String query(Network n, String command, Duration patience) throws Exception; }
    OlderChatLoader(OlderChatSteps steps, Bot bot, BridgeProvisioner provisioner, RoomArrivals arrivals,
                    SettingsWriter settings, Executor io);
    CompletableFuture<Map<Network, OlderChats.Answer>> load(List<Network> networks, Set<Network> included);
    @Nullable OlderChats.Loading loading();
    void addListener(Runnable l); void removeListener(Runnable l);
}
```
`included` carries the sheet's choice: Instagram runs only if it's in `included`. Quiet 45 s, max 15 min. Constants in `OlderChatLoader`.

The fakes, in the test folder:
- `FakeBot`: records each command, and answers from a script per command ("Successfully…", "Unknown command", an exception, or a future that never completes).
- `FakeProvisioner`: records each `reconfigure` with the settings at that moment, and can throw `missing key` or `BridgeDidntAnswer`.
- A room map the test adds ids to.
- A manual clock and `later` queue the test advances by hand.
- No real time anywhere.

The tests:
- `RoomArrivalsTest.countsOnlyThatNetworksNewRooms`: rooms already there, and another network's new rooms, don't count.
- `RoomArrivalsTest.quietAfterFortyFiveSecondsWithoutANewRoom`: the future completes when the clock passes 45 s since the last room, not before.
- `RoomArrivalsTest.stopsAtFifteenMinutesWhileRoomsKeepComing`.
- `OlderChatLoaderTest.telegramRaisesBothLimitsRestartsThenSyncsChats`:
  - the order is setting `extra=100` written, then `reconfigure(TELEGRAM)` (and the provisioner saw `extra=100`), then `sync-chats` with 10 min patience;
  - 30 rooms added, then quiet, gives `Loaded(30)`.
- `whatsappSendsSyncGroupsWithoutARestart`: no `reconfigure`. Command `sync groups`, then `olderChats.whatsapp.groups=done`.
- `whatsappAsksTheHistoryChatsOfThePatch`: command `sync-history-chats 100`.
- `whatsappWithoutThePatchIsNotPossible`: the bot answers "Unknown command…", giving `NotPossible` with the "needs the next update" reason. Nothing is marked done, so the patched build can still be asked later.
- `discordRaisesFiftyAndRestartsOnce`: exactly one `reconfigure`. `olderChats.discord.at` is set to the clock.
- `aSecondDiscordStepWithinTenMinutesDoesntRestart`: a second `load([DISCORD])` 5 minutes later → `NotPossible` (the wait), and no second `reconfigure`.
- `instagramRunsOnlyWhenIncluded`: `load([INSTAGRAM, TELEGRAM], included={TELEGRAM})` → no Instagram reconfigure, and Instagram's answer is absent. With `included` holding Instagram, it runs.
- `oneShotSettingsAreClearedWhenTheirStepEnds`: after Google Messages' and Instagram's steps (any answer, the error ones included), `olderChats.<net>.once` is removed, and `.done` is set on success.
- `noNewRoomsMeansNothingMore`: 0 rooms, then quiet → `NothingMore`, `olderChats.telegram.done=true`, `hasMore({TELEGRAM})` false.
- `networksRunOneAtATime`: with Telegram's `reconfigure` held on a latch, no Discord call is recorded. Released, Discord runs after Telegram's answer.
- `aSecondClickWhileRunningIsIgnored`: a second `load` while one runs returns the same future and records nothing new.
- `aBridgeThatDoesntComeBackGetsItsOldLimitBack`: `reconfigure` throws `BridgeDidntAnswer` the first time, so the setting is back to its old value and a second `reconfigure` happened with it. The answer is `NotPossible` ("didn't come back; the old setting is back").
- `aMissingKeyIsNotPossibleAndChangesNothing`: `reconfigure` throws the missing-key error, so the setting is back to its old value, there's no second reconfigure, and the answer is `NotPossible`.
- `aCommandThatTimesOutAnswersWhatArrived`: the bot throws a timeout after 12 rooms arrived → `Loaded(12)`.
- `aStepThatFailsDoesntStopTheNextNetwork`: Telegram's command throws, and Discord still runs.
- `progressSaysTheNetworkStageAndNOfM`: `loading()` goes through `RESTARTING`, `ASKING`, `ARRIVING` (`newChats` rising), then `DONE`, with `done/total` 1/2 then 2/2. Listeners are told each time.
- `nothingRunsWithoutBeingAsked`: build the loader, the steps, `RoomArrivals` and the service. Run `roomsChanged`, a login refresh and a `dispatch` → no command, no reconfigure, no setting written.
- Wiring: `MatrixServiceDirectTest.olderChatsAreReachedThroughTheService`: `svc instanceof OlderChats`, and `plans` and `load` reach the loader (with fakes).

The steps:
- [ ] Write the tests and watch them fail.
- [ ] Implement:
  - writes go through `Settings.get().set`, and removals through its remove;
  - the provisioner is the static one `BridgeAccounts` already has, or one passed in;
  - every log line in English, and no chat names in logs.
- [ ] Commit: `feat(matrix): load older chats one network at a time, with an honest answer each`.

### Task 6: Old chats arriving don't notify (verify the 5-minute rule, and lock it)
**Files:**
- `src/test/java/app/unichat/matrix/NotifyGateTest.java`
- one service-level test next to the sync tests (e.g. new `src/test/java/app/unichat/matrix/OlderChatsNotifyTest.java`, with the fake homeserver `StartupBeforeSyncTest` and `RestServer` use)
- `src/main/java/app/unichat/matrix/CLAUDE.md` (a line: why old chats don't notify)

**Interfaces:** none. This is a check. If a test fails, the fix goes in `NotifyGate` or `dispatch`, and a Lessons line goes with it.

The tests:
- `NotifyGateTest.aChatLoadedWithItsHistoryDoesntNotify`: a chat never seen, and 50 messages timed from 6 minutes to 4 years ago → `isNew` is false for every one.
- `NotifyGateTest.aRecentMessageInANewlyLoadedChatStillNotifies`: the same chat, plus one message 1 minute old → true once.
- `OlderChatsNotifyTest.aRoomJoinedWithBackfillFiresNoIncomingMessage`: a live sync (`notify=true`) brings an invite. It's joined, and the next sync brings the room with 50 messages hours to years old → no `incomingMessage` reaches a listener. `chatsChanged` fires.
- `OlderChatsNotifyTest.theBridgeBotsReplyDoesntNotify`: "Successfully synced 100 chats" in the bot room → no `incomingMessage`.

The steps:
- [ ] Write the tests. They should pass at once, since the spec says the gate already covers this. If one fails, follow superpowers:systematic-debugging: fix it, add its regression test and a Lessons line.
- [ ] Record the unread-badge question in `matrix/CLAUDE.md`: bridgev2's `backfill.unread_hours_threshold` is 720 h by default, so loaded chats with messages from the last 30 days may arrive unread. Read the local `config.yaml` of whatsapp and telegram, read-only, and write down the value found.
- [ ] Commit: `test(matrix): chats loaded with their history don't notify`.

### Task 7: WhatsApp's `sync-history-chats <n>` patch
**Files:**
- New: `native/patches/whatsapp/sync-history-chats.patch`
- `src/main/resources/app/unichat/server/versions.env` (`NATIVES_ALL=twitter,discord,whatsapp`; `NATIVES=vaoan/unichat@natives-3`; a comment on why WhatsApp is patched)
- `src/test/java/app/unichat/server/ManifestTest.java` (if its expectations name `NATIVES_ALL`)
- `native/CLAUDE.md`, `docs/NATIVE-SERVER.md` (the patch and why)

**Interfaces (the bridge's):** `!whatsapp sync-history-chats <n>`, with `n` from 1 to 500. It calls `createPortalsFromHistorySync` for the command's login with limit `n`. That creates rooms for the `n` most recent history-sync conversations not yet synced, and the reply is `Created <k> chats from history sync`. A bad `n` replies with usage. It needs no admin, only the login's own user.

The steps:
- [ ] Clone `mautrix/whatsapp` at `v0.2610.0` into the session scratchpad (a throwaway folder, never inside the repo). Read `pkg/connector/backfill.go` (`createPortalsFromHistorySync`) and `pkg/connector/commands.go` (`sync groups`). Settle the open points of the spec's "Left to the builder": the exact signature, whether the flag `history_sync_portals_need_creating` matters, and how the count is known.
- [ ] Write the command next to `sync groups`, then add a Go test in `pkg/connector` for what can be tested without WhatsApp: argument parsing (`0`, `501` and `abc` are refused; `100` is accepted). Run `go build ./... && go test ./pkg/connector/...` in the clone.
- [ ] Make the patch with `git diff` against `v0.2610.0`. Check it with `git apply --check` on a fresh clone of the tag.
- [ ] Update `versions.env` and the docs. The ManifestTest expectation is that WhatsApp on `MAC_ARM64` comes from `NATIVES`. `sha256sums.txt` can only be pinned once CI has published natives-3, so leave it alone, and mark `ManifestTest` as expected-red in the report (don't disable it), as the patched-bridges plan did.
- [ ] Until natives-3 is installed, Task 5's `whatsappWithoutThePatchIsNotPossible` is what the person sees. Say so in `native/CLAUDE.md`.
- [ ] Commit: `build(natives): WhatsApp gets a sync-history-chats command (Unichat's patch)`.

### Task 8: The warning sheet (`OlderChatsSheet`)
**Files:**
- New: `src/main/java/app/unichat/ui/chats/OlderChatsSheet.java`
- `src/main/resources/app/unichat/i18n/messages.properties`, `messages_es.properties` (`olderChats.sheet.*`)
- CSS, only if needed: `.claude/rules/css-icons.md`
- Tests: new `src/test/java/app/unichat/ui/chats/OlderChatsSheetUiTest.java`

**Interfaces:**
```java
public final class OlderChatsSheet {
    /** show/hide: the window's overlay (MainView.showOverlay / hideOverlay), as DeleteSheet takes them. */
    public OlderChatsSheet(Consumer<Node> show, Runnable hide);
    /** Shows plans; load gets the networks to run and those included (Instagram only when ticked). */
    public void open(List<OlderChats.Plan> plans, BiConsumer<List<Network>, Set<Network>> load);
}
```
Build it the way `DeleteSheet` builds its card, with the safe answer first and focused.

The tests (headless, `-Pheadless-ui`):
- `cancelIsFocusedAndRunsNothing`: once open, the focus owner is Cancel. Clicking it (and, in a second case, Enter on it) hides the overlay, and `load` was never called.
- `escapeCancels`: Escape hides it, and `load` isn't called.
- `eachNetworkSaysHowManyAndWhetherItRestarts`: plans for Telegram (100, restart), WhatsApp (-1, no restart) and LinkedIn (-1, restart) → each row's text holds `olderChats.sheet.upTo` with 100, `olderChats.sheet.everyGroup` or `olderChats.sheet.all`, and the restart line only where `restarts`.
- `discordSaysItReconnectsEveryServer`: Discord's row holds `olderChats.sheet.reconnectsServers`. With `waitFirst=7 min` it says "available in 7 min", and Discord isn't in what `load` receives.
- `instagramIsOffByDefaultAndRunsOnlyWhenTicked`:
  - Instagram's check box isn't selected;
  - the warning label is visible (not a tooltip), with `olderChats.sheet.instagramRisk`;
  - Load → `included` lacks Instagram;
  - tick it, Load → `included` has it.
- `loadIsDisabledWhenNothingWouldRun`: a single Instagram plan, unticked → Load is disabled. Ticked → enabled.
- `theTrafficAndWhatsappLinesShow`: the 50-messages line always shows. The WhatsApp pairing line shows only when WhatsApp is in the plans.
- `keyboardReachesEveryControlWithItsText`: Tab goes from Cancel through the check box to Load. Each one has a non-blank accessible text, and each row is focusable with its whole text.
- `fitsAt160PercentAndInHighContrast`: at 1.6 text scale and in high contrast, no label is cut (the measured width ≤ the available width; reuse the helper the other `*UiTest`s use for "nothing cut").

The steps:
- [ ] Write the tests and watch them fail.
- [ ] Implement the sheet and its texts in both languages. Then run the sheet test with `-Pheadless-ui`.
- [ ] Commit: `feat(ui): a warning sheet before loading older chats, Instagram off unless ticked`.

### Task 9: The button, the top bar, and the wiring
**Files:**
- `src/main/java/app/unichat/ui/chats/ChatListPane.java` (the button under `list`, above the footers; which networks are in view)
- New: `src/main/java/app/unichat/ui/chats/OlderChatsBar.java` (`extends TaskBar`)
- `src/main/java/app/unichat/ui/MainView.java`:
  - the bar on top, in the `VBox` with `resting`;
  - the sheet, made in `sheets(...)`, or beside it in the same overlay;
  - the button's action: `plans`, then the sheet, then `load`.
- `src/main/java/app/unichat/service/MockChatService.java` (implements `OlderChats` only with `-Dunichat.demoOlderChats`, for snapshots: canned plans and a canned run)
- i18n: `olderChats.button*`, `olderChats.bar.*`
- `.claude/skills/snapshot/` notes, only if a new property needs saying
- Tests: new `src/test/java/app/unichat/ui/chats/OlderChatsButtonUiTest.java`, new `src/test/java/app/unichat/ui/chats/OlderChatsBarUiTest.java`

**Interfaces:** the button is internal to `ChatListPane`. `ChatListPane.setOnLoadOlderChats(Consumer<Collection<Network>> open)`, and `MainView` gives it the sheet. The networks in view are `filter.network()` when set, else the bridged networks with an account (the `accounts` the pane already keeps).

The tests:
- `OlderChatsButtonUiTest`:
  - `showsAtTheEndOfTheInboxWhileANetworkHasMore`: a fake `OlderChats` with `hasMore` true → the button is visible, below the list, above the account alerts.
  - `hiddenWhenNoNetworkInViewHasMore`: `hasMore` false → not visible (and not managed).
  - `hiddenWhileSearchingAndInFoldersArchiveFavoritesAndServers`: one case each, with the filter set as the user would.
  - `followsTheRailsNetwork`: rail on Telegram → `hasMore` asked with `{TELEGRAM}`; rail on all → the networks with an account.
  - `aClickEnterOrSpaceOpensTheSheet`: each one calls the sheet once with those networks.
  - `disabledAndSaysLoadingWhileARunIsUnderWay`: `loading()` is not null → disabled, with the `olderChats.button.loading` text. When it's done → enabled again, or hidden if nothing more.
  - `itsAccessibleTextNamesTheNetworks`.
  - `notThereWithoutOlderChats`: a service that isn't `OlderChats` → no button.
- `OlderChatsBarUiTest`:
  - `showsTheNetworkTheStageAndNOfM`: each `Stage` gives its text, with "2 of 3".
  - `countsChatsArriving`: `ARRIVING` with `newChats` 37 shows 37.
  - `saysTheResultThenHides`: the `DONE` text names each answer. After `later` runs (tests run it by hand, as `RestBarUiTest` does), it's hidden.
  - `saysWhatWasNotPossible`: a `NotPossible` reason shows in the result.
  - `focusableWithItsWholeText`.
  - `nothingCutAt160PercentNorWithAWideFont`, as `CloakBarUiTest` does (`Monospaced`).
- `MainView` wiring: `OlderChatsButtonUiTest.theButtonsSheetLoadsWhatWasChosen`: in a headless `MainView` over the demo with `-Dunichat.demoOlderChats`, button → sheet → Load → the demo's `load` receives the networks.

The steps:
- [ ] Write the tests and watch them fail.
- [ ] Implement. Use `TaskBar`'s layout, `FxCoalescer` for redraws, and `Showing.spinWhileSeen` for the bar's wheel (`ui/CLAUDE.md` Lessons).
- [ ] Look at it with the `snapshot` skill (headless): the inbox with the button, the sheet (light, dark, 160 %), and the bar mid-run.
- [ ] Commit: `feat(ui): "Load older chats" at the end of the inbox, with its progress on top`.

### Task 10: Notes and docs
**Files:**
- `src/main/java/app/unichat/matrix/CLAUDE.md`: `OlderChatSteps`, `OlderChatLoader`, `RoomArrivals`; only on demand; one at a time
- `src/main/java/app/unichat/server/CLAUDE.md`: `ChatLimits` in the config; `reconfigure`
- `src/main/java/app/unichat/service/CLAUDE.md`: the `OlderChats` role
- `src/main/java/app/unichat/ui/chats/CLAUDE.md`: the button, the sheet, the bar
- `docs/ARCHITECTURE.md`: the new classes in the `matrix` table, and `ChatLimits` in `bridge`
- `docs/BRIDGES.md`: each bridge's limits and who raises them
- `README.md` and `README.es.md`, only if they list features; keep them in step

The steps:
- [ ] Write the notes. They're short, and they say why, not what the code says.
- [ ] Run `./mvnw -q verify -DskipTests`.
- [ ] Commit: `docs: notes on loading older chats`.

### Controller steps (not tasks)
1. Run the full `./mvnw verify -Pheadless-ui`, expecting only `ManifestTest` red until natives-3 is pinned. Then use the `reviewer` and `security-reviewer` agents on the diff. Bot commands carry only fixed text and a number, but the patch is new code in a bridge.
2. Merge to main and push, so CI builds natives-3 (WhatsApp with the patch). Then run `native/pin-hashes.sh`, commit `sha256sums.txt`, and run the full verify again.
3. Install (`./install-mac.sh`). Then the person tries it by hand, since it's the real networks: Telegram, then Discord (once), then WhatsApp. Instagram only if they choose to.
