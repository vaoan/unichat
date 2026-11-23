# Archived Discord rests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** archived Discord servers and channels are left out of Unichat's sync (`room.not_rooms`) and woken while the person has Archivados open, with no message lost.
**Spec:** `docs/superpowers/specs/2026-11-23-discord-archive-rest-design.md`. Read it whole.

## Global Constraints
- **Discord only.** Never DMs, never other networks, never a chat that isn't archived.
- **`notify.archived` on:** nothing rests.
- **Waking order:** bridge mode `create-on-message` for archived servers, the filter, a catch-up sync limited to the woken rooms, the `TaskBar` text with N of M, then "Al día".
- **UI rules:** texts through I18n in English and Spanish, reachable by keyboard and screen reader, fine at 160 %, headless UI tests only.
- **Tests:** they wait on what the code does, never on time.

## Review Focus
1. A message sent to a resting room appears after waking. `aMessageWhileRestingIsShownAfterWaking`
2. Archiving or unarchiving while Archivados is open or closed. `archivingMovesARoomInOrOutAtOnce`
3. Leaving Archivados mid catch-up. `leavingMidCatchUpFinishesThenRests`
4. `notify.archived` toggled on while rooms rest wakes them at once. `notifyArchivedOnWakesEverything`
5. A filter with thousands of rooms: no URL-length failure. Post it with `/user/{id}/filter`, then sync by `filter=<id>`. `aFilterOfThousandsOfRoomsIsPostedNotInlined`

### Task 1: The whole feature
Files: `matrix/` (the sync filter, `DiscordServers`, `SyncProcessor` / the sync loop), the archive view in `ui/chats`, `TaskBar`, I18n.
- [ ] Write the Review Focus tests plus the spec's test list first, and watch each one fail.
- [ ] Implement.
- [ ] Add notes to `matrix/CLAUDE.md` and `ui/chats/CLAUDE.md`.
- [ ] Commit: `perf(matrix): archived Discord rests out of the sync, woken while the archive is open`.
