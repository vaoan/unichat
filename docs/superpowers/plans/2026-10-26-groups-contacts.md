# Grupos, servidores y contactos — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spaces stop showing as empty chats, each network gets a Bandeja | Grupos view (tree of servers/groups → channels/topics), bridges create more chats at login, and "Nuevo chat" reaches any contact through the bridge's contacts API.

**Architecture:** Sync already delivers every state event (the sync filter only lazy-loads members). `SyncProcessor` records space facts on `MatrixRoom` and copies them to `Chat`. A pure `SpaceTree` builds the Grupos tree from `List<Chat>`. `ChatListPane` shows either its `ListView` (Bandeja) or a `TreeView` (Grupos). `BridgeContacts` is a small HTTP client for `/_matrix/provision/v3/{contacts,search_users,resolve_identifier,create_dm}`, exposed through `ChatService` defaults. `NewChatPane` is an overlay like `AddAccountPane`.

**Tech Stack:** Java 21, JavaFX 23, Jackson, JUnit 5, `com.sun.net.httpserver` fakes.

**Spec:** `docs/superpowers/specs/2026-10-26-groups-contacts-design.md`

## Global Constraints

- User-facing text in Spanish; code, comments, commits in English. Must work on Windows.
- Every new control: accessible text, keyboard reachable, survives 160 % text and high contrast (CLAUDE.md).
- Nothing personal in logs (use existing `AppLog`/Redactor paths; never log contact names/ids).
- Only the user's typed query goes to `search_users`.
- `./mvnw -q test` green before each commit; UI tests with `-Dunichat.uiTests=true`.
- Commits authored `vaoan <3722960+vaoan@users.noreply.github.com>`, ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Limits: Telegram `network.sync.create_limit` = 100 and `network.sync.login_sync_limit` = 100; Discord `bridge.startup_private_channel_create_limit` = 50; WhatsApp `network.history_sync.max_initial_conversations` = 200.
- Personal folder = space whose `m.bridge` `channel.id` starts with `__`.
- Space unread = sum over joined, non-archived, non-muted children (`ChatFilter.inboxUnread`).
- Shortcut ⌘N / Ctrl+N opens Nuevo chat. View setting key `list.view.<NETWORK>` = `INBOX` | `GROUPS`.
- `search_users` debounce 300 ms.

## Review Focus

1. A space that is also listed as a child of another space (nesting) or a cycle (A→B→A) must not loop or duplicate forever — the tree stops at a room already on the path.
2. `m.space.child` with empty content (child removed) must remove the child; a later re-add brings it back.
3. A bridge that answers 404/`M_UNRECOGNIZED` for contacts or search must show the "no disponible" message, not an error dialog or a spinner forever.
4. Multi-account: contacts/search/create_dm use the chosen account's `login_id`, never the first login of the network.
5. Switching Bandeja ↔ Grupos must keep the network's chip filter and selection rules the rail already has (`FilterUiTest` stays green).

---

### Task 1: Space facts from sync

**Files:**
- Modify: `src/main/java/app/unichat/matrix/MatrixRoom.java`, `src/main/java/app/unichat/matrix/SyncProcessor.java` (`applyState`, `refresh`), `src/main/java/app/unichat/model/Chat.java`
- Test: `src/test/java/app/unichat/matrix/SpaceSyncTest.java`

**Interfaces:**
- Produces on `Chat`: `boolean space()`, `boolean personalSpace()`, `List<String> spaceChildren()` (ordered: `order` field, then insertion), `Set<String> spaceParents()`, with setters `setSpace`, `setPersonalSpace`, `setSpaceChildren(List<String>)`, `setSpaceParents(Set<String>)`.
- `MatrixRoom` gets `boolean space`, `String bridgeChannelId`, `Map<String,String> spaceChildOrder` (LinkedHashMap child → order or ""), `Set<String> spaceParents`.

