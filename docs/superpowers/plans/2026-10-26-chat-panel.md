# Side panel, per-network options, stickers and GIFs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Telegram-like side panel in every chat (Perfil · Emojis · Stickers · GIFs), message actions that follow what each network supports, "Guardar como…", shared favourite/recent stickers and GIFs, and GIF search through configurable providers.

**Architecture:** Bridges publish `com.beeper.room_features` per room; `SyncProcessor` parses it into a pure `RoomFeatures` value on each `Chat`, and the UI asks it what to offer (Discord has a fixed table). A `SidePanel` replaces the info pane on the thread's right and hosts the tabs. Favourites/recents live in a local `MediaLibrary` file. GIF search goes through a `GifProvider` interface with Giphy/Klipy/Tenor adapters configured in Ajustes.

**Tech Stack:** Java 21, JavaFX 23, Jackson, JUnit 5 (UI tests behind `-Dunichat.uiTests=true`).

**Spec:** `docs/superpowers/specs/2026-10-26-chat-panel-design.md` (stage 5, per-network packs, gets its own plan).

## Global Constraints
- User-facing text in Spanish; code, comments, commits in English. Commits authored as vaoan noreply, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Support levels in `room_features`: −2 rejected, −1 dropped, 0 unsupported, 1 partial, 2 full; "offered" means level ≥ 1.
- No `room_features` in a room (external server, unknown bridge) → today's behaviour (everything offered).
- Only the search text goes to a GIF provider. Unichat ships no provider key; the user pastes keys in Ajustes → GIFs.
- Accessibility: every new control has accessible text, keyboard reachability, survives 160 % text and high contrast.
- `./mvnw test` green before each commit; UI tests run with `-Dunichat.uiTests=true`.

## Review Focus
1. Edit/delete time limits measured from the message's origin timestamp, not "now minus receive time" — a 20-min-old WhatsApp message must not offer Editar (15 min). → test in Task 1.
2. A room whose `room_features` changes later (bridge restart, new id) must update the chat's options. → test in Task 2.
3. `allowed_reactions` with variation selectors (❤️ vs ❤) must still match the quick reactions. → test in Task 1.
4. "Guardar como…" on a file whose name has path separators or is missing must not write outside the chosen folder. → test in Task 4.
5. A GIF provider that fails or times out must not break the others' results. → test in Task 8.

---

### Task 1: `RoomFeatures` — parse and decide

**Files:**
- Create: `src/main/java/app/unichat/model/RoomFeatures.java`
- Test: `src/test/java/app/unichat/model/RoomFeaturesTest.java`

**Interfaces:**
- Produces: `record RoomFeatures(...)` with `static RoomFeatures parse(JsonNode content)`, `static final RoomFeatures UNKNOWN` (everything offered, no limits), `static RoomFeatures discord()`;
  `boolean accepts(String type)` for `m.image|m.video|m.audio|m.file|org.matrix.msc3245.voice|fi.mau.gif|m.sticker`;
  `long maxSize(String type)` (0 = unknown); `boolean canEdit(Instant sent, Instant now)`; `boolean canDeleteForEveryone(Instant sent, Instant now)`;
  `boolean deleteForMe()`; `boolean replies()`; `List<String> reactionsOffered(List<String> quick)`; `boolean oneReactionPerUser()`.

