package app.unichat.service;

import app.unichat.service.PreferencesFile.Flag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code server-seen.json} (this machine only): which flags this server has said "true" about, so a fresh server's
 * empty answers don't erase what was copied from the old one; and which server that is. Not thread-safe:
 * {@link PreferencesFile}'s lock guards it.
 */
final class ServerSeen {
    private final Map<String, Set<Flag>> seen = new HashMap<>();
    private String server;
    private boolean dirty;

    /** What {@code file} remembers; nothing when it's missing or damaged (it's only a hint). */
    static ServerSeen load(Path file) {
        ServerSeen s = new ServerSeen();
        if (file == null || !Files.exists(file)) return s;
        try {
            JsonNode root = PreferencesFile.JSON.readTree(file.toFile());
            s.server = root.path("server").asText(null);
            for (Iterator<Map.Entry<String, JsonNode>> it = root.path("seen").fields(); it.hasNext(); ) {
                var c = it.next();
                Set<Flag> flags = EnumSet.noneOf(Flag.class);
                for (JsonNode n : c.getValue()) for (Flag f : Flag.values()) if (f.json.equals(n.asText())) flags.add(f);
                if (!flags.isEmpty()) s.seen.put(c.getKey(), flags);
            }
        } catch (IOException | RuntimeException e) {
            s.seen.clear();
        }
        return s;
    }

    /** The server said "true" about {@code f}. */
    void saidTrue(String key, Flag f) {
        if (seen.computeIfAbsent(key, k -> EnumSet.noneOf(Flag.class)).add(f)) dirty = true;
    }

    boolean saidTrueBefore(String key, Flag f) { return seen.getOrDefault(key, Set.of()).contains(f); }

    void forget(String key, Flag f) {
        Set<Flag> flags = seen.get(key);
        if (flags != null && flags.remove(f)) {
            dirty = true;
            if (flags.isEmpty()) seen.remove(key);
        }
    }

    void rekey(String oldKey, String newKey) {
        Set<Flag> old = seen.remove(oldKey);
        if (old == null) return;
        seen.computeIfAbsent(newKey, k -> EnumSet.noneOf(Flag.class)).addAll(old);
        dirty = true;
    }

    /** True if it's another server than the one remembered: what that one said is forgotten. */
    boolean use(String homeserver) {
        if (Objects.equals(homeserver, server)) return false;
        server = homeserver;
        seen.clear();
        dirty = true;
        return true;
    }

    /** Changed since the last {@link #take}. */
    boolean dirty() { return dirty; }

    /** What to write; no longer dirty until {@link #notSaved} or another change. */
    ObjectNode take() {
        dirty = false;
        ObjectNode root = PreferencesFile.JSON.createObjectNode().put("version", PreferencesFile.VERSION);
        if (server != null) root.put("server", server);
        ObjectNode s = root.putObject("seen");
        seen.forEach((k, flags) -> {
            ArrayNode a = s.putArray(k);
            for (Flag f : Flag.values()) if (flags.contains(f)) a.add(f.json);
        });
        return root;
    }

    /** Writing what {@link #take} gave failed: it's still to be written. */
    void notSaved() { dirty = true; }
}
