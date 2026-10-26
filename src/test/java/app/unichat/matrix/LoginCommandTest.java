package app.unichat.matrix;

import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Login commands must name a flow id the installed bridge actually offers (see docs/BRIDGES.md). */
class LoginCommandTest {

    @Test
    void picksAFlowForMultiFlowBridges() {
        assertEquals("login qr", MatrixChatService.loginCommand(Network.WHATSAPP));
        assertEquals("login qr", MatrixChatService.loginCommand(Network.TELEGRAM));
        assertEquals("login facebook", MatrixChatService.loginCommand(Network.MESSENGER));
        assertEquals("login email", MatrixChatService.loginCommand(Network.SLACK));
        assertEquals("login cookies", MatrixChatService.loginCommand(Network.X));
        assertEquals("login cookies", MatrixChatService.loginCommand(Network.LINKEDIN));
    }

    @Test
    void singleFlowBridgesUsePlainLogin() {
        // Google Messages dropped its "qr" flow; only "google" (account + emoji pairing) is left.
        assertEquals("login", MatrixChatService.loginCommand(Network.GOOGLE_MESSAGES));
        assertEquals("login", MatrixChatService.loginCommand(Network.SIGNAL));
        assertEquals("login", MatrixChatService.loginCommand(Network.INSTAGRAM));
        assertEquals("login", MatrixChatService.loginCommand(Network.DISCORD));
    }

    @Test
    void commandsUseTheBridgeIdAsPrefix() {
        // Must match the "!<id>" command_prefix and bridge ids set by server/setup.*.
        assertEquals("!whatsapp login qr", MatrixChatService.botCommand(Network.WHATSAPP, "login qr"));
        assertEquals("!facebook list-logins", MatrixChatService.botCommand(Network.MESSENGER, "list-logins"));
        assertEquals("!twitter logout", MatrixChatService.botCommand(Network.X, "logout"));
        assertEquals("!gmessages login", MatrixChatService.botCommand(Network.GOOGLE_MESSAGES, "login"));
    }

    @Test
    void everyBridgedNetworkHasACommand() {
        for (Network n : Network.values()) {
            if (n.isBridged()) assertTrue(MatrixChatService.loginCommand(n).startsWith("login"), n.name());
        }
    }
}
