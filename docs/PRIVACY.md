# Privacy

English · [Español](PRIVACIDAD.md)

Unichat has no servers or accounts of its own. It collects no usage statistics or error reports, and
nothing is sent on its own to whoever made it.

## Where your messages are

- **With the local server** (the default on Mac and Windows), the Matrix server (tuwunel) and the bridges
  run on your computer and only listen on `127.0.0.1`. Your messages go from your computer to each network (WhatsApp,
  Telegram…) directly, as in each one's official app.
- **With your own server** (Docker, `server/`), they go through the server you set up.
- Photos, videos and files stay on the network they came from and are downloaded when you open them. What's downloaded is
  kept in `cache/media`, with the limit you choose in Settings → Privacy.
- The session (`session.properties`) and the local server's keys are stored in plain text in your user
  folder. Anyone with access to your computer account can read them, just like the messages.

## What leaves your computer

| What | Where to | When |
|---|---|---|
| Your messages and what you send | Each chat's network | Always: that's the app |
| The download of the bridges and the server | GitHub (mautrix and Unichat's repository) | When connecting a network for the first time, or when updating |
| Checking for updates | GitHub's API (`api.github.com`), with no data of yours | Once a day, if turned on (Settings → Privacy) |
| ffmpeg and the Whisper or RNNoise model | GitHub and Hugging Face | The first time they're needed (ffmpeg is turned off in Settings → Networks → Install the converter automatically) |
| What protecting a photo from AI needs: uv and Python, PyTorch, Stable Diffusion's image encoder and Mist's pattern | GitHub, PyPI (`pypi.org`, `files.pythonhosted.org`) and Hugging Face | Only the first time you choose "Photo protected from AI…" in the clip, after it tells you so. The photo itself is protected on your computer and only goes to the chat |
| What a link leads to (a video, photos), in a chat with "Save what they send in this chat" on (the gear in its profile), or when you choose "Save link" in a message's menu: yt-dlp, and gallery-dl if you have it | GitHub (yt-dlp, the first time), then the link's site (YouTube, Instagram, TikTok…). If you put a site's cookies in a file named `*cookie*.txt` in your Downloads folder, they go to that site, as when you're signed in there | Only for those chats and those links; what's saved stays on your computer (and in your storage, if you connected one) |
| **Your own storage** (Settings → Cloud, off until you connect one) | The pCloud, Dropbox or WebDAV account you connect (Icedrive, Nextcloud…). Signing in happens in your browser, on the service's page; Dropbox only sees Unichat's own folder | About once a minute and after something is saved: your settings and each chat's options (never this computer's own: the session and logins, its folders, and the commands it runs; your keys — Telegram's, the GIF services', the cloud apps' — only with the vault on, encrypted), what the AI learned of how you write (`writing-style.md`), the files saved by "Save what they send in this chat", and the texts of the chats that keep them ("Texts" among its kinds), with who wrote each, the chat and when: each computer's own copy, in `Unichat/Texts`, so the others can read what was deleted (where your own texts are kept, also what you wrote before the AI corrected it, though it never went to the chat); these go up as soon as they're kept, and the others' come down when you look at a deleted message. On your computer they're kept in your data folder, readable by your account alone. Your login to it stays on this computer. Unichat deletes nothing of yours there but the unencrypted copies it moves into the vault, the copies it moves from the folders' old names (`Multimedia`, `Ajustes`, `Textos`, `Bóveda`) to the English ones (`Media`, `Settings`, `Texts`, `Vault`), on the service itself and each once its new place holds it (while a computer of yours is still on an older Unichat, its photos and videos are kept under both names), (each once its encrypted copy reads back whole, never while you haven't confirmed you kept the recovery code) and the vault itself when you turn it off or delete it. With the vault on (Settings → Cloud → Vault), everything above but the saved files goes up encrypted (AES-256-GCM) with a key the service never sees, opened on another computer only with your recovery code or your password; turning it off puts your settings, what the AI learned and the texts back unencrypted and deletes the vault from the account (your keys, pinned messages and voice notes' transcripts, which only the vault carries, then stay on each computer) |
| GIF search | The chosen GIF search provider (Settings → GIFs) | When you search |
| The links in messages | The link's page, without cookies; for a post on X, FxTwitter's public API (api.fxtwitter.com) | When showing a message with a link, to show its preview (title, text, picture, video) and whether it has a video. Never to your local network. Turned off in Settings → Privacy → Connections → Link previews |
| **Write with AI** (off by default) | The Claude Code or Codex service, with your account | Only when you press the button: the open chat for a draft; only your text (and `writing-style.md`) to correct it, or a message of yours already sent with "Correct with AI" in its menu. With "Correct what I send" on, each message you send from Unichat on this computer: right after it goes ("after sending") or before it goes ("before sending"). A message already sent is replaced by its correction as an edit, without asking (unless the correction adds a link, a mention, a command or much more text, then it's left alone); the original stays in the network's edit history. Before sending, the correction is what goes, also without asking: as written if the correction fails or adds something, and commands (`!…`, `/…`) always as typed. With "Learn how I write", also your messages in that chat and what you changed in the drafts, with the message each draft answered, to update `writing-style.md` (your way of writing, kept in your data folder; the versions it replaced stay in `writing-style-history/` on this computer). A draft also carries a few of your earlier corrections (what it proposed, what you sent, the message it answered), the same chat's first; they're kept in `writing-style-learned.json` on this computer.  If what you wrote has data in [brackets], it may look it up on the web: searches only, never opening pages |

Read receipts are chosen in Settings → Privacy: when you open the chat, **when you reply** (opening a chat
tells no one; when you write or react in it, the other person sees you read everything up to then) or never. The network's app on your phone marks
as read on its own if you open the chat there.

Voice notes are transcribed on your computer with Whisper: the audio doesn't leave it.

## The vault

Optional (Settings → Cloud → Vault), only with your own storage connected. Nothing is sent to anyone but that
storage: no server of Unichat's, no other third party.

- **What goes up, sealed**: your settings and chat options, what the AI learned, the texts of chats that keep them, your
  keys (Telegram's, the GIF services', the cloud apps'), pinned messages and voice notes' transcripts. Each file is
  encrypted on your computer with AES-256-GCM before it leaves. The saved media isn't part of it.
- **What stays on each computer**: the vault key itself, your login to the storage, the session and logins of the
  networks, and what is this computer's own (paths, commands). The vault key is kept in your data folder, readable by
  your account alone, like the rest of what's stored there.
- **Who can read what**: your storage service, and anyone who gets into your account there, sees only encrypted files
  and their names and sizes, never their content. Anyone with your recovery code or password and access to the
  storage can open it, so keep the code somewhere safe: without the code or a password, a computer you lose can't be
  replaced and nobody, not even whoever made Unichat, can recover the vault. A computer that holds the key can read
  everything in the vault; "Change the key" in the page cuts off a computer you remove. A computer takes a new key only
  when the key it holds vouches for it, so someone with only your storage can't hand it one; but a removed computer
  still holds the old key, and a computer of yours that was off during the removal can be fooled by it until you open
  the vault there with the recovery code or a password set after the removal (an older password, put back by the removed
  computer, can open its key): it can hand that computer a key of its own, or make it believe the
  vault was turned off, so that it sends unencrypted.
- **Someone else changing the storage**: if the vault's file is deleted, or its key changed, without Unichat doing it,
  your computers stop sending anything personal and say so in Settings → Cloud → Vault; they don't go back to
  unencrypted by themselves. Only a vault really turned off (it leaves a mark only its key could write) does that, on the
  computers that had it open; one that went on without it, never opened it, or was off when its key last changed asks
  you once even then.
- **Turning it off** (Settings → Cloud → Vault): your settings, what the AI learned and the texts go back to the
  storage unencrypted, a mark that it was turned off is left in `Unichat/Settings`, and the vault is deleted from the
  account. Your keys, pinned messages and transcripts, which only
  the vault carries, stay on each computer, and aren't put back unencrypted.
- You can also go on without it on a computer ("Go on without the vault on this computer"): nothing personal then goes
  up or comes down from it, and you enter your keys by hand.

## Logs

Errors are saved in `logs/unichat.log` inside the data folder. Before each line is written,
tokens, passwords, phone numbers, emails, user and room identifiers, and the text of
messages are removed (`support/Redactor`). "Copy error
report" (Settings → About) copies the version, the system and the last lines of the log to the clipboard. Nothing is
sent by itself: you decide whether to paste it into a report.

## Your accounts

The networks don't authorize the bridges. See "Risks" in the [README](../README.md#risks-of-using-bridges).
