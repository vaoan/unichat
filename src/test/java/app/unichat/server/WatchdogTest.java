package app.unichat.server;

import app.unichat.bridge.BridgeStates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WatchdogTest {
    private static final long NOW = 100 * 60_000;

    @Test
    void aHealthyProcessIsLeftAlone() {
        assertNull(Watchdog.reason(new Watchdog.Health(0, -1, 0), NOW));
        assertNull(Watchdog.reason(new Watchdog.Health(2, -1, 4), NOW), "two missed probes, four drops: not yet");
        assertNull(Watchdog.reason(new Watchdog.Health(0, NOW - 9 * 60_000, 0), NOW), "disconnected 9 minutes");
    }

    @Test
    void hungStuckOrFlappingIsRestarted() {
        assertEquals("not responding", Watchdog.reason(new Watchdog.Health(3, -1, 0), NOW));
        assertNotNull(Watchdog.reason(new Watchdog.Health(0, NOW - 10 * 60_000, 0), NOW));
        assertEquals("5 disconnections in 10 min", Watchdog.reason(new Watchdog.Health(0, -1, 5), NOW));
    }

    @Test
    void restartsInARowWaitLongerUpToAnHour() {
        assertEquals(5 * 60_000, Watchdog.cooldown(1));
        assertEquals(10 * 60_000, Watchdog.cooldown(2));
        assertEquals(40 * 60_000, Watchdog.cooldown(4));
        assertEquals(60 * 60_000, Watchdog.cooldown(9));
    }

    @Test
    void theWatchdogLeavesABridgeWithoutAccountsAlone() throws Exception {
        HttpServer whatsapp = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        whatsapp.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        whatsapp.start();
        try {
            // gmessages has no account: not running, so nothing answers on its port.
            HttpServer closed = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            int nobody = closed.getAddress().getPort();
            closed.stop(0);
            BridgeLauncher launcher = new BridgeLauncher(new BridgeLauncher.Processes() {
                @Override public boolean isRunning(String bridge) { return bridge.equals("whatsapp"); }
                @Override public void prepare(String bridge, java.util.function.Consumer<String> progress) { /* the fake ignores it */ }
                @Override public void start(String bridge) { /* the fake ignores it */ }
                @Override public void stop(String bridge) { /* the fake ignores it */ }
                @Override public void restarting(String bridge) { /* the fake ignores it */ }
                @Override public java.nio.file.Path log(String bridge) { return java.nio.file.Path.of(bridge); }
                @Override public java.nio.file.Path config(String bridge) { return java.nio.file.Path.of(bridge); }
                @Override public void clearOneShotLimits() { /* the fake ignores it */ }
            }, new BridgeDemand(Set.of("whatsapp")::contains, b -> true, Duration.ZERO, Duration.ZERO),
                    () -> Set.of("whatsapp", "gmessages"));
            launcher.launch();
            List<String> restarted = new ArrayList<>();
            boolean[] online = {false};
            Watchdog dog = new Watchdog(Map.of("whatsapp", whatsapp.getAddress().getPort(), "gmessages", nobody),
                    launcher::watched, new BridgeStates(), (name, why) -> restarted.add(name + ": " + why), () -> online[0]);
            for (int i = 0; i < PROBE_ROUNDS; i++) dog.check();
            online[0] = true;
            dog.check();
            assertEquals(List.of("whatsapp: internet is back"), restarted, "never probed, counted nor restarted");
        } finally {
            whatsapp.stop(0);
        }
    }

    /**
     * "Load older chats" restarts a bridge on purpose (BridgeLauncher.reconfigure): the drop its stop reports, on top of
     * the ones before, must not make the watchdog take it for flapping and restart it once more.
     */
    @Test
    void aReconfiguredBridgeIsntCountedAsFlapping() throws Exception {
        HttpServer discord = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        discord.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        discord.start();
        try {
            BridgeStates states = new BridgeStates();
            long now = BridgeStates.now();
            for (int i = 0; i < Watchdog.FLAPS; i++) { // the last one is the reconfigure's own stop
                states.record("discord", state("TRANSIENT_DISCONNECT"), now);
                states.record("discord", state("CONNECTED"), now);
            }
            states.restarting("discord", now);
            states.record("discord", state("CONNECTED"), now);
            List<String> restarted = new ArrayList<>();
            Watchdog dog = new Watchdog(Map.of("discord", discord.getAddress().getPort()), () -> List.of("discord"), states,
                    (name, why) -> restarted.add(name + ": " + why), () -> true);
            dog.check();
            assertEquals(List.of(), restarted);
        } finally {
            discord.stop(0);
        }
    }

    private static JsonNode state(String event) {
        return new ObjectMapper().createObjectNode().put("state_event", event).put("remote_id", "me").put("remote_name", "Me");
    }

    private static final int PROBE_ROUNDS = Watchdog.PROBE_FAILURES + 1;
}
