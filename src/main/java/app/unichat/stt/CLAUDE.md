# stt — notes for Claude

Voice notes to text. The rules, commands and map are in the root `CLAUDE.md`.

- `stt/` — voice notes to text: `Transcriber` runs whisper.cpp's `whisper-cli` (`native/build-whisper.sh` on Mac,
  pinned official zip in package.ps1; shipped in `app/server/whisper/`) or the user's own command (`CommandLine`);
  `OggOpus` decodes Opus at 48 kHz then low-passes to 16 kHz (Concentus returns silence for the first CELT frames
  at 16 kHz); `WhisperModel` downloads the pinned model on first use; `Transcripts` caches per media URL.
  Claude Code / Codex CLIs can't take audio (tested), so they aren't engines.
