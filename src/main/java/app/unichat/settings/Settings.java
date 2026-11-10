package app.unichat.settings;

import app.unichat.support.Os;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * User preferences ({@code settings.properties}, which travels with the person) and session and per-machine state
 * ({@code session.properties}, see {@link #staysOnThisMachine}), in the per-OS app data folder:
 * Windows {@code %APPDATA%\Unichat}, macOS {@code ~/Library/Application Support/Unichat},
 * Linux {@code $XDG_CONFIG_HOME/unichat}.
 */
public final class Settings {
    public enum Theme { SYSTEM, LIGHT, DARK }

    /** Tri-state accessibility switch: follow the OS, or force on/off. */
    public enum Mode { SYSTEM, ON, OFF }
    /**
     * When others see that you read their messages: on opening the chat, only once you send something in it (reading
     * stays private until then), or never.
     */
    public enum ReadReceipts { ALWAYS, ON_REPLY, NEVER }

    public static final double MIN_FONT_SCALE = 0.85;
    public static final double MAX_FONT_SCALE = 1.6;

    private static final Settings INSTANCE = new Settings(dataDir());

    private final Path dir;
    private final Path file;
    private final Path sessionFile;
    /** Preferences, safe to copy between machines. */
    private final Properties props = new Properties();
    /** Session, secrets and per-machine state (see {@link #staysOnThisMachine}); never copied. */
    private final Properties machine = new Properties();

    Settings(Path dir) {
        this.dir = dir;
        this.file = dir.resolve("settings.properties");
        this.sessionFile = dir.resolve("session.properties");
        load(file, props);
        load(sessionFile, machine);
        migrate();
    }

    /** True for keys that hold the session, a secret or a value only meaningful on this computer. */
    static boolean staysOnThisMachine(String key) {
        return key.startsWith("matrix.") || key.startsWith("session.") || key.startsWith("server.")
                || key.startsWith("window.") || key.startsWith("discord.guilds.")
                // What the update check heard, about the version installed here.
                || key.startsWith("app.update.")
                // Folders where chats' things are saved, and the AI's folder: paths of this computer.
                || key.startsWith("autosave.") || key.equals("ai.folder")
                // A command this computer runs, and the engine that says to run it: from the cloud, anyone able to
                // write there would choose what runs here.
                || key.equals("stt.command") || key.equals("stt.engine")
                // The cloud account: its login, and what this computer last agreed on with it.
                || key.startsWith("cloud.")
                // Whether this computer's Telegram bridge already re-synced its chats: a marker, not a choice.
                || key.equals("telegram.chatSyncLimit")
                || (key.startsWith("gif.") && key.endsWith(".key"));
    }

    private Properties holder(String key) { return staysOnThisMachine(key) ? machine : props; }

    /**
     * Moves machine keys left in settings.properties (older versions, or a crash between the two writes) to
     * session.properties. The session file is written first, so a crash never loses the session; on a conflict it wins.
     */
    private void migrate() {
        var moving = props.stringPropertyNames().stream().filter(Settings::staysOnThisMachine).toList();
        if (!moving.isEmpty()) {
            for (String k : moving) {
                machine.putIfAbsent(k, props.getProperty(k));
                props.remove(k);
            }
            save();
        }
        try {
            Files.deleteIfExists(dir.resolve("settings.properties.docker-backup"));
        } catch (IOException e) {
            System.err.println("Could not delete the old settings backup: " + e);
        }
    }

    public static Settings get() { return INSTANCE; }

    public static Path dataDir() {
        String override = System.getProperty("unichat.dataDir");
        if (override != null) return Path.of(override);
        String home = System.getProperty("user.home");
        if (Os.windows()) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null ? appData : home, "Unichat");
        }
        if (Os.mac()) return Path.of(home, "Library", "Application Support", "Unichat");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return Path.of(xdg != null ? xdg : home + "/.config", "unichat");
    }

    public Path dir() { return dir; }
    public Path cacheDir() { return dir.resolve("cache"); }

    private static void load(Path from, Properties into) {
        if (!Files.exists(from)) return;
        try (InputStream in = Files.newInputStream(from)) {
            into.load(in);
        } catch (IOException e) {
            System.err.println("Could not read settings: " + e);
        }
    }

    /** Writes session.properties first, then settings.properties; each through a temp file, so a failure keeps the old one. */
    public synchronized void save() {
        try {
            Files.createDirectories(dir);
            if (!machine.isEmpty() || Files.exists(sessionFile)) {
                write(sessionFile, machine, "Unichat session and secrets: do not copy to another computer", true);
            }
            write(file, props, "Unichat settings", false);
        } catch (IOException e) {
            System.err.println("Could not save settings: " + e);
        }
    }

    private void write(Path target, Properties from, String comment, boolean ownerOnly) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        from.store(content, comment);
        SyncedFile.replace(target, content.toByteArray(), ownerOnly);
    }

    /** The settings that may travel to another computer (none of this one's: those are apart). */
    public synchronized java.util.Map<String, String> portable() {
        java.util.Map<String, String> copy = new java.util.TreeMap<>();
        for (String k : props.stringPropertyNames()) copy.put(k, props.getProperty(k));
        return copy;
    }

    /** Another computer's settings in place of these (from the cloud), but for the keys {@code keepHere} holds on to. */
    public synchronized void replacePortable(java.util.Map<String, String> values, java.util.function.Predicate<String> keepHere) {
        props.stringPropertyNames().stream().filter(k -> !keepHere.test(k)).forEach(props::remove);
        values.forEach((k, v) -> { if (!keepHere.test(k) && !staysOnThisMachine(k)) props.setProperty(k, v); });
        save();
    }

    public synchronized String getString(String key, String def) { return holder(key).getProperty(key, def); }

    public synchronized void set(String key, String value) {
        if (value == null) holder(key).remove(key); else holder(key).setProperty(key, value);
        save();
    }

    public boolean getBool(String key, boolean def) {
        String v = getString(key, null);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public void setBool(String key, boolean value) { set(key, Boolean.toString(value)); }

    /** A number of at least {@code min}; {@code def} when missing or not a number. */
    public int getInt(String key, int def, int min) {
        try {
            String v = getString(key, null);
            return v == null ? def : Math.max(min, Integer.parseInt(v.strip()));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** {@code def} when missing or not one of the enum's names. */
    public <E extends Enum<E>> E getEnum(String key, E def) {
        try {
            return Enum.valueOf(def.getDeclaringClass(), getString(key, def.name()));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    /** The values of a comma-separated setting, in the order they were added; a copy. */
    public synchronized java.util.Set<String> getSet(String key) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String v : getString(key, "").split(",")) if (!v.isBlank()) out.add(v.strip());
        return out;
    }

    public synchronized void addToSet(String key, String value) {
        java.util.Set<String> now = getSet(key);
        if (now.add(value)) set(key, String.join(",", now));
    }

    /** The key goes away with its last value. */
    public synchronized void removeFromSet(String key, String value) {
        java.util.Set<String> now = getSet(key);
        if (now.remove(value)) set(key, now.isEmpty() ? null : String.join(",", now));
    }

    public double getDouble(String key, double def) {
        try {
            String v = getString(key, null);
            return v == null ? def : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // --- Session ---
    /** "matrix" or "demo"; null when not logged in. */
    public String backend() { return getString("session.backend", null); }
    public void setBackend(String backend) { set("session.backend", backend); }
    public String homeserver() { return getString("matrix.homeserver", null); }
    public String userId() { return getString("matrix.userId", null); }
    public String accessToken() { return getString("matrix.accessToken", null); }
    public String deviceId() { return getString("matrix.deviceId", null); }

    public synchronized void setMatrixSession(String homeserver, String userId, String accessToken, String deviceId) {
        machine.setProperty("matrix.homeserver", homeserver);
        machine.setProperty("matrix.userId", userId);
        machine.setProperty("matrix.accessToken", accessToken);
        if (deviceId != null) machine.setProperty("matrix.deviceId", deviceId);
        machine.setProperty("session.backend", "matrix");
        save();
    }

    /** All four at once: this runs on the UI thread and each save reaches the disk. */
    public synchronized void setWindowGeometry(double width, double height, double x, double y) {
        machine.setProperty("window.width", Double.toString(width));
        machine.setProperty("window.height", Double.toString(height));
        machine.setProperty("window.x", Double.toString(x));
        machine.setProperty("window.y", Double.toString(y));
        save();
    }

    public synchronized void clearSession() {
        machine.stringPropertyNames().stream()
                .filter(k -> k.startsWith("matrix.") || k.startsWith("session."))
                .forEach(machine::remove);
        save();
    }

    // --- Appearance & behaviour ---
    /** The language chosen in Ajustes, or {@link app.unichat.support.I18n#AUTO} (the system's). */
    public String language() { return getString("ui.language", app.unichat.support.I18n.AUTO); }
    public void setLanguage(String code) { set("ui.language", code); }

    public Theme theme() { return getEnum("ui.theme", Theme.SYSTEM); }
    public void setTheme(Theme theme) { set("ui.theme", theme.name()); }
    /** Accent color as CSS hex. */
    public String accent() { return getString("ui.accent", "#5B5BD6"); }
    public void setAccent(String hex) { set("ui.accent", hex); }
    /** {@link #MIN_FONT_SCALE} .. {@link #MAX_FONT_SCALE} */
    public double fontScale() { return clampScale(getDouble("ui.fontScale", 1.0)); }
    public void setFontScale(double scale) { set("ui.fontScale", Double.toString(clampScale(scale))); }

    static double clampScale(double s) {
        return Math.max(MIN_FONT_SCALE, Math.min(MAX_FONT_SCALE, Math.round(s * 100) / 100.0));
    }
    public boolean compactList() { return getBool("ui.compact", false); }
    public void setCompactList(boolean v) { setBool("ui.compact", v); }
    public boolean showNetworkBadges() { return getBool("ui.networkBadges", true); }
    public void setShowNetworkBadges(boolean v) { setBool("ui.networkBadges", v); }
    public boolean sendOnEnter() { return getBool("compose.sendOnEnter", true); }
    public void setSendOnEnter(boolean v) { setBool("compose.sendOnEnter", v); }
    public boolean notificationsEnabled() { return getBool("notify.enabled", true); }
    public void setNotificationsEnabled(boolean v) { setBool("notify.enabled", v); }
    public boolean notificationPreview() { return getBool("notify.preview", true); }
    public void setNotificationPreview(boolean v) { setBool("notify.preview", v); }
    public boolean notificationSound() { return getBool("notify.sound", true); }
    public void setNotificationSound(boolean v) { setBool("notify.sound", v); }
    /** Logs older than this are deleted (Ajustes → Privacidad). */
    public int logRetentionDays() { return getInt("logs.retentionDays", 7, 1); }
    public void setLogRetentionDays(int days) { set("logs.retentionDays", Integer.toString(days)); }
    /** Photos and videos of Discord channels older than this are deleted; 0 = never (Ajustes → Redes). */
    public int discordMediaDays() { return getInt("discord.mediaDays", 30, 0); }
    public void setDiscordMediaDays(int days) { set("discord.mediaDays", Integer.toString(days)); }

    /** Size limit of the downloaded media (photos, videos, files) in MB; the least recently seen go first. */
    public int mediaCacheMb() { return getInt("cache.mediaMb", 1024, 100); }
    public void setMediaCacheMb(int mb) { set("cache.mediaMb", Integer.toString(mb)); }

    // Voice notes to text (engine and model: stt/SttSettings).
    /** Whisper language code ("es", "en"…) or "auto". */
    public String sttLanguage() { return getString("stt.language", "auto"); }
    public void setSttLanguage(String lang) { set("stt.language", lang); }
    public String sttCommand() { return getString("stt.command", ""); }
    public void setSttCommand(String cmd) { set("stt.command", cmd); }

    /** The session is on the server Unichat runs on this computer (app.unichat.server.LocalServer). */
    public boolean localServer() { return getBool("server.local", false); }
    public void setLocalServer(boolean v) { setBool("server.local", v); }
    /** Off by default: archiving a chat means it shouldn't interrupt you. */
    public boolean notifyArchived() { return getBool("notify.archived", false); }
    public void setNotifyArchived(boolean v) { setBool("notify.archived", v); }
    /** {@code privacy.readReceipts} was an on/off switch before; it still decides until a choice is made. */
    public ReadReceipts readReceipts() {
        return getEnum("privacy.readReceiptsWhen", getBool("privacy.readReceipts", true) ? ReadReceipts.ALWAYS : ReadReceipts.NEVER);
    }
    public void setReadReceipts(ReadReceipts v) { set("privacy.readReceiptsWhen", v.name()); }
    public boolean sendTypingNotifications() { return getBool("privacy.typing", true); }
    public void setSendTypingNotifications(boolean v) { setBool("privacy.typing", v); }
    // --- Accounts ---
    /** User-chosen name for an account ("Trabajo"), or null. */
    public String accountAlias(String accountId) { return getString("account.alias." + accountId, null); }
    public void setAccountAlias(String accountId, String alias) {
        set("account.alias." + accountId, alias == null || alias.isBlank() ? null : alias.strip());
    }

    // --- Accessibility ---
    public Mode highContrast() { return getEnum("a11y.highContrast", Mode.SYSTEM); }
    public void setHighContrast(Mode m) { set("a11y.highContrast", m.name()); }
    public Mode reduceMotion() { return getEnum("a11y.reduceMotion", Mode.SYSTEM); }
    public void setReduceMotion(Mode m) { set("a11y.reduceMotion", m.name()); }
    public boolean strongFocus() { return getBool("a11y.strongFocus", false); }
    public void setStrongFocus(boolean v) { setBool("a11y.strongFocus", v); }
    public boolean readableFont() { return getBool("a11y.readableFont", false); }
    public void setReadableFont(boolean v) { setBool("a11y.readableFont", v); }
    public boolean wideSpacing() { return getBool("a11y.wideSpacing", false); }
    public void setWideSpacing(boolean v) { setBool("a11y.wideSpacing", v); }
    public boolean alwaysShowMessageActions() { return getBool("a11y.messageActions", false); }
    public void setAlwaysShowMessageActions(boolean v) { setBool("a11y.messageActions", v); }
    public boolean speakMessages() { return getBool("a11y.speak", false); }
    public void setSpeakMessages(boolean v) { setBool("a11y.speak", v); }

    public boolean minimizeToTray() { return getBool("app.minimizeToTray", true); }
    /** macOS: ⌃⌥U brings Unichat to the front from any app, which ⌘Tab can't without a Dock icon. */
    public boolean globalShortcut() { return getBool("app.globalShortcut", true); }
    public void setGlobalShortcut(boolean v) { setBool("app.globalShortcut", v); }
    /** macOS: an icon in the Dock while the window is open; off, Unichat lives only in the menu bar. */
    public boolean showInDock() { return getBool("app.showInDock", false); }
    public void setShowInDock(boolean v) { setBool("app.showInDock", v); }
    public void setMinimizeToTray(boolean v) { setBool("app.minimizeToTray", v); }
    /** Ask GitHub once a day whether there's a newer Unichat (update/UpdateChecker). */
    /** Opens the links in messages to show their video (links/LinkInspector): it visits sites nobody chose. */
    public boolean linkPreviews() { return getBool("privacy.linkPreviews", true); }
    public void setLinkPreviews(boolean v) { setBool("privacy.linkPreviews", v); }

    public boolean checkUpdates() { return getBool("app.checkUpdates", true); }
    public void setCheckUpdates(boolean v) { setBool("app.checkUpdates", v); }
}
