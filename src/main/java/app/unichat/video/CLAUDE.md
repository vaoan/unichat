# video — notes for Claude

ffmpeg, yt-dlp, stickers and picture conversions.

- `native/build-gif2webp.sh` — libwebp's `gif2webp` (and `anim_dump`, which `video/AnimDump` runs to decode animated WebP: TwelveMonkeys gets some lossy frames' colours wrong, black blotches; it's the fallback) built from pinned sources (with GIFLib; Google's Mac binary needs macOS 15) and shipped in `app/server/` (Windows: Google's build, pinned in package.ps1);
  `matrix/StickerConverter` uses it when a chat's room_features refuse a sticker's MIME (GIF → WebP on WhatsApp), keeping `fi.mau.bridged_sticker` when the sticker is the chat's own network's (`MessageSender.ownSticker`): Telegram's bridge first rejects a type the chat doesn't take (image/gif), then sends the original from its pack; without the mark it passes the file through its ffmpeg, which fails without a WebP encoder (Homebrew's).
  Ajustes → Redes (`<network>.animatedStickers` = STICKER|GIF|VIDEO) can send them as mp4 instead (`GifFrames` +
  JCodec, pure Java).
- `VideoTranscoder.makePlayable`: a saved video as H.264/AAC in its place (what ffmpeg says of it, `playsEverywhere`; no ffprobe, only ffmpeg ships), used on what a link saves (`autosave/LinkSaver`). `YtDlp` keeps the pinned yt-dlp ready (running it is InstaTray's engine); `GalleryDl` finds the person's gallery-dl.

## Lessons
- ffmpeg tells a file's format by its content: a "video" from a chat that is really an HLS playlist (or a concat list, a DASH manifest) names other files, and `-protocol_whitelist file` still let it read them into the output that's saved and uploaded. Files from others go in through `Ffmpeg.input`, which refuses those (`FfmpegInputTest`; `.claude/lessons.tsv` `ffmpeg-input`). A forced format (`-f matroska`, `-f s16le`) doesn't probe and is fine.
