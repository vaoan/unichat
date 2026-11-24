package app.unichat.autosave;

import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.MessageType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One version of what a message said, as a computer kept it ({@link TextLog}): which message, of which chat, sent when
 * ({@code at}) and by whom, and when this text was first seen ({@code seen}: an edit is seen after the message was
 * sent). {@code written}: what the person wrote before the AI corrected it, which never went out (corrected before
 * sending). A line of JSON on disk, so whatever a stranger wrote is kept as it is and can't pass for another entry.
 */
public record SavedText(String id, String chatId, Instant at, Instant seen, String from, String fromId, boolean mine, String text,
                        boolean written) {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a version is among its message's: the message as it came, what was written before the AI corrected it, the correction, an edit. */
    public enum Mark { SENT, WRITTEN, CORRECTED, EDITED }

    /**
     * Each version's mark, in the order given (each message's versions oldest first, as {@link TextLog#merged} gives
     * them): the first is as it came or as written; after what was written comes its correction; any later, an edit.
     */
    public static java.util.List<Mark> marks(java.util.List<SavedText> versions) {
        java.util.Map<String, SavedText> before = new java.util.HashMap<>();
        java.util.List<Mark> out = new java.util.ArrayList<>();
        for (SavedText t : versions) {
            SavedText prev = before.put(t.id(), t);
            out.add(t.written() ? Mark.WRITTEN : prev == null ? Mark.SENT : prev.written() ? Mark.CORRECTED : Mark.EDITED);
        }
        return out;
    }

    /**
     * What {@code m} says now, seen {@code seen}; null when there's nothing to keep: deleted, media (auto-save's other
     * kinds), nothing said, or mine still going out (its id is a temporary one).
     */
    static @Nullable SavedText of(Message m, Instant seen) {
        boolean said = m.type() == MessageType.TEXT || m.type() == MessageType.NOTICE || m.type() == MessageType.EMOTE;
        if (!said || m.deleted() || m.timestamp() == null || m.body().isBlank()) return null;
        if (m.status() == MessageStatus.SENDING || m.status() == MessageStatus.FAILED) return null;
        return new SavedText(m.id(), m.chatId(), m.timestamp(), seen, m.senderName(), m.senderId(), m.outgoing(), m.body(), false);
    }

    String json() {
        ObjectNode n = JSON.createObjectNode().put("id", id).put("chat", chatId).put("at", at.toString()).put("seen", seen.toString())
                .put("from", from).put("fromId", fromId).put("mine", mine).put("text", text);
        if (written) n.put("written", true);
        return n.toString();
    }

    /** The entry a line holds; null for one that isn't whole (cut short, written by hand, from a later version). */
    static @Nullable SavedText parse(String line) {
        try {
            JsonNode n = JSON.readTree(line);
            if (n == null || !n.isObject()) return null;
            String id = n.path("id").asText(null);
            String chat = n.path("chat").asText(null);
            String text = n.path("text").asText(null);
            String at = n.path("at").asText(null);
            String seen = n.path("seen").asText(null);
            if (id == null || chat == null || text == null || at == null || seen == null) return null;
            return new SavedText(id, chat, Instant.parse(at), Instant.parse(seen), n.path("from").asText(""),
                    n.path("fromId").asText(""), n.path("mine").asBoolean(false), text, n.path("written").asBoolean(false));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
