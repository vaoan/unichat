package app.unichat.stt;

import app.unichat.settings.Settings;

/** The voice-to-text choices of {@link Settings} that need this package's types (Ajustes → Voz a texto). */
public final class SttSettings {
    private SttSettings() {}

    public static Transcriber.Engine engine() {
        return "CUSTOM".equals(Settings.get().getString("stt.engine", "WHISPER")) ? Transcriber.Engine.CUSTOM : Transcriber.Engine.WHISPER;
    }

    public static void setEngine(Transcriber.Engine e) { Settings.get().set("stt.engine", e.name()); }

    public static WhisperModel model() {
        return "BASE".equals(Settings.get().getString("stt.model", "SMALL")) ? WhisperModel.BASE : WhisperModel.SMALL;
    }

    public static void setModel(WhisperModel m) { Settings.get().set("stt.model", m.name()); }
}
