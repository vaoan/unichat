package app.unichat.matrix;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Message;
import app.unichat.model.Network;
import app.unichat.service.BridgeAccess.DiscordGuild;
import app.unichat.service.ChatService.GroupOffer;
import app.unichat.support.I18n;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the mautrix bridges understand and answer: bot commands, their replies and account states. No state, no I/O. */
final class BridgeProtocol {
    /** One line of bridgev2 {@code list-logins}: {@code * `id` (Remote name) - `STATE`}. */
    private static final Pattern LOGIN_LINE = Pattern.compile("(?m)^\\s*[*-]\\s*`([^`\\s]+)`\\s*(?:\\(([^)\\n]*)\\))?\\s*(?:-\\s*`?([A-Z_]+)`?)?");
    private static final Pattern DISCORD_LOGGED_IN = Pattern.compile("logged in as @?([^\\s(]+)\\s*\\(`?(\\d+)`?\\)");
    private static final Pattern GUILD_LINE = Pattern.compile("(?m)^\\s*[*-]\\s*(.+?)\\s*\\(`?(\\d{5,})`?\\)\\s*-\\s*(.+)$");
    /** A drop shorter than this still shows as connected: bridges usually reconnect within seconds. */
    static final long LIVE_GRACE_MS = 60_000;

    private static final Pattern LOGGED_IN_AS = Pattern.compile("(?i)logged in as ([^\\n.]+)");

    /** A bridge login as reported by {@code list-logins}. */
    record Login(String id, String name, String state) {}

    /** What a bridge's bot chat says about its only account. */
    record BotChatLogin(AccountStatus status, String label) {}

    private BridgeProtocol() {}

    /** A bot command that answers "Successfully…" on success: any other answer is the failure's message. */
    static void requireSuccess(String reply) throws MatrixException {
        if (!reply.startsWith("Successfully")) throw new MatrixException(0, null, reply);
    }

    /**
     * The login state inferred from a bot chat's {@code timeline} (oldest first; empty without a bot chat) for a bridge that
     * reports no accounts: its newest message about logging in decides, else having {@code portals} chats means connected.
     */
    static BotChatLogin inferFromBotChat(List<Message> timeline, int portals, String label) {
        for (int i = timeline.size() - 1; i >= 0; i--) {
            Message m = timeline.get(i);
            if (m.outgoing() || m.deleted()) continue;
            String b = m.body().toLowerCase(Locale.ROOT);
            if (b.contains("logged out") || b.contains("not logged in") || b.contains("you're not logged")) {
                return new BotChatLogin(AccountStatus.NEEDS_LOGIN, label);
            }
            if (b.contains("successfully logged in") || b.contains("logged in as")) {
                Matcher mt = LOGGED_IN_AS.matcher(m.body());
                return new BotChatLogin(AccountStatus.CONNECTED, mt.find() ? mt.group(1).replace("`", "").trim() : label);
            }
        }
        return new BotChatLogin(portals > 0 ? AccountStatus.CONNECTED : AccountStatus.NEEDS_LOGIN, label);
    }

    /**
     * Prefixes a bot command with {@code !<protocol id>} (e.g. {@code !whatsapp login qr}); server/setup.* sets that
     * prefix on every bridge. Without it a bridge ignores commands in any bot DM other than its management room.
     */
    static String botCommand(Network network, String command) {
        return "!" + network.protocolId() + " " + command;
    }

    /**
     * Whether adding an account sends the login command right away. Discord waits for the user's choice: its QR
     * login often ends in a captcha the bridge can't show, so Unichat offers logging in on discord.com first.
     */
    static boolean startsLoginByItself(Network network) {
        return network != Network.DISCORD;
    }

    /**
     * First command sent to a bridge bot to start logging in. Bridges with several login flows answer a bare
     * {@code login} with a list of flow ids, so pick the one that suits a desktop user (checked against the
     * bridge versions installed by server/setup.*; see docs/BRIDGES.md).
     */
    static String loginCommand(Network network) {
        return switch (network) {
            case WHATSAPP -> "login qr";              // flows: qr, phone
            case MESSENGER -> "login facebook";       // flows: facebook, messenger, messenger-lite, ...
            case SLACK -> "login email";              // flows: email, token, app
            case X, LINKEDIN -> "login cookies";      // flows: cookies, password
            case TELEGRAM -> "login qr";              // flows: qr, phone, bot
            // Single flow (Signal QR, Google Messages "google", Instagram cookies) or legacy bridge (Discord QR).
            default -> "login";
        };
    }

    /** The bridge login behind an account ("whatsapp:573…" → "573…"); null for a network-wide account. */
    static String loginIdOf(Account a) {
        String prefix = a.network().protocolId() + ":";
        return a.id().startsWith(prefix) ? a.id().substring(prefix.length()) : null;
    }

    static String accountId(Network n, String loginId) {
        return n.protocolId() + ":" + loginId;
    }

    static AccountStatus loginStatus(String state) {
        if (state == null) return AccountStatus.CONNECTED;
        return switch (state) {
            case "CONNECTED", "BACKFILLING" -> AccountStatus.CONNECTED;
            case "CONNECTING", "TRANSIENT_DISCONNECT", "STARTING" -> AccountStatus.CONNECTING;
            case "BAD_CREDENTIALS", "LOGGED_OUT" -> AccountStatus.NEEDS_LOGIN;
            default -> state.startsWith("UNKNOWN") || state.contains("ERROR") ? AccountStatus.ERROR : AccountStatus.CONNECTED;
        };
    }

