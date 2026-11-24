package app.unichat.server;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import org.jspecify.annotations.Nullable;

/**
 * Each person's own Telegram API keys (my.telegram.org): Telegram requires them, and they aren't shareable, so
 * Unichat ships none. Kept in the settings file like the session token. The format check is pure and tested.
 */
public final class TelegramKeys {
    public static final String URL = "https://my.telegram.org";
    private static final String ID = app.unichat.settings.TelegramKeyFormat.ID;
    private static final String HASH = app.unichat.settings.TelegramKeyFormat.HASH;

    private TelegramKeys() {}

    /** What's wrong with these values, for the person to read; null when they look right. */
    public static @Nullable String problem(@Nullable String apiId, @Nullable String apiHash) {
        String id = apiId == null ? "" : apiId.strip();
        String hash = apiHash == null ? "" : apiHash.strip();
        if (id.isEmpty()) return I18n.t("telegramKeys.noId");
        if (!app.unichat.settings.TelegramKeyFormat.id(id)) return I18n.t("telegramKeys.badId");
        if (hash.isEmpty()) return I18n.t("telegramKeys.noHash");
        if (!app.unichat.settings.TelegramKeyFormat.hash(hash)) return I18n.t("telegramKeys.badHash");
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
