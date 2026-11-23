package app.unichat.bridge;

/**
 * The logins Unichat keeps of each network's bridge, in the setting {@code matrix.logins.<protocolId>}: a timestamp
 * line, then one login per line ({@code id<TAB>name<TAB>state}). {@code matrix} writes it; {@code server} reads it to
 * know which bridges have an account.
 */
public final class LoginsCache {
    private LoginsCache() {}

    public static String key(String protocolId) {
        return "matrix.logins." + protocolId;
    }

    /** Whether {@code cached} (null when nothing was kept) holds at least one login. */
    public static boolean hasLogin(String cached) {
        if (cached == null) return false;
        String[] lines = cached.split("\n");
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].split("\t", -1)[0].isBlank()) return true;
        }
        return false;
    }
}
