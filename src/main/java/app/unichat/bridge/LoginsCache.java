package app.unichat.bridge;

import java.util.ArrayList;
import java.util.List;

/**
 * The logins Unichat keeps of each network's bridge, in the setting {@code matrix.logins.<protocolId>}: a timestamp
 * line, then one login per line ({@code id<TAB>name<TAB>state}). {@code matrix} writes and reads it; {@code server}
 * reads it to know which bridges have an account.
 */
public final class LoginsCache {
    private LoginsCache() {}

    /** One login; {@code name} and {@code state} null when unknown. */
    public record Row(String id, String name, String state) {}

    /** What was kept, and when ({@code at} 0 when the time can't be read: stale). */
    public record Cached(long at, List<Row> rows) {}

    public static String key(String protocolId) {
        return "matrix.logins." + protocolId;
    }

    public static String format(long at, List<Row> rows) {
        StringBuilder sb = new StringBuilder().append(at).append('\n');
        for (Row r : rows) {
            sb.append(r.id()).append('\t').append(r.name() == null ? "" : r.name()).append('\t')
                    .append(r.state() == null ? "" : r.state()).append('\n');
        }
        return sb.toString();
    }

    /** {@code cached} read back; null when nothing was kept. */
    public static Cached parse(String cached) {
        if (cached == null) return null;
        String[] lines = cached.split("\n");
        long at = 0;
        try {
            at = Long.parseLong(lines[0]);
        } catch (NumberFormatException ignored) {
            // treated as stale
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t", -1);
            if (f[0].isBlank()) continue;
            rows.add(new Row(f[0], f.length > 1 && !f[1].isEmpty() ? f[1] : null, f.length > 2 && !f[2].isEmpty() ? f[2] : null));
        }
        return new Cached(at, rows);
    }

    /** Whether {@code cached} (null when nothing was kept) holds at least one login. */
    public static boolean hasLogin(String cached) {
        Cached c = parse(cached);
        return c != null && !c.rows().isEmpty();
    }
}