- [ ] **Step 1: Write the failing tests**
```java
@Test void whatsappLimits() {
    RoomFeatures f = RoomFeatures.parse(json("""
        {"id":"wa","edit":2,"edit_max_age":900,"delete":2,"delete_max_age":172800,"reply":2,"reaction":2,"reaction_count":1,
         "file":{"m.sticker":{"mime_types":{"image/webp":2}},"fi.mau.gif":{"mime_types":{"video/mp4":2}},
                 "m.image":{"mime_types":{"image/*":2},"max_size":104857600}}}"""));
    Instant sent = Instant.parse("2026-10-26T10:00:00Z");
    assertTrue(f.canEdit(sent, sent.plusSeconds(14 * 60)));
    assertFalse(f.canEdit(sent, sent.plusSeconds(20 * 60)));
    assertTrue(f.canDeleteForEveryone(sent, sent.plusSeconds(47 * 3600)));
    assertFalse(f.canDeleteForEveryone(sent, sent.plusSeconds(49 * 3600)));
    assertTrue(f.accepts("m.sticker")); assertTrue(f.accepts("fi.mau.gif")); assertFalse(f.accepts("org.matrix.msc3245.voice"));
    assertEquals(104857600, f.maxSize("m.image"));
    assertTrue(f.oneReactionPerUser());
}
@Test void googleMessagesHasNoEditAndOnlyDeleteForMe() {
    RoomFeatures f = RoomFeatures.parse(json("{\"id\":\"gm\",\"delete_for_me\":true,\"reply\":2}"));
    assertFalse(f.canEdit(Instant.EPOCH, Instant.EPOCH)); assertFalse(f.canDeleteForEveryone(Instant.EPOCH, Instant.EPOCH));
    assertTrue(f.deleteForMe());
}
@Test void allowedReactionsMatchIgnoringVariationSelector() {
    RoomFeatures f = RoomFeatures.parse(json("{\"id\":\"tg\",\"reaction\":2,\"allowed_reactions\":[\"❤\",\"👍\",\"🔥\"]}"));
    assertEquals(List.of("❤️", "👍"), f.reactionsOffered(List.of("❤️", "👍", "😂")));
}
@Test void unknownOffersEverything() {
    assertTrue(RoomFeatures.UNKNOWN.canEdit(Instant.EPOCH, Instant.now()));
    assertTrue(RoomFeatures.UNKNOWN.accepts("m.sticker"));
}
@Test void discordTable() {
    RoomFeatures d = RoomFeatures.discord();
    assertTrue(d.canEdit(Instant.EPOCH, Instant.now())); assertTrue(d.accepts("fi.mau.gif")); assertTrue(d.accepts("m.sticker"));
}
```
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=RoomFeaturesTest` — expected: compilation failure (class missing).
- [ ] **Step 3: Implement `RoomFeatures`** — levels ≥ 1 mean offered; `accepts` checks the `file` map key exists with any MIME level ≥ 1; missing `edit_max_age`/`delete_max_age` = no limit; reaction matching strips U+FE0F on both sides; Discord: edit/delete/reply/reaction/thread full, files incl. sticker and GIF accepted, no voice.
- [ ] **Step 4: Run** the test — expected PASS.
- [ ] **Step 5: Commit** `feat(chat): RoomFeatures from the bridges' com.beeper.room_features`.

### Task 2: Every chat knows its features

**Files:**
- Modify: `src/main/java/app/unichat/model/Chat.java` (field `RoomFeatures features = RoomFeatures.UNKNOWN`, getter/setter)
- Modify: `src/main/java/app/unichat/matrix/MatrixRoom.java` (`JsonNode roomFeatures`)
- Modify: `src/main/java/app/unichat/matrix/SyncProcessor.java` (`applyState`: case `com.beeper.room_features`; `refresh(room)`: set `chat.setFeatures(...)`, Discord network → `RoomFeatures.discord()` when the room has none)
- Test: `src/test/java/app/unichat/matrix/RoomFeaturesSyncTest.java`

**Interfaces:**
- Consumes: `RoomFeatures.parse`, `RoomFeatures.discord()`, `RoomFeatures.UNKNOWN` (Task 1).
- Produces: `Chat.features()`.

- [ ] **Step 1: Write the failing test** — a sync with a WhatsApp portal room carrying `com.beeper.room_features` (`edit_max_age: 900`) → `chat.features().canEdit(...)` follows it; a second sync with a new `id` and `edit: 0` → `canEdit` false (Review Focus 2); a Discord puppet room with no features → `features()` equals `RoomFeatures.discord()`; a plain Matrix room → `UNKNOWN`.
- [ ] **Step 2: Run** — expected FAIL.
- [ ] **Step 3: Implement** in `applyState` / `refresh`.
- [ ] **Step 4: Run** `./mvnw -q test` — expected PASS.
- [ ] **Step 5: Commit** `feat(sync): read each room's features into its chat`.

### Task 3: Message actions follow the network

**Files:**
- Create: `src/main/java/app/unichat/ui/MessageActions.java`
- Modify: `src/main/java/app/unichat/ui/ThreadPane.java` (`messageMenu`, quick-reaction keys 1…6)
- Test: `src/test/java/app/unichat/ui/MessageActionsTest.java`

