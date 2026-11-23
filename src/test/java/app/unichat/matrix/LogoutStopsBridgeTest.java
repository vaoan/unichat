package app.unichat.matrix;

import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Logging out of a bridge's last account stops it (it costs battery for nothing), but only when the bridge itself
 * answered that no login is left: a refresh that got no answer, or one that still lists a login, never stops it.
 */
class LogoutStopsBridgeTest {
    private final List<Network> released = new ArrayList<>();
    private final BridgeProvisioner provisioner = new BridgeProvisioner() {
        @Override public void ensure(Network n) { /* the fake ignores it */ }
        @Override public void loggedOut(Network n) { released.add(n); }
    };

    @Test
    void theBridgeConfirmingNoLoginIsLeftStopsIt() {
        BridgeAccounts.afterLogout(provisioner, Network.GOOGLE_MESSAGES, List.of());
        assertEquals(List.of(Network.GOOGLE_MESSAGES), released);
    }

    @Test
    void aLoginLeftOrNoAnswerKeepsItRunning() {
        BridgeAccounts.afterLogout(provisioner, Network.WHATSAPP, List.of(new BridgeProtocol.Login("2", "Casa", "CONNECTED")));
        BridgeAccounts.afterLogout(provisioner, Network.WHATSAPP, null);
        BridgeAccounts.afterLogout(null, Network.WHATSAPP, List.of());
        assertEquals(List.of(), released);
    }
}
