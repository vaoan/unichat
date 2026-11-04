package app.unichat.matrix;

import app.unichat.model.ConnectionState;
import app.unichat.model.Message;
import app.unichat.model.Network;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the stack from server/setup.* through {@link MatrixChatService}: every running bridge bot is detected and
 * answers the login command with a login step (QR, cookies prompt, ...), never "invalid flow" / "unknown command".
 * Does not link any account: each login is cancelled right away. Run with e.g.
 * {@code UNICHAT_IT_BRIDGES=http://localhost:8008 UNICHAT_IT_USER=yo UNICHAT_IT_PASSWORD=... ./mvnw test -Dtest=BridgesLiveIT}.
 * Optional {@code UNICHAT_IT_EXPECT} = comma-separated protocol ids that must be available.
 */
@EnabledIfEnvironmentVariable(named = "UNICHAT_IT_BRIDGES", matches = ".+")
class BridgesLiveIT {

    @Test
    void everyBridgeAnswersLogin() throws Exception {
        MatrixChatService svc = MatrixLogin.login(System.getenv("UNICHAT_IT_BRIDGES"),
                System.getenv("UNICHAT_IT_USER"), System.getenv("UNICHAT_IT_PASSWORD")).join();
        svc.start();
        try {
            // Connecting before the first sync would not find existing bot DMs and create duplicates.
            long online = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < online && svc.connectionState() != ConnectionState.ONLINE) Thread.sleep(250);
            String expect = System.getenv().getOrDefault("UNICHAT_IT_EXPECT", "");
            long end = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < end && !containsAll(svc.availableNetworks(), expect)) Thread.sleep(250);
            List<Network> available = svc.availableNetworks();
            System.out.println("availableNetworks = " + available);
            assertTrue(containsAll(available, expect), "missing bridges, got " + available);

            StringBuilder failures = new StringBuilder();
            for (Network n : available) {
                String roomId = svc.connectNetwork(n).join();
                String cmd = BridgeProtocol.botCommand(n, BridgeProtocol.loginCommand(n));
                String reply = awaitBotReply(svc, roomId, cmd);
                System.out.println("=== " + n + " <- " + cmd + "\n" + reply);
                String lower = reply == null ? "" : reply.toLowerCase(Locale.ROOT);
                if (reply == null || lower.contains("invalid login flow") || lower.contains("unknown command")) {
                    failures.append(n).append(": ").append(reply).append('\n');
                }
                svc.sendText(roomId, BridgeProtocol.botCommand(n, "cancel"), null).join();
            }
            System.out.println("accounts = " + svc.accounts());
            assertFalse(available.isEmpty(), "no bridge bots found");
            if (!failures.isEmpty()) fail(failures.toString());
        } finally {
            svc.logout().join();
            svc.stop();
        }
    }

    private static boolean containsAll(List<Network> available, String expect) {
        for (String id : expect.split(",")) {
            if (id.isBlank()) continue;
            Network n = Network.fromProtocolId(id.trim());
            if (n == null || !available.contains(n)) return false;
        }
        return true;
    }

    /** Joins the bot messages that arrive after our command (QR images are reported by their body). */
    private static String awaitBotReply(MatrixChatService svc, String roomId, String command) throws InterruptedException {
        long end = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < end) {
            List<Message> msgs = svc.messages(roomId);
            int cmd = -1;
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if (msgs.get(i).outgoing() && command.equals(msgs.get(i).body())) { cmd = i; break; }
            }
            if (cmd >= 0) {
                StringBuilder sb = new StringBuilder();
                for (Message m : msgs.subList(cmd + 1, msgs.size())) {
                    if (!m.outgoing()) sb.append('[').append(m.type()).append("] ").append(m.body()).append('\n');
                }
                // Give multi-message replies (notice + QR image) a moment to complete.
                if (!sb.isEmpty()) {
                    Thread.sleep(3000);
                    sb.setLength(0);
                    msgs = svc.messages(roomId);
                    for (Message m : msgs.subList(cmd + 1, msgs.size())) {
                        if (!m.outgoing()) sb.append('[').append(m.type()).append("] ").append(m.body()).append('\n');
                    }
                    return sb.toString().trim();
                }
            }
            Thread.sleep(250);
        }
        return null;
    }
}