**Interfaces:**
- Consumes: `Chat.features()`.
- Produces: `enum MessageActions.Action { REPLY, COPY, OPEN_FILE, SAVE_AS, EDIT, DELETE_FOR_EVERYONE, DELETE_FOR_ME, FAVORITE }` and `static Set<Action> of(Message m, RoomFeatures f, Instant now)`.

- [ ] **Step 1: Write the failing tests** — outgoing text in WhatsApp 10 min old → contains EDIT and DELETE_FOR_EVERYONE; 20 min old → no EDIT, still DELETE; incoming image → SAVE_AS and OPEN_FILE, no EDIT; sticker/GIF → FAVORITE; Google Messages outgoing → DELETE_FOR_ME, no DELETE_FOR_EVERYONE; deleted message → empty.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** and use it in `messageMenu` (labels: "Responder", "Copiar texto", "Abrir archivo", "Guardar como…", "Editar", "Eliminar para todos", "Eliminar para mí", "Agregar a favoritos"); reactions in the menu and the 1…6 keys use `f.reactionsOffered(EmojiPicker.QUICK)`.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(thread): message actions follow what the network supports`.

### Task 4: "Guardar como…"

**Files:**
- Create: `src/main/java/app/unichat/ui/MediaSaver.java`
- Modify: `ThreadPane.java` (SAVE_AS action), `MediaViewer.java` (button "Guardar como…")
- Test: `src/test/java/app/unichat/ui/MediaSaverTest.java`

**Interfaces:**
- Produces: `static String suggestedName(Message m, String mime)` (pure) and `static void save(Window owner, ChatService svc, Message m)` (FileChooser + copy of `svc.media(url, 0, 0)`).

- [ ] **Step 1: Write the failing test** — `suggestedName` keeps a safe file name; `"../../etc/passwd"` → `"passwd"`; `null` file name + `image/jpeg` → `"imagen-<yyyyMMdd-HHmmss>.jpg"` from the message time; sticker webp → `".webp"` (Review Focus 4).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(media): Guardar como… for received media`.

### Task 5: Side panel with Perfil and Emojis

**Files:**
- Create: `src/main/java/app/unichat/ui/SidePanel.java`, `src/main/java/app/unichat/ui/EmojiGrid.java`
- Modify: `MainView.java` (`threadArea.setRight(sidePanel)`, `showPanel(SidePanel.Tab)`, `togglePanel(SidePanel.Tab)`; `toggleInfo()` → `togglePanel(PROFILE)`), `Composer.java` (emoji button → `onPanel.accept(EMOJIS)`; new sticker/GIF button → `STICKERS`), `EmojiPicker.java` (emoji data reused by `EmojiGrid`), `app.css`
- Test: `src/test/java/app/unichat/ui/SidePanelUiTest.java`

**Interfaces:**
- Produces: `enum SidePanel.Tab { PROFILE, EMOJIS, STICKERS, GIFS }`; `SidePanel(ChatService, InfoPane, Consumer<String> insertText)`; `void show(Chat c, Tab t)`; `Tab current()`; Stickers/GIFs tabs start as placeholders filled by Tasks 7 and 9.

- [ ] **Step 1: Write the failing UI test** — demo `MainView`, open `!familia:demo`, `togglePanel(PROFILE)` shows the info content; switching to EMOJIS keeps the panel open; clicking an emoji inserts it into the composer; `togglePanel(EMOJIS)` again closes it; F6 cycle includes the panel.
- [ ] **Step 2: Run** with `-Dunichat.uiTests=true` — FAIL.
- [ ] **Step 3: Implement**; tab bar with accessible tab names; remembers last tab in Settings `panel.tab`.
- [ ] **Step 4: Run** UI test and snapshot (`-Dunichat.snapshot`) — check the layout visually.
- [ ] **Step 5: Commit** `feat(ui): Telegram-like side panel with Perfil and Emojis`.

### Task 6: `MediaLibrary` — favourite and recent stickers/GIFs

**Files:**
- Create: `src/main/java/app/unichat/model/MediaLibrary.java`
- Test: `src/test/java/app/unichat/model/MediaLibraryTest.java`

**Interfaces:**
- Produces: `record Item(Kind kind, String url, String mime, int width, int height, JsonNode info)` with `enum Kind { STICKER, GIF }`;
  `MediaLibrary(Path file)`; `List<Item> favorites(Kind)`, `List<Item> recents(Kind)`; `void addRecent(Item)` (most recent first, unique by url, max 60);
  `boolean toggleFavorite(Item)`; persisted as JSON in `<data dir>/media-library.json`.

