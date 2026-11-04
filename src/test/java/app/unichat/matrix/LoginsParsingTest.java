package app.unichat.matrix;

import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginsParsingTest {
    @Test
    void parsesBridgev2ListLogins() {
        String reply = """
                * `573001112233` (+57 300 111 2233) - `CONNECTED`
                * `573105550000` (Trabajo SAS) - `TRANSIENT_DISCONNECT`
                """;
        List<MatrixChatService.Login> l = MatrixChatService.parseLogins(reply);
        assertEquals(2, l.size());
        assertEquals(new MatrixChatService.Login("573001112233", "+57 300 111 2233", "CONNECTED"), l.get(0));
        assertEquals("TRANSIENT_DISCONNECT", l.get(1).state());
        assertEquals(AccountStatus.CONNECTING, MatrixChatService.loginStatus(l.get(1).state()));
        assertEquals("whatsapp:573001112233", MatrixChatService.accountId(Network.WHATSAPP, l.get(0).id()));
    }

    @Test
    void notLoggedInAndBareIds() {
        assertTrue(MatrixChatService.parseLogins("You're not logged in").isEmpty());
        List<MatrixChatService.Login> l = MatrixChatService.parseLogins("- `abc`");
        assertEquals(1, l.size());
        assertEquals(null, l.get(0).name());
        assertEquals(AccountStatus.CONNECTED, MatrixChatService.loginStatus(null));
        assertEquals(AccountStatus.NEEDS_LOGIN, MatrixChatService.loginStatus("BAD_CREDENTIALS"));
    }

    @Test
    void loginChangeTriggers() {
        assertTrue(MatrixChatService.loginChanged("Successfully logged in as +57 300"));
        assertTrue(MatrixChatService.loginChanged("Logged out"));
        assertFalse(MatrixChatService.loginChanged("You're not logged in"), "list-logins reply must not loop");
        assertFalse(MatrixChatService.loginChanged("* `x` (y) - `LOGGED_OUT`"));
    }

    @Test
    void liveStatesGiveABriefDropTimeToHeal() {
        long t = 1_000_000;
        assertEquals(app.unichat.model.AccountStatus.CONNECTED, MatrixChatService.liveStatus("TRANSIENT_DISCONNECT", t - 30_000, t));
        assertEquals(app.unichat.model.AccountStatus.CONNECTING, MatrixChatService.liveStatus("TRANSIENT_DISCONNECT", t - 90_000, t));
        assertEquals(app.unichat.model.AccountStatus.ERROR, MatrixChatService.liveStatus("UNKNOWN_ERROR", t - 90_000, t));
        assertEquals(app.unichat.model.AccountStatus.NEEDS_LOGIN, MatrixChatService.liveStatus("LOGGED_OUT", t, t),
                "a closed session shows at once");
        assertEquals(app.unichat.model.AccountStatus.CONNECTED, MatrixChatService.liveStatus("BACKFILLING", t - 999_999, t));
    }
}
