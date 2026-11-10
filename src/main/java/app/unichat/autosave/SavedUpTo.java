package app.unichat.autosave;

import app.unichat.model.Message;
import app.unichat.support.SafeFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Up to where each chat's messages were saved ({@code autosave.json}): the newest one's time, and the ids sharing that
 * time (two can). What's newer is new; a chat with nothing yet starts now, never with what was there before. Kept
 * on disk so what arrives while Unichat is closed is saved at the next start.
 */
final class SavedUpTo {
    private record Mark(Instant at, Set<String> ids) {}

    /** How far back exploring the history got: everything after {@code back} was looked at; {@code start}: all of it. */
    record Back(Instant back, boolean start) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final Map<String, Mark> marks = new HashMap<>();
    private final Map<String, Back> backs = new HashMap<>();

    SavedUpTo(Path file) {
        this.file = file;
        load();
    }

    /** From now on. */
    synchronized void startNow(String chatId) {
        marks.put(chatId, new Mark(Instant.now(), Set.of()));
        save();
    }

    /** Whether {@code m} comes after the last one saved; a chat never seen starts now. */
    synchronized boolean isNew(String chatId, Message m) {
        Mark mark = marks.get(chatId);
        if (mark == null) {
            startNow(chatId);
            mark = marks.get(chatId);
        }
        int c = m.timestamp().compareTo(mark.at());
        return c > 0 || c == 0 && !mark.ids().contains(m.id());
    }

    /** Where exploring {@code chatId}'s past got to; null: never explored. */
    synchronized Back back(String chatId) {
        return backs.get(chatId);
    }

    /**
     * What exploring the past has to look at: older than where it got to, and older than what saving as it arrives
     * took (that's done already).
     */
    synchronized boolean isOld(String chatId, Message m) {
        Back b = backs.get(chatId);
        Mark mark = marks.get(chatId);
        Instant limit = b != null ? b.back() : mark != null ? mark.at() : Instant.now();
        return m.timestamp().isBefore(limit);
    }

    /** Exploring got back to {@code at} (looked at, saved or not); {@code start}: the chat's beginning. */
    synchronized void exploredBack(String chatId, Instant at, boolean start) {
        Back b = backs.get(chatId);
        Instant back = b == null || at.isBefore(b.back()) ? at : b.back();
        backs.put(chatId, new Back(back, start || b != null && b.start()));
        save();
    }

    /** Exploring starts over from the newest (it got to the beginning before). */
    synchronized void forgetBack(String chatId) {
        if (backs.remove(chatId) != null) save();
    }

    /** {@code m} is done: saved, or nothing in it to save. */
    synchronized void saved(String chatId, Message m) {
        Mark mark = marks.get(chatId);
        Set<String> ids = new HashSet<>();
        if (mark != null && mark.at().equals(m.timestamp())) ids.addAll(mark.ids());
        ids.add(m.id());
        marks.put(chatId, new Mark(m.timestamp(), Set.copyOf(ids)));
        save();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonNode root = JSON.readTree(file.toFile());
            root.fields().forEachRemaining(f -> {
                Set<String> ids = new HashSet<>();
                f.getValue().path("ids").forEach(n -> ids.add(n.asText()));
                if (f.getValue().hasNonNull("at")) {
                    marks.put(f.getKey(), new Mark(Instant.parse(f.getValue().path("at").asText()), Set.copyOf(ids)));
                }
                if (f.getValue().hasNonNull("back")) {
                    backs.put(f.getKey(), new Back(Instant.parse(f.getValue().path("back").asText()), f.getValue().path("start").asBoolean()));
                }
            });
        } catch (IOException | RuntimeException e) {
            System.err.println("Auto-save: couldn't read " + file.getFileName() + ", starting from now: " + e.getMessage());
        }
    }

    private void save() {
        ObjectNode root = JSON.createObjectNode();
        marks.forEach((chat, mark) -> {
            ObjectNode n = root.putObject(chat);
            n.put("at", mark.at().toString()); // exact: rounded, a message could be saved twice
            mark.ids().forEach(n.putArray("ids")::add);
        });
        backs.forEach((chat, b) -> {
            ObjectNode n = root.has(chat) ? (ObjectNode) root.get(chat) : root.putObject(chat);
            n.put("back", b.back().toString());
            n.put("start", b.start());
        });
        try {
            SafeFile.at(file).write(out -> JSON.writeValue(out, root));
        } catch (IOException e) {
            System.err.println("Auto-save: couldn't keep where it got to: " + e.getMessage());
        }
    }
}
