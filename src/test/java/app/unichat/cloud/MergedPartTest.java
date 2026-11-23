package app.unichat.cloud;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A part that only grows: every computer's entries end up on every computer, and none is ever lost. */
class MergedPartTest {
    private static final String PATH = "Unichat/Settings/prueba-unida.json";

    /** One computer's entries, in memory; which of them were read whole. */
    private static final class Computer {
        final Map<String, String> entries = new TreeMap<>();
        final List<String> read = new ArrayList<>();
        final MergedPart part = new MergedPart(PATH, () -> Set.copyOf(entries.keySet()), name -> {
            read.add(name);
            return entries.get(name);
        }, entries::putAll);
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
    void anEntryGoneBetweenItsListingAndItsReadNeverGoesUpEmpty() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            Storage dav = new WebDav(cloud.url(), "u", "p");
            Computer a = new Computer(), b = new Computer();
            a.entries.put("a1", "hola");
            a.entries.put("a2", ""); // listed, then gone: read as nothing
            a.part.sync(dav);
            b.part.sync(dav);
            assertEquals(Map.of("a1", "hola"), b.entries, "no empty transcript ever spreads");
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

    @Test
    void onlyWhatGoesUpIsReadWhole() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            Storage dav = new WebDav(cloud.url(), "u", "p");
            Computer a = new Computer(), b = new Computer();
            a.entries.put("a1", "x");
            a.part.sync(dav);
            assertEquals(List.of("a1"), a.read);
            b.entries.put("a1", "x"); // b has it too (it came some other way): only its own goes up
            b.entries.put("b1", "y");
            b.part.sync(dav);
            assertEquals(List.of("b1"), b.read);
            a.part.sync(dav);
            a.part.sync(dav);
            assertEquals(List.of("a1"), a.read, "nothing more of a's to send: nothing of it read again");
        }
    }
}
