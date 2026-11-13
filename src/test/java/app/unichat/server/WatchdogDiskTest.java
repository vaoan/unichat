package app.unichat.server;

import app.unichat.bridge.BridgeStates;
import app.unichat.support.Storage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The disk filled (2026-11-12): tuwunel and the bridges couldn't write, so nothing new arrived while every process
 * still answered, and it stayed so after there was room again, until Unichat itself started over hours later.
 */
class WatchdogDiskTest {
    private HttpServer everyoneAnswers;
    private long free = 50L << 30;
    private long clock = 1_000_000;
    private final List<String> restarted = new ArrayList<>();
    private Storage storage;
    private Watchdog dog;

    @BeforeEach
    void setUp() throws Exception {
        everyoneAnswers = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        everyoneAnswers.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        everyoneAnswers.start();
        int port = everyoneAnswers.getAddress().getPort();
        storage = new Storage(() -> free, () -> clock);
        dog = new Watchdog(Map.of("tuwunel", port, "telegram", port), () -> List.of("tuwunel", "telegram"), new BridgeStates(),
                (name, why) -> restarted.add(name + ": " + why), () -> true, storage);
    }

    @AfterEach
    void tearDown() {
        everyoneAnswers.stop(0);
    }

    @Test
    void whenTheDiskHasRoomAgainTheServerAndEveryBridgeStartOver() {
        dog.check();
        assertEquals(List.of(), restarted);

        free = 100L << 20;
        dog.check();
        assertEquals(List.of(), restarted, "with the disk full a restart can't help");

        free = 5L << 30;
        dog.check();
        assertEquals(List.of("tuwunel: the disk has room again", "telegram: the disk has room again"), restarted);

        restarted.clear();
        dog.check();
        assertEquals(List.of(), restarted, "only once");
    }

    @Test
    void oneThatCouldntWriteIsStartedOverOnceThereIsRoom() {
        storage.failed("telegram"); // a dip the 30 s rounds never saw
        dog.check();
        assertEquals(List.of("telegram: it couldn't write to the disk"), restarted);

        restarted.clear();
        dog.check();
        assertEquals(List.of(), restarted, "its failure is forgotten once it starts over");
    }

    @Test
    void whileTheDiskIsFullAFailureWaitsForRoom() {
        free = 10L << 20;
        storage.failed("telegram");
        dog.check();
        assertEquals(List.of(), restarted);

        free = 5L << 30;
        dog.check();
        assertEquals(List.of("tuwunel: the disk has room again", "telegram: the disk has room again"), restarted);
    }
}
