package app.unichat.ui.emoji;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import javafx.scene.Node;

/**
 * The tiles of a media grid, kept between rebuilds: changing chats or adding a favourite used to make every sticker load and
 * decode again. {@link #next} starts a rebuild; tiles it doesn't ask for are let go.
 */
final class TileCache {
    private Map<String, Node> previous = new HashMap<>();
    private Map<String, Node> current = new HashMap<>();

    void next() {
        previous = current;
        current = new HashMap<>();
    }

    Node get(String key, Supplier<Node> create) {
        Node n = previous.remove(key);
        if (n == null) n = current.get(key);
        if (n == null) n = create.get();
        current.put(key, n);
        return n;
    }
}
