# stt — notes for Claude

Voice notes to text.

- `stt/` — voice notes to text: `Transcriber` runs whisper.cpp's `whisper-cli` (`native/build-whisper.sh` on Mac,
  pinned official zip in package.ps1; shipped in `app/server/whisper/`) or the user's own command (`CommandLine`);
  `OggOpus` decodes Opus at 48 kHz then low-passes to 16 kHz (Concentus returns silence for the first CELT frames
  at 16 kHz); `WhisperModel` downloads the pinned model on first use; `Transcripts` caches per media URL.
  Claude Code / Codex CLIs can't take audio (tested), so they aren't engines.

## Lessons
- Concentus decoding Opus straight at 16 kHz returned silence for the first CELT frames (~0.3 s of every note lost). Decode at 48 kHz, then low-pass/decimate; `OggOpusTest` checks RMS and zero crossings over the whole fixture, not just its length.
- `stt.command` is run as written, so it and `stt.engine` stay on this machine (`Settings.staysOnThisMachine`): synced through the cloud, whoever could write there chose what runs here.
- `Transcriber.run` waited on whisper itself: interrupted (a draft no longer waiting), it left whisper running for minutes; a timeout killed it without its children. Programs go through `support/Processes` (`TranscriberRunTest`; lesson `own-process`).
- `Transcripts.request` checked the state, then set it: two threads asking at once both transcribed the note. The check and the claim are one `ConcurrentHashMap.compute` (`TranscriptsTest.askedAtOnceFromSeveralThreadsItsTranscribedOnce`).
