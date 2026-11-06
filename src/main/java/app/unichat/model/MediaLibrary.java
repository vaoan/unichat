package app.unichat.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Your favourite and recent emojis, stickers and GIFs, kept in Unichat (not on the server) so they work in every chat
 * of every network that accepts them. Stored as JSON in the data folder; a damaged file starts an empty library.
 */
public final class MediaLibrary {
    /** EMOJI items keep the emoji itself in {@code url}. */
    public enum Kind { STICKER, GIF, EMOJI }

    /** {@code info}: the original event's info (keeps e.g. fi.mau.bridged_sticker so bridges resend the native sticker). */
    public record Item(Kind kind, String url, String mime, int width, int height, JsonNode info) {}

    static final int MAX_RECENTS = 60;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final List<Item> favorites = new ArrayList<>();
    private final List<Item> recents = new ArrayList<>();
    private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public MediaLibrary(Path file) {
        this.file = file;
        load();
    }

    public static Item emoji(String emoji) {
        return new Item(Kind.EMOJI, emoji, null, 0, 0, null);
    }

    /** Runs after every change, on the thread that made it. */
    public void addListener(Runnable r) { listeners.add(r); }

    public synchronized List<Item> favorites(Kind k) {
        return favorites.stream().filter(i -> i.kind() == k).toList();
    }

    public synchronized List<Item> recents(Kind k) {
        return recents.stream().filter(i -> i.kind() == k).toList();
    }

    public synchronized boolean isFavorite(String url) {
        return favorites.stream().anyMatch(i -> i.url().equals(url));
    }

    public synchronized boolean isFavorite(Item item) {
        return favorites.stream().anyMatch(i -> same(i, item));
    }

    /** Used or received: moves to the front of its kind's recents (one per sticker, whatever its url; at most 60 per kind). */
    public synchronized void addRecent(Item item) {
        recents.removeIf(i -> same(i, item));
        recents.add(0, item);
        int seen = 0;
        for (var it = recents.iterator(); it.hasNext(); ) {
            if (it.next().kind() == item.kind() && ++seen > MAX_RECENTS) it.remove();
        }
        changed();
    }

    public synchronized boolean isRecent(String url) {
        return recents.stream().anyMatch(i -> i.url().equals(url));
    }

    public synchronized boolean isRecent(Item item) {
        return recents.stream().anyMatch(i -> same(i, item));
    }

    /** Takes it out of the recents (it comes back the next time it's used or received). */
    public synchronized void removeRecent(String url) {
        if (recents.removeIf(i -> i.url().equals(url))) changed();
    }

    public synchronized void removeRecent(Item item) {
        if (recents.removeIf(i -> same(i, item))) changed();
    }

    /** A sticker/GIF shown in a chat: added to the recents if it's new, without moving existing ones. */
    public synchronized void rememberSeen(Item item) {
        if (recents.stream().anyMatch(i -> same(i, item))) return;
        addRecent(item);
    }

    /** Adds or removes a favourite; true when it's now a favourite. */
    public synchronized boolean toggleFavorite(Item item) {
        boolean removed = favorites.removeIf(i -> same(i, item));
        if (!removed) favorites.add(0, item);
        changed();
        return !removed;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            JsonNode root = JSON.readTree(file.toFile());
            for (JsonNode n : root.path("favorites")) if (known(n)) favorites.add(item(n));
            for (JsonNode n : root.path("recents")) if (known(n)) recents.add(item(n));
            if (dropCopies(favorites) | dropCopies(recents)) save();
        } catch (IOException | RuntimeException e) {
            favorites.clear();
            recents.clear();
        }
    }

    /** Keeps the first of each sticker: lists are newest first. True if any went. */
    private static boolean dropCopies(List<Item> items) {
        List<Item> kept = new ArrayList<>();
        for (Item i : items) if (kept.stream().noneMatch(k -> same(k, i))) kept.add(i);
        boolean dropped = kept.size() < items.size();
        items.clear();
        items.addAll(kept);
        return dropped;
    }

    /**
     * Whether two items are one sticker or GIF: bridges with direct media give the same file another address in each message.
     * The bridge's own id decides when both have one; otherwise the same format, dimensions and bytes (a stored copy has no id).
     */
    static boolean same(Item a, Item b) {
        if (a.kind() != b.kind()) return false;
        if (a.url().equals(b.url())) return true;
        String idA = bridgeId(a), idB = bridgeId(b);
        if (idA != null && idB != null) return idA.equals(idB);
        long size = size(a);
        return size > 0 && size == size(b) && a.width() == b.width() && a.height() == b.height()
                && java.util.Objects.equals(a.mime(), b.mime());
    }

    private static String bridgeId(Item i) {
        JsonNode sticker = i.info() == null ? null : i.info().get("fi.mau.bridged_sticker");
        return sticker == null ? null : sticker.path("id").asText(null);
    }

    private static long size(Item i) {
        return i.info() == null ? 0 : i.info().path("size").asLong(0);
    }

    /** An item of a kind this version doesn't know (written by a newer Unichat) is skipped, not the whole file. */
    private static boolean known(JsonNode n) {
        return java.util.Arrays.stream(Kind.values()).anyMatch(k -> k.name().equals(n.path("kind").asText()));
    }

    private void changed() {
        save();
        for (Runnable r : listeners) r.run();
    }

    private void save() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode fav = root.putArray("favorites"), rec = root.putArray("recents");
        favorites.forEach(i -> fav.add(node(i)));
        recents.forEach(i -> rec.add(node(i)));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writeValue(tmp.toFile(), root);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Could not save the media library: " + e);
        }
    }

    private static Item item(JsonNode n) {
        return new Item(Kind.valueOf(n.path("kind").asText()), n.path("url").asText(), n.path("mime").asText(null),
                n.path("width").asInt(), n.path("height").asInt(), n.has("info") ? n.get("info") : null);
    }

    private static ObjectNode node(Item i) {
        ObjectNode n = JSON.createObjectNode().put("kind", i.kind().name()).put("url", i.url()).put("mime", i.mime())
                .put("width", i.width()).put("height", i.height());
        if (i.info() != null) n.set("info", i.info());
        return n;
    }
}
