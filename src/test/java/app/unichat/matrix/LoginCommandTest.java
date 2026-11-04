package app.unichat.matrix;

import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Login commands must name a flow id the installed bridge actually offers (see docs/BRIDGES.md). */
class LoginCommandTest {

    @Test
    void picksAFlowForMultiFlowBridges() {
        assertEquals("login qr", BridgeProtocol.loginCommand(Network.WHATSAPP));
        assertEquals("login qr", BridgeProtocol.loginCommand(Network.TELEGRAM));
        assertEquals("login facebook", BridgeProtocol.loginCommand(Network.MESSENGER));
        assertEquals("login email", BridgeProtocol.loginCommand(Network.SLACK));
        assertEquals("login cookies", BridgeProtocol.loginCommand(Network.X));
        assertEquals("login cookies", BridgeProtocol.loginCommand(Network.LINKEDIN));
    }

    @Test
    void singleFlowBridgesUsePlainLogin() {
        // Google Messages dropped its "qr" flow; only "google" (account + emoji pairing) is left.
        assertEquals("login", BridgeProtocol.loginCommand(Network.GOOGLE_MESSAGES));
        assertEquals("login", BridgeProtocol.loginCommand(Network.SIGNAL));
        assertEquals("login", BridgeProtocol.loginCommand(Network.INSTAGRAM));
        assertEquals("login", BridgeProtocol.loginCommand(Network.DISCORD));
    }

    @Test
    void commandsUseTheBridgeIdAsPrefix() {
        // Must match the "!<id>" command_prefix and bridge ids set by server/setup.*.
        assertEquals("!whatsapp login qr", BridgeProtocol.botCommand(Network.WHATSAPP, "login qr"));
        assertEquals("!facebook list-logins", BridgeProtocol.botCommand(Network.MESSENGER, "list-logins"));
        assertEquals("!twitter logout", BridgeProtocol.botCommand(Network.X, "logout"));
        assertEquals("!gmessages login", BridgeProtocol.botCommand(Network.GOOGLE_MESSAGES, "login"));
    }

    @Test
    void everyBridgedNetworkHasACommand() {
        for (Network n : Network.values()) {
            if (n.isBridged()) assertTrue(BridgeProtocol.loginCommand(n).startsWith("login"), n.name());
        }
    }

    @Test
    void discordWaitsForTheUsersChoice() {
        assertEquals(false, BridgeProtocol.startsLoginByItself(Network.DISCORD));
        assertEquals(true, BridgeProtocol.startsLoginByItself(Network.WHATSAPP));
    }

    @Test
    void discordAccountsComeFromPing() {
        assertEquals("ping", BridgeProtocol.statusCommand(Network.DISCORD));
        assertEquals("list-logins", BridgeProtocol.statusCommand(Network.WHATSAPP));
        var in = BridgeProtocol.parseDiscordPing("You're logged in as @ejemplo (`123456789012345678`)");
        assertEquals(1, in.size());
        assertEquals("123456789012345678", in.get(0).id());
        assertEquals("@ejemplo", in.get(0).name());
        assertEquals("CONNECTED", in.get(0).state());
        assertEquals(0, BridgeProtocol.parseDiscordPing("You're not logged in").size());
        assertEquals("TRANSIENT_DISCONNECT",
                BridgeProtocol.parseDiscordPing("You're logged in, but the Discord connection seems to be dead 💥").get(0).state());
    }
}
