package app.unichat.cloud;

import app.unichat.support.Hashes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * One thing that travels through the account as a file, with who wrote it and when: the settings (the portable ones,
 * never this computer's, and {@code preferences.json}, each chat's options and the folders), or what the AI learned of
 * how the person writes ({@code values} holding the file, no preferences). Pure.
 * <p>
 * Whether two of them differ ({@link #hash}) leaves out what changes by itself, or a second computer would restart all
 * the time for nothing: the side panel's open tab ({@code panel.}), and chat flags that only follow the server (each
 * chat's {@code fromServer}), which every computer gets from the server anyway. Emoji favorites, recents and reaction
 * counts (media-library.json) change at every use, so they don't travel.
 */
public record Bundle(String machine, Instant savedAt, Map<String, String> settings, JsonNode preferences) {
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** A setting that changes as the person moves around the window, not by choosing anything. */
    static boolean travels(String key) {
        return !key.startsWith("panel.");
    }

    public static Bundle of(String machine, Instant savedAt, Map<String, String> settings, JsonNode preferences) {
        Map<String, String> kept = new TreeMap<>();
        settings.forEach((k, v) -> { if (travels(k)) kept.put(k, v); });
        return new Bundle(machine, savedAt, kept, preferences);
    }

    /** What the person chose, the same whichever computer wrote it. */
    public String hash() {
        ObjectNode n = JSON.createObjectNode();
        n.set("settings", JSON.valueToTree(new TreeMap<>(settings)));
        n.set("preferences", chosen(preferences));
        try {
            return Hashes.sha256(JSON.writeValueAsString(JSON.treeToValue(n, Object.class)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The preferences without what only follows the server. */
    private static JsonNode chosen(JsonNode prefs) {
        if (prefs == null || !prefs.isObject()) return JSON.nullNode();
        ObjectNode copy = prefs.deepCopy();
        if (copy.get("chats") instanceof ObjectNode chats) {
            for (Iterator<Map.Entry<String, JsonNode>> it = chats.fields(); it.hasNext(); ) {
                if (!(it.next().getValue() instanceof ObjectNode chat)) continue;
                for (JsonNode f : chat.path("fromServer")) chat.remove(f.asText());
                chat.remove("fromServer");
            }
            chats.properties().removeIf(e -> e.getValue().isObject() && e.getValue().isEmpty());
        }
        return copy;
    }

    public byte[] toBytes() throws IOException {
        ObjectNode n = JSON.createObjectNode().put("version", 1).put("machine", machine).put("savedAt", savedAt.toString());
        n.set("settings", JSON.valueToTree(settings));
        n.set("preferences", preferences == null ? JSON.nullNode() : preferences);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(n);
    }

    /** Null for a file that isn't one of these. */
    public static Bundle parse(byte[] data) {
        try {
            JsonNode n = JSON.readTree(data);
            if (n == null || n.path("version").asInt() != 1 || !n.path("settings").isObject()) return null;
            Map<String, String> settings = new TreeMap<>();
            n.path("settings").fields().forEachRemaining(e -> settings.put(e.getKey(), e.getValue().asText()));
            return new Bundle(n.path("machine").asText(), Instant.parse(n.path("savedAt").asText()), settings,
                    n.path("preferences").isObject() ? n.get("preferences") : null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static JsonNode readPreferences(byte[] data) {
        try {
            return data == null ? null : JSON.readTree(data);
        } catch (IOException e) {
            return null;
        }
    }

    static byte[] writePreferences(JsonNode prefs) throws IOException {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(prefs);
    }
}
