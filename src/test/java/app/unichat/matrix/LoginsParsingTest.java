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
        List<BridgeProtocol.Login> l = BridgeProtocol.parseLogins(reply);
        assertEquals(2, l.size());
        assertEquals(new BridgeProtocol.Login("573001112233", "+57 300 111 2233", "CONNECTED"), l.get(0));
        assertEquals("TRANSIENT_DISCONNECT", l.get(1).state());
        assertEquals(AccountStatus.CONNECTING, BridgeProtocol.loginStatus(l.get(1).state()));
        assertEquals("whatsapp:573001112233", BridgeProtocol.accountId(Network.WHATSAPP, l.get(0).id()));
    }

    @Test
    void notLoggedInAndBareIds() {
        assertTrue(BridgeProtocol.parseLogins("You're not logged in").isEmpty());
        List<BridgeProtocol.Login> l = BridgeProtocol.parseLogins("- `abc`");
        assertEquals(1, l.size());
        assertEquals(null, l.get(0).name());
        assertEquals(AccountStatus.CONNECTED, BridgeProtocol.loginStatus(null));
        assertEquals(AccountStatus.NEEDS_LOGIN, BridgeProtocol.loginStatus("BAD_CREDENTIALS"));
    }

    @Test
    void loginChangeTriggers() {
        assertTrue(BridgeProtocol.loginChanged("Successfully logged in as +57 300"));
        assertTrue(BridgeProtocol.loginChanged("Logged out"));
        assertFalse(BridgeProtocol.loginChanged("You're not logged in"), "list-logins reply must not loop");
        assertFalse(BridgeProtocol.loginChanged("* `x` (y) - `LOGGED_OUT`"));
    }

    @Test
    void liveStatesGiveABriefDropTimeToHeal() {
        long t = 1_000_000;
        assertEquals(app.unichat.model.AccountStatus.CONNECTED, BridgeProtocol.liveStatus("TRANSIENT_DISCONNECT", t - 30_000, t));
        assertEquals(app.unichat.model.AccountStatus.CONNECTING, BridgeProtocol.liveStatus("TRANSIENT_DISCONNECT", t - 90_000, t));
        assertEquals(app.unichat.model.AccountStatus.ERROR, BridgeProtocol.liveStatus("UNKNOWN_ERROR", t - 90_000, t));
        assertEquals(app.unichat.model.AccountStatus.NEEDS_LOGIN, BridgeProtocol.liveStatus("LOGGED_OUT", t, t),
                "a closed session shows at once");
        assertEquals(app.unichat.model.AccountStatus.CONNECTED, BridgeProtocol.liveStatus("BACKFILLING", t - 999_999, t));
    }
}
