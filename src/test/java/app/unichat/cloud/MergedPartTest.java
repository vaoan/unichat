package app.unichat.cloud;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A part that only grows: every computer's entries end up on every computer, and none is ever lost. */
class MergedPartTest {
    private static final String PATH = "Unichat/Ajustes/prueba-unida.json";

    /** One computer's entries, in memory. */
    private static final class Computer {
        final Map<String, String> entries = new TreeMap<>();
        final MergedPart part = new MergedPart(PATH, () -> new TreeMap<>(entries), entries::putAll);
    }

    @Test
    void transcriptsAreTheUnionOfEveryComputers() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            Storage dav = new WebDav(cloud.url(), "u", "p");
            Computer a = new Computer(), b = new Computer();
            a.entries.put("a1", "hola");
            b.entries.put("b1", "adiós");
            a.part.sync(dav);
            b.part.sync(dav);
            a.part.sync(dav);
            Map<String, String> both = Map.of("a1", "hola", "b1", "adiós");
            assertEquals(both, a.entries, "each has the other's");
            assertEquals(both, b.entries);

            b.entries.remove("a1"); // gone here, by hand: still in the account, so it comes back, never deleted there
            a.entries.put("a2", "otra");
            a.part.sync(dav);
            b.part.sync(dav);
            assertEquals(Map.of("a1", "hola", "a2", "otra", "b1", "adiós"), b.entries);

            int puts = cloud.puts.get(), gets = cloud.gets.get();
            a.part.sync(dav);
            a.part.sync(dav);
            b.part.sync(dav);
            assertEquals(puts, cloud.puts.get(), "nothing new: nothing goes up");
            assertEquals(gets, cloud.gets.get(), "nor down: each one's stamp says it already has it");
        }
    }

    @Test
    void twoComputersWritingAtOnceLoseNothing() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            Storage dav = new WebDav(cloud.url(), "u", "p");
            Computer a = new Computer(), b = new Computer();
            a.entries.put("a1", "x");
            a.part.sync(dav);
            b.entries.put("b1", "y");
            b.part.sync(dav);
            // a's next write was made from what it read before b's: b's entry is overwritten in the account…
            cloud.put(PATH, "{\"entries\":{\"a1\":\"x\"}}".getBytes(StandardCharsets.UTF_8));
            b.part.sync(dav); // …and b, missing from it, puts it back
            a.part.sync(dav);
            assertEquals(Map.of("a1", "x", "b1", "y"), a.entries);
        }
    }

    @Test
    void aFileThatIsntOneReadsAsEmpty() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            Storage dav = new WebDav(cloud.url(), "u", "p");
            cloud.put(PATH, "{\"entries\":[1,2], \"x\":".getBytes(StandardCharsets.UTF_8));
            Computer a = new Computer();
            a.entries.put("a1", "x");
            a.part.sync(dav);
            cloud.put(PATH, "{\"entries\":{\"n\":{\"no\":\"texto\"},\"b1\":\"y\"}}".getBytes(StandardCharsets.UTF_8));
            a.part.sync(dav);
            assertEquals(Map.of("a1", "x", "b1", "y"), a.entries, "only text values are taken");
        }
    }
}