- [ ] **Step 1: Write the failing tests** (feed `SyncProcessor` a /sync JSON like the existing `RoomFeaturesSyncTest` does)
  - `spaceRoomIsMarkedAndListsChildrenInOrder`: room with `m.room.create {type:"m.space"}`, children `!b` (order "b"), `!a` (order "a"), `!c` (no order) → `space()==true`, `spaceChildren()==[!a,!b,!c]`.
  - `emptyChildContentRemovesTheChild`: second sync with `m.space.child` state_key `!a` content `{}` → children `[!b,!c]`.
  - `personalSpaceIsTheBridgeFolder`: `m.bridge` with `channel.id:"__personal_space"` → `personalSpace()==true`; with `channel.id:"123"` → false.
  - `childDeclaresItsParent`: plain room with `m.space.parent` state_key `!s` content `{via:["x"]}` → `spaceParents()=={!s}`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=SpaceSyncTest` — Expected: compile FAIL (no `space()`).
- [ ] **Step 3: Implement.** `applyState` cases: `m.room.create` → `room.space = "m.space".equals(type)`; `m.space.child` → put/remove by content emptiness; `m.space.parent` → add/remove; `m.bridge` also stores `channel.id`. `refresh` copies to `Chat`, sorting children by order (non-empty orders lexicographic first, then the rest in insertion order). A space is never `group` and never a bridge-bot room.
- [ ] **Step 4: Run** same command — Expected: PASS (4 tests).
- [ ] **Step 5: Commit** `feat(sync): record Matrix spaces, children and parents`.

### Task 2: Spaces out of the Bandeja and counts

**Files:**
- Modify: `src/main/java/app/unichat/ui/ChatFilter.java`
- Test: `src/test/java/app/unichat/ui/ChatFilterTest.java` (add)

**Interfaces:** Consumes `Chat.space()`.

- [ ] **Step 1: Failing tests:** `spacesNeverShowInAnyView` (a space with unread 3 is absent from INBOX, ARCHIVE, FAVORITES and from a search matching its title) and `spacesNeverCountAsUnread` (`ChatFilter.inboxUnread(space)==false`).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=ChatFilterTest` — Expected: FAIL on both.
- [ ] **Step 3: Implement:** `matches` returns false first thing for `c.space()`; `inboxUnread` adds `&& !c.space()`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `fix(list): spaces are containers, not chats`.

### Task 3: SpaceTree

**Files:**
- Create: `src/main/java/app/unichat/ui/SpaceTree.java`
- Test: `src/test/java/app/unichat/ui/SpaceTreeTest.java`

**Interfaces:**
- Produces: `record SpaceTree.Node(String id, Chat chat, String name, List<Node> children)` (`chat` null = not joined yet; `name` from chat title or from `names` map), `static List<Node> build(List<Chat> chats, Network network, Map<String,String> names)`, `static int unread(Node n)`, `static final String LOOSE = "Chats directos y otros"`.

- [ ] **Step 1: Failing tests:**
  - `serversListTheirChannels`: space S (Discord) with children c1, c2 → one root S with [c1, c2].
  - `personalFolderIsNotAGroup`: personal space P with children d1, S; S with c1 → roots [S, LOOSE] where LOOSE holds d1 (chats hanging only from P are loose).
  - `chatsWithoutASpaceGoToLoose`: chat d2 with no parent → under LOOSE; LOOSE omitted when empty.
  - `nestedSpacesNest` (S → S2 → c3) and `cyclesStop` (A→B→A gives A[B] once, no stack overflow).
  - `unjoinedChildrenAppearWithTheirHierarchyName`: S lists `!x` which is not in `chats`, `names` has `!x → "anuncios"` → node chat null, name "anuncios".
  - `unreadSumsInboxUnreadChildren`: children unread 2 (normal), 5 (muted), 1 (archived) → `unread(S)==2`.
  - Only chats of `network`; bridge-bot chats and archived chats excluded from children and LOOSE.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=SpaceTreeTest` — Expected: compile FAIL.
- [ ] **Step 3: Implement** (roots = non-personal spaces of the network that are not a child of another non-personal space; children from `spaceChildren()` ∪ chats whose `spaceParents()` contain the space; path set for cycles; roots sorted by title, LOOSE last sorted by `ChatFilter.ORDER`).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(list): build the Grupos tree from spaces`.

### Task 4: Join and hierarchy names in the service

**Files:**
- Modify: `src/main/java/app/unichat/service/ChatService.java`, `src/main/java/app/unichat/matrix/MatrixChatService.java`, `src/main/java/app/unichat/service/MockChatService.java`
- Test: `src/test/java/app/unichat/matrix/SpaceServiceTest.java`

