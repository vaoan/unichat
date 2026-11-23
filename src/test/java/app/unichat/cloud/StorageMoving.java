package app.unichat.cloud;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What every {@link Storage} must do when moving: the same checks for each service. */
final class StorageMoving {
    private StorageMoving() {}

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A file and a folder with all in it move on the server, names with spaces and accents too; never over something
     * already there (both stay as they were); nothing there to move fails; a path that could reach more is refused.
     */
    static void check(Storage storage) throws Exception {
        storage.mkdirs("Unichat/Old/Mamá d");
        storage.put("Unichat/Old/Mamá d/video [1].mp4", bytes("video"));
        storage.put("Unichat/Old/one.json", bytes("{}"));
        storage.mkdirs("Unichat/Other");

        storage.move("Unichat/Old/one.json", "Unichat/Other/two.json");
        assertFalse(storage.exists("Unichat/Old/one.json"), "a file moved leaves its place");
        assertArrayEquals(bytes("{}"), storage.get("Unichat/Other/two.json"));

        storage.move("Unichat/Old", "Unichat/New");
        assertFalse(storage.exists("Unichat/Old"), "a folder moved leaves its place");
        assertArrayEquals(bytes("video"), storage.get("Unichat/New/Mamá d/video [1].mp4"), "with all in it");
        assertEquals(Set.of("Mamá d"), Set.copyOf(storage.list("Unichat/New")));

        storage.put("Unichat/Other/three.json", bytes("tres"));
        assertThrows(java.io.IOException.class, () -> storage.move("Unichat/Other/two.json", "Unichat/Other/three.json"), "never over a file");
        assertArrayEquals(bytes("{}"), storage.get("Unichat/Other/two.json"));
        assertArrayEquals(bytes("tres"), storage.get("Unichat/Other/three.json"));
        assertThrows(java.io.IOException.class, () -> storage.move("Unichat/New", "Unichat/Other"), "never over a folder");
        assertTrue(storage.exists("Unichat/New/Mamá d/video [1].mp4"));
        assertTrue(storage.exists("Unichat/Other/two.json"));

        assertThrows(java.io.IOException.class, () -> storage.move("Unichat/Nada", "Unichat/Algo"), "nothing there to move");
        for (String bad : new String[] {"", "/", "a/../b", "Unichat/.."}) {
            assertThrows(java.io.IOException.class, () -> storage.move(bad, "Unichat/X"), "refused: '" + bad + "'");
            assertThrows(java.io.IOException.class, () -> storage.move("Unichat/New", bad), "refused: '" + bad + "'");
        }
        assertTrue(storage.exists("Unichat/New/Mamá d/video [1].mp4"), "a refused path moves nothing");
    }
}
