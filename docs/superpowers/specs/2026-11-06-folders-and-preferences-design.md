# Folders and portable preferences — design

Date: 2026-11-06 · Status: proposed

## Why

The user wants to sort their chats into folders of their own, as in Discord or Telegram, and to take their preferences
to any installation by copying files. Messages can be downloaded again, and it doesn't matter where they live. What
can't be recovered is what the person decided (who is archived, what is in each folder), and today almost all of that
lives in each machine's local Matrix server.

Success: in a freshly installed Unichat, without copying the server, the preference files are copied, the accounts are
linked, and as the chats appear, each one ends up archived, a favorite or inside its folders as before.

## Goals

1. A folders icon in the rail, right above Settings.
2. Folders made by the person; a chat can be in several.
3. Every preference lives in files that can be copied; secrets, separately.
4. Preferences hold in a new installation: they are saved by the chat's identity on its network, not by the room id.

## Out of scope

- Syncing preferences between machines by itself: the files are copied by hand.
- An export or import button.
- Syncing with the phone more than is done already (archiving stays one-way).
- Reordering folders or the chats inside them, and folders inside folders.
- Copying messages or media.

## What must not happen

- Losing preferences silently, for example by overwriting a file that couldn't be read.
- The copied file carrying a token or a password.
- Deleting a folder touching the chats in it.

## Approaches considered

1. **The file is the only source of truth (chosen by the user).** Unichat reads and writes there and stops writing those
   preferences to Matrix. What comes from the phone comes in as input.
2. **The file rules and is also copied to Matrix.** It keeps other Matrix clients up to date, which the user doesn't
   use, at the cost of two copies that can disagree.
3. **Matrix rules, with export and import.** The cheapest, but it's a snapshot one has to remember to take, and it
   doesn't meet "preferences live in files".

For folders, keeping them in a tag per chat was rejected: it allows neither empty folders nor an order.

## Files

All in `Settings.dataDir()`.

| File | Contents | Copied? |
|---|---|---|
| `preferences.json` (new) | Per-chat preferences and folders | Yes |
| `settings.properties` | The app's settings, now without session or secrets | Yes |
| `media-library.json` | Saved stickers and GIFs | Yes |
| `session.properties` (new) | Session, secrets and what belongs to this machine | **No** |
| `server-seen.json` (new) | What this machine's server has said (see Rules) | **No** |

`session.properties` keeps the keys `matrix.*`, `session.*`, `server.*`, `window.*`, `discord.guilds.entire`,
`discord.guilds.paused` and `gif.<id>.key`. The Discord ones record what was already asked of one particular bridge:
copied, a new installation would neither load the channels nor pause archived servers. The GIF keys are secrets: they
are typed again on each machine. The rest stay in `settings.properties`. On the first start, those keys move out of
`settings.properties` so that a failure halfway doesn't lose the session (the new file is written first). On Mac and
Linux, `session.properties` gets 0600 permissions. `Settings` keeps its API: its callers don't change.

`settings.properties.docker-backup` may hold an old token: the migration deletes it.

### `preferences.json`

```json
{
  "version": 1,
  "chats": {
    "telegram:123456": { "archived": true, "muted": true, "fromServer": ["archived"] },
    "whatsapp:573001234567@s.whatsapp.net": { "favorite": true, "pinned": true },
    "matrix:!abc:example.org": { "lowPriority": true, "reminderAt": "2026-11-07T14:00:00Z" }
  },
  "folders": [
    { "id": "f3a1", "name": "Work", "chats": ["telegram:123456", "matrix:!abc:example.org"] }
  ]
}
```

- An explicit `false` is kept: it means "the person decided" (see Rules).
- `fromServer` lists the fields copied from the server that the person hasn't touched in Unichat yet.
- Entries for chats that haven't appeared in this installation yet are kept untouched.
- Atomic write (a temporary file, then a move).
- If the file can't be read, it's renamed to `preferences.json.bad-<date>`, the person is told with a visible message,
  and it starts empty. An unknown future version is treated the same way.

### The chat's identity

The key is `"<protocol>:<channel.id>"`, with the values of `m.bridge` / `uk.half-shot.bridge` that `SyncProcessor`
already reads (`room.bridgeProtocol`, `room.bridgeChannelId`). Chats without that data use `"matrix:<room id>"`.

- A bridged room whose bridge state hasn't arrived yet must not record anything under `matrix:`: if a chat's key changes
  from `matrix:` to the bridge's, its entry moves, inside the folders too.
- The key doesn't include the account: two accounts on the same network share preferences for the groups and contacts
  they have in common, direct messages included. That's accepted: today the user has a single account per network
  (decided with them). Whoever plans to add a second account must know the saved keys depend on this.

## Preference rules

