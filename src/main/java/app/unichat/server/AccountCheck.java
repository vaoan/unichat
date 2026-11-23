package app.unichat.server;

import app.unichat.bridge.LoginsCache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Whether a bridge has an account, so it's started at launch: the logins Unichat keeps ({@link LoginsCache}), or a login
 * in the bridge's own database (one Unichat hasn't kept yet, as after an upgrade). When neither tells, it has one: a
 * bridge with an account left stopped would silence it, which is worse than the battery it costs.
 */
final class AccountCheck {
    private AccountCheck() {}

    /** @param cached the logins kept, null when none were ever read; @param db the bridge's SQLite database */
    static boolean hasAccount(String cached, Path db) {
        if (LoginsCache.hasLogin(cached)) return true;
        Boolean inDb = inDatabase(db);
        if (inDb != null) return inDb;
        return cached == null;
    }

    /** A login in the bridge's database: a {@code user_login} row (bridgev2) or a user with a token (legacy Discord); null when it can't tell. */
    private static Boolean inDatabase(Path db) {
        if (!Files.isRegularFile(db)) return null;
        org.sqlite.SQLiteConfig ro = new org.sqlite.SQLiteConfig();
        ro.setReadOnly(true);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath(), ro.toProperties())) {
            if (hasTable(c, "user_login")) return anyRow(c, "SELECT 1 FROM user_login LIMIT 1");
            if (hasTable(c, "user")) return anyRow(c, "SELECT 1 FROM \"user\" WHERE discord_token IS NOT NULL AND discord_token <> '' LIMIT 1");
            return null;
        } catch (SQLException e) {
            return null;
        }
    }

    private static boolean hasTable(Connection c, String table) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        try (ResultSet r = meta.getTables(null, null, table, new String[]{"TABLE"})) {
            return r.next();
        }
    }

    private static boolean anyRow(Connection c, String query) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            return r.next();
        }
    }
}
