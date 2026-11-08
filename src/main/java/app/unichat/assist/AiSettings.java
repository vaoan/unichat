package app.unichat.assist;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import java.nio.file.Path;

/** Ajustes → Escribir con IA. Off until the person turns it on: it sends their chats to another company's service. */
public final class AiSettings {
    private AiSettings() {}

    /** What the instructions are until the person writes their own: in the language in use now. */
    public static String defaultInstructions() { return I18n.t("ai.defaultInstructions"); }

    public static boolean enabled() { return Settings.get().getBool("ai.enabled", false); }

    public static void setEnabled(boolean on) { Settings.get().setBool("ai.enabled", on); }

    /** The chosen program; before any choice, the first one installed. */
    public static AiEngine engine() {
        String saved = Settings.get().getString("ai.engine", "");
        for (AiEngine e : AiEngine.values()) {
            if (e.name().equals(saved)) return e;
        }
        return AiEngine.CLAUDE.find() == null && AiEngine.CODEX.find() != null ? AiEngine.CODEX : AiEngine.CLAUDE;
    }

    public static void setEngine(AiEngine e) { Settings.get().set("ai.engine", e.name()); }

    public static String instructions() {
        String saved = Settings.get().getString("ai.instructions", null);
        return saved != null ? saved : defaultInstructions();
    }

    public static void setInstructions(String text) { Settings.get().set("ai.instructions", text); }

    /** Voice notes being answered are transcribed (on this computer, Whisper) so a draft knows what they said. */
    public static boolean hearVoiceNotes() { return Settings.get().getBool("ai.voiceNotes", true); }

    public static void setHearVoiceNotes(boolean on) { Settings.get().setBool("ai.voiceNotes", on); }

    /** The folder it may read, or null. */
    public static Path folder() {
        String f = Settings.get().getString("ai.folder", "");
        return f.isBlank() ? null : Path.of(f);
    }

    public static void setFolder(Path folder) { Settings.get().set("ai.folder", folder == null ? "" : folder.toString()); }
}