**Interfaces:**
- Produces: `default CompletableFuture<Void> joinChat(String roomId)` (default failed), `default CompletableFuture<Map<String,String>> spaceNames(String spaceId)` (default empty map). Matrix: `POST /_matrix/client/v3/join/{roomId}`; `GET /_matrix/client/v1/rooms/{spaceId}/hierarchy?max_depth=1&limit=100` → `room_id → name` (fallback `canonical_alias`, else skip).

- [ ] **Step 1: Failing test** with a fake homeserver: `joinPostsToJoin` (path `/_matrix/client/v3/join/%21x%3As`), `hierarchyGivesNames` (two rooms, one without name but alias → both mapped; the space itself excluded).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=SpaceServiceTest` — Expected: compile FAIL.
- [ ] **Step 3: Implement** in `MatrixChatService` (off the FX thread on its `io` executor) and Mock (Mock seeds a demo Discord server "Servidor Unichat" with channels `#general`, `#memes` and one unjoined `#anuncios`; `joinChat` turns it into a joined chat and fires `chatsChanged`).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(service): join rooms and read space names`.

### Task 5: Bandeja | Grupos in the chat list

**Files:**
- Modify: `src/main/java/app/unichat/ui/ChatListPane.java`, `src/main/resources/app/unichat/css/app.css`
- Test: `src/test/java/app/unichat/ui/GroupsUiTest.java` (UI)

**Interfaces:** Consumes `SpaceTree`, `ChatService.joinChat/spaceNames`, `Settings` `list.view.<NETWORK>`.

- [ ] **Step 1: Failing UI test** `groupsViewShowsServersAndChannels` (demo, Discord selected in rail): a `.list-view-toggle` with "Bandeja" and "Grupos" is visible; selecting Grupos shows a `.groups-tree` whose root row's accessible text starts with "Servidor Unichat" and contains "sin leer" or "canales"; expanding lists `#general`; Enter on `#general` opens it (thread title "#general"); `#anuncios` row says "Unirse" and Enter joins it. Also `toggleHiddenWhenNetworkHasNoSpaces` (WhatsApp demo: toggle not visible) and `choiceIsRememberedPerNetwork`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=GroupsUiTest -Dunichat.uiTests=true` — Expected: FAIL (no toggle).
- [ ] **Step 3: Implement:** two-chip segmented toggle in the header (only when `f.network()!=null` and the tree has a non-LOOSE root); `TreeView<SpaceTree.Node>` swapped with the `ListView` (managed/visible); cell: avatar + name + unread badge; space rows bold; unjoined rows dimmed with "Unirse"; accessible text "Servidor X, N canales, M sin leer, contraído/desplegado"; keys: Left/Right collapse/expand (TreeView default), Enter opens or joins; names for unjoined children fetched once per space via `spaceNames` and cached; refresh keeps expanded state by id.
- [ ] **Step 4: Run** the UI test, then `./mvnw -q test -Dtest='*UiTest' -Dunichat.uiTests=true` — Expected: all PASS (FilterUiTest included).
- [ ] **Step 5: Snapshot** `-Dunichat.snapshot` of the Grupos view (demo, Discord) and check it.
- [ ] **Step 6: Commit** `feat(list): Bandeja | Grupos view per network`.

### Task 6: Higher creation limits

**Files:**
- Modify: `src/main/java/app/unichat/server/BridgeConfig.java`
- Test: `src/test/java/app/unichat/server/BridgeConfigTest.java`

**Interfaces:** Produces `static void setExisting(ObjectNode root, String path, int value)` — throws `IllegalStateException("missing config key " + path)` when the key isn't in the bridge's template.

- [ ] **Step 1: Failing tests:** `telegramCreatesMoreChats` (template containing `network.sync.create_limit: 15` / `login_sync_limit: 15` → 100/100), `discordCreatesMoreDms` (legacy template `bridge.startup_private_channel_create_limit: 5` → 50), `whatsappSyncs200Conversations`, `aMistypedKeyFails` (`setExisting` on a template without the key throws).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=BridgeConfigTest` — Expected: FAIL.
- [ ] **Step 3: Implement** with the values in Global Constraints (WhatsApp keeps creating the key since it already did; Telegram/Discord use `setExisting`).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(bridges): create more chats at login`.

