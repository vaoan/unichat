package app.unichat.matrix;

import app.unichat.model.Network;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

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

    /** When set, the next look at the rooms waits here (a sync's change still in flight). */
    private volatile @Nullable CountDownLatch inFlight;
    private final CountDownLatch looking = new CountDownLatch(1);

    private Set<String> roomsOf(Network n) {
        CountDownLatch hold = inFlight;
        if (hold != null) {
            inFlight = null;
            looking.countDown();
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return Set.copyOf(rooms.getOrDefault(n, Set.of()));
    }

    private final RoomArrivals arrivals = new RoomArrivals(this::roomsOf, this::now, this::later);

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

    @Test
    void aChangeInFlightWhenCountingStopsTellsNobody() throws Exception {
        RoomArrivals.Counting c = arrivals.start(Network.TELEGRAM);
        List<Integer> told = new CopyOnWriteArrayList<>();
        c.onChange(() -> told.add(c.newRooms()));
        rooms.computeIfAbsent(Network.TELEGRAM, k -> ConcurrentHashMap.newKeySet()).add("!late");
        CountDownLatch release = new CountDownLatch(1);
        inFlight = release;
        CompletableFuture<Void> change = CompletableFuture.runAsync(arrivals::roomsChanged);
        looking.await(); // the change has started looking at the rooms
        c.stop();
        release.countDown();
        change.get();
        assertEquals(List.of(), told, "after stop, a change already under way says nothing");
        assertEquals(0, c.newRooms());
    }
}
