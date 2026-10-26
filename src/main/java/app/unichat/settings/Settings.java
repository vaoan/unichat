package app.unichat.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/**
 * User preferences and session, stored as {@code settings.properties} in the per-OS app data folder:
 * Windows {@code %APPDATA%\Unichat}, macOS {@code ~/Library/Application Support/Unichat},
 * Linux {@code $XDG_CONFIG_HOME/unichat}.
 */
public final class Settings {
    public enum Theme { SYSTEM, LIGHT, DARK }

    /** Tri-state accessibility switch: follow the OS, or force on/off. */
    public enum Mode { SYSTEM, ON, OFF }

    public static final double MIN_FONT_SCALE = 0.85;
    public static final double MAX_FONT_SCALE = 1.6;

    private static final Settings INSTANCE = new Settings(dataDir());

    private final Path dir;
    private final Path file;
    private final Properties props = new Properties();

    Settings(Path dir) {
        this.dir = dir;
        this.file = dir.resolve("settings.properties");
        load();
    }

    public static Settings get() { return INSTANCE; }

    public static Path dataDir() {
        String override = System.getProperty("unichat.dataDir");
        if (override != null) return Path.of(override);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null ? appData : home, "Unichat");
        }
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "Unichat");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return Path.of(xdg != null ? xdg : home + "/.config", "unichat");
    }

    public Path dir() { return dir; }
    public Path cacheDir() { return dir.resolve("cache"); }

    private void load() {
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("Could not read settings: " + e);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("settings.properties.tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                props.store(out, "Unichat settings");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Could not save settings: " + e);
        }
    }

    public synchronized String getString(String key, String def) { return props.getProperty(key, def); }

    public synchronized void set(String key, String value) {
        if (value == null) props.remove(key); else props.setProperty(key, value);
        save();
    }

    public boolean getBool(String key, boolean def) {
        String v = getString(key, null);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public void setBool(String key, boolean value) { set(key, Boolean.toString(value)); }

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
        props.setProperty("matrix.homeserver", homeserver);
        props.setProperty("matrix.userId", userId);
        props.setProperty("matrix.accessToken", accessToken);
        if (deviceId != null) props.setProperty("matrix.deviceId", deviceId);
        props.setProperty("session.backend", "matrix");
        save();
    }

    public synchronized void clearSession() {
        props.stringPropertyNames().stream()
                .filter(k -> k.startsWith("matrix.") || k.startsWith("session."))
                .forEach(props::remove);
        save();
    }

    // --- Appearance & behaviour ---
    public Theme theme() {
        try {
            return Theme.valueOf(getString("ui.theme", "SYSTEM"));
        } catch (IllegalArgumentException e) {
            return Theme.SYSTEM;
        }
    }
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
    public boolean sendReadReceipts() { return getBool("privacy.readReceipts", true); }
    public void setSendReadReceipts(boolean v) { setBool("privacy.readReceipts", v); }
    public boolean sendTypingNotifications() { return getBool("privacy.typing", true); }
    public void setSendTypingNotifications(boolean v) { setBool("privacy.typing", v); }
    // --- Accounts ---
    /** User-chosen name for an account ("Trabajo"), or null. */
    public String accountAlias(String accountId) { return getString("account.alias." + accountId, null); }
    public void setAccountAlias(String accountId, String alias) {
        set("account.alias." + accountId, alias == null || alias.isBlank() ? null : alias.strip());
    }

    // --- Accessibility ---
    private Mode mode(String key) {
        try {
            return Mode.valueOf(getString(key, "SYSTEM"));
        } catch (IllegalArgumentException e) {
            return Mode.SYSTEM;
        }
    }
    public Mode highContrast() { return mode("a11y.highContrast"); }
    public void setHighContrast(Mode m) { set("a11y.highContrast", m.name()); }
    public Mode reduceMotion() { return mode("a11y.reduceMotion"); }
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
    public void setMinimizeToTray(boolean v) { setBool("app.minimizeToTray", v); }
}
