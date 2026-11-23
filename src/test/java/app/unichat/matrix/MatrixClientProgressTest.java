package app.unichat.matrix;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A download says how far it got: each whole percent once, up to all of it, and the file is whole. */
class MatrixClientProgressTest {
    @TempDir
    Path dir;

    @Test
    @SuppressFBWarnings(value = {"DMI_RANDOM_USED_ONLY_ONCE", "PREDICTABLE_RANDOM"}, justification = "seeded test data, the same on every run on purpose; nothing secret")
    void eachPercentOnceUpToAll() throws Exception {
        byte[] data = new byte[1_000_000];
        new java.util.Random(1).nextBytes(data);
        List<Double> told = new ArrayList<>();
        MatrixClient.copyTelling(new ByteArrayInputStream(data), dir.resolve("f"), data.length, told::add);
        assertEquals(1.0, told.getLast(), 1e-9);
        for (int i = 1; i < told.size(); i++) assertTrue(told.get(i) > told.get(i - 1), "always forward, never twice: " + told);
        assertTrue(told.size() <= 101, "not every packet: a screen shows it");
        assertEquals(data.length, Files.size(dir.resolve("f")));
    }
}
