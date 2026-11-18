package app.unichat.cloud;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What every {@link Storage} must do when listing a folder: the same checks for each service. */
final class StorageListing {
    private StorageListing() {}

    /** A folder's files and folders by name, each once, nothing deeper; a folder that isn't there lists nothing. */
    static void check(Storage storage) throws Exception {
        storage.mkdirs("Unichat/Textos/9b1c d");
        storage.put("Unichat/Textos/9b1c d/a1b2 2026-11.jsonl", "{}".getBytes(StandardCharsets.UTF_8));
        storage.put("Unichat/Textos/9b1c d/index.json", "{}".getBytes(StandardCharsets.UTF_8));
        storage.mkdirs("Unichat/Textos/otro");
        storage.put("Unichat/Textos/índice ñ.json", "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(Set.of("9b1c d", "otro", "índice ñ.json"), Set.copyOf(storage.list("Unichat/Textos")));
        assertEquals(3, storage.list("Unichat/Textos").size(), "each once");
        assertEquals(Set.of("a1b2 2026-11.jsonl", "index.json"), Set.copyOf(storage.list("Unichat/Textos/9b1c d")));
        assertEquals(List.of(), storage.list("Unichat/Nada"));
    }
}
