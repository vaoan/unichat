package app.unichat.matrix;

import app.unichat.model.Network;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Counts the rooms of one network that weren't there when a step began, as the sync brings them ("Load older chats").
 * A step is over when no new room came for a while, or after a longest wait: bridges never say when they're done.
 */
final class RoomArrivals {
    /** One network's count, from {@link #start} until {@link #stop}. */
    interface Counting {
        int newRooms();

        /** Completes with {@link #newRooms} once {@code quiet} passed without a new room, or {@code max} after it was asked. */
        CompletableFuture<Integer> whenQuiet(Duration quiet, Duration max);

        /** The rooms counted so far. */
        Set<String> rooms();

        /** Told (on the thread that saw it) each time the network's rooms changed while counting, a new one or not. */
        void onChange(Runnable listener);

        /** Stops counting; {@link #whenQuiet} completes with what it has. */
        void stop();
    }

    private final Function<Network, Set<String>> rooms;
    private final Supplier<Instant> now;
    private final BiConsumer<Duration, Runnable> later;
    private final List<Count> counting = new CopyOnWriteArrayList<>();

    /** rooms: the network's room ids now (bridge-bot rooms excluded); later: schedules a check (tests run it by hand). */
    RoomArrivals(Function<Network, Set<String>> rooms, Supplier<Instant> now, BiConsumer<Duration, Runnable> later) {
        this.rooms = rooms;
        this.now = now;
        this.later = later;
    }

    /** A snapshot of the network's rooms now: only rooms that come after it count. */
    Counting start(Network n) {
        Count c = new Count(n, Set.copyOf(rooms.apply(n)), now.get());
        counting.add(c);
        return c;
    }

    /** From {@code MatrixChatService.dispatch} when chats changed; nothing to do while nothing is counting. */
    void roomsChanged() {
        for (Count c : counting) c.changed();
    }

    private final class Count implements Counting {
        private final Network network;
        private final Set<String> before;
        private final Set<String> arrived = new HashSet<>();
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Integer> quiet = new CompletableFuture<>();
        private Instant lastArrival;
        private Instant asked = Instant.MAX;
        private Duration quietFor = Duration.ZERO;
        private Duration max = Duration.ZERO;
        private boolean waiting;
        /** Once stopped, a change still under way tells nobody: no progress after the step is done. */
        private boolean stopped;

        Count(Network network, Set<String> before, Instant at) {
            this.network = network;
            this.before = before;
            this.lastArrival = at;
        }

        void changed() {
            Set<String> nowThere = rooms.apply(network);
            synchronized (this) {
                if (stopped) return;
                for (String id : nowThere) {
                    if (!before.contains(id) && arrived.add(id)) lastArrival = now.get();
                }
                // Under the lock, so stop() returns only once no listener is running and none will.
                for (Runnable l : listeners) l.run();
            }
        }

        @Override public synchronized int newRooms() { return arrived.size(); }

        @Override public synchronized Set<String> rooms() { return Set.copyOf(arrived); }

        @Override public void onChange(Runnable listener) { listeners.add(listener); }

        @Override
        public CompletableFuture<Integer> whenQuiet(Duration quietWanted, Duration longest) {
            synchronized (this) {
                if (waiting) return quiet;
                waiting = true;
                quietFor = quietWanted;
                max = longest;
                asked = now.get();
                if (lastArrival.isBefore(asked)) lastArrival = asked;
            }
            check();
            return quiet;
        }

        /** Completes when it's quiet or the longest wait is over, else looks again when it next could be. */
        private void check() {
            @Nullable Duration next;
            synchronized (this) {
                if (quiet.isDone()) return;
                Instant t = now.get();
                Duration sinceLast = Duration.between(lastArrival, t);
                Duration sinceAsked = Duration.between(asked, t);
                if (sinceLast.compareTo(quietFor) < 0 && sinceAsked.compareTo(max) < 0) {
                    Duration toQuiet = quietFor.minus(sinceLast);
                    Duration toMax = max.minus(sinceAsked);
                    next = toQuiet.compareTo(toMax) < 0 ? toQuiet : toMax;
                } else {
                    next = null;
                }
            }
            if (next != null) {
                later.accept(next, this::check);
                return;
            }
            finish();
        }

        @Override
        public void stop() {
            counting.remove(this);
            finish();
        }

        private void finish() {
            synchronized (this) {
                stopped = true;
            }
            counting.remove(this);
            quiet.complete(newRooms());
        }
    }
}
