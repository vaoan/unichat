package app.unichat.matrix;

import app.unichat.model.Network;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for "every network is grey in Cuentas" on the local server: its bridges are only installed when
 * the network is added, so they must be offered before any bridge exists.
 */
class AvailableNetworksTest {
    @AfterEach
    void reset() {
        MatrixChatService.setBridgeProvisioner(null);
    }

    @Test
    void localServerOffersEveryNetworkItCanInstall() {
        MatrixChatService.setBridgeProvisioner(new MatrixChatService.BridgeProvisioner() {
            @Override public void ensure(Network n) { }
            @Override public boolean provides(Network n) { return n != Network.SIGNAL; }
        });
        List<Network> available = new MatrixChatService("http://127.0.0.1:8008", "@yo:localhost", "t").availableNetworks();
        assertTrue(available.contains(Network.WHATSAPP));
        assertTrue(available.contains(Network.TELEGRAM));
        assertFalse(available.contains(Network.SIGNAL), "not offered when the local server can't install it");
    }

    @Test
    void externalServerOnlyOffersBridgesItHas() {
        List<Network> available = new MatrixChatService("https://example.org", "@yo:example.org", "t").availableNetworks();
        assertTrue(available.isEmpty(), "nothing synced yet: no bridge known");
    }
}
