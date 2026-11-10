package app.unichat.stt;

import app.unichat.support.ConvertedFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A file the player can read: voice notes are Ogg/Opus, which JavaFX refuses, so those become a WAV once, beside the
 * original in the media cache (its limit covers them). Anything else is passed through for the player to judge.
 */
public final class PlayableAudio {
    private PlayableAudio() {}

    /** Blocking: decoding takes a moment on long notes. */
    public static Path of(Path audio) throws IOException {
        if (!OggOpus.isOggOpus(audio)) return audio;
        Path wav = ConvertedFiles.beside(audio, "wav");
        if (ConvertedFiles.reuse(wav)) return wav;
        Path tmp = wav.resolveSibling(wav.getFileName() + ".part");
        try {
            OggOpus.toPlayableWav(audio, tmp);
            Files.move(tmp, wav, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); // lesson-ok: atomic-move (the decoder writes the temp)
        } finally {
            Files.deleteIfExists(tmp);
        }
        return wav;
    }
}
