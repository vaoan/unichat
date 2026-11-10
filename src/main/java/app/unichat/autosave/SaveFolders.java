package app.unichat.autosave;

import app.unichat.model.Chat;
import app.unichat.settings.Settings;

import java.nio.file.Path;

/**
 * Where saved things go: a base folder (Ajustes → Redes; {@code ~/Downloads/Unichat} unless chosen) with one folder per
 * chat under it, or a chat's own folder chosen in its options. Paths of this computer: they stay here
 * ({@code autosave.} keys, {@link Settings}). {@code -Dunichat.autosaveDir} sets the base (tests never touch the
 * person's folders).
 */
public final class SaveFolders {
    static final String BASE_KEY = "autosave.folder";
    private static final String CHAT_KEY = "autosave.folder.chat.";
    private static final String NAME_KEY = "autosave.folder.name.";

    private SaveFolders() {}

    /** The base folder in use. */
    public static Path base() {
        String forced = System.getProperty("unichat.autosaveDir");
        if (forced != null) return Path.of(forced);
        String chosen = Settings.get().getString(BASE_KEY, null);
        return chosen != null ? Path.of(chosen) : defaultBase();
    }

    /** {@code <home>/Downloads/Unichat}: Windows and macOS both call the folder Downloads on disk. */
    public static Path defaultBase() {
        return Path.of(System.getProperty("user.home"), "Downloads", "Unichat");
    }

    /** Null: back to the default. */
    public static void setBase(Path folder) {
        Settings.get().set(BASE_KEY, folder == null ? null : folder.toString());
    }

    public static boolean baseChosen() {
        return Settings.get().getString(BASE_KEY, null) != null;
    }

    /** Where {@code chat}'s things go: its own folder, or one named after it under the base. */
    public static Path of(Chat chat) {
        String own = Settings.get().getString(CHAT_KEY + chat.id(), null);
        return own != null ? Path.of(own) : base().resolve(name(chat));
    }

    /**
     * The name of {@code chat}'s folder under the base: its title the first time, kept from then on. Anyone in a group
     * can rename it, and following the title split its files into a new folder, where exploring saved them all again.
     */
    static synchronized String name(Chat chat) {
        Settings s = Settings.get();
        String kept = s.getString(NAME_KEY + chat.id(), null);
        if (kept != null) return kept;
        String name = FileNames.folder(chat.title());
        s.set(NAME_KEY + chat.id(), name);
        return name;
    }

    /** Null: under the base again. */
    public static void setOwn(String chatId, Path folder) {
        Settings.get().set(CHAT_KEY + chatId, folder == null ? null : folder.toString());
    }

    public static boolean hasOwn(String chatId) {
        return Settings.get().getString(CHAT_KEY + chatId, null) != null;
    }

    /** {@code folder} as a person reads it: under their home, "~/…". */
    public static String shown(Path folder) {
        Path home = Path.of(System.getProperty("user.home"));
        return folder.startsWith(home) ? "~" + java.io.File.separator + home.relativize(folder) : folder.toString();
    }
}
