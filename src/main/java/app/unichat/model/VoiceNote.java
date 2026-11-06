package app.unichat.model;

import java.nio.file.Path;
import java.util.List;

/**
 * A recorded voice note ready to send.
 *
 * @param waveform 0..1024, what networks draw for it (MSC3246)
 */
public record VoiceNote(Path file, long durationMs, List<Integer> waveform) {}
