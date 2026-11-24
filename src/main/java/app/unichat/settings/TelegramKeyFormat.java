package app.unichat.settings;

import org.jspecify.annotations.Nullable;

/**
 * What a Telegram API key looks like (my.telegram.org): a positive number for the id, 32 hex digits for the hash. Pure;
 * {@code server.TelegramKeys} says what's wrong in words, and keys from the vault that aren't ones are never taken.
 */
public final class TelegramKeyFormat {
    public static final String ID = "server.telegram.apiId";
    public static final String HASH = "server.telegram.apiHash";

    private TelegramKeyFormat() {}

    public static boolean id(@Nullable String apiId) {
        try {
            return apiId != null && Integer.parseInt(apiId.strip()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean hash(@Nullable String apiHash) {
        return apiHash != null && apiHash.strip().matches("[0-9a-fA-F]{32}");
    }

    /** Whether {@code value} may be {@code key}'s: any value for a key that isn't Telegram's. */
    static boolean fits(String key, String value) {
        return key.equals(ID) ? id(value) : !key.equals(HASH) || hash(value);
    }
}
