package app.unichat.server;

import app.unichat.bridge.BridgePorts;
import app.unichat.bridge.ChatLimits;
import app.unichat.support.SafeFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Unichat's keys on a bridge's config.yaml (the bridge generates the file with {@code -e}; these keys are
 * re-applied on every start, everything else is left as the bridge wrote it). Same rules as server/setup.sh,
 * with SQLite and 127.0.0.1 instead of Postgres and Docker host names. See docs/NATIVE-SERVER.md.
 */
public final class BridgeConfig {
    /** The SQLite database of bridge {@code b}, in its folder {@code bridgeDir}. */
    static Path database(Path bridgeDir, String b) {
        return bridgeDir.resolve(b + ".db");
    }

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    /** Room tag for archived chats; must match SyncProcessor.TAG_ARCHIVED. */
    static final String ARCHIVE_TAG = "u.archived";

    private static final String TELEGRAM_CREATE = "/network/sync/create_limit";
    private static final String TELEGRAM_UPDATE = "/network/sync/update_limit";
    private static final String TELEGRAM_LOGIN = "/network/sync/login_sync_limit";
    private static final String DISCORD_DMS = "/bridge/startup_private_channel_create_limit";
    private static final String LINKEDIN_CREATE = "/network/sync/create_limit";
    private static final String GMESSAGES_COUNT = "/network/initial_chat_sync_count";
    private static final String INSTAGRAM_PAGES = "/network/thread_backfill/batch_count";
    /** mautrix-gmessages' own {@code initial_chat_sync_count}, put back once its one-shot step is over. */
    static final int GMESSAGES_DEFAULT = 25;

    /** The keys each bridge's chat limits are written to: the same paths {@link #apply} sets. */
    private static final Map<String, List<String>> LIMIT_KEYS = Map.of(
            "telegram", List.of(TELEGRAM_CREATE, TELEGRAM_UPDATE, TELEGRAM_LOGIN),
            "discord", List.of(DISCORD_DMS),
            "linkedin", List.of(LINKEDIN_CREATE),
            "gmessages", List.of(GMESSAGES_COUNT),
            "instagram", List.of(INSTAGRAM_PAGES));

    /**
     * What a bridge's config is written from.
     *
     * @param dir         the bridge's folder (database and log go there)
     * @param telegramApiId / telegramApiHash only used by the telegram bridge
     * @param statusEndpoint where the bridge reports its connection state (StatusEndpoint); null for none
     * @param limits      how many chats the person asked the bridges for ("Load older chats")
     */
    public record Settings(String bridge, int port, String domain, String adminUserId, String homeserverUrl,
                           Path dir, @Nullable String doublePuppetToken, @Nullable String telegramApiId,
                           @Nullable String telegramApiHash, @Nullable String statusEndpoint, ChatLimits limits) {
        public Settings(String bridge, int port, String domain, String adminUserId, String homeserverUrl,
                        Path dir, @Nullable String doublePuppetToken, @Nullable String telegramApiId,
                        @Nullable String telegramApiHash) {
            this(bridge, port, domain, adminUserId, homeserverUrl, dir, doublePuppetToken, telegramApiId, telegramApiHash,
                    null, ChatLimits.NONE);
        }
    }

    private BridgeConfig() {}

    public static ObjectNode read(String yaml) throws IOException {
        return (ObjectNode) YAML.readTree(yaml);
    }

    /**
     * Whole or not at all (written in place, a crash or a full disk mid-write left it empty), and only the person's: it holds
     * the bridge's as_token and hs_token and the double-puppet secret.
     */
    public static void rewrite(Path file, Settings s) throws IOException {
        ObjectNode c = read(Files.readString(file));
        SafeFile.replace(file, YAML.writeValueAsBytes(apply(c, s)), true);
    }

