package app.unichat.server;

import app.unichat.bridge.BridgePorts;
import app.unichat.bridge.ChatLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeConfigTest {
    private static final String V2 = """
            network:
                some_network_key: 1
            homeserver:
                address: http://example.localhost:8008
            appservice:
                address: http://localhost:29318
                bot:
                    username: whatsappbot
            bridge:
                permissions:
                    example.com: user
            database:
                type: postgres
            backfill:
                enabled: false
            """;

    private static final String LEGACY = """
            homeserver:
                address: http://example.localhost:8008
            appservice:
                database:
                    type: postgres
            bridge:
                backfill:
                    forward_limits:
                        initial:
                            dm: 0
            """;

    private static BridgeConfig.Settings settings(String bridge) {
        return new BridgeConfig.Settings(bridge, 29318, "localhost", "@yo:localhost", "http://127.0.0.1:8008",
                Path.of("/data/" + bridge), "dp-token", "123", "hash");
    }

    @Test
    void megabridgeGetsSqliteBackfillAndDoublePuppet() throws Exception {
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(V2), settings("signal"));
        assertEquals("http://127.0.0.1:8008", c.at("/homeserver/address").asText());
        assertEquals("localhost", c.at("/homeserver/domain").asText());
        assertEquals("http://127.0.0.1:29318", c.at("/appservice/address").asText());
        assertEquals("127.0.0.1", c.at("/appservice/hostname").asText()); // NOPMD: AvoidUsingHardCodedIP, what the config says
        assertEquals(29318, c.at("/appservice/port").asInt());
        assertEquals("signal", c.at("/appservice/id").asText());
        assertEquals("signalbot", c.at("/appservice/bot/username").asText());
        assertEquals("signal_{{.}}", c.at("/appservice/username_template").asText());
        assertEquals("!signal", c.at("/bridge/command_prefix").asText());
        assertEquals("sqlite3-fk-wal", c.at("/database/type").asText());
        assertEquals("file:/data/signal/signal.db?_txlock=immediate", c.at("/database/uri").asText());
        assertEquals("admin", c.at("/bridge/permissions").get("@yo:localhost").asText());
        assertEquals("user", c.at("/bridge/permissions/localhost").asText());
        assertEquals("relay", c.at("/bridge/permissions").get("*").asText());
        assertFalse(c.at("/bridge/permissions").has("example.com"), "example permissions replaced");
        assertFalse(c.at("/encryption/allow").asBoolean(true));
        assertEquals("as_token:dp-token", c.at("/double_puppet/secrets/localhost").asText());
        assertTrue(c.at("/backfill/enabled").asBoolean());
        assertEquals(50, c.at("/backfill/max_initial_messages").asInt());
        assertEquals(500, c.at("/backfill/max_catchup_messages").asInt());
        assertEquals("stdout", c.at("/logging/writers/0/type").asText(), "logs go through Unichat's redactor");
        assertEquals(1, c.at("/logging/writers").size());
        assertEquals("warn", c.at("/logging/min_level").asText());
        assertEquals(1, c.at("/network/some_network_key").asInt(), "network keys untouched");
    }

    @Test
    void bridgesSayWhetherEachMessageReachedTheNetwork() throws Exception {
        ObjectNode v2 = BridgeConfig.apply(BridgeConfig.read(V2), settings("telegram"));
        assertTrue(v2.at("/matrix/message_status_events").asBoolean());
        assertTrue(v2.at("/matrix/message_error_notices").asBoolean(), "the notice says why, in any client");
        ObjectNode legacy = BridgeConfig.apply(BridgeConfig.read(LEGACY), settings("discord"));
        assertTrue(legacy.at("/bridge/message_status_events").asBoolean());
        assertTrue(legacy.at("/bridge/message_error_notices").asBoolean());
    }

    @Test
    void whatsappSyncsArchivedChats() throws Exception {
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(V2), settings("whatsapp"));
        assertEquals("u.archived", c.at("/network/archive_tag").asText());
        assertEquals(200, c.at("/network/history_sync/max_initial_conversations").asInt());
        assertFalse(c.at("/bridge/tag_only_on_create").asBoolean(true));
        assertFalse(c.at("/network/enable_status_broadcast").asBoolean(true), "no Status stories chat");
        JsonNode tags = c.at("/bridge/only_bridge_tags");
        assertEquals("[\"m.favourite\",\"m.lowpriority\",\"u.archived\"]", tags.toString());
    }

    @Test
    void telegramGetsApiKeysAndStickersAsTheyAre() throws Exception {
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(V2), settings("telegram"));
        assertEquals(123, c.at("/network/api_id").asInt());
        assertEquals("hash", c.at("/network/api_hash").asText());
        // Lottie as it is (its JSON): Unichat draws it and makes it a WebP where it's going. "gif" needs the bridge to find
        // LottieConverter on its PATH, and without it the .tgs came anyway, unlabelled.
        assertEquals("disable", c.at("/network/animated_sticker/target").asText());
        // Video stickers as they are: the bridge made each a GIF of 5-9 MB in 2-3 s (still labelled video/webm), and a
        // GIF forwarded to Discord can't become the animated WebP the WebM would, transparency and all.
        assertFalse(c.at("/network/animated_sticker/convert_from_webm").asBoolean(true));
    }

    @Test
    void instagramRunsInInstagramMode() throws Exception {
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(V2), settings("instagram"));
        assertEquals("instagram", c.at("/network/mode").asText());
    }

    @Test
    void legacyLayoutForDiscord() throws Exception {
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(LEGACY), settings("discord"));
        assertEquals("sqlite3-fk-wal", c.at("/appservice/database/type").asText());
        assertEquals("file:/data/discord/discord.db?_txlock=immediate", c.at("/appservice/database/uri").asText());
        assertEquals("discord_{{.}}", c.at("/bridge/username_template").asText());
        assertEquals("as_token:dp-token", c.at("/bridge/login_shared_secret_map/localhost").asText());
        assertEquals(50, c.at("/bridge/backfill/forward_limits/initial/dm").asInt());
        assertEquals(1, c.at("/bridge/backfill/forward_limits/initial/channel").asInt(), "a new channel shows its last message");
        assertEquals(0, c.at("/bridge/backfill/forward_limits/missed/channel").asInt(), "no catch-up per channel on start");
        assertEquals(500, c.at("/bridge/backfill/forward_limits/missed/dm").asInt());
        assertFalse(c.has("database"), "no bridgev2 keys on the legacy layout");
    }

    @Test
    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "a fixed path the test passes in as input; nothing is read or written there")
    void homeserverConfigIsLocalOnlyWithTokenRegistration() {
        String toml = HomeserverConfig.toml(Path.of("/srv/u"), 8008, "localhost", "tok\"en");
        assertTrue(toml.contains("server_name = \"localhost\""));
        assertTrue(toml.contains("address = [\"127.0.0.1\"]"));
        assertTrue(toml.contains("allow_federation = false"));
        assertTrue(toml.contains("dns_servers = [\"1.1.1.1\", \"9.9.9.9\"]"), "never reads /etc/resolv.conf");
        assertTrue(toml.contains("registration_token = \"tok\\\"en\""), toml);
        assertTrue(toml.contains("appservice_dir = \"/srv/u/appservices\""));
        assertTrue(toml.contains("database_path = \"/srv/u/tuwunel/db\""));
    }

    private static final String TELEGRAM = V2.replace("    some_network_key: 1\n", """
                some_network_key: 1
                sync:
                    update_limit: 100
                    create_limit: 15
                    login_sync_limit: 15
                    direct_chats: true
            """);

    @Test
    void telegramCreatesMoreChats() throws Exception {
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(TELEGRAM), settings("telegram"));
        assertEquals(100, c.at("/network/sync/create_limit").asInt());
        assertEquals(100, c.at("/network/sync/login_sync_limit").asInt());
        assertEquals(100, c.at("/network/sync/update_limit").asInt());
    }

    /**
     * The bridge walks at most {@code update_limit} dialogs (its default 100) and creates rooms for {@code create_limit} of
     * them: with only {@code create_limit} raised, a limit above 100 did nothing.
     */
    @Test
    void telegramsUpdateLimitFollowsItsCreateLimit() throws Exception {
        String template = TELEGRAM.replace("update_limit: 100", "update_limit: 50");
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(template), settings("telegram"));
        assertEquals(BridgePorts.TELEGRAM_CREATE_LIMIT, c.at("/network/sync/create_limit").asInt());
        assertEquals(BridgePorts.TELEGRAM_CREATE_LIMIT, c.at("/network/sync/update_limit").asInt());
    }

    private static BridgeConfig.Settings settings(String bridge, ChatLimits limits) {
        return new BridgeConfig.Settings(bridge, 29318, "localhost", "@yo:localhost", "http://127.0.0.1:8008",
                Path.of("/data/" + bridge), "dp-token", "123", "hash", null, limits);
    }

    private static ChatLimits telegramExtra(int extra) { return new ChatLimits(extra, 0, false, false, false); }

    private static ChatLimits discordExtra(int extra) { return new ChatLimits(0, extra, false, false, false); }

    private static final ChatLimits LINKEDIN_ALL = new ChatLimits(0, 0, true, false, false);
    private static final ChatLimits GMESSAGES_ONCE = new ChatLimits(0, 0, false, true, false);
    private static final ChatLimits INSTAGRAM_ONCE = new ChatLimits(0, 0, false, false, true);

    private static final String DISCORD = LEGACY.replace("bridge:\n", "bridge:\n    startup_private_channel_create_limit: 5\n");

    private static final String LINKEDIN = V2.replace("    some_network_key: 1\n", """
                some_network_key: 1
                sync:
                    update_limit: 0
                    create_limit: 10
            """);

    private static String gmessages(int count) {
        return V2.replace("    some_network_key: 1\n", "    some_network_key: 1\n    initial_chat_sync_count: " + count + "\n");
    }

    private static String instagram(int batches) {
        return V2.replace("    some_network_key: 1\n", """
                some_network_key: 1
                thread_backfill:
                    enabled: true
                    batch_count: \
            """ + batches + "\n");
    }

    @Test
    void telegramsExtraRaisesBothLimits() throws Exception {
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(TELEGRAM), settings("telegram", telegramExtra(200)));
        assertEquals(300, c.at("/network/sync/create_limit").asInt());
        assertEquals(300, c.at("/network/sync/update_limit").asInt());
        assertEquals(100, c.at("/network/sync/login_sync_limit").asInt(), "a new login still brings its first 100 at once");
    }

    @Test
    void discordsPrivateChannelLimitIsFiftyPlusWhatWasAsked() throws Exception {
        assertEquals(50, BridgeConfig.apply(BridgeConfig.read(DISCORD), settings("discord", discordExtra(0)))
                .at("/bridge/startup_private_channel_create_limit").asInt());
        assertEquals(150, BridgeConfig.apply(BridgeConfig.read(DISCORD), settings("discord", discordExtra(100)))
                .at("/bridge/startup_private_channel_create_limit").asInt());
    }

    @Test
    void linkedInCreatesEveryChatOnceAskedFor() throws Exception {
        assertEquals(0, BridgeConfig.apply(BridgeConfig.read(LINKEDIN), settings("linkedin", LINKEDIN_ALL))
                .at("/network/sync/create_limit").asInt(), "0 is no limit");
        assertEquals(10, BridgeConfig.apply(BridgeConfig.read(LINKEDIN), settings("linkedin", ChatLimits.NONE))
                .at("/network/sync/create_limit").asInt());
    }

    /**
     * Google Messages asks the phone for its chats at every start, and Instagram walks Meta's inbox: each only for the step
     * that set it. Once cleared, the next rewrite puts the bridge's own value back over the one the step wrote.
     */
    @Test
    void oneShotLimitsApplyOnlyWhileSet() throws Exception {
        assertEquals(ChatLimits.GMESSAGES_ALL, BridgeConfig.apply(BridgeConfig.read(gmessages(25)), settings("gmessages", GMESSAGES_ONCE))
                .at("/network/initial_chat_sync_count").asInt());
        assertEquals(25, BridgeConfig.apply(BridgeConfig.read(gmessages(25)), settings("gmessages", ChatLimits.NONE))
                .at("/network/initial_chat_sync_count").asInt(), "the template's value untouched");
        assertEquals(25, BridgeConfig.apply(BridgeConfig.read(gmessages(ChatLimits.GMESSAGES_ALL)), settings("gmessages", ChatLimits.NONE))
                .at("/network/initial_chat_sync_count").asInt(), "the step's 500 doesn't stay for every start after it");
        assertEquals(ChatLimits.INSTAGRAM_PAGES, BridgeConfig.apply(BridgeConfig.read(instagram(0)), settings("instagram", INSTAGRAM_ONCE))
                .at("/network/thread_backfill/batch_count").asInt());
        assertEquals(0, BridgeConfig.apply(BridgeConfig.read(instagram(ChatLimits.INSTAGRAM_PAGES)), settings("instagram", ChatLimits.NONE))
                .at("/network/thread_backfill/batch_count").asInt());
    }

    /** The template's -1 walks the whole inbox at every new login: what Meta flags. A new login brings its first page only. */
    @Test
    void instagramWalksNoInboxPagesByDefault() throws Exception {
        assertEquals(0, BridgeConfig.apply(BridgeConfig.read(instagram(-1)), settings("instagram"))
                .at("/network/thread_backfill/batch_count").asInt());
    }

    @Test
    void aMissingLimitKeyIsReported() throws Exception {
        assertEquals(List.of("/bridge/startup_private_channel_create_limit"), BridgeConfig.missingLimitKeys(BridgeConfig.read(LEGACY), "discord"));
        assertEquals(List.of(), BridgeConfig.missingLimitKeys(BridgeConfig.read(DISCORD), "discord"));
        assertEquals(List.of("/network/sync/create_limit", "/network/sync/update_limit", "/network/sync/login_sync_limit"),
                BridgeConfig.missingLimitKeys(BridgeConfig.read(V2), "telegram"));
        assertEquals(List.of(), BridgeConfig.missingLimitKeys(BridgeConfig.read(TELEGRAM), "telegram"));
        assertEquals(List.of(), BridgeConfig.missingLimitKeys(BridgeConfig.read(LINKEDIN), "linkedin"));
        assertEquals(List.of("/network/initial_chat_sync_count"), BridgeConfig.missingLimitKeys(BridgeConfig.read(V2), "gmessages"));
        assertEquals(List.of(), BridgeConfig.missingLimitKeys(BridgeConfig.read(instagram(0)), "instagram"));
        assertEquals(List.of(), BridgeConfig.missingLimitKeys(BridgeConfig.read(V2), "twitter"), "a bridge with no limit needs none");
    }

    @Test
    void discordCreatesMoreDms() throws Exception {
        String legacy = LEGACY.replace("bridge:\n", "bridge:\n    startup_private_channel_create_limit: 5\n");
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(legacy), settings("discord"));
        assertEquals(50, c.at("/bridge/startup_private_channel_create_limit").asInt());
    }

    @Test
    void whatsappSyncs200Conversations() throws Exception {
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(V2), settings("whatsapp"));
        assertEquals(200, c.at("/network/history_sync/max_initial_conversations").asInt());
    }

    @Test
    void mediaStaysOnTheNetworkUntilOpened() throws Exception {
        ObjectNode v2 = BridgeConfig.read(V2 + """
                direct_media:
                    enabled: false
                    server_name: discord-media.example.com
                    server_key: ed25519 AAAA generated
                """);
        BridgeConfig.apply(v2, settings("telegram"));
        assertTrue(v2.at("/direct_media/enabled").asBoolean());
        assertEquals("media-telegram.localhost", v2.at("/direct_media/server_name").asText());
        assertEquals("ed25519 AAAA generated", v2.at("/direct_media/server_key").asText(), "the bridge's own key is kept");

        ObjectNode legacy = BridgeConfig.apply(BridgeConfig.read(LEGACY), settings("discord"));
        assertTrue(legacy.at("/bridge/direct_media/enabled").asBoolean());
        assertEquals("media-discord.localhost", legacy.at("/bridge/direct_media/server_name").asText());
    }

    @Test
    void googleMessagesKeepsItsMediaOnTheServer() throws Exception {
        // Its connector has no direct media: with it on, the bridge stopped at every start ("does not support it").
        ObjectNode c = BridgeConfig.read(V2 + """
                direct_media:
                    enabled: true
                    server_name: media-gmessages.localhost
                """);
        BridgeConfig.apply(c, settings("gmessages"));
        assertFalse(c.at("/direct_media/enabled").asBoolean());
    }

    @Test
    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "a fixed path the test passes in as input; nothing is read or written there")
    void bridgesReportTheirStateToUnichat() throws Exception {
        BridgeConfig.Settings with = new BridgeConfig.Settings("whatsapp", 29318, "localhost", "@yo:localhost",
                "http://127.0.0.1:8008", Path.of("/data/whatsapp"), "dp", null, null, "http://127.0.0.1:5000/status/whatsapp", ChatLimits.NONE);
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(V2), with);
        assertEquals("http://127.0.0.1:5000/status/whatsapp", c.at("/homeserver/status_endpoint").asText());
        ObjectNode legacy = BridgeConfig.apply(BridgeConfig.read(LEGACY), new BridgeConfig.Settings("discord", 29334,
                "localhost", "@yo:localhost", "http://127.0.0.1:8008", Path.of("/data/discord"), "dp", null, null,
                "http://127.0.0.1:5000/status/discord", ChatLimits.NONE));
        assertEquals("http://127.0.0.1:5000/status/discord", legacy.at("/homeserver/status_endpoint").asText());
        assertTrue(BridgeConfig.apply(BridgeConfig.read(V2), settings("signal")).at("/homeserver/status_endpoint").isNull());
    }

    @Test
    void aMistypedKeyIsNotInvented() throws Exception {
        ObjectNode c = BridgeConfig.read(V2);
        assertFalse(BridgeConfig.setExisting(c, "/network/sync/create_limitt", 100));
        assertTrue(c.at("/network/sync").isMissingNode(), "nothing created for a key the bridge doesn't have");
    }

    /**
     * Written beside and renamed over, never truncated in place: a crash or a full disk mid-write left an empty config.yaml.
     * A hard link to the old file shows it: rewritten in place, the link would see the new text.
     */
    @Test
    void theConfigIsReplacedWholeNeverRewrittenInPlace(@TempDir Path dir) throws Exception {
        Path config = Files.writeString(dir.resolve("config.yaml"), V2);
        Path before = Files.createLink(dir.resolve("before.yaml"), config);
        BridgeConfig.rewrite(config, settings("whatsapp"));
        assertEquals(V2, Files.readString(before));
        assertTrue(Files.readString(config).contains("127.0.0.1:8008"));
        assertFalse(Files.exists(dir.resolve("config.yaml.tmp")));
    }

    @Test
    void bridgesLogWarningsByDefault() throws Exception {
        assertEquals("warn", BridgeConfig.apply(BridgeConfig.read(V2), settings("signal")).at("/logging/min_level").asText());
        assertEquals("warn", BridgeConfig.apply(BridgeConfig.read(LEGACY), settings("discord")).at("/logging/min_level").asText());
    }

    @Test
    void theFlagBringsInfoBack() throws Throwable {
        withFlag("info", () -> {
            assertEquals("info", BridgeConfig.apply(BridgeConfig.read(V2), settings("signal")).at("/logging/min_level").asText());
            assertEquals("info", BridgeConfig.apply(BridgeConfig.read(LEGACY), settings("discord")).at("/logging/min_level").asText());
        });
    }

    @Test
    void aFlagThatIsNoLevelFallsBackToWarn() throws Throwable {
        withFlag("loud", () ->
                assertEquals("warn", BridgeConfig.apply(BridgeConfig.read(V2), settings("signal")).at("/logging/min_level").asText()));
    }

    private static void withFlag(String value, org.junit.jupiter.api.function.Executable body) throws Throwable {
        String before = System.getProperty("unichat.bridgeLogs");
        System.setProperty("unichat.bridgeLogs", value);
        try {
            body.execute();
        } finally {
            if (before == null) System.clearProperty("unichat.bridgeLogs");
            else System.setProperty("unichat.bridgeLogs", before);
        }
    }

    /** One place says where a bridge's database is: its config, the account check, the session keeper and the prunes read it. */
    @Test
    @SuppressFBWarnings(value = "DMI_HARDCODED_ABSOLUTE_FILENAME", justification = "a fixed path the test passes in as input; nothing is read or written there")
    void aBridgesDatabaseIsItsNameInItsFolder() {
        assertEquals(Path.of("/data/signal/signal.db"), BridgeConfig.database(Path.of("/data/signal"), "signal"));
    }
}
