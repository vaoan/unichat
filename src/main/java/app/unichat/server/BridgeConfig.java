package app.unichat.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unichat's keys on a bridge's config.yaml (the bridge generates the file with {@code -e}; these keys are
 * re-applied on every start, everything else is left as the bridge wrote it). Same rules as server/setup.sh,
 * with SQLite and 127.0.0.1 instead of Postgres and Docker host names. See docs/SERVIDOR-NATIVO.md.
 */
public final class BridgeConfig {
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    /** Room tag for archived chats; must match SyncProcessor.TAG_ARCHIVED. */
    static final String ARCHIVE_TAG = "u.archived";

    /**
     * @param dir         the bridge's folder (database and log go there)
     * @param telegramApiId / telegramApiHash only used by the telegram bridge
     */
    public record Settings(String bridge, int port, String domain, String adminUserId, String homeserverUrl,
                           Path dir, String doublePuppetToken, String telegramApiId, String telegramApiHash) {}

    private BridgeConfig() {}

    public static ObjectNode read(String yaml) throws IOException {
        return (ObjectNode) YAML.readTree(yaml);
    }

    public static void rewrite(Path file, Settings s) throws IOException {
        ObjectNode c = read(Files.readString(file));
        Files.writeString(file, YAML.writeValueAsString(apply(c, s)));
    }

    public static ObjectNode apply(ObjectNode c, Settings s) {
        String b = s.bridge();
        set(c, "/homeserver/address", s.homeserverUrl());
        set(c, "/homeserver/domain", s.domain());
        set(c, "/homeserver/software", "standard");
        set(c, "/appservice/address", "http://127.0.0.1:" + s.port());
        set(c, "/appservice/hostname", "127.0.0.1");
        obj(c, "/appservice").put("port", s.port());
        set(c, "/appservice/id", b);
        set(c, "/appservice/bot/username", b + "bot");
        // Unichat prefixes every bot command with "!<id>"; keep in sync with Network.protocolId().
        set(c, "/bridge/command_prefix", "!" + b);
        ObjectNode perms = obj(c, "/bridge").putObject("permissions");
        perms.put("*", "relay");
        perms.put(s.domain(), "user");
        perms.put(s.adminUserId(), "admin");
        String db = "file:" + s.dir().resolve(b + ".db") + "?_txlock=immediate";
        // Bridges log to stdout; Unichat's Supervisor strips personal data before it reaches the disk.
        ArrayNode writers = YAML.createArrayNode();
        writers.addObject().put("type", "stdout").put("format", "json");

        if (c.has("network")) {
            // bridgev2 ("megabridge") layout: every bridge except discord.
            set(c, "/appservice/username_template", b + "_{{.}}");
            set(c, "/database/type", "sqlite3-fk-wal");
            set(c, "/database/uri", db);
            ObjectNode enc = obj(c, "/encryption");
            enc.put("allow", false).put("default", false).put("require", false);
            obj(c, "/double_puppet").putObject("secrets").put(s.domain(), "as_token:" + s.doublePuppetToken());
            // History from the first room creation on: a room created without it is never filled later.
            obj(c, "/backfill").put("enabled", true).put("max_initial_messages", 50).put("max_catchup_messages", 500);
            obj(c, "/logging").put("min_level", "info").set("writers", writers);
            switch (b) {
                case "instagram" -> {
                    set(c, "/network/mode", "instagram");
                    set(c, "/appservice/bot/displayname", "Instagram bridge bot");
                }
                case "whatsapp" -> {
                    // Only the 50 most recent chats get rooms when connecting (all of them overloads the server).
                    obj(c, "/network/history_sync").put("max_initial_conversations", 50);
                    // Chats archived on the phone land in Unichat's archive, also later (one-way).
                    set(c, "/network/archive_tag", ARCHIVE_TAG);
                    obj(c, "/bridge").set("only_bridge_tags",
                            YAML.createArrayNode().add("m.favourite").add("m.lowpriority").add(ARCHIVE_TAG));
                    obj(c, "/bridge").put("tag_only_on_create", false);
                    // WhatsApp Status (24 h stories) would arrive as a "WhatsApp Status Broadcast" chat: not a chat.
                    obj(c, "/network").put("enable_status_broadcast", false);
                }
                case "telegram" -> {
                    ObjectNode net = obj(c, "/network");
                    net.put("api_id", Integer.parseInt(s.telegramApiId()));
                    net.put("api_hash", s.telegramApiHash());
                    obj(c, "/network/animated_sticker").put("target", "gif").put("convert_from_webm", true);
                }
                default -> { }
            }
        } else {
            // Legacy layout (mautrix-discord).
            set(c, "/bridge/username_template", b + "_{{.}}");
            set(c, "/appservice/database/type", "sqlite3-fk-wal");
            set(c, "/appservice/database/uri", db);
            obj(c, "/bridge/encryption").put("allow", false).put("default", false);
            obj(c, "/bridge").putObject("login_shared_secret_map").put(s.domain(), "as_token:" + s.doublePuppetToken());
            obj(c, "/bridge/backfill/forward_limits/initial").put("dm", 50).put("channel", 50);
            obj(c, "/bridge/backfill/forward_limits/missed").put("dm", 500).put("channel", 500);
            obj(c, "/logging").put("min_level", "info").set("writers", writers);
        }
        return c;
    }

    /** The object at {@code path} ("/a/b"), creating missing levels. */
    static ObjectNode obj(ObjectNode root, String path) {
        ObjectNode cur = root;
        for (String key : path.substring(1).split("/")) {
            JsonNode next = cur.get(key);
            cur = next instanceof ObjectNode o ? o : cur.putObject(key);
        }
        return cur;
    }

    private static void set(ObjectNode root, String path, String value) {
        int slash = path.lastIndexOf('/');
        ObjectNode parent = slash == 0 ? root : obj(root, path.substring(0, slash));
        parent.put(path.substring(slash + 1), value);
    }
}