They apply to archived, muted, favorite, low priority, pinned and reminder.

- **Unichat no longer writes them to Matrix**, except for the mute rule (below). What the person chooses is written only
  to `preferences.json` and takes that field out of `fromServer`.
- **Reading the server (`m.tag`, `m.push_rules`, reminder):** if the field isn't in the file, or is in `fromServer`,
  what comes from the server is followed, both ways, and stays in `fromServer`. If the person already touched it in
  Unichat, the file wins and what comes is ignored.
- **A new server erases nothing:** a `false` from the server only counts if that same server said `true` before. A
  freshly installed server has no tags, and without this everything copied from the old one would be lost. That memory
  goes in `server-seen.json` (new, the machine's own, not copied). If no data about a chat has come from the server yet,
  nothing changes.
- **Reminders:** the server's value only comes in if the field isn't in the file. When it fires or is removed, it
  becomes `"reminderAt": null`, so the server's old value doesn't fire it again.
- That one rule covers three cases with no separate code: the migration (what's already on the server comes in the
  first time), what is done on the phone, and a new installation.
- Accepted consequence: if the person already touched a chat's field in Unichat, what they do later with that field on
  the phone changes nothing here. If they didn't, Unichat follows the phone, unarchiving included.
- **The exception, the mute rule:** unread counts only come from the server (`unread_notifications`), and a mute rule
  left there leaves the chat without a count for good. So the file stays the truth, but Unichat applies the file's
  `muted` value to the server: when it changes and when a chat appears in a new installation. The loop must be avoided:
  that rule, written by Unichat, doesn't count as a change made on the phone.
- `QuietRule` stays the same: muted is archived, and a server carries its channels along.
- **Stays on the server:** unread (`m.marked_unread`), which is the messages' state and not a preference, and pausing
  Discord servers (`setGuildPaused`), which is an order to the bridge.
- The builder must confirm that `Notifier` decides by `Chat.muted` and not by what the server returns.

Demo mode (`MockChatService`) uses the same store, so the UI can be tried and photographed.

## UI

Texts in Spanish (the app's only language then). It follows the accessibility rules of `CLAUDE.md`: accessible text,
keyboard, 160 % and high contrast.

**Rail.** A folder button right above Settings (`Icons` doesn't have the icon yet), marked active while in folders. No
count: each folder shows its own, and adding them up would count twice the chats that are in several.

**Folder list.** When pressed, the chat column shows the list of names, each with its number of unread chats, and a
"New folder" button that asks for the name. With no folders, a text explains how to make the first one. Right click on
a folder: Rename and Delete (Delete asks for confirmation and says the chats aren't deleted).

**Inside a folder.** Like a server's channel view: the list is replaced, with a header holding a back button and the
name. It's not an accordion. The chats use the same cell and the same order as the inbox. Opening a chat doesn't leave
the folder. Back with the button, Esc or the left arrow. Typing in the search leaves the folder, as it already does with
a server. The filter chips aren't shown inside.

**What shows inside.** Everything the person put there, archived chats too: if they put it in a folder it's because they
want to see it there. Chats that don't exist in this installation yet aren't shown, but are kept.

**Adding and removing.** In a chat's context menu (`ChatMenus`), a "Folders" submenu with each folder checked or not, and
"New folder…". Checking and unchecking adds and removes, so a chat can be in several. Inside a folder, also "Remove from
this folder". A Discord server can be added too; opening it leads to its channels. Not offered on bridge bots nor on the
personal space.

## Structure

- `ChatListPane` already has 723 lines (limit 800): folders go in new `ui` classes, reusing `ChatCell`.
- A new role in `service` for folders (list, create, rename, delete, add, remove), part of `ChatService`.
- The `preferences.json` store and its rules, without JavaFX and covered by unit tests. The layers follow
  `docs/ARCHITECTURE.md`.

## Tests that matter

- New installation: with a `preferences.json` and new rooms (other ids, same `channel.id`), the chats get everything
  back.
- Rules: what comes from the server is followed both ways while the person doesn't touch the field; after that it's
  ignored, and an explicit `false` holds.
- Mute: removing it in Unichat brings the counts back; in a new installation, what's muted shows no count.
- Damaged file: kept aside, not overwritten, and the person is told.
- `settings.properties` migration: the session keeps working; `settings.properties` is left without a token; running it
  again harms nothing.
- UI: create, enter, go back, add a chat to two folders, delete a folder and check the chat is still there.

## Known risk

Stickers saved in `media-library.json` may point to media of the original machine's bridge
(`mxc://media-<bridge>.localhost/…`). In a new installation they may not load until seen again. Not solved here.

## Left to the builder

Class names, the shape of the folder id, when to save to disk so as not to write too often, and how to report the
damaged file within the existing notice pattern.
