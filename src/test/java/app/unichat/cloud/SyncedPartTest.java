package app.unichat.cloud;

import app.unichat.cloud.SyncedPart.Action;
import app.unichat.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Two computers on one account: what one changes reaches the other, and nothing goes to and fro. */
class SyncedPartTest {
    private static final String PATH = "Unichat/Ajustes/prueba.json";
    private static final Instant T = Instant.parse("2026-11-10T08:00:00Z");

    /** One computer's settings, in memory. */
    private static final class Computer {
        final Map<String, String> settings = new TreeMap<>();
        final SyncedPart part;

        Computer(String name) {
            part = new SyncedPart(PATH, "test-" + name, now -> Bundle.of(name, now, settings, null), theirs -> {
                settings.clear();
                settings.putAll(theirs.settings());
            });
        }
    }

    @AfterEach
    void forget() {
        for (String c : new String[] {"a", "b"}) Settings.get().set("cloud.base.test-" + c, null);
    }

    @Test
    void theRules() {
        assertEquals(Action.UPLOAD, SyncedPart.decide("x", null, null, T, T), "the account has none: it goes up");
        assertEquals(Action.NOTHING, SyncedPart.decide("x", "x", "y", T, T), "the same");
        assertEquals(Action.ADOPT, SyncedPart.decide("x", "y", null, T, T), "first time here: the account's is taken");
        assertEquals(Action.ADOPT, SyncedPart.decide("x", "y", "x", T, T), "only the account changed");
        assertEquals(Action.UPLOAD, SyncedPart.decide("x", "y", "y", T, T), "only this computer changed");
        assertEquals(Action.ADOPT, SyncedPart.decide("x", "y", "z", T, T.plusSeconds(1)), "both: the newer, theirs");
        assertEquals(Action.UPLOAD, SyncedPart.decide("x", "y", "z", T.plusSeconds(1), T), "both: the newer, mine");
    }

    @Test
    void whatOneComputerChangesReachesTheOtherAndStops() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            WebDav dav = new WebDav(cloud.url(), "u", "p");
            Computer a = new Computer("a"), b = new Computer("b");
            a.settings.put("ui.theme", "DARK");
            assertEquals(Action.UPLOAD, a.part.sync(dav, T), "the first computer fills the account");
            b.settings.put("ui.theme", "LIGHT");
            assertEquals(Action.ADOPT, b.part.sync(dav, T), "the second one takes it");
            assertEquals("DARK", b.settings.get("ui.theme"));

            b.settings.put("ui.accent", "#FF0000");
            assertEquals(Action.UPLOAD, b.part.sync(dav, T.plusSeconds(60)));
            assertEquals(Action.ADOPT, a.part.sync(dav, T.plusSeconds(60)));
            assertEquals("#FF0000", a.settings.get("ui.accent"), "and back the other way");

            int puts = cloud.puts.get(), gets = cloud.gets.get();
            assertEquals(Action.NOTHING, a.part.sync(dav, T.plusSeconds(120)));
            assertEquals(Action.NOTHING, b.part.sync(dav, T.plusSeconds(120)));
            assertEquals(puts, cloud.puts.get(), "nothing goes up again");
            assertEquals(gets, cloud.gets.get(), "nor down: each one's stamp says it already has it");
        }
    }

    @Test
    void bothChangedTheNewerWins() throws Exception {
        try (FakeDav cloud = new FakeDav("u", "p")) {
            WebDav dav = new WebDav(cloud.url(), "u", "p");
            Computer a = new Computer("a"), b = new Computer("b");
            a.part.sync(dav, T);
            b.part.sync(dav, T);
            a.settings.put("ui.theme", "DARK");
            a.part.sync(dav, T.plusSeconds(10)); // a's change went up at 10
            b.settings.put("ui.theme", "LIGHT");
            assertEquals(Action.UPLOAD, b.part.sync(dav, T.plusSeconds(20)), "b changed later: b wins");
            assertEquals(Action.ADOPT, a.part.sync(dav, T.plusSeconds(30)));
            assertEquals("LIGHT", a.settings.get("ui.theme"));
        }
    }
}
