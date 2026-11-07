package app.unichat.server;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;

/**
 * Each person's own Telegram API keys (my.telegram.org): Telegram requires them, and they aren't shareable, so
 * Unichat ships none. Kept in the settings file like the session token. The format check is pure and tested.
 */
public final class TelegramKeys {
    public static final String URL = "https://my.telegram.org";
    private static final String ID = "server.telegram.apiId", HASH = "server.telegram.apiHash";

    private TelegramKeys() {}

    /** What's wrong with these values, for the person to read; null when they look right. */
    public static String problem(String apiId, String apiHash) {
        String id = apiId == null ? "" : apiId.strip(), hash = apiHash == null ? "" : apiHash.strip();
        if (id.isEmpty()) return I18n.t("telegramKeys.noId");
        try {
            if (Integer.parseInt(id) <= 0) return I18n.t("telegramKeys.badId");
        } catch (NumberFormatException e) {
            return I18n.t("telegramKeys.badId");
        }
        if (hash.isEmpty()) return I18n.t("telegramKeys.noHash");
        if (!hash.matches("[0-9a-fA-F]{32}")) return I18n.t("telegramKeys.badHash");
        return null;
    }

    public static String apiId() { return Settings.get().getString(ID, ""); }
    public static String apiHash() { return Settings.get().getString(HASH, ""); }

    /** Whether valid keys are saved. */
    public static boolean saved() { return problem(apiId(), apiHash()) == null; }

    public static void save(String apiId, String apiHash) {
        Settings.get().set(ID, apiId.strip());
        Settings.get().set(HASH, apiHash.strip());
    }
}
