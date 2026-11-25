package app.unichat.matrix;

import app.unichat.model.Network;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RoomArrivalsTest {
    private static final Duration QUIET = Duration.ofSeconds(45);
    private static final Duration MAX = Duration.ofMinutes(15);

    private final ManualTime time = new ManualTime(Instant.parse("2026-11-24T12:00:00Z"));
    private final Map<Network, Set<String>> rooms = new EnumMap<>(Network.class);
    // Through this, not the fields: a reference to a field's method is made before the field is (SpotBugs' CT_CONSTRUCTOR_THROW).
    private Instant now() { return time.now(); }

    private void later(Duration d, Runnable r) { time.later(d, r); }

    private final RoomArrivals arrivals = new RoomArrivals(n -> Set.copyOf(rooms.getOrDefault(n, Set.of())), this::now, this::later);

    private void add(Network n, String id) {
        rooms.computeIfAbsent(n, k -> ConcurrentHashMap.newKeySet()).add(id);
        arrivals.roomsChanged();
    }

    @Test
    void countsOnlyThatNetworksNewRooms() {
        add(Network.TELEGRAM, "!old");
        RoomArrivals.Counting c = arrivals.start(Network.TELEGRAM);
        arrivals.roomsChanged();
        add(Network.DISCORD, "!discord");
        add(Network.TELEGRAM, "!new1");
        add(Network.TELEGRAM, "!new2");
        assertEquals(2, c.newRooms());
        assertEquals(Set.of("!new1", "!new2"), c.rooms());
    }

    @Test
    void quietAfterFortyFiveSecondsWithoutANewRoom() throws Exception {
        RoomArrivals.Counting c = arrivals.start(Network.TELEGRAM);
        CompletableFuture<Integer> quiet = c.whenQuiet(QUIET, MAX);
        time.advance(Duration.ofSeconds(30));
        add(Network.TELEGRAM, "!a");
        time.advance(Duration.ofSeconds(15));
        assertFalse(quiet.isDone(), "45 s since asking, but only 15 s since the last room");
        time.advance(Duration.ofSeconds(29));
        assertFalse(quiet.isDone(), "44 s since the last room");
        time.advance(Duration.ofSeconds(1));
        assertEquals(1, quiet.get());
    }

    @Test
    void stopsAtFifteenMinutesWhileRoomsKeepComing() throws Exception {
        RoomArrivals.Counting c = arrivals.start(Network.TELEGRAM);
        CompletableFuture<Integer> quiet = c.whenQuiet(QUIET, MAX);
        for (int i = 0; i < 29; i++) {
            time.advance(Duration.ofSeconds(30));
            add(Network.TELEGRAM, "!r" + i);
        }
        assertFalse(quiet.isDone(), "14.5 minutes, rooms still coming");
        time.advance(Duration.ofSeconds(30));
        assertEquals(29, quiet.get(), "at 15 minutes it stops with what came");
    }
}
