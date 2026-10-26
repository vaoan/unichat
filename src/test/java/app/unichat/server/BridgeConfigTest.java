package app.unichat.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

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
        assertEquals("127.0.0.1", c.at("/appservice/hostname").asText());
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
        assertEquals("info", c.at("/logging/min_level").asText());
        assertEquals(1, c.at("/network/some_network_key").asInt(), "network keys untouched");
    }

    @Test
    void whatsappSyncsArchivedChats() throws Exception {
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(V2), settings("whatsapp"));
        assertEquals("u.archived", c.at("/network/archive_tag").asText());
        assertEquals(50, c.at("/network/history_sync/max_initial_conversations").asInt());
        assertFalse(c.at("/bridge/tag_only_on_create").asBoolean(true));
        assertFalse(c.at("/network/enable_status_broadcast").asBoolean(true), "no Status stories chat");
        JsonNode tags = c.at("/bridge/only_bridge_tags");
        assertEquals("[\"m.favourite\",\"m.lowpriority\",\"u.archived\"]", tags.toString());
    }

    @Test
    void telegramGetsApiKeysAndGifStickers() throws Exception {
        ObjectNode c = BridgeConfig.apply(BridgeConfig.read(V2), settings("telegram"));
        assertEquals(123, c.at("/network/api_id").asInt());
        assertEquals("hash", c.at("/network/api_hash").asText());
        assertEquals("gif", c.at("/network/animated_sticker/target").asText());
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
        assertEquals(500, c.at("/bridge/backfill/forward_limits/missed/channel").asInt());
        assertFalse(c.has("database"), "no bridgev2 keys on the legacy layout");
    }

    @Test
    void homeserverConfigIsLocalOnlyWithTokenRegistration() {
        String toml = HomeserverConfig.toml(Path.of("/srv/u"), 8008, "localhost", "tok\"en");
        assertTrue(toml.contains("server_name = \"localhost\""));
        assertTrue(toml.contains("address = [\"127.0.0.1\"]"));
        assertTrue(toml.contains("allow_federation = false"));
        assertTrue(toml.contains("registration_token = \"tok\\\"en\""), toml);
        assertTrue(toml.contains("appservice_dir = \"/srv/u/appservices\""));
        assertTrue(toml.contains("database_path = \"/srv/u/tuwunel/db\""));
    }
}
