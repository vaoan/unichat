package app.unichat.assist;

import app.unichat.settings.Settings;
import java.nio.file.Path;

/** Ajustes → Escribir con IA. Off until the person turns it on: it sends their chats to another company's service. */
public final class AiSettings {
    public static final String DEFAULT_INSTRUCTIONS = "Asegúrate de que el idioma coincida con el del mensaje que llega. "
            + "Usa toda la conversación como contexto para responder. Usa un tono amigable, profesional y al grano, "
            + "sin ningún símbolo típico de IA, para que se vea lo más humano posible.";

    private AiSettings() {}

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

    public static String instructions() { return Settings.get().getString("ai.instructions", DEFAULT_INSTRUCTIONS); }

    public static void setInstructions(String text) { Settings.get().set("ai.instructions", text); }

    /** The folder it may read, or null. */
    public static Path folder() {
        String f = Settings.get().getString("ai.folder", "");
        return f.isBlank() ? null : Path.of(f);
    }

    public static void setFolder(Path folder) { Settings.get().set("ai.folder", folder == null ? "" : folder.toString()); }
}
