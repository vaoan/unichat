package app.unichat.matrix;

import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.model.Network;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reading Matrix events and names: pure functions, no state. */
final class SyncEvents {
    private SyncEvents() {}

    /** Bridges append the network to puppet names ("Ana (WA)"); the app shows a network badge instead. */
    private static final java.util.regex.Pattern NETWORK_SUFFIX = java.util.regex.Pattern.compile(
            "\\s*\\((WA|WhatsApp|Telegram|Signal|Instagram|Facebook|Messenger|Discord|Slack|Google Messages|GMessages|SMS|Twitter|X|LinkedIn)\\)$");

    static String cleanName(String name) {
        if (name == null) return null;
        String n = NETWORK_SUFFIX.matcher(name).replaceFirst("");
        return n.isBlank() ? name : n;
    }

    /** Sets type, body, media fields from a message content (also used for m.new_content). */
    static void applyContent(Message m, String msgtype, String body, JsonNode content) {
        MessageType t = switch (msgtype == null ? "" : msgtype) {
            case "m.emote" -> MessageType.EMOTE;
            case "m.notice" -> MessageType.NOTICE;
            case "m.image" -> MessageType.IMAGE;
            case "m.video" -> MessageType.VIDEO;
            case "m.audio" -> MessageType.AUDIO;
            case "m.file" -> MessageType.FILE;
            case "m.sticker" -> MessageType.STICKER;
            default -> MessageType.TEXT;
        };
        String b = body == null ? "" : body;
        m.setType(t);
        if (!m.isMedia()) {
            String html = MessageHtml.of(content);
            String previews = MessageHtml.previews(content);
            String own = MessageHtml.withoutEmbedText(b, html);
            m.setBody(own);
            m.setHtml(html);
            m.setPreviewHtml(previews);
            String extra = (own.equals(b) ? "" : MessageHtml.text(html)) + (previews == null ? "" : " " + MessageHtml.text(previews));
            m.setHtmlText(extra.isBlank() ? null : extra.strip());
            return;
        }
        String url = text(content, "url");
        if (url == null && content.has("file")) {
            m.setType(MessageType.TEXT);
            m.setBody("🔒 Archivo cifrado no compatible");
            return;
        }
        m.setMediaUrl(url);
        m.setExternalUrl(text(content, "external_url"));
        String filename = text(content, "filename");
        if (filename != null && !filename.equals(b)) {
            m.setFileName(filename);
            m.setBody(b);
        } else {
            m.setFileName(b.isEmpty() ? null : b);
            m.setBody("");
        }
        JsonNode info = content.path("info");
        if (info.isObject()) m.setMediaInfo(info);
        m.setMimeType(text(info, "mimetype"));
        m.setSize(info.path("w").asInt(0), info.path("h").asInt(0));
        m.setFileSize(info.path("size").asLong(0));
    }

    /** Removes the "> quoted" reply fallback that precedes the real body. */
    static String stripReplyFallback(String body) {
        if (body == null || !body.startsWith(">")) return body;
        String[] lines = body.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].startsWith(">")) i++;
        if (i < lines.length && lines[i].isEmpty()) i++;
        if (i >= lines.length) return body;
        return String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length));
    }

    /** Rooms with a room (or per-room override) push rule that does not notify. */
    static Set<String> mutedRoomsFrom(JsonNode pushRules) {
        Set<String> muted = new HashSet<>();
        JsonNode global = pushRules.path("global");
        for (JsonNode rule : global.path("room")) {
            if (rule.path("enabled").asBoolean(true) && isSilent(rule.path("actions"))) {
                String id = text(rule, "rule_id");
                if (id != null) muted.add(id);
            }
        }
        for (JsonNode rule : global.path("override")) {
            String id = text(rule, "rule_id");
            if (id == null || !id.startsWith("!") || !rule.path("enabled").asBoolean(true)) continue;
            if (isSilent(rule.path("actions"))) muted.add(id);
        }
        return muted;
    }

    static boolean isSilent(JsonNode actions) {
        if (!actions.isArray() || actions.isEmpty()) return true;
        for (JsonNode a : actions) if ("dont_notify".equals(a.asText())) return true;
        return false;
    }

    /** Name for a user whose profile isn't known yet: "+57300…" for phone puppets, else "Contacto de WhatsApp". */
    static String fallbackName(String userId) {
        String local = localpart(userId);
        Network n = Network.fromUserId(userId);
        if (n == null || n.puppetPrefix() == null || !local.startsWith(n.puppetPrefix())) return local;
        String rest = local.substring(n.puppetPrefix().length());
        if (rest.matches("\\d{6,15}")) return "+" + rest;
        return "Contacto de " + n.displayName();
    }

    static String localpart(String userId) {
        if (userId == null) return "";
        int colon = userId.indexOf(':');
        int start = userId.startsWith("@") ? 1 : 0;
        return colon > start ? userId.substring(start, colon) : userId.substring(start);
    }

    /** True if {@code userId} is a bridge bot (e.g. {@code @whatsappbot:server}). */
    static boolean isBot(String userId) {
        Network n = Network.fromUserId(userId);
        return n != null && Objects.equals(localpart(userId), n.botLocalpart());
    }

    static JsonNode prevContent(JsonNode ev) {
        JsonNode p = ev.path("unsigned").path("prev_content");
        return p.isMissingNode() ? ev.path("prev_content") : p;
    }

    static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) if (n.isTextual()) out.add(n.asText());
        return List.copyOf(out);
    }
}
