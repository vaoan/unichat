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
 * Your favourite and recent stickers and GIFs, kept in Unichat (not on the server) so they work in every chat of
 * every network that accepts them. Stored as JSON in the data folder; a damaged file starts an empty library.
 */
public final class MediaLibrary {
    public enum Kind { STICKER, GIF }

    /** {@code info}: the original event's info (keeps e.g. fi.mau.bridged_sticker so bridges resend the native sticker). */
    public record Item(Kind kind, String url, String mime, int width, int height, JsonNode info) {}

    static final int MAX_RECENTS = 60;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final List<Item> favorites = new ArrayList<>();
    private final List<Item> recents = new ArrayList<>();

    public MediaLibrary(Path file) {
        this.file = file;
        load();
    }

    public synchronized List<Item> favorites(Kind k) {
        return favorites.stream().filter(i -> i.kind() == k).toList();
    }

    public synchronized List<Item> recents(Kind k) {
        return recents.stream().filter(i -> i.kind() == k).toList();
    }

    public synchronized boolean isFavorite(String url) {
        return favorites.stream().anyMatch(i -> i.url().equals(url));
    }

    /** Used or received: moves to the front of its kind's recents (unique by url, at most 60 per kind). */
    public synchronized void addRecent(Item item) {
        recents.removeIf(i -> i.url().equals(item.url()));
        recents.add(0, item);
        int seen = 0;
        for (var it = recents.iterator(); it.hasNext(); ) {
            if (it.next().kind() == item.kind() && ++seen > MAX_RECENTS) it.remove();
        }
        save();
    }

    /** Adds or removes a favourite; true when it's now a favourite. */
    public synchronized boolean toggleFavorite(Item item) {
        boolean removed = favorites.removeIf(i -> i.url().equals(item.url()));
        if (!removed) favorites.add(0, item);
        save();
        return !removed;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            JsonNode root = JSON.readTree(file.toFile());
            for (JsonNode n : root.path("favorites")) favorites.add(item(n));
            for (JsonNode n : root.path("recents")) recents.add(item(n));
        } catch (IOException | RuntimeException e) {
            favorites.clear();
            recents.clear();
        }
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
