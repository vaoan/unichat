package app.unichat.server;

import java.sql.SQLException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a bridge has an account, so it's started at launch. The logins Unichat keeps say so; when it kept none yet
 * (an upgrade), the bridge's own database does; when nothing tells, it's started: an account gone silent is worse
 * than the battery.
 */
class AccountCheckTest {
    @TempDir Path dir;

    private static final String ONE_LOGIN = "1763000000000\n573001112233\tTrabajo\tCONNECTED\n";
    private static final String NO_LOGIN = "1763000000000\n";

    private int dbs;

    /** A new database each call. */
    @SuppressFBWarnings(value = {"SQL_INJECTION_JDBC", "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"}, justification = "builds or reads a fixture database with the test's own SQL")
    private Path db(String... sql) throws SQLException {
        Path db = dir.resolve("bridge" + dbs++ + ".db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            for (String q : sql) s.execute(q);
        }
        return db;
    }

    private Path bridgev2(boolean withLogin) throws SQLException {
        return withLogin
                ? db("CREATE TABLE \"user\" (mxid TEXT)", "CREATE TABLE user_login (id TEXT)", "INSERT INTO user_login VALUES ('1')")
                : db("CREATE TABLE \"user\" (mxid TEXT)", "CREATE TABLE user_login (id TEXT)", "INSERT INTO \"user\" VALUES ('@yo:localhost')");
    }

    private Path legacyDiscord(String token) throws SQLException {
        return db("CREATE TABLE \"user\" (mxid TEXT PRIMARY KEY, dcid TEXT, discord_token TEXT)",
                "INSERT INTO \"user\" VALUES ('@yo:localhost', null, " + (token == null ? "null" : "'" + token + "'") + ")");
    }

    @Test
    void theLoginsKeptSaySo() throws Exception {
        assertTrue(AccountCheck.hasAccount(ONE_LOGIN, dir.resolve("none.db")));
        assertFalse(AccountCheck.hasAccount(NO_LOGIN, bridgev2(false)), "Google Messages: no user logins found");
    }

    @Test
    void beforeAnyLoginWasKeptTheBridgesDatabaseSays() throws Exception {
        assertTrue(AccountCheck.hasAccount(null, bridgev2(true)));
        assertFalse(AccountCheck.hasAccount(null, bridgev2(false)));
    }

    @Test
    void theLegacyDiscordBridgeHasAnAccountWhenItKeepsAToken() throws Exception {
        assertTrue(AccountCheck.hasAccount(null, legacyDiscord("tok")));
        assertFalse(AccountCheck.hasAccount(null, legacyDiscord(null)));
    }

    @Test
    void aLoginInTheDatabaseWinsOverNoLoginKept() throws Exception {
        assertTrue(AccountCheck.hasAccount(NO_LOGIN, bridgev2(true)), "the safe direction: it starts");
    }

    @Test
    void noLoginKeptAndADatabaseThatCantBeReadStarts() throws Exception {
        assertTrue(AccountCheck.hasAccount(NO_LOGIN, dir.resolve("none.db")), "no database");
        assertTrue(AccountCheck.hasAccount(NO_LOGIN, db("CREATE TABLE other (x TEXT)")), "one it can't read");
    }

    @Test
    void whenNothingTellsTheBridgeStarts() throws Exception {
        assertTrue(AccountCheck.hasAccount(null, dir.resolve("none.db")), "no database yet");
        assertTrue(AccountCheck.hasAccount(null, db("CREATE TABLE other (x TEXT)")), "a database it can't read");
        assertFalse(Files.exists(dir.resolve("none.db")), "looking doesn't create it");
    }
}
