package app.unichat.server;

import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * Has a bridge say again what each of its chats allows. A bridgev2 bridge publishes it in each room
 * ({@code com.beeper.room_features}, what Unichat offers from) when the chat is made or its info changes, and X's
 * (mautrix-twitter) decides editing and deleting for everyone by whether it has the chat's XChat keys, which often come
 * later without either: the chat kept "no edit" for good while X and the bridge itself allowed it. The bridge says them
 * all again at start when the capabilities' version it last used ({@code kv_store}'s {@code bridge_info_version},
 * "info,capabilities") isn't its own, sending only the chats whose answer changed; so that half is marked stale before
 * it starts. The info's half is kept, or it would send every chat's info again too.
 */
final class BridgeCapabilities {
    /** The bridges whose capabilities change without the bridge saying so. */
    private static final Set<String> STALE = Set.of("twitter");
    private static final String VERSION_ROW = "bridge_info_version"; // a row of the bridge's, not a setting

    private BridgeCapabilities() {}

    /** Before {@code bridge} starts from {@code bridgeDir}; does nothing when there's nothing to mark, and never fails. */
    static void beforeStart(String bridge, Path bridgeDir) {
        Path db = BridgeConfig.database(bridgeDir, bridge);
        if (!STALE.contains(bridge) || !Files.isRegularFile(db)) return;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
            String version = read(c);
            if (version == null || !version.matches("-?\\d+,-?\\d+")) return;
            try (PreparedStatement s = c.prepareStatement("UPDATE kv_store SET value = ? WHERE key = ?")) {
                s.setString(1, version.substring(0, version.indexOf(',')) + ",-1");
                s.setString(2, VERSION_ROW);
                s.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println(bridge + ": what its chats allow not refreshed: " + e.getMessage()); // it starts as ever
        }
    }

    private static @Nullable String read(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT value FROM kv_store WHERE key = ?")) {
            s.setString(1, VERSION_ROW);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }
}
