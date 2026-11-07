package app.unichat.ui;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountAlertsTest {
    private static final java.util.Set<Network> ALL = java.util.EnumSet.allOf(Network.class);

    private static Account acc(Network n, String label, AccountStatus s) {
        return new Account(n.protocolId() + ":" + label, n, label, s, null);
    }

    @Test
    void workingAccountsSayNothing() {
        assertTrue(AccountAlerts.of(List.of(acc(Network.TELEGRAM, "yo", AccountStatus.CONNECTED)), ALL).isEmpty());
    }

    @Test
    void aBridgeNeverSignedInToIsNotAClosedSession() {
        assertTrue(AccountAlerts.of(List.of(acc(Network.X, "X", AccountStatus.NEEDS_LOGIN)), java.util.Set.of()).isEmpty());
    }

    @Test
    void aClosedSessionAsksToRelinkAndComesFirst() {
        List<AccountAlerts.Alert> a = AccountAlerts.of(List.of(
                acc(Network.DISCORD, "yo", AccountStatus.CONNECTING),
                acc(Network.WHATSAPP, "+57", AccountStatus.NEEDS_LOGIN)), ALL);
        assertEquals(2, a.size());
        assertEquals(Network.WHATSAPP, a.get(0).network());
        assertTrue(a.get(0).relink());
        assertTrue(a.get(0).text().startsWith("WhatsApp: se cerró la sesión"));
        assertFalse(a.get(1).relink(), "a drop fixes itself: no button");
        assertTrue(a.get(1).text().contains("Reconectando"));
    }

    @Test
    void oneLinePerNetworkNamingTheBrokenAccount() {
        List<AccountAlerts.Alert> a = AccountAlerts.of(List.of(
                acc(Network.WHATSAPP, "Personal", AccountStatus.CONNECTED),
                acc(Network.WHATSAPP, "Trabajo", AccountStatus.NEEDS_LOGIN)), ALL);
        assertEquals(1, a.size());
        assertTrue(a.get(0).text().startsWith("WhatsApp (Trabajo):"), a.get(0).text());
    }

    @Test
    void whatFixesItselfSharesOneShortLine() {
        List<AccountAlerts.Alert> a = AccountAlerts.of(List.of(
                acc(Network.TELEGRAM, "yo", AccountStatus.CONNECTING),
                acc(Network.DISCORD, "yo", AccountStatus.ERROR)), ALL);
        assertEquals("Reconectando Telegram…", AccountAlerts.recoveringLine(a.subList(0, 1)));
        assertEquals("Reconectando Telegram y Discord…", AccountAlerts.recoveringLine(a));
        assertEquals("Reconectando 3 redes…", AccountAlerts.recoveringLine(AccountAlerts.of(List.of(
                acc(Network.TELEGRAM, "yo", AccountStatus.CONNECTING),
                acc(Network.DISCORD, "yo", AccountStatus.CONNECTING),
                acc(Network.SLACK, "yo", AccountStatus.CONNECTING)), ALL)));
    }

    @Test
    void reconnectingNetworksAreSyncingAtTheTopAndNothingElseIs() {
        List<AccountAlerts.Alert> a = AccountAlerts.of(List.of(
                acc(Network.TELEGRAM, "yo", AccountStatus.CONNECTING),
                acc(Network.DISCORD, "yo", AccountStatus.CONNECTING),
                acc(Network.WHATSAPP, "+57", AccountStatus.NEEDS_LOGIN)), ALL);
        assertEquals("Sincronizando Telegram y Discord…", AccountAlerts.syncingLine(a));
        assertEquals(null, AccountAlerts.syncingLine(AccountAlerts.of(List.of(
                acc(Network.TELEGRAM, "yo", AccountStatus.CONNECTED),
                acc(Network.WHATSAPP, "+57", AccountStatus.NEEDS_LOGIN)), ALL)), "a closed session isn't syncing");
    }
}