    /** Bot command that lists the accounts: the legacy Discord bridge has no {@code list-logins}, only {@code ping}. */
    static String statusCommand(Network n) {
        return n == Network.DISCORD ? "ping" : "list-logins";
    }

    /** The legacy Discord bridge's {@code ping} answer as a login list (empty when not logged in). */
    static List<Login> parseDiscordPing(String reply) {
        if (reply == null) return new ArrayList<>();
        Matcher m = DISCORD_LOGGED_IN.matcher(reply);
        if (m.find()) return new ArrayList<>(List.of(new Login(m.group(2), "@" + m.group(1), "CONNECTED")));
        if (reply.contains("not connected") || reply.contains("seems to be dead")) {
            return new ArrayList<>(List.of(new Login("discord", null, "TRANSIENT_DISCONNECT")));
        }
        return new ArrayList<>();
    }

    /** Parses a {@code list-logins} reply; empty when the bot says we're not logged in. */
    static List<Login> parseLogins(String reply) {
        List<Login> out = new ArrayList<>();
        if (reply == null) return out;
        Matcher m = LOGIN_LINE.matcher(reply);
        while (m.find()) out.add(new Login(m.group(1), m.group(2) == null ? null : m.group(2).trim(), m.group(3)));
        return out;
    }

    /** Bot messages after which the list of logins may have changed. */
    static boolean loginChanged(String body) {
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return b.contains("successfully logged in") || b.contains("logged out") || b.contains("logged in as");
    }

    /** Account status from a bridge's live report. */
    static AccountStatus liveStatus(String event, long since, long now) {
        boolean brief = now - since < LIVE_GRACE_MS;
        return switch (event) {
            case "CONNECTED", "BACKFILLING" -> AccountStatus.CONNECTED;
            case "BAD_CREDENTIALS", "LOGGED_OUT" -> AccountStatus.NEEDS_LOGIN;
            case "UNKNOWN_ERROR" -> brief ? AccountStatus.CONNECTED : AccountStatus.ERROR;
            default -> brief ? AccountStatus.CONNECTED : AccountStatus.CONNECTING;
        };
    }

    /** What to tell the person about a live state (Spanish, short). */
    static String liveDetail(AccountStatus status) {
        return switch (status) {
            case CONNECTED -> I18n.t("account.state.connected");
            case CONNECTING -> I18n.t("account.state.connecting");
            case ERROR -> I18n.t("account.state.error");
            case NEEDS_LOGIN -> I18n.t("account.state.needsLogin");
            case NOT_AVAILABLE -> I18n.t("account.state.notAvailable");
        };
    }

    /** Servers still to add: not bridged and whose space hasn't arrived ({@code inUnichat}: guild ids with a space). */
    static List<GroupOffer> offers(List<DiscordGuild> guilds, Set<String> inUnichat) {
        return guilds.stream().filter(g -> !g.bridged() && !inUnichat.contains(g.id()))
                .map(g -> new GroupOffer(g.id(), g.name())).toList();
    }

    /** Parses the bridge's {@code guilds status} answer ("* Name (`id`) - never bridge messages"). */
    static List<DiscordGuild> parseGuilds(String reply) {
        List<DiscordGuild> out = new ArrayList<>();
        if (reply == null) return out;
        Matcher m = GUILD_LINE.matcher(reply);
        while (m.find()) out.add(new DiscordGuild(m.group(2), m.group(1).trim(), !m.group(3).trim().startsWith("never")));
        return out;
    }

    /** A room the homeserver can no longer write to ("no forward extremities"): only replacing it helps. */
    static boolean brokenRoom(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("no forward extremities")) return true;
        }
        return false;
    }

    /** Whether Telegram's chats should be listed again: never done, or done for a lower limit than now. */
    static boolean needsChatResync(String doneForLimit, int limit) {
        return !Integer.toString(limit).equals(doneForLimit);
    }

    /**
     * Merges m.direct content: union of the server's and the locally cached room lists per user, then
     * appends {@code roomId} under {@code otherUser} if absent. Never drops existing entries.
     */
    static ObjectNode mergeDirect(JsonNode serverContent, JsonNode cachedContent, String otherUser, String roomId) {
        ObjectNode out = MatrixClient.JSON.createObjectNode();
        for (JsonNode src : new JsonNode[] {serverContent, cachedContent}) {
            if (src == null || !src.isObject()) continue;
            src.fields().forEachRemaining(e -> {
                ArrayNode arr = out.has(e.getKey()) && out.get(e.getKey()) instanceof ArrayNode a
                        ? a : out.putArray(e.getKey());
                if (!e.getValue().isArray()) return;
                for (JsonNode r : e.getValue()) if (!containsText(arr, r.asText())) arr.add(r.asText());
            });
        }
        ArrayNode target = out.get(otherUser) instanceof ArrayNode a ? a : out.putArray(otherUser);
        if (!containsText(target, roomId)) target.add(roomId);
        return out;
    }

    private static boolean containsText(ArrayNode arr, String v) {
        for (JsonNode n : arr) if (v.equals(n.asText())) return true;
        return false;
    }
}
