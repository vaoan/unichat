package app.unichat.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.unichat.Present;
import app.unichat.model.Network;
import app.unichat.service.Changes;
import app.unichat.service.OlderChats;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class MatrixServiceDirectTest {

    private static JsonNode json(String s) {
        try {
            return MatrixClient.JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void keepsServerEntriesAndAddsNewBot() {
        JsonNode server = json("{\"@a:x\":[\"!1\"],\"@b:x\":[\"!2\"]}");
        ObjectNode out = BridgeProtocol.mergeDirect(server, json("{}"), "@c:x", "!3");
        assertEquals(json("{\"@a:x\":[\"!1\"],\"@b:x\":[\"!2\"],\"@c:x\":[\"!3\"]}"), out);
    }

    @Test
    void doesNotDuplicateRoom() {
        JsonNode server = json("{\"@a:x\":[\"!1\"]}");
        ObjectNode out = BridgeProtocol.mergeDirect(server, json("{\"@a:x\":[\"!1\"]}"), "@a:x", "!1");
        assertEquals(json("{\"@a:x\":[\"!1\"]}"), out);
    }

    @Test
    void nullOrEmptyServerFallsBackToCached() {
        JsonNode cached = json("{\"@a:x\":[\"!1\"]}");
        assertEquals(json("{\"@a:x\":[\"!1\"],\"@c:x\":[\"!3\"]}"),
                BridgeProtocol.mergeDirect(null, cached, "@c:x", "!3"));
        assertEquals(json("{\"@a:x\":[\"!1\"],\"@c:x\":[\"!3\"]}"),
                BridgeProtocol.mergeDirect(json("{}"), cached, "@c:x", "!3"));
    }

    @Test
    void localOnlyEntrySurvivesAndListsAreUnioned() {
        JsonNode server = json("{\"@a:x\":[\"!1\"]}");
        JsonNode cached = json("{\"@a:x\":[\"!1\",\"!9\"],\"@l:x\":[\"!8\"]}");
        ObjectNode out = BridgeProtocol.mergeDirect(server, cached, "@a:x", "!3");
        assertEquals(json("{\"@a:x\":[\"!1\",\"!9\",\"!3\"],\"@l:x\":[\"!8\"]}"), out);
        assertTrue(out.has("@l:x"));
    }

    /** A loader over fakes: what it asks the bot and the provisioner goes in {@code asked}. */
    private static OlderChatLoader fakeLoader(RoomArrivals arrivals, Map<String, String> settings, List<String> asked,
                                              ExecutorService io) {
        return fakeLoader(arrivals, settings, asked, io, new CountDownLatch(0), new CountDownLatch(1));
    }

    /** The same, its restarts held until {@code restarted} is released; {@code restarting} goes down as one starts. */
    private static OlderChatLoader fakeLoader(RoomArrivals arrivals, Map<String, String> settings, List<String> asked,
                                              ExecutorService io, CountDownLatch restarted, CountDownLatch restarting) {
        ManualTime time = new ManualTime(Instant.parse("2026-11-24T12:00:00Z"));
        OlderChatSteps steps = new OlderChatSteps(settings::get, EnumSet.of(Network.WHATSAPP, Network.TELEGRAM), time::now);
        BridgeProvisioner provisioner = new BridgeProvisioner() {
            @Override public void ensure(Network network) { asked.add("ensure " + network); }

            @Override
            public void reconfigure(Network network, java.util.function.Consumer<String> progress) throws InterruptedException {
                asked.add("reconfigure " + network);
                restarting.countDown();
                restarted.await();
            }
        };
        return new OlderChatLoader(steps, (n, command, patience) -> {
            asked.add("command " + n + " " + command);
            return "Unknown command, use the `help` command for help.";
        }, provisioner, arrivals, new OlderChatLoader.SettingsWriter() {
            @Override public @Nullable String get(String key) { return settings.get(key); }

            @Override public void set(String key, @Nullable String value) { asked.add("set " + key); }
        }, id -> asked.add("markRead " + id), id -> null, io);
    }

    @Test
    void olderChatsAreReachedThroughTheService() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:9", "@me:x", "t");
        OlderChats older = assertInstanceOf(OlderChats.class, svc);
        List<String> asked = new CopyOnWriteArrayList<>();
        try (ExecutorService io = Executors.newSingleThreadExecutor()) {
            svc.useOlderChats(fakeLoader(svc.olderChatLoader().arrivals(), new ConcurrentHashMap<>(Map.of("olderChats.whatsapp.groups", "done")), asked, io));
            assertEquals(List.of(new OlderChats.Plan(Network.WHATSAPP, 100, false, false, false, null)),
                    older.plans(List.of(Network.WHATSAPP, Network.MESSENGER)));
            assertTrue(older.hasMore(List.of(Network.WHATSAPP)));
            Map<Network, OlderChats.Answer> a = older.load(List.of(Network.WHATSAPP)).get();
            assertInstanceOf(OlderChats.Answer.NotPossible.class, a.get(Network.WHATSAPP));
            assertEquals(List.of("command WHATSAPP sync-history-chats 100"), asked);
            assertEquals(OlderChats.Stage.DONE, Present.of(older.loading()).stage());
        }
    }

    @Test
    void stoppingTheServiceEndsALoad() throws Exception {
        MatrixChatService svc = new MatrixChatService("http://127.0.0.1:9", "@me:x", "stopping-ends-a-load");
        List<String> asked = new CopyOnWriteArrayList<>();
        try (ExecutorService io = Executors.newSingleThreadExecutor()) {
            CountDownLatch never = new CountDownLatch(1);
            CountDownLatch restarting = new CountDownLatch(1);
            svc.useOlderChats(fakeLoader(svc.olderChatLoader().arrivals(), new ConcurrentHashMap<>(), asked, io, never, restarting));
            var run = svc.load(List.of(Network.TELEGRAM, Network.WHATSAPP));
            restarting.await(); // Telegram's restart, held for good
            svc.stop();
            assertTrue(run.get().isEmpty(), "the run ended in Telegram's restart, before WhatsApp");
            assertTrue(asked.stream().noneMatch(e -> e.contains("WHATSAPP") || e.contains("whatsapp")), asked.toString());
        }
    }

    @Test
    void nothingRunsWithoutBeingAsked() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        try (ExecutorService io = Executors.newSingleThreadExecutor(); RestServer server = new RestServer()) {
            server.initialSync = RestServer.sync(RestServer.telegramGroup("!tg:example.org"), RestServer.botRoom());
            MatrixChatService svc = new MatrixChatService(server.url(), RestServer.ME, "nothing-runs");
            svc.useOlderChats(fakeLoader(svc.olderChatLoader().arrivals(), new ConcurrentHashMap<>(), asked, io));
            Changes changes = Changes.of(svc);
            svc.start();
            try {
                // The first sync was dispatched, with its login refresh (refreshStaleLogins), once the next one is asked.
                changes.until("the first sync dispatched", () -> svc.chat("!tg:example.org") != null);
                server.changes.until("the next sync", () -> server.log.stream().anyMatch(l -> l.startsWith("sync") && l.contains("since=")));
                svc.olderChatLoader().arrivals().roomsChanged();
                assertEquals(List.of(), asked, "no command, no reconfigure, no setting written");
                assertNull(svc.loading());
            } finally {
                svc.stop();
            }
        }
    }
}
