package app.unichat.cloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What every {@link Storage} must do when listing a folder: the same checks for each service. */
final class StorageListing {
    private StorageListing() {}

    /** A folder's files and folders by name, each once, nothing deeper; a folder that isn't there lists nothing. */
    static void check(Storage storage) throws IOException {
        storage.mkdirs("Unichat/Texts/9b1c d");
        storage.put("Unichat/Texts/9b1c d/a1b2 2026-11.jsonl", "{}".getBytes(StandardCharsets.UTF_8));
        storage.put("Unichat/Texts/9b1c d/index.json", "{}".getBytes(StandardCharsets.UTF_8));
        storage.mkdirs("Unichat/Texts/otro");
        storage.put("Unichat/Texts/índice ñ.json", "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(Set.of("9b1c d", "otro", "índice ñ.json"), Set.copyOf(storage.list("Unichat/Texts")));
        assertEquals(3, storage.list("Unichat/Texts").size(), "each once");
        assertEquals(Set.of("a1b2 2026-11.jsonl", "index.json"), Set.copyOf(storage.list("Unichat/Texts/9b1c d")));
        assertEquals(List.of(), storage.list("Unichat/Nada"));
    }
}
