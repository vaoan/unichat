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

    /** Each draft first learns how the person writes from what's new ({@link StyleLearner}): one more call then. */
    public static boolean learnStyle() { return Settings.get().getBool("ai.learnStyle", true); }

    public static void setLearnStyle(boolean on) { Settings.get().setBool("ai.learnStyle", on); }

    /** Whether, and when, what's sent from the message box is corrected by itself. */
    public enum FixMode {
        /** As written. */
        OFF,
        /** It goes at once and is edited once corrected (many networks mark it as edited). */
        AFTER,
        /** It waits in its chat's queue while corrected, then goes corrected (the person can cancel or send it as is). */
        BEFORE
    }

    public static FixMode fixMode() { return Settings.get().getEnum("ai.fixMode", FixMode.OFF); }

    public static void setFixMode(FixMode mode) { Settings.get().set("ai.fixMode", mode.name()); }

    /**
     * A message that waited to be corrected while others wrote goes as an answer to what was last said when it was
     * written, so the thread reads right (on unless turned off).
     */
    public static boolean keepThread() { return Settings.get().getBool("ai.keepThread", true); }

    public static void setKeepThread(boolean on) { Settings.get().setBool("ai.keepThread", on); }

    /** The sound when a draft or a fix is ready. */
    public static ReadySound.Tone readyTone() { return Settings.get().getEnum("ai.readySound", ReadySound.Tone.CHIME); }

    public static void setReadyTone(ReadySound.Tone t) { Settings.get().set("ai.readySound", t.name()); }

    /** 0–100. */
    public static int readyVolume() { return Math.min(100, Settings.get().getInt("ai.readyVolume", 60, 0)); }

    public static void setReadyVolume(int v) { Settings.get().set("ai.readyVolume", Integer.toString(Math.max(0, Math.min(100, v)))); }

    /** The folder it may read, or null. */
    public static Path folder() {
        String f = Settings.get().getString("ai.folder", "");
        return f.isBlank() ? null : Path.of(f);
    }

    public static void setFolder(Path folder) { Settings.get().set("ai.folder", folder == null ? "" : folder.toString()); }
}
