# video — notes for Claude

ffmpeg, yt-dlp, stickers and picture conversions. The rules, commands and map are in the root `CLAUDE.md`.

- `native/build-gif2webp.sh` — libwebp's `gif2webp` (and `anim_dump`, which `video/AnimDump` runs to decode animated WebP: TwelveMonkeys gets some lossy frames' colours wrong, black blotches; it's the fallback) built from pinned sources (with GIFLib; Google's Mac binary needs macOS 15) and shipped in `app/server/` (Windows: Google's build, pinned in package.ps1);
  `matrix/StickerConverter` uses it when a chat's room_features refuse a sticker's MIME (GIF → WebP on WhatsApp), keeping `fi.mau.bridged_sticker` when the sticker is the chat's own network's (`MessageSender.ownSticker`): Telegram's bridge first rejects a type the chat doesn't take (image/gif), then sends the original from its pack; without the mark it passes the file through its ffmpeg, which fails without a WebP encoder (Homebrew's).
  Ajustes → Redes (`<network>.animatedStickers` = STICKER|GIF|VIDEO) can send them as mp4 instead (`GifFrames` +
  JCodec, pure Java).
