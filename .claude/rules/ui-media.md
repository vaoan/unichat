---
paths:
  - "src/main/java/app/unichat/ui/*Video*.java"
  - "src/main/java/app/unichat/ui/MediaViewer.java"
  - "src/main/java/app/unichat/ui/Photo*.java"
  - "src/main/java/app/unichat/ui/Gallery.java"
  - "src/main/java/app/unichat/ui/Audio*.java"
  - "src/main/java/app/unichat/ui/AnimatedImage.java"
  - "src/main/java/app/unichat/ui/MessageMedia.java"
  - "src/main/java/app/unichat/ui/Media*.java"
  - "src/main/java/app/unichat/ui/Stills.java"
  - "src/main/java/app/unichat/ui/TileCache.java"
  - "src/main/java/app/unichat/ui/ImageCache.java"
  - "src/main/java/app/unichat/ui/FilePreview.java"
  - "src/main/java/app/unichat/ui/*Grid.java"
  - "src/main/java/app/unichat/video/**"
---
# Photos, video, audio and animations on screen

- Video: `Message.playsInline` decides by what the bridge reports (GIF flag, video stickers, or up to 2 MB and 15 s); `InlineVideo` (muted loop in the timeline, a native player only while on screen, none with reduced motion), `VideoViewer` (window; downloads on opening) with `VideoControls` (±10 s, volume, speed, repeat, watch again, full screen with controls that hide while playing, always on top, save the video or the frame; YouTube's keys: K J L, ←→ ↑↓, M, F, < >, , ., Home/End, digits). javafx-media comes with javafx-web; WebM it can't play goes through `video/WebmStickers` (ffmpeg to animated WebP or, if that ffmpeg lacks the encoder like Homebrew's, GIF; cached in `cache/media`; `video/Ffmpeg` uses the person's own or downloads a pinned build on first use, `video.ffmpegAuto`, Ajustes → Redes), `VideoViewer` converts other unreadable videos to H.264 (`video/VideoTranscoder`), and what still can't play falls back to a file chip and the system player. Trust the file, not its label: a sticker labelled `video/webm` may be delivered as a GIF; `.mov` is cached as `.mp4` because JavaFX refuses that name. Reels that arrive as a picture + `external_url` open their page (`Message.isReelLink`).
- Photos: `MediaViewer` (window and controls; steps through the chat's pictures with ← → / Page Up/Down and the side buttons, `Gallery`: photos and pictures sent as documents, which open there too; the arrows pan while zoomed in; F full screen, Esc leaves it then closes) over `PhotoPane` (mouse) and `PhotoZoom` (pure: scale, turn, position). Animated GIF/WebP stay decoded at up to 1600 px, so real size there is the decoded one.
- A still WebP over 3 MP (a phone's photo) is never decoded in Java: TwelveMonkeys takes ~25 bytes/pixel, 600 MB+ for 24 MP; `video/BigPictures` makes a 1600 px PNG with `sips` (Mac) or ffmpeg, cached (`AnimatedImage.isBigWebp`, read from the header).
- Audio: `AudioPlayback` (one per window: starting another stops it; survives changing chats), shown by `AudioControls` in the message and in `AudioBar` on top. JavaFX refuses Ogg/Opus: `stt/PlayableAudio` makes a 48 kHz WAV once (`OggOpus.toPlayableWav`; 16 kHz is only for Whisper).
- Animations: one over `AnimatedImage.MAX_ANIMATION_BYTES` first loses resolution, down to its size on screen (`shrinkToKeep`), and only then frames: dropping them halved a sticker's frame rate. Media grids keep their tiles (`TileCache`) and `MessageMedia.loadMedia` shows what `AnimatedImage` already decoded at once.

## Lessons
- Never hand a GIF to `new Image(...)`: JavaFX animates it with a Timeline that never stops and holds every decoded frame, so each GIF ever shown stayed in the heap (OutOfMemoryError in a day with Discord). Animations go through `AnimatedImage.decodeGif/decodeWebp` + `play`; thumbnails/avatars through `AnimatedImage.still` (with direct media a "thumbnail" can be the original animated GIF).
- Stickers never animated: images loaded the server *thumbnail* (a static PNG of frame 1), and JavaFX can't read WebP at all. Stickers/GIF/WebP load the original; animated WebP is composited from its ANMF chunks (TwelveMonkeys only returns raw patches without durations; `AnimatedImageTest`, synthetic fixture). Decoding long stickers takes seconds: show the thumbnail first. Reduce-motion shows frame 1 only.
- An OutOfMemoryError in the render thread freezes the window for good (the FX thread waits for a frame that never finishes, `PaintCollector.waitForRenderingToComplete`) while the sync and server keep going. The cause was iPhone photos as WebP (24 MP). Measure peak heap with real files (`-Xmx256m` and a folder of them) before trusting a decoder with someone else's pictures.
- TwelveMonkeys gets some lossy animated WebP frames' colours wrong (black blotches): `video/AnimDump` (libwebp's `anim_dump`) decodes them, TwelveMonkeys is the fallback.
