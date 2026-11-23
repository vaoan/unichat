# Archived Discord rests — design

## Intent
The person's account is in 3 699 rooms, nearly all channels of 123 Discord servers. Every incremental `/sync` makes tuwunel walk all of them, about 0.4 s of server CPU each time. With about 17 syncs a minute, the homeserver runs at 25–60 % of a core while the window is closed.

Measured on 2026-11-23: excluding rooms with the sync filter's `room.not_rooms` cuts a sync to about 0.03 s.

The person's call (2026-11-23):
- What is archived in Discord rests, both whole servers and single channels.
- Opening the archive brings them back, saying clearly that they're coming back and receiving everything again.
- This applies to Discord only.

## Goals
- **What rests:** every Discord room that is archived in Unichat.
  - The rooms of an archived Discord server (its space and every channel in it).
  - A single archived Discord channel.
  - Discord DMs never rest: one room each, and personal.
- **Resting means:**
  - **Unichat's sync filter** lists these rooms in `room.not_rooms`, so tuwunel skips them on every sync.
  - **The bridge:** archived servers already bring nothing (`DiscordServers`, `guilds bridging-mode nothing`). That stays as it is. A single channel can't be paused at the bridge, so its messages keep reaching tuwunel, cheaply, and Unichat doesn't read them while it rests.
- **Opening Archivados** (the archive view) wakes the resting rooms:
  1. Servers go back to `create-on-message` at the bridge.
  2. The rooms leave `not_rooms`.
  3. Unichat catches up on what they missed: one sync limited to those rooms (`room.rooms`, no `since`, a small timeline limit), then the usual incremental sync covers all of them.
  4. A bar on top (`TaskBar`) says "Reactivando lo archivado de Discord: recibiendo todo de nuevo…" with N of M, then "Al día" for a few seconds.
  5. While awake, archived chats behave as today.
- **Leaving Archivados** puts them back to rest, at once. If a catch-up is still running when the person leaves, it finishes first and then they rest.
- **Notifications:** with "Avisar de los archivados" (`notify.archived`) on, archived Discord rooms never rest, because they must notify. With it off (the default), they rest.
- **Unread counts:** a resting room keeps the count it had when it went to rest, and gets the real one when woken.
- **Unarchiving a chat or server** wakes it for good. Archiving one puts it to rest.

## Non-goals
- Other networks.
- Leaving Matrix rooms, or unbridging channels. Nothing is deleted, and waking needs no new joins.

## Anti-goals
- **A message lost for good.** Everything a resting room got is there once it wakes, through its catch-up.
- **An archived chat that should notify staying silent.** That's why rooms never rest while `notify.archived` is on.
- **A chat that isn't archived resting.**

## Approaches
1. **The sync filter's `not_rooms`, plus the existing server pause at the bridge** (chosen). Measured at about 13 times cheaper per sync. Nothing leaves Matrix, and waking is a catch-up sync.
2. **Leaving the rooms** (the person's Matrix user leaves). Rejected: waking needs joins and history reloads, and the bridge may re-invite or treat a left room as gone.
3. **Delaying syncs while unseen.** Rejected: it delays notifications, against the battery work's anti-goal.

## Tests
- **The filter:** it lists exactly the archived Discord rooms (server and channel), never DMs, other networks or rooms that aren't archived. It lists none while `notify.archived` is on.
- **Waking:** opening Archivados wakes them in this order: bridge mode, filter, catch-up of the missed events, then the bar's text and progress. Leaving puts them back to rest.
- **Catch-up:**
  - a message sent to a resting room while it rests is shown after waking;
  - its unread count is right;
  - archiving or unarchiving moves a room in or out at once.
- **Headless UI:** the bar, reachable by keyboard and screen reader, at 160 % text.
- **Cost (a measured check, not a test):** with the archive closed on the person's Mac, tuwunel's CPU before and after.

## Left to the builder
- How the filter id is cached and replaced (a filter change is a new `/user/{id}/filter`, so the next sync's `since` stays valid).
- The catch-up's timeline limit.
- Where the archive view's open and close events come from.
