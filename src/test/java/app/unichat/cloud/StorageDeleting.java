package app.unichat.cloud;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What every {@link Storage} must do when deleting: the same checks for each service. */
final class StorageDeleting {
    private StorageDeleting() {}

    /** A file goes; a folder goes with all in it; what isn't there is fine; the neighbours stay. */
    static void check(Storage storage) throws Exception {
        storage.mkdirs("Unichat/Vault/slots/a b");
        storage.put("Unichat/Vault/slots/a b/one.json", "{}".getBytes(StandardCharsets.UTF_8));
        storage.put("Unichat/Vault/slots/two.json", "{}".getBytes(StandardCharsets.UTF_8));
        storage.put("Unichat/Vault/keep.json", "{}".getBytes(StandardCharsets.UTF_8));

        storage.delete("Unichat/Vault/slots/two.json");
        assertFalse(storage.exists("Unichat/Vault/slots/two.json"));
        assertTrue(storage.exists("Unichat/Vault/slots/a b/one.json"), "a file's neighbours stay");

        storage.delete("Unichat/Vault/slots");
        assertFalse(storage.exists("Unichat/Vault/slots"));
        assertFalse(storage.exists("Unichat/Vault/slots/a b/one.json"), "all in a folder goes");
        assertEquals(Set.of("keep.json"), Set.copyOf(storage.list("Unichat/Vault")), "the folder's neighbours stay");

        for (String bad : new String[] {"", "/", "//", "a/../b", "./x", "Unichat/Vault/.."}) {
            assertThrows(java.io.IOException.class, () -> storage.delete(bad), "refused: '" + bad + "'");
        }
        assertTrue(storage.exists("Unichat/Vault/keep.json"), "a refused path deletes nothing");
        assertEquals(Set.of("keep.json"), Set.copyOf(storage.list("Unichat/Vault")));

        storage.delete("Unichat/Vault/slots"); // already gone
        storage.delete("Unichat/Nada/nunca.json");
    }
}
