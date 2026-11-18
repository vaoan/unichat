# Chat text backup — design

## Intent
A message deleted in a chat (by its sender, on any network) can still be read: Unichat keeps a copy of the texts of the
chats that ask for it, next to what auto-save already keeps (photos, videos…). "What did it say?" is answered from that
copy, or "there's no copy of it" when there isn't one (it came and went while no computer of the person's was on, or the
chat didn't keep texts then). The person uses several computers; each one's copy counts, merged, live, no restart.

## Goals
- A chat's options gain "Texts" among the kinds auto-save keeps, for what others send and for what I send.
- A deleted (or edited) message's menu has "See what it said": every version kept, or "no copy".
- Every computer connected to the same cloud account sees what any of them kept, within about a minute, and asks the
  cloud first when the person looks.
- A readable `.txt` per chat in the chat's folder, to read or recover by hand without Unichat.

## Non-goals
- Media inside deleted messages (auto-save already keeps files as an archive).
- Searching texts across chats (a local SQLite index could be built from the logs later).
- Restoring a message into the chat itself.

## Anti-goals
- Two computers writing the same file (cloud conflicts, lost lines). Never.
- A live database in a synced folder (SQLite there corrupts or diverges).
- Text from strangers reaching a path, a shell, or forging an entry.

## Approaches considered
1. One readable `.txt` per chat shared by all computers, merged by each: simple, but every computer rewrites the same
   file, so cloud clients make conflicted copies and lines get lost. Rejected.
2. SQLite in the saved folder: fast lookups, but SQLite files in sync folders corrupt or diverge, need a native library
   per system, and aren't readable without a tool. Rejected (kept as a possible local index later).
3. **Chosen:** each computer appends only to its own files (JSON Lines, one file per chat and month); every computer
   reads everyone's and merges by message id. No file ever has two writers, so the cloud never sees a conflict; a month
   that ended never changes again, so it goes up once. A readable `.txt` per chat is regenerated locally from the merge.

## Design
- **Kind.** `SaveKind.TEXTS`, opt-in (`Flag.AUTO_SAVE_TEXTS`, `AUTO_SAVE_MINE_TEXTS`: a positive flag, unlike the other
  kinds' "left out" ones, so chats already saving don't start keeping texts without being asked).
- **Capture.** On `messagesChanged`, on the calling thread, a snapshot of the chat's text messages (id, time, sender,
  text) is taken and handed to a thread of its own: texts never wait behind a video downloading, so a message deleted a
  minute later was already kept. Only text, notices and emotes; nothing deleted, nothing still sending.
- **Own log.** `<data>/texts/here/<chat key> <yyyy-MM>.jsonl`, appended only. Chat key: the first 12 hex of the SHA-256
  of the chat's id (same on every computer: same Matrix account). A line per version: id, chat, time, when it was seen,
  sender name and id, mine, text. Written when this computer hasn't written that (id, text) before (edits add a line).
- **Others' logs.** `<data>/texts/<machine id>/…`, copies of what other computers wrote, brought by the cloud.
- **Merge.** All folders' files of a chat key; versions of a message are its distinct texts, oldest seen first;
  malformed lines skipped.
- **Readable copy.** `Chat (<Network>) <chat key>.txt` in the chat's folder: a comment line naming the chat, then each
  entry "date · sender · {id}" with its text indented two spaces (an entry's text can't make a header). Regenerated a
  few seconds after a change (own lines or others'), whole, through a temporary file. Not uploaded as media (derived).
- **Cloud.** `cloud/TextSync`, in each round and on demand on a thread of its own (a round may be busy uploading a
  video): own files whose size changed go up to `Unichat/Textos/<machine id>/`, then its `index.json` (name → size);
  other machines are found by listing `Unichat/Textos/` (new `Storage.list`, for WebDAV, pCloud and Dropbox), their
  index read when its stamp changed, and each file larger than the local copy downloaded. Names from the account are
  checked against strict patterns before becoming local paths. The chat keys that changed are told back, and their
  readable copies regenerated.
- **Looking.** "See what it said" asks the cloud for others' news first (waits a few seconds at most), then reads the
  merge for that message: each version with when and who, or "There's no copy of this message".

## Left to the builder
Wording, the sheet's layout, debounce times, where in the round texts go.
