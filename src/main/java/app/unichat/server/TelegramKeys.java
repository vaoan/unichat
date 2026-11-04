package app.unichat.server;

import app.unichat.settings.Settings;

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
        if (id.isEmpty()) return "Falta el api_id.";
        try {
            if (Integer.parseInt(id) <= 0) return "El api_id debe ser un número, por ejemplo 1234567.";
        } catch (NumberFormatException e) {
            return "El api_id debe ser un número, por ejemplo 1234567.";
        }
        if (hash.isEmpty()) return "Falta el api_hash.";
        if (!hash.matches("[0-9a-fA-F]{32}")) return "El api_hash debe tener 32 caracteres, solo números y letras de la a a la f.";
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
