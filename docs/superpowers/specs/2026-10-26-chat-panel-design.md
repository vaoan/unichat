# Side panel, per-network options, stickers, emojis and GIFs

## Why
The user has no sticker picker, custom emojis nor GIFs; can't download what they're sent nor save GIFs to favorites;
and every chat shows the same options even when its network doesn't support them (or lacks the ones it does support).
They want, as in Telegram, **a panel on the right in every chat** to see the profile and, without closing it, switch to
the emoji, sticker or GIF palette.

## Goals
- A single side panel per chat with the tabs **Profile · Emojis · Stickers · GIFs**.
- Each chat offers **its network's native options**, read from the bridge itself (always up to date).
- **Download** ("Save as…") received photos, videos, files, audio and stickers.
- **Favorites and recents** of stickers and GIFs, **shared across all chats and networks**.
- Each network's sticker/emoji packs where the bridge offers them.
- **Search GIFs** with configurable outside providers (Giphy first).
- Stickers and photos open large with a single click (done: `db039b4`).

## Non-goals (for now)
- Creating or editing sticker packs.
- Sending Discord's native stickers (its bridge sends them as an image).
- Full polls, location and disappearing messages: at this stage they are only **shown or offered** where the network
  supports them; their creation UI comes in a later stage.

## Anti-goals
- Offering an action the network refuses (for example "Edit" on Google Messages, or past WhatsApp's 15 min limit).
- Sending a GIF provider anything other than the text the user searches (no chats, no contacts, not their id).
- Breaking use with outside servers (Docker or others): with no data from the bridge, today's behaviour is used.

## Where the data comes from (checked by reading the bridges' code)
- **Per-chat options:** the **`com.beeper.room_features`** state event each bridgev2 bridge publishes in every room
  (mautrix-go's `event.RoomFeatures` structure). Fields used: `file` per type (`m.image`, `m.video`, `m.audio`,
  `m.file`, `org.matrix.msc3245.voice`, `fi.mau.gif`, `m.sticker`: MIME types and maximum size), `edit` +
  `edit_max_age` + `edit_max_count`, `delete` + `delete_max_age`, `delete_for_me`, `reply`, `thread`, `reaction` +
  `reaction_count` + `allowed_reactions` + `custom_emoji_reactions`, `poll`, `location_message`,
  `disappearing_timer`, `read_receipts`, `typing_notifications`, `max_text_length`. Levels: −2 rejected, −1 lost,
  0 no, 1 partial, 2 full.
- **Discord** (the old bridge) publishes nothing: a fixed table in Unichat (custom stickers and emojis are received;
  GIFs, replies, threads, edit and delete with no limit; reactions with custom emojis already seen).
- **Packs:** the bridge's API `GET /v3/image_pack/list` (Telegram: every installed pack; WhatsApp/Signal: empty) and
  `import?pack_url=` (Telegram `t.me/addstickers|addemoji`, WhatsApp `wa.me/stickerpack/…`, Signal
  `signal.art/addstickers#…`). The bridge writes the pack as `m.room.image_pack` / `im.ponies.room_emotes` (MSC2545)
  in the user's space. Slack publishes the workspace's custom emojis as a pack. A pack sticker carries
  `info["fi.mau.bridged_sticker"]`; when it's forwarded, the bridge sends the native sticker.
- **GIFs:** no network offers a search; all accept a GIF as `fi.mau.gif` (mp4 video or image/gif).

## Design (decided)

### 1. Side panel
- It replaces the current "Information" panel and keeps its contents in the **Profile** tab (name, details, media,
  members, actions).
- It opens from the header's ⓘ button (Profile), the message box's emoji button (Emojis) and a new sticker/GIF button
  (Stickers / GIFs). Switching tabs doesn't close it; Esc or the same button closes it.
- It remembers the last tab and the width; reachable by keyboard (F6 includes it as a zone) and with a screen reader.

### 2. Options by network (`RoomFeatures` in Unichat)
- `SyncProcessor` reads each room's `com.beeper.room_features`; the chat keeps its capabilities.
- The message menu: **Edit** and **Delete for everyone** only if the network allows them and within the time limit
  (`edit_max_age`, `delete_max_age`); **Delete for me** where it exists; **Reply** as `reply` says.
- Reactions: if there are `allowed_reactions`, the picker shows only those; if `reaction_count` is 1, choosing another
  replaces the previous one.
- The message box: the attach, sticker, GIF and voice note buttons show only if the network accepts them; it warns
  before sending a file bigger than `max_size`.
- Without `room_features` (an old outside server or an unknown network): the current behaviour.

### 3. For every chat (they don't depend on the network)
- **Save as…** in the menu of every photo, video, audio, file and sticker, and a button in the viewer.
- **Favorites** (a star in the viewer and in the message menu) and **Recents** of stickers and GIFs, kept in Unichat
  (not on the server), usable in any chat whose network accepts that type. If it doesn't, the item shows dimmed with
  the explanation.

### 4. Stickers and emojis
- The **Emojis** tab: the usual emojis + the chat network's custom emojis (Slack, Telegram Premium) + on Discord, the
  custom ones already seen in that server.
- The **Stickers** tab: Favorites, Recents and the chat network's packs (Telegram automatically; WhatsApp and Signal
  with "Add pack by link").

### 5. GIFs with configurable providers
- A `GifProvider` interface (search, trending) with adapters: **Giphy** (first), Klipy and Tenor.
- **Settings → GIFs:** turn providers on and paste each one's key; the search asks the active ones and mixes the
  results. Adding another provider = one new adapter.
- Only the searched text is sent to the provider. The chosen GIF is downloaded and sent as `fi.mau.gif`.

## Build order
1. Side panel with Profile (what there is today) + the Emojis tab + "Save as…".
2. `room_features` → menus and buttons by network.
3. Sticker/GIF favorites and recents + the Stickers tab.
4. GIFs: Giphy + Settings → GIFs + Klipy/Tenor.
5. Packs by network (Telegram, WhatsApp/Signal by link, Slack) and custom emojis.

## For whoever builds it
- Test each piece of logic (reading `room_features`, edit/delete time limits, the reaction filter, mixing providers)
  with unit tests; the screens with UI tests in an invisible window, and snapshots.
- The project's accessibility: screen-reader texts, keyboard and high contrast in everything new.
- **The Giphy key:** the user gets it (a free developer account) and pastes it in Settings; Unichat ships no key of
  its own.
