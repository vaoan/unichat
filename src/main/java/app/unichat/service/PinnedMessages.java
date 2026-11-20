package app.unichat.service;

import app.unichat.model.Message;
import app.unichat.model.PinnedMessage;
import app.unichat.settings.Settings;
import app.unichat.support.BackgroundWriter;
import app.unichat.support.I18n;
import app.unichat.support.SafeFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The messages pinned in each chat, Unichat's own (no bridge carries pins to its network): {@code pinned-messages.json}
 * in the data folder, this computer's, since message ids are the local server's. Each keeps what the message said, so
 * the chat's bar shows it however far back it is. Saved in the background; listeners hear the chat that changed.
 */
public final class PinnedMessages {
    /** How much of a message the bar keeps: one line of it. */
    static final int MAX_PREVIEW = 200;
    /** Pins per chat: the oldest go past this. */
    static final int MAX_PER_CHAT = 50;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static PinnedMessages shared;

    private final Path file;
    /** Per chat, newest pin first. */
    private final Map<String, List<PinnedMessage>> pins = new LinkedHashMap<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final BackgroundWriter writer;

    PinnedMessages(Path file) {
        this.file = file;
        this.writer = new BackgroundWriter("pinned-messages", this, this::save);
        try {
            if (Files.isRegularFile(file)) read(JSON.readTree(file.toFile()));
        } catch (IOException | RuntimeException e) {
            System.err.println("Pinned messages not read (" + e.getClass().getSimpleName() + "): starting with none");
        }
    }

    public static synchronized PinnedMessages get() {
        if (shared == null) shared = new PinnedMessages(Settings.dataDir().resolve("pinned-messages.json"));
        return shared;
    }

    /** {@code chatId}'s pins, newest first. */
    public synchronized List<PinnedMessage> of(String chatId) {
        return List.copyOf(pins.getOrDefault(chatId, List.of()));
    }

    public synchronized boolean isPinned(String chatId, String messageId) {
        return pins.getOrDefault(chatId, List.of()).stream().anyMatch(p -> p.messageId().equals(messageId));
    }

    /** Pins {@code m} in {@code chatId}, first of its pins (pinned again, it moves there). */
    public void pin(String chatId, Message m) {
        String sender = m.outgoing() ? I18n.t("pinned.you") : m.senderName();
        String preview = oneLine(m.preview());
        synchronized (this) {
            List<PinnedMessage> list = pins.computeIfAbsent(chatId, k -> new ArrayList<>());
            list.removeIf(p -> p.messageId().equals(m.id()));
            list.addFirst(new PinnedMessage(m.id(), sender == null ? "" : sender, preview, m.timestamp(), Instant.now()));
            while (list.size() > MAX_PER_CHAT) list.removeLast();
        }
        changed(chatId);
    }

    public void unpin(String chatId, String messageId) {
        synchronized (this) {
            List<PinnedMessage> list = pins.get(chatId);
            if (list == null || !list.removeIf(p -> p.messageId().equals(messageId))) return;
            if (list.isEmpty()) pins.remove(chatId);
        }
        changed(chatId);
    }

    /** Every chat's pins as {@code pinned-messages.json} holds them: what travels to the person's other computers. */
    public byte[] json() {
        ObjectNode root = JSON.createObjectNode();
        synchronized (this) {
            pins.forEach((chat, list) -> {
                ArrayNode a = root.putArray(chat);
                for (PinnedMessage p : list) {
                    ObjectNode o = a.addObject().put("id", p.messageId()).put("sender", p.sender()).put("preview", p.preview());
                    if (p.sentAt() != null) o.put("sentAt", p.sentAt().toEpochMilli());
                    if (p.pinnedAt() != null) o.put("pinnedAt", p.pinnedAt().toEpochMilli());
                }
            });
        }
        try {
            return JSON.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Another computer's pins in place of these (through the person's vault), saved as any change is, and each chat whose
     * pins may differ told. Nothing, or what isn't pins, is no pins.
     */
    public void adopt(byte[] theirs) {
        java.util.Set<String> chats;
        synchronized (this) {
            chats = new java.util.HashSet<>(pins.keySet());
            pins.clear();
            try {
                JsonNode root = theirs.length == 0 ? null : JSON.readTree(theirs);
                if (root != null && root.isObject()) read(root);
            } catch (IOException | RuntimeException e) {
                pins.clear();
                System.err.println("Pinned messages from another computer not read (" + e.getClass().getSimpleName() + "): none");
            }
            chats.addAll(pins.keySet());
        }
        writer.request();
        chats.forEach(c -> listeners.forEach(l -> l.accept(c)));
    }

    public void addListener(Consumer<String> l) { listeners.add(l); }

    public void removeListener(Consumer<String> l) { listeners.remove(l); }

    /** Returns once what was changed so far is on disk (tests, quitting). */
    public void flush() { writer.flush(); }

    private void changed(String chatId) {
        writer.request();
        listeners.forEach(l -> l.accept(chatId));
    }

    private static String oneLine(String text) {
        String s = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return s.codePointCount(0, s.length()) > MAX_PREVIEW ? s.substring(0, s.offsetByCodePoints(0, MAX_PREVIEW)) + "…" : s;
    }

    private void read(JsonNode root) {
        root.properties().forEach(e -> {
            List<PinnedMessage> list = new ArrayList<>();
            for (JsonNode p : e.getValue()) {
                if (!p.path("id").isTextual()) continue; // not a pin (the file may come from another computer)
                list.add(new PinnedMessage(p.path("id").asText(), p.path("sender").asText(""), p.path("preview").asText(""),
                        instant(p.path("sentAt")), instant(p.path("pinnedAt"))));
            }
            if (!list.isEmpty()) pins.put(e.getKey(), list);
        });
    }

    private static Instant instant(JsonNode n) {
        return n.isNumber() ? Instant.ofEpochMilli(n.asLong()) : null;
    }

    private void save() {
        try {
            SafeFile.replace(file, json(), false);
        } catch (IOException e) {
            System.err.println("Pinned messages not saved: " + e.getMessage());
        }
    }
}
