package app.unichat.matrix;

import app.unichat.model.Network;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replies are matched by order in the bot chat, so a command waits for the one before it. */
class BotChatTest {
    private static final String ME = "@me:example.org";
    private static final String BOT = "@whatsappbot:example.org";
    private static final String ROOM = "!bot:example.org";

    private final SyncProcessor proc = new SyncProcessor(ME);
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger events = new AtomicInteger();
    private final java.util.concurrent.ExecutorService botThread = Executors.newSingleThreadExecutor();
    private volatile boolean botAnswers = true;
    private HttpServer server;
    private BotChat bots;

    @BeforeEach
    void fakeHomeserver() throws Exception {
        synchronized (proc) {
            proc.process(MatrixClient.JSON.readTree(("{'rooms':{'join':{'" + ROOM + "':{'state':{'events':["
                    + member(ME) + "," + member(BOT) + "]},'timeline':{'events':[]}}}}}").replace('\'', '"')), false);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String command = MatrixClient.JSON.readTree(body).path("body").asText();
            log.add("sent " + command);
            if (botAnswers) botThread.execute(() -> reply(command)); // on its own thread, after the send returns
            byte[] out = ("{\"event_id\":\"$e" + events.incrementAndGet() + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
        MatrixClient client = new MatrixClient("http://127.0.0.1:" + server.getAddress().getPort(), "tok");
        MatrixCore core = new MatrixCore(client, proc, ME, "example.org", "tok");
        bots = new BotChat(core, new MessageSender(core, null));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        botThread.shutdownNow();
    }

    private static String member(String user) {
        return "{'type':'m.room.member','state_key':'" + user + "','sender':'" + user + "','event_id':'$m" + user.hashCode()
                + "','origin_server_ts':1,'content':{'membership':'join'}}";
    }

    private void reply(String command) {
        log.add("reply " + command);
        String event = ("{'rooms':{'join':{'" + ROOM + "':{'timeline':{'events':[{'type':'m.room.message','event_id':'$r" + events.incrementAndGet()
                + "','sender':'" + BOT + "','origin_server_ts':9,'content':{'msgtype':'m.notice','body':'" + command + " ok'}}]}}}}}").replace('\'', '"');
        try {
            synchronized (proc) {
                proc.process(MatrixClient.JSON.readTree(event), true);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void returnsTheBotsNextReply() throws Exception {
        assertTrue(bots.ask(Network.WHATSAPP, "list-logins").endsWith("list-logins ok"));
    }

    @Test
    void noChatNoCommand() throws Exception {
        assertNull(bots.ask(Network.TELEGRAM, "list-logins"));
        assertTrue(log.isEmpty());
    }

    @Test
    void aBotThatDoesntAnswerIsNull() throws Exception {
        botAnswers = false;
        // No patience at all: the answer that never comes isn't waited for, whatever the machine.
        assertNull(bots.ask(Network.WHATSAPP, "list-logins", java.time.Duration.ZERO));
        assertEquals(1, log.size());
    }

    @Test
    void oneCommandAtATime() throws Exception {
        CompletableFuture<String> a = CompletableFuture.supplyAsync(() -> ask("uno"));
        CompletableFuture<String> b = CompletableFuture.supplyAsync(() -> ask("dos"));
        String first = a.get();
        String second = b.get();

        assertTrue(first.endsWith("uno ok"), first);
        assertTrue(second.endsWith("dos ok"), second);
        assertEquals(4, log.size(), log.toString());
        assertTrue(log.get(0).startsWith("sent") && log.get(1).startsWith("reply")
                && log.get(2).startsWith("sent") && log.get(3).startsWith("reply"), log.toString());
    }

    private String ask(String command) {
        try {
            return bots.ask(Network.WHATSAPP, command);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