### Task 7: BridgeContacts client and service API

**Files:**
- Create: `src/main/java/app/unichat/model/Contact.java`, `src/main/java/app/unichat/login/BridgeContacts.java`
- Modify: `ChatService.java`, `MatrixChatService.java`, `MockChatService.java`
- Test: `src/test/java/app/unichat/login/BridgeContactsTest.java`

**Interfaces:**
- `record Contact(String id, String name, List<String> identifiers, String mxid, String dmRoomId)`.
- `BridgeContacts(String base, String token, String userId, String loginId)`: `Optional<List<Contact>> list()` (empty Optional on 404/`M_UNRECOGNIZED`), `Optional<List<Contact>> search(String q)`, `Contact resolve(String identifier)` (`resolve_identifier`, null when not found), `String createDm(String identifier)` → room id (`room_id` of the response).
- `ChatService`: `record ContactSupport(boolean list, boolean search)`; `default CompletableFuture<ContactSupport> contactSupport(Account a)`; `default CompletableFuture<List<Contact>> contacts(Account a)`; `default CompletableFuture<List<Contact>> searchContacts(Account a, String q)`; `default CompletableFuture<Contact> resolveContact(Account a, String identifier)`; `default CompletableFuture<String> openDm(Account a, Contact c)` (existing `dmRoomId` or `createDm(c.id())`). Defaults: unsupported / failed.
- loginId = `account.id()` after `network.protocolId() + ":"`.

- [ ] **Step 1: Failing tests** (fake bridge): `listParsesContacts` (fields incl. `dm_room_mxid`), `notSupportedIsEmptyNotAnError` (404 `M_UNRECOGNIZED` → `Optional.empty()`), `searchSendsOnlyTheQuery` (body `{"query":"ana"}`, URL has `user_id` and `login_id`, nothing else), `createDmReturnsTheRoom`, `everyCallCarriesTheChosenLogin` (two accounts → `login_id` matches the one passed).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=BridgeContactsTest` — Expected: compile FAIL.
- [ ] **Step 3: Implement** (HttpClient with 10 s connect / 30 s request timeouts, Bearer token like `BridgeLogin`); `MatrixChatService` builds it from `provisioner.provisioningUrl(network)`; null URL → unsupported. Mock: WhatsApp supports both (6 demo contacts, one with an existing DM), Instagram search only, X none.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(contacts): bridge contacts, search and new DMs`.

### Task 8: Nuevo chat

**Files:**
- Create: `src/main/java/app/unichat/ui/NewChatPane.java`
- Modify: `MainView.java` (overlay + ⌘N), `ChatListPane.java` (header button "Nuevo chat"), `UnichatApp.java` (`-Dunichat.overlay=newchat`), `app.css`, `CLAUDE.md` (overlay option)
- Test: `src/test/java/app/unichat/ui/NewChatUiTest.java` (UI)

**Interfaces:** Consumes Task 7 API and `ChatService.accounts()`.

- [ ] **Step 1: Failing UI test:** ⌘N opens `.new-chat-pane`; network chooser lists connected networks; with WhatsApp the contact list shows 6 rows; typing "ana" (after 300 ms) narrows to search results; Enter on the contact with an existing DM opens that chat and closes the pane; Enter on another calls `openDm` and the chat opens when it appears; choosing X shows "X no permite buscar contactos desde Unichat"; typing "+57 300…" offers "Escribir a +57 300…"; Esc closes.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=NewChatUiTest -Dunichat.uiTests=true` — Expected: FAIL.
- [ ] **Step 3: Implement** following `AddAccountPane` layout: network row (+ account chips when several), search field (accessible "Buscar contacto en <red>"), list of contacts (avatar initial, name, first identifier), states "Cargando…", "Sin resultados", "no disponible" with per-network hint (Discord: "En Discord encuentras a la gente en sus servidores: usa la vista Grupos."). Opening waits for the room via `chatsChanged` up to 30 s, then toast "El chat se está creando; aparecerá en la bandeja".
- [ ] **Step 4: Run** UI test, full suite, all UI tests — Expected: PASS.
- [ ] **Step 5: Snapshot** `-Dunichat.overlay=newchat` and check it.
- [ ] **Step 6: Commit** `feat(ui): Nuevo chat to reach any contact`.