    public static ObjectNode apply(ObjectNode c, Settings s) {
        String b = s.bridge();
        set(c, "/homeserver/address", s.homeserverUrl());
        set(c, "/homeserver/domain", s.domain());
        set(c, "/homeserver/software", "standard");
        // Live connection state of every account, for Unichat's status and its watchdog (both layouts).
        String status = s.statusEndpoint();
        if (status != null) set(c, "/homeserver/status_endpoint", status);
        else obj(c, "/homeserver").putNull("status_endpoint");
        set(c, "/appservice/address", "http://127.0.0.1:" + s.port());
        set(c, "/appservice/hostname", "127.0.0.1"); // NOPMD: AvoidUsingHardCodedIP, the bridge listens on the loopback only
        obj(c, "/appservice").put("port", s.port());
        set(c, "/appservice/id", b);
        set(c, "/appservice/bot/username", b + "bot");
        // Unichat prefixes every bot command with "!<id>"; keep in sync with Network.protocolId().
        set(c, "/bridge/command_prefix", "!" + b);
        ObjectNode perms = obj(c, "/bridge").putObject("permissions");
        perms.put("*", "relay");
        perms.put(s.domain(), "user");
        perms.put(s.adminUserId(), "admin");
        String db = "file:" + database(s.dir(), b) + "?_txlock=immediate";
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
            obj(c, "/logging").put("min_level", logLevel()).set("writers", writers);
            // Media stays on the network until opened (the server_key the bridge generated is kept). Google Messages'
            // connector has none: with it on, that bridge stopped at every start.
            obj(c, "/direct_media").put("enabled", !b.equals("gmessages")).put("server_name", BridgePorts.mediaServerName(b));
            // Whether each message reached the network: one that didn't shows as failed in Unichat, and the notice says why.
            obj(c, "/matrix").put("message_status_events", true).put("message_error_notices", true);
            networkKeys(c, s);
        } else {
            // Legacy layout (mautrix-discord).
            // Private chats (DMs, group DMs) given rooms at each start: 50, plus what "Load older chats" asked for
            // since (its steps raise it by 50).
            setExisting(c, DISCORD_DMS, s.limits().discordLimit());
            set(c, "/bridge/username_template", b + "_{{.}}");
            set(c, "/appservice/database/type", "sqlite3-fk-wal");
            set(c, "/appservice/database/uri", db);
            obj(c, "/bridge/encryption").put("allow", false).put("default", false);
            obj(c, "/bridge").putObject("login_shared_secret_map").put(s.domain(), "as_token:" + s.doublePuppetToken());
            // History comes with its attachments copied at full size, for every channel created (hundreds at a
            // time) and every missed message on each start: GBs per start. A new channel brings only its last
            // message (preview and activity); no catch-up on start. DMs keep their history.
            obj(c, "/bridge/backfill/forward_limits/initial").put("dm", 50).put("channel", 1);
            obj(c, "/bridge/backfill/forward_limits/missed").put("dm", 500).put("channel", 0);
            // Big public servers: no missed-message backfill at all (only new messages come in).
            setExisting(c, "/bridge/backfill/max_guild_members", 1000);
            obj(c, "/bridge/direct_media").put("enabled", true).put("server_name", BridgePorts.mediaServerName(b));
            obj(c, "/bridge").put("message_status_events", true).put("message_error_notices", true);
            obj(c, "/logging").put("min_level", logLevel()).set("writers", writers);
        }
        return c;
    }

    /** Each bridgev2 bridge's own network keys. */
    private static void networkKeys(ObjectNode c, Settings s) {
        switch (s.bridge()) {
            case "instagram" -> {
                set(c, "/network/mode", "instagram");
                set(c, "/appservice/bot/displayname", "Instagram bridge bot");
                // Inbox pages walked after a new login: Meta flags that walk (checkpoints, forced logout), and the
                // template's -1 walked all of it. None, so a login brings its first page; 3 only for the start after a
                // "Load older chats" step the person ticked, which then clears it.
                setExisting(c, INSTAGRAM_PAGES, s.limits().instagramOnce() ? ChatLimits.INSTAGRAM_PAGES : 0);
            }
            case "whatsapp" -> {
                // The 200 most recent chats get rooms when connecting; the rest open from "Nuevo chat".
                obj(c, "/network/history_sync").put("max_initial_conversations", 200);
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
                String apiId = java.util.Objects.requireNonNull(s.telegramApiId(),
                        "Telegram is set up only with its keys (BridgeInstaller.prepare refuses it without)");
                net.put("api_id", java.util.Objects.requireNonNull(app.unichat.support.Numbers.intOf(apiId),
                        "a Telegram id is a number (TelegramKeys.saved checks it before a bridge is set up)"));
                net.put("api_hash", s.telegramApiHash());
                // Animated stickers as they are: Lottie as its JSON, video stickers as the WebM. Unichat draws both
                // and forwards them as an animated WebP with their transparency (video/LottieStickers, WebmStickers).
                // Made GIFs by the bridge, each took 2-3 s and 5-9 MB; and "gif" for Lottie needs LottieConverter on
                // the bridge's PATH, without which the .tgs came anyway, labelled application/x-tgsticker.
                obj(c, "/network/animated_sticker").put("target", "disable").put("convert_from_webm", false);
                // 15 by default: most chats never showed up. 100, plus what "Load older chats" asked for since (its
                // steps raise it by 100). The bridge paces itself on Telegram's flood waits.
                int limit = s.limits().telegramLimit();
                setExisting(c, TELEGRAM_CREATE, limit);
                // The walk stops after update_limit dialogs (100 by default), so a create_limit above it did nothing.
                setExisting(c, TELEGRAM_UPDATE, limit);
                // A new login's first chats, at once: the older ones come from the button.
                setExisting(c, TELEGRAM_LOGIN, BridgePorts.TELEGRAM_CREATE_LIMIT);
            }
            // The bridge creates 10 rooms per page of its inbox walk, at every start; "Load older chats" lifts the limit
            // (0) for good once asked, since the walk is the same either way.
            case "linkedin" -> {
                if (s.limits().linkedinAll()) setExisting(c, LINKEDIN_CREATE, 0);
            }
            // The chats asked of the phone at every start (25): 500 only for the start after a "Load older chats" step,
            // which then clears it, so later starts don't ask the phone for 500 again.
            case "gmessages" -> setExisting(c, GMESSAGES_COUNT,
                    s.limits().gmessagesOnce() ? ChatLimits.GMESSAGES_ALL : GMESSAGES_DEFAULT);
            default -> { }
        }
    }

    /**
     * The chat-limit keys {@code bridge}'s config lacks (a bridge version that renamed one): a step that would raise a
     * limit there is refused before its bridge is stopped. Empty for a bridge with no such key.
     */
    static List<String> missingLimitKeys(ObjectNode c, String bridge) {
        return LIMIT_KEYS.getOrDefault(bridge, List.of()).stream().filter(path -> parentHolding(c, path) == null).toList();
    }

    /**
     * Sets a number only where the bridge's own template already has that key: a mistyped path or a bridge version
     * that renamed it is reported (false) instead of adding a key the bridge would silently ignore.
     */
    static boolean setExisting(ObjectNode root, String path, int value) {
        ObjectNode o = parentHolding(root, path);
        if (o == null) {
            System.err.println("Bridge config key not found, left as is: " + path);
            return false;
        }
        o.put(path.substring(path.lastIndexOf('/') + 1), value);
        return true;
    }

    /** The object holding the key at {@code path}, or null when the config has no such key. */
    private static @Nullable ObjectNode parentHolding(ObjectNode root, String path) {
        int slash = path.lastIndexOf('/');
        JsonNode parent = root.at(path.substring(0, slash));
        return parent instanceof ObjectNode o && o.has(path.substring(slash + 1)) ? o : null;
    }

    /**
     * What the bridges log: {@code warn} (nothing in the app reads their info lines), or what
     * {@code -Dunichat.bridgeLogs} says when it is debug, info, warn or error.
     */
    static String logLevel() {
        String v = System.getProperty("unichat.bridgeLogs", "warn");
        if (v.matches("debug|info|warn|error")) return v;
        System.err.println("unichat.bridgeLogs=" + v + " is not debug, info, warn or error: using warn");
        return "warn";
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
