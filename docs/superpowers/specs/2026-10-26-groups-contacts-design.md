# Groups, servers and contacts — design

Date: 2026-10-26 · Status: proposed

## Why

In Unichat, Telegram groups with topics and Discord servers can't be used. Also, many contacts are missing: they don't
show in the inbox, so they can't be written to. The user wants two ways to see each network:

- **Inbox** (what there is today): who writes to you, sorted by activity and with notifications.
- **Groups**: your servers and groups with their channels or topics.

They also want to be able to talk to any contact on the network.

## What we found (real data, Oct 26)

- The bridges represent containers as **Matrix spaces** (`m.room.create` with `type: m.space`, children in
  `m.space.child`). There are three cases:
  - each network's personal folder (Telegram: 15 children);
  - Telegram groups with topics (forums), whose children are the topics;
  - on Discord (legacy bridge), one space per server with the channels as children, once the server is turned on with
    the "Servers" button.
- Unichat doesn't tell spaces apart: it shows them as chats ("Empty chat", with no way to write). That's the failure
  seen in "Telegram groups don't work".
- Creation limits at sign-in, in the configs `BridgeConfig` generates:
  - Telegram: `sync.create_limit: 15`, `login_sync_limit: 15`;
  - Discord: `startup_private_channel_create_limit: 5`;
  - WhatsApp: `history_sync.max_initial_conversations: 50`.
- bridgev2's provisioning API (`/_matrix/provision/v3/…`):
  - `GET contacts` (lists `{id, name, identifiers[], mxid, dm_room_mxid?}`);
  - `POST search_users {query}`;
  - `GET resolve_identifier/{id}`;
  - `POST create_dm/{id}`.
  - Tried live: WhatsApp (158 contacts, search), Telegram (129 contacts of which only 4 have a chat, search), Facebook
    and Instagram (search only). X and Discord have neither (404).

## Goals

1. **Spaces out of the inbox.** A space never shows as a chat. Its children do, as normal chats.
2. **An Inbox | Groups switch** in the chat list's header, remembered per network (`list.view.<network>`). Only shown
   when the network has spaces with children.
3. **The Groups view.** A tree in the list:
   - Each space is a row that expands (avatar, name, an unread count that adds up its children).
   - Under it go its children: channels on Discord, topics on Telegram.
   - A child that is also a space nests one level deeper.
   - The loose chats that hang from no space other than the personal folder are grouped at the end under "Direct
     chats and others". The personal folder is not a group: it's the set of all of them.
   - Opening a child works the same as from the Inbox.
   - Keyboard: arrows to move, right/left or Enter to expand, Enter on a chat to open it.
   - Screen reader: "Server X, 3 channels, 5 unread, collapsed".
4. **More syncing.** Raise the limits in the bridges' configs:
   - Telegram `create_limit` and `login_sync_limit`: 100;
   - Discord `startup_private_channel_create_limit`: 50;
   - WhatsApp `max_initial_conversations`: 200.

   The bridges already respect the waits each network asks for. It applies when the config is generated again, without
   signing in again.
5. **New chat.** A button and a shortcut (⌘N / Ctrl+N) open a contact finder:
   - You choose the network; if there are several accounts, the account too.
   - The contact list (`contacts`) shows and, as you type, the results of `search_users` (with a 300 ms wait between
     keys).
   - Typing a phone number or an @user offers "Write to <identifier>", which uses `resolve_identifier`.
   - On choosing: if the contact already has a chat (`dm_room_mxid`), it opens; if not, `create_dm` creates it and it
     opens when it arrives through sync.
   - Networks with neither a list nor a search (X, Discord) say why and how to reach the person (on Discord, through
     their servers).

## Non-goals

- Creating new groups (`create_group`) and managing members.
- Joining new Discord servers or Telegram groups from Unichat.
- Changing the history sync (backfill) beyond the creation limits.
- Discord without servers turned on: the server picker that already exists stays.

## Anti-goals

- The Inbox changing its behaviour: filters, archived chats, notifications and order stay as they are.
- Sending the network anything more than the search the user types.
- A bridge without these features breaking the screen: it degrades with a clear message.

## Approaches considered

- **A. A tree in the list (chosen by the user, following the recommendation).** It works the same for Discord and for
  Telegram, and the left bar stays a bar of networks. Cost: a list with two modes.
- B. Servers in the left bar. It's the Discord style, but with many servers the bar becomes unmanageable, and it doesn't
  fit Telegram's topics.
- Syncing: **option 2 (chosen)**, high limits plus an on-demand "New chat". Creating everything at sign-in was rejected
  because it fills the inbox slowly and Telegram slows down whoever asks for a lot at once.

## Constraints

- Java 21 + JavaFX; Windows too. Visible text is in Spanish (the app's only language then).
- Accessibility (CLAUDE.md): accessible text, reachable by keyboard, works at 160 % and with high contrast.
- No personal data in the logs (Redactor).
- Tests: the pure logic (the tree from spaces, filtering out spaces, parsing contacts) with unit tests, and the UI with
  `-Dunichat.uiTests=true`.

## Answers to the design review (my decisions, marked as such)

1. **The account with several accounts:** `Account`'s id already carries the bridge's login (`<protocol>:<loginId>`,
   see `MatrixChatService.accountId`). The contact calls go with `?user_id=<me>&login_id=<loginId>` and with the same
   Matrix token the dynamic sign-in already uses (`BridgeLogin`).
2. **The tree:** a child counts if it's in the space's `m.space.child` with non-empty content, or if it declares an
   `m.space.parent` to that space. Unichat's sync filter doesn't limit the state types, so those events already arrive.
   The plan starts with a test that confirms it with a recorded sync. Discord (legacy bridge) follows the same model
   once a server is turned on; its "New chat" only explains how to reach people.
3. **YAML paths (checked in the configs generated today):**
   - Telegram: `network.sync.create_limit` and `network.sync.login_sync_limit`;
   - Discord: `bridge.startup_private_channel_create_limit`;
   - WhatsApp: `network.history_sync.max_initial_conversations`.

   `BridgeConfig`'s test checks that each key already existed in the bridge's template, so that a misspelt path fails
   instead of creating a new key the bridge ignores.
4. **Children you haven't joined yet:** shown dimmed with "Join". Pressing it, Unichat joins the chat and opens it.
   Children the sync doesn't know (only in `m.space.child`) take their name from the space's `/hierarchy`.
5. **The personal folder:** the space whose `m.bridge` has a `channel.id` starting with `__` (bridgev2 calls it
   `__personal…`; in your data it shows as `__pe…`). Not shown as a group.
6. **A space's unread count:** adds up the joined children that are neither archived nor muted. The screen reader's text
   says that same number.
7. **⌘N / Ctrl+N:** not used in `MainView`.

## Decisions left to the builder

- How the tree is represented inside (a `SpaceTree` model or similar), as long as it comes from the sync's
  `m.space.child` / `m.space.parent` events.
- The exact design of the space row and of the New chat dialog, following the style of `AddAccountPane` and
  `BridgeLoginPane`.
- The order inside a space: `m.space.child`'s `order` if there is one; if not, the name.
