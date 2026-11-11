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
| **Your own storage** (Settings → Cloud, off until you connect one) | The pCloud, Dropbox or WebDAV account you connect (Icedrive, Nextcloud…). Signing in happens in your browser, on the service's page; Dropbox only sees Unichat's own folder | About once a minute and after something is saved: your settings and each chat's options (never this computer's own: the session, keys and logins, its folders, and the commands it runs), what the AI learned of how you write (`writing-style.md`), and the files saved by "Save what they send in this chat". Your login to it stays on this computer, and nothing is ever deleted there |
| GIF search | The chosen GIF search provider (Settings → GIFs) | When you search |
| The links in messages | The link's page, without cookies | When showing a message with a link, to find out whether it has a video. Never to your local network. Turned off in Settings → Privacy → Connections → Videos in links |
| **Write with AI** (off by default) | The Claude Code or Codex service, with your account | Only when you press the button: the open chat for a draft; only your text to correct it. With "Learn how I write", also your messages in that chat and what you changed in the drafts, with the message each draft answered, to update `writing-style.md` (your way of writing, kept in your data folder; the versions it replaced stay in `writing-style-history/` on this computer). A draft also carries a few of your earlier corrections (what it proposed, what you sent, the message it answered), the same chat's first; they're kept in `writing-style-learned.json` on this computer.  If what you wrote has data in [brackets], it may look it up on the web: searches only, never opening pages |

Read receipts are chosen in Settings → Privacy: when you open the chat, **when you reply** (opening a chat
tells no one; when you write in it, the other person sees you read it) or never. The network's app on your phone marks
as read on its own if you open the chat there.

Voice notes are transcribed on your computer with Whisper: the audio doesn't leave it.

## Logs

Errors are saved in `logs/unichat.log` inside the data folder. Before each line is written,
tokens, passwords, phone numbers, emails, user and room identifiers, and the text of
messages are removed (`support/Redactor`). "Copy error
report" (Settings → About) copies the version, the system and the last lines of the log to the clipboard. Nothing is
sent by itself: you decide whether to paste it into a report.

## Your accounts

The networks don't authorize the bridges. See "Risks" in the [README](../README.md#risks-of-using-bridges).
