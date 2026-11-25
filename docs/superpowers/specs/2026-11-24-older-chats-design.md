# "Load older chats" at the end of the chat list — design

## Intent
Chats missing from Unichat's list are missing at the bridge: they have no Matrix room yet. Unichat never cuts the list itself (`SyncFilter.BASE` limits each room's timeline, never the number of rooms). Each bridge brings only its newest chats when it connects: WhatsApp 200 of the 392 it knows, Telegram the newest 100 dialogs, Discord the 50 newest private channels, Instagram the first inbox page.

The person (2026-11-24) wants a "Load older chats" button ("Cargar chats anteriores") at the end of the chat list. Each click, after a warning window, asks the bridges of the networks in view, one at a time, for older chats they haven't brought into Matrix yet. Progress shows in the existing top bar (`ui/TaskBar`). The button hides when no network in view has more. Older *messages* in a chat are a separate button, already built on `feat/load-older-button`, and not part of this design.

What each bridge can do was researched at the exact versions Unichat runs (`older-chats-research.md` in the session's scratchpad, 2026-11-24). Its findings are binding here and are summed up under "Each network" below.

## Decided by the person (2026-11-24)
- **Only on demand.** Nothing loads older chats by itself: no automatic runs, no retries in the background. ("igual es por demanda")
- **A warning window before every load** ("si podemos poner una ventana de warning"). Every click opens a confirmation sheet first, with Cancel focused. It says, per network in view, what will happen and the risks. Instagram is included only when ticked there, off by default. "Load" runs it.
- **Each network's step**, as the research recommends, with two choices of the person's: LinkedIn and Google Messages bring everything, and Instagram asks first.
- **50 messages** of history per new chat, as today.
- **Restarts are rare and say what they do.** A bridge restart reconnects the network, and Discord's resubscribes to every server.
- **No notifications** from old chats arriving.

## Goals
- **One button at the end of the inbox list**, shown while some network in view has more to load. Reachable by keyboard, with an accessible text, fine at 160 % text and in high contrast.
- **A warning sheet before every load**, in the window's overlay like `DeleteSheet` and `ForwardSheet`. It lists each network that will run, how many chats it asks for, whether its bridge restarts, and the risks.
- **One step per network per click, one bridge at a time,** in the order the sheet lists them. Each step answers honestly:
  - "loaded n" (n new rooms of that network appeared);
  - "nothing more" (the step added none, or the network has nothing to fetch);
  - "not possible", with a reason the person can read (no account, the bridge didn't come back, the patched WhatsApp isn't installed yet, Discord's pause between steps).
- **Progress in a `TaskBar` on top:** the network, the stage (restarting its bridge, asking for chats, chats arriving: N new), N of M networks, then the result for a few seconds.
- **The Telegram bug fixed.** Unichat raises `create_limit` but not `update_limit`, which stays at the bridge's default of 100 and caps the walk, so asking for more than 100 does nothing. Both go together from now on.
- **WhatsApp's stored history chats become reachable** through a small Unichat patch to the bridge (`sync-history-chats <n>`), shipped the way Discord's patch already is (`native/patches/`, `NATIVES_ALL`, natives build, pinned hashes).

## Non-goals
- Older messages inside a chat (`feat/load-older-button`).
- Facebook and X: their bridges already brought every chat they can list. They answer "nothing more".
- Telegram's Takeout: it needs the person to accept on another device.
- Writing to any bridge's database (WhatsApp's `history_sync_portals_need_creating`, Meta's `backfill_completed`). Too tied to their schemas.
- Rooms for portals a bridge already has without a room, one by one (`create-portal`). Possible later, not needed for this.
- Cancelling a step under way. A restart can't be undone halfway; the sheet is where the person says no.
- Signal and Slack: not active. Signal has no chat list to sync.

## Anti-goals
- **An account flagged without warning.** Instagram's inbox walk is what Meta flags (checkpoints, "suspicious activity", forced logout). It never runs unless the person ticked it in the sheet on that click. The sheet's Instagram warning is visible text, not a tooltip. No other step walks Meta's inboxes.
- **A chat lost.** Nothing here deletes or hides a room, and no limit ever goes down in a way that removes rooms (bridges never delete rooms when a limit drops). No step touches a bridge's database.
- **A notification storm.** Chats arriving with 50 old messages each must not notify. `NotifyGate` already makes this true: a message notifies only if it is under 5 minutes old *and* newer than anything seen in its chat, and backfilled history is older. A test locks it for this case (see Tests). Bot replies ("Successfully synced…") come in bridge-bot rooms, which never notify.
- **A bridge left broken.** A restart with a new limit that doesn't come back puts the old limit back and restarts once more, and says so. A limit key missing from the bridge's config refuses the step *before* stopping anything. Steps never overlap: one runs at a time, and a second click while one runs does nothing.
- **Restarts that happen by themselves.** Only a click on "Load" in the sheet restarts a bridge for this feature. Discord's step waits 10 minutes after its last one: a reconnect resubscribes every server, which is what caused the 4008 loop the pace patch fixes.

## Approach
Three new pieces, each with one job, in the existing layers (`docs/ARCHITECTURE.md`):

1. **What the bridges are told** (`server` + `bridge`). A small record in `bridge`, `ChatLimits`, holds what the person has asked for so far, per network. `BridgeInstaller` reads it from `Settings` and passes it in `BridgeConfig.Settings`. `BridgeConfig.apply` writes each bridge's limit keys from it (with `setExisting`, so a renamed key is reported, not added). The Settings keys stay on this computer (`Settings.staysOnThisMachine`): they describe this machine's bridges.
2. **A restart that waits for the bridge** (`server`, through `matrix/BridgeProvisioner`). `BridgeProvisioner.reconfigure(Network, progress)` rewrites the bridge's config and restarts that bridge only (`BridgeLauncher.applySettings`, which already tells `BridgeStates` it is restarting), then waits for it to answer (as `open` does, at most 60 s). It checks first that every limit key it needs is in the config, and refuses before stopping anything if not.
3. **The steps** (`matrix`, behind a new `service` role, `OlderChats`):
   - `OlderChatSteps` (pure, no network) decides, for each network, what the next click does, from `ChatLimits`, the network's accounts and the time of its last step.
   - `OlderChatLoader` runs the steps of one click, one network at a time: it writes the new limit, restarts or sends the command, then counts the network's new rooms (`RoomArrivals`) until they stop arriving.
   - `RoomArrivals` counts rooms of one network that weren't there when the step began, as the sync brings them. A step is over when the bridge has answered (or come back) and no new room of that network arrived for 45 s, or after 15 minutes at most.

The UI asks `svc instanceof OlderChats`, as it does for `BridgeAccess`. The demo implements it only with `-Dunichat.demoOlderChats` (for snapshots).

Rejected: one generic "sync chats" call for every bridge (bridgev2 has none; each connector decides alone); paging through `create-portal` (it creates rooms only for portals the bridge already has, one command each); raising every limit at once on the first click (several restarts and thousands of rooms at once, and no honest "nothing more").

## Each network
"In view" means the rail's network when one is chosen (with or without an account filter), else every network with an account. A network without an account, or whose bridge isn't running by design, answers "not possible" and isn't offered.

| Network | A click does | Restart | Settings it keeps | "Nothing more" when |
|---|---|---|---|---|
| **Telegram** | +100 chats: `create_limit` and `update_limit` both go to 100 + what was asked so far, then the restart, then the existing `sync-chats` (10 min patience, needs "Successfully…"). The walk starts again at the top each time; that's the bridge's way. | yes | `olderChats.telegram.extra` (+100 per step, kept) | a step brings no new room |
| **WhatsApp** | First click: `!whatsapp sync groups` (every joined group, no restart). Later clicks: `!whatsapp sync-history-chats 100` (Unichat's patch). The sheet says chats older than what the phone sent at pairing (about 3 months) can't come. | no | `olderChats.whatsapp.groups` (done), then nothing: the bridge itself leaves out what it already synced | the patched command creates none. Without the patch installed (the bridge answers "Unknown command"), the second part answers "not possible: needs the next Unichat update". |
| **Discord** | +50 private chats (DMs and group DMs): `startup_private_channel_create_limit` to 50 + what was asked so far, then the restart. Not offered within 10 minutes of its last step; the sheet says how long is left. | yes, and it reconnects to every server | `olderChats.discord.extra` (+50, kept), `olderChats.discord.at` (time of the last step) | a step brings no new room |
| **LinkedIn** | Everything: `create_limit` 0 (no limit), then the restart. The bridge walks the whole inbox at each start anyway, so 0 is kept. | yes | `olderChats.linkedin.all` | after its one step |
| **Google Messages** | Everything: `initial_chat_sync_count` to 500 for this restart only (one request, through the person's phone), then back to the default at the next config rewrite. | yes | `olderChats.gmessages.once` (set for the step, cleared when it ends) | after its one step |
| **Instagram** | Only when ticked in the sheet: `thread_backfill.batch_count` to 3 inbox pages for this restart only, then back to 0. The bridge then marks its thread backfill done, so it's one pass per login. | yes | `olderChats.instagram.once` (set for the step, cleared when it ends), `olderChats.instagram.done` | after its one step |
| **Facebook** | Nothing: the bridge already paged the whole inbox (`backfill_completed`). | no | | always |
| **X** | Nothing: the bridge imports the whole XChat inbox on first connect. | no | | always |

Kept for all: `backfill.max_initial_messages` 50 (bridgev2) and Discord's `forward_limits.initial.dm` 50.

Instagram's `batch_count` is written as 0 by default from now on, as it is on this Mac today. The template's -1 would walk the whole inbox at every new login, which is the anti-goal above. It goes to 3 only for a step the person ticked.

"Nothing more" is kept per network (`olderChats.<net>.done`) and cleared when that network's login changes (a new account has its own older chats).

## Service API
In `service`, a role interface the UI takes as it takes `ArchiveWaking`:

```java
public interface OlderChats {
    /** What one click would do for a network, for the warning sheet. chats: -1 means "all". */
    record Plan(Network network, int chats, boolean restarts, boolean reconnectsServers, boolean risky,
                @Nullable Duration waitFirst) {}

    /** The answer of one network's step. */
    sealed interface Answer {
        record Loaded(int chats) implements Answer {}
        record NothingMore() implements Answer {}
        record NotPossible(String reason) implements Answer {} // in the person's words (I18n)
    }

    /** Where a run is: which network, at what stage, N of M; the answers so far. */
    record Loading(Network network, Stage stage, int newChats, int done, int total, Map<Network, Answer> answers) {}
    enum Stage { RESTARTING, ASKING, ARRIVING, DONE }

    /** What a click would do for these networks, in the order they run; a network with nothing to do is left out. */
    default List<Plan> plans(Collection<Network> inView) { return List.of(); }

    /** Whether any of these networks has something left to load. */
    default boolean hasMore(Collection<Network> inView) { return false; }

    /** Runs one step for each network, one at a time, in order. Ignored (same future) while a run is under way. */
    default CompletableFuture<Map<Network, Answer>> load(List<Network> networks) { return CompletableFuture.completedFuture(Map.of()); }

    default @Nullable Loading loading() { return null; }
    default void addLoadingListener(Runnable l) { }
    default void removeLoadingListener(Runnable l) { }
}
```

`MatrixChatService` implements it with `OlderChatLoader`. The answers come back on the service's own executor; listeners are told on any thread, and the UI marshals them (`FxCoalescer`).

## UI
- **The button**, "Load older chats" / "Cargar chats anteriores", sits right under the chat list in `ChatListPane`, above the footers (update bar, account alerts), full width in the list's style. It shows only in the inbox view (all chats or one network, with any quick filter), never while searching, in a folder, in a server's channels, in Favorites view or in Archivados; and only while `hasMore(networks in view)`. While a run is under way it is disabled and says "Loading older chats…". Its accessible text says which networks it will ask.
- **The sheet**, `OlderChatsSheet` in `ui/chats`, opens in the window's overlay on every click:
  - the title, "Load older chats?";
  - one row per `Plan`: the network's icon and name, then what it does ("up to 100 more chats", "every group", "all of them", "50 more private chats"), and "its bridge restarts: <network> disconnects for a moment" where it does. Discord's row adds "it reconnects to every server". A Discord step still in its pause says "available in N min" and doesn't run;
  - Instagram's row has a check box, off by default, and beside it a visible warning: "Meta may ask you to verify your account, or close the session, when many chats are loaded at once." It runs only when ticked;
  - one line on traffic: "Each new chat brings its last 50 messages: this can take several minutes and a lot of data.";
  - WhatsApp's row says that chats older than what the phone sent at pairing can't come;
  - Cancel (focused, the default for Escape) and "Load" (disabled while nothing would run, e.g. only an unticked Instagram).
  Cancel and Escape close it and nothing runs. Tab goes through the check box and both buttons; every row is focusable with its whole text for screen readers.
- **The bar**, `OlderChatsBar extends TaskBar`, on top with the others in `MainView`: "Loading older chats from Telegram", the stage ("Restarting its bridge…", "Asking for chats…", "Chats arriving: 37 new"), "2 of 3 networks". Then the result for 6 s ("37 new chats: Telegram 30, Discord 7. WhatsApp: nothing more. Instagram: not possible, the bridge didn't come back."), then it hides. Focusable with its whole text, nothing cut at 160 % (and with a wide font, as `CloakBarUiTest` does).

All texts through `I18n`, in `messages.properties` and `messages_es.properties` (`olderChats.*`).

## Errors and timeouts
| Case | What happens | Answer |
|---|---|---|
| A limit key isn't in the bridge's config (renamed in a new version) | refused before stopping the bridge; logged | not possible ("this bridge version doesn't allow it") |
| The bridge doesn't answer 60 s after the restart | the setting goes back to what it was, one more restart; the watchdog takes over from there | not possible ("the bridge didn't come back; the old setting is back") |
| A command times out (Telegram `sync-chats` 10 min, others 2 min) | the rooms that arrived are counted | loaded n, or nothing more if none |
| The bot answers with an error, or "Unknown command" | no retry | not possible, with the reason |
| Rooms keep arriving | counted until 45 s without a new one, 15 min at most; the rest still arrive later (the bridge goes on) | loaded n (those counted) |
| A step fails | the next network still runs | that network's answer |
| Unichat quits mid-run | the limit already written stays (the person asked for it); a one-shot setting (Google Messages, Instagram) is cleared at the next start before the bridge runs | the run isn't resumed |
| A second click during a run | ignored, the button is disabled | |
| The internet is down | the step's bridge answers what it answers; nothing is retried in the background | not possible or nothing more |

## Tests
Everything headless, with fakes for the bridges: a fake bot chat that records commands and answers, a fake `BridgeProvisioner` that records reconfigures and can refuse or not come back, a fake room source the test adds rooms to, a manual clock. No test waits on time.

- **Config:** Telegram's `update_limit` follows `create_limit` (the bug, failing first); each network's key from `ChatLimits`; Instagram 0 unless asked; one-shot values only while set; a missing key is reported.
- **Restart:** only that bridge restarts; it waits for its answer; a missing key refuses before stopping; a bridge that doesn't answer is reported; a reconfigure isn't counted as flapping by the watchdog.
- **Steps:** each network's plan and step as in the table; "nothing more" after a step that adds none, kept, and cleared by a new login; Discord's 10-minute pause; one network at a time in order; a failure doesn't stop the next; the rollback; a second click ignored; nothing runs without a call to `load` (start, login refresh and sync run no command and no restart).
- **WhatsApp patch:** it applies to v0.2610.0 (`git apply --check`), builds, and the natives build lists it; without it, the step answers "not possible".
- **Notifications:** a room arriving with 50 backfilled messages from hours to years ago fires no `incomingMessage`; one message in it under 5 minutes old does (it's news).
- **UI:** the sheet (Cancel focused and does nothing; Escape; each row's text; Discord's reconnect line; Instagram off by default, its warning visible, runs only when ticked; Load disabled when nothing would run; keyboard and accessible texts; 160 % and high contrast); the button (where it shows and hides, keyboard, disabled while running); the bar (stages, N of M, result, hiding, accessible text, a wide font).

## Left to the builder
- The exact signature of `createPortalsFromHistorySync` in mautrix-whatsapp v0.2610.0, how the patched command reports its count, and whether it must also set `history_sync_portals_need_creating`.
- Whether mautrix-meta v0.2607.0 already marked Instagram's thread backfill done while `batch_count` was 0. If it did, the Instagram step can't work without a database write: it answers "not possible" and the sheet doesn't offer it.
- How `RoomArrivals` hears new rooms (the sync's `chatsChanged` and `proc.rooms()`, or the invites joined).
- Whether bridgev2's `backfill.unread_hours_threshold` (720 h by default) marks the loaded chats read; Discord's legacy bridge's equivalent. Chats loaded with unread messages from the last 30 days would raise the unread badge, though they don't notify.
- The button's exact look under the list.