- [ ] **Step 1: Write the failing tests** — recents dedupe and cap at 60; favourites survive reload from file; corrupt file → empty library, no exception.
- [ ] **Step 2–4:** run (FAIL), implement, run (PASS).
- [ ] **Step 5: Commit** `feat(media): favourite and recent stickers and GIFs`.

### Task 7: Stickers tab and sending stickers

**Files:**
- Create: `src/main/java/app/unichat/ui/StickerGrid.java`
- Modify: `SidePanel.java`, `ThreadPane.java` (FAVORITE action; received/sent stickers → `addRecent`), `ChatService.java` (`CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item)`), `MatrixChatService.java` (send `m.sticker` with `url`, `body`, `info` incl. `fi.mau.bridged_sticker` when present), `MockChatService.java`
- Test: `src/test/java/app/unichat/matrix/SendStickerTest.java`, extend `SidePanelUiTest`

- [ ] **Step 1: Failing tests** — `sendSticker` posts an `m.sticker` event whose content keeps `info` (fake homeserver like `StartupBeforeSyncTest`); UI: Stickers tab lists favourites then recents; in a room whose features reject `m.sticker` items are disabled with "Esta red no acepta stickers".
- [ ] **Step 2–4:** run, implement, run.
- [ ] **Step 5: Commit** `feat(stickers): Stickers tab with favourites and recents`.

### Task 8: GIF providers

**Files:**
- Create: `src/main/java/app/unichat/gif/GifProvider.java`, `GiphyProvider.java`, `KlipyProvider.java`, `TenorProvider.java`, `GifSearch.java`
- Test: `src/test/java/app/unichat/gif/GifSearchTest.java`

**Interfaces:**
- Produces: `record Gif(String provider, String id, String title, String previewUrl, String mp4Url, String gifUrl, int width, int height)`;
  `interface GifProvider { String id(); String name(); List<Gif> search(String q, int limit) throws IOException, InterruptedException; List<Gif> trending(int limit) throws … }`;
  `GifSearch(List<GifProvider>)` with `List<Gif> search(String q, int limit)` that queries providers in parallel (5 s timeout each) and interleaves results.
- Each adapter takes `(String apiKey, String baseUrl)` so tests point it at a fake server; the real base URLs are verified against each provider's current API docs during implementation (Giphy `/v1/gifs/search`).

- [ ] **Step 1: Failing tests** — Giphy JSON fixture parsed into `Gif`s; two providers interleaved; one provider throwing or timing out → the other's results still returned (Review Focus 5); the request carries only `q`, `limit`, `api_key` (no user data).
- [ ] **Step 2–4:** run, implement, run.
- [ ] **Step 5: Commit** `feat(gif): pluggable GIF providers (Giphy, Klipy, Tenor)`.

### Task 9: GIFs tab, Ajustes → GIFs, sending GIFs

**Files:**
- Create: `src/main/java/app/unichat/ui/GifGrid.java`
- Modify: `SidePanel.java`, `SettingsWindow.java` (page "GIFs": per provider a switch + key field + link to get a key), `Settings.java` (`gif.<id>.enabled`, `gif.<id>.key`), `ChatService.java` / `MatrixChatService.java` (`sendGif(String chatId, Path file, String mime, int w, int h)`: upload, send `m.video` with the GIF flags the bridges read — confirm the exact `info` keys in mautrix-go before implementing), `MockChatService.java`
- Test: extend `GifSearchTest` (settings → providers), `SendGifTest`, UI test for the tab

- [ ] **Step 1: Failing tests** — `sendGif` posts `m.video` with the GIF flags; GIFs tab without any provider shows "Activa un proveedor en Ajustes → GIFs" plus favourites/recents; search shows results and clicking one sends it and adds it to recents.
- [ ] **Step 2–4:** run, implement, run.
- [ ] **Step 5: Commit** `feat(gif): GIFs tab, Ajustes → GIFs and sending GIFs`.

---

After Task 9: whole-branch review, then the follow-up plan for stage 5 (per-network packs: Telegram, WhatsApp/Signal by link, Slack emoji, Discord custom emoji).
