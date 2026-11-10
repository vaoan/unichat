package app.unichat.matrix;

import app.unichat.settings.Settings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pausing a Discord server the bridge refused isn't forgotten: it's tried again, like a mute rule. */
class DiscordPauseRetryTest {
    private static final String ME = "@me:example.org";
    private static final String BOT = "@discordbot:example.org";
    private static final String ROOM = "!discordbot:example.org";
    private static final String GUILD = "998877665544332211";

    private final SyncProcessor proc = new SyncProcessor(ME);
    private final List<String> commands = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger events = new AtomicInteger();
    private final ExecutorService botThread = Executors.newSingleThreadExecutor();
    /** What the bot answers each command, in turn; the last one from then on. */
    private final List<String> answers = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private DiscordServers discord;

    @BeforeEach
    void fakeHomeserver() throws Exception {
        Settings.get().removeFromSet(DiscordServers.PAUSED_GUILDS_KEY, GUILD);
        synchronized (proc) {
            proc.process(MatrixClient.JSON.readTree(("{'rooms':{'join':{'" + ROOM + "':{'state':{'events':["
                    + member(ME) + "," + member(BOT) + "]},'timeline':{'events':[]}}}}}").replace('\'', '"')), false);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            commands.add(MatrixClient.JSON.readTree(body).path("body").asText());
            String answer = answers.size() > 1 ? answers.removeFirst() : answers.getFirst();
            botThread.execute(() -> reply(answer)); // on its own thread, after the send returns
            byte[] out = ("{\"event_id\":\"$e" + events.incrementAndGet() + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
        MatrixClient client = new MatrixClient("http://127.0.0.1:" + server.getAddress().getPort(), "tok");
        MatrixCore core = new MatrixCore(client, proc, ME, "example.org", "tok");
        discord = new DiscordServers(core, new BotChat(core, new MessageSender(core, null)), List::of);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        botThread.shutdownNow();
        Settings.get().removeFromSet(DiscordServers.PAUSED_GUILDS_KEY, GUILD);
    }

    private static String member(String user) {
        return "{'type':'m.room.member','state_key':'" + user + "','sender':'" + user + "','event_id':'$m" + user.hashCode()
                + "','origin_server_ts':1,'content':{'membership':'join'}}";
    }

    private void reply(String text) {
        String event = ("{'rooms':{'join':{'" + ROOM + "':{'timeline':{'events':[{'type':'m.room.message','event_id':'$r"
                + events.incrementAndGet() + "','sender':'" + BOT + "','origin_server_ts':9,'content':{'msgtype':'m.notice','body':'"
                + text + "'}}]}}}}}").replace('\'', '"');
        try {
            synchronized (proc) {
                proc.process(MatrixClient.JSON.readTree(event), true);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aRefusedPauseIsTriedAgainWhenItsTimeComes() throws Exception {
        answers.addAll(List.of("Failed to get guild", "Set guild bridging mode to nothing"));
        discord.setGuildPaused(GUILD, true).get();
        assertFalse(DiscordServers.pausedGuilds().contains(GUILD), "the bridge refused it");

        discord.retryGuildPauses(Instant.now()).get();
        assertEquals(1, commands.size(), "not before its time: " + commands);

        discord.retryGuildPauses(Instant.now().plus(Retry.LAST)).get();
        assertEquals(2, commands.size(), commands.toString());
        assertTrue(commands.get(1).contains("guilds bridging-mode " + GUILD + " nothing"), commands.toString());
        assertTrue(DiscordServers.pausedGuilds().contains(GUILD), "paused at last");

        discord.retryGuildPauses(Instant.now().plus(Retry.LAST)).get();
        assertEquals(2, commands.size(), "done is done: " + commands);
    }

    @Test
    void theLatestChoiceIsTheOneTriedAgain() throws Exception {
        answers.addAll(List.of("Failed to get guild", "Failed to get guild", "Set guild bridging mode to create-on-message"));
        discord.setGuildPaused(GUILD, true).get();
        discord.setGuildPaused(GUILD, false).get();

        discord.retryGuildPauses(Instant.now().plus(Retry.LAST)).get();
        assertEquals(3, commands.size(), "one retry, not one per choice: " + commands);
        assertTrue(commands.get(2).endsWith("create-on-message"), commands.toString());
        assertFalse(DiscordServers.pausedGuilds().contains(GUILD));
    }
}
