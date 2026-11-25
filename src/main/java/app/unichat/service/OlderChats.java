package app.unichat.service;

import app.unichat.model.Network;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asking the bridges for older chats they haven't brought into Matrix yet ("Load older chats"), one network at a time.
 * Only on demand: nothing here runs unless {@link #load} is called. The demo has none.
 */
public interface OlderChats {
    /** What one click would do for a network, for the warning sheet. chats: -1 means "all". */
    record Plan(Network network, int chats, boolean restarts, boolean reconnectsServers, boolean risky,
                @Nullable Duration waitFirst) {}

    /** The answer of one network's step. */
    sealed interface Answer {
        record Loaded(int chats) implements Answer {}
        record NothingMore() implements Answer {}
        record NotPossible(String reason) implements Answer {} // in the person's words (I18n)
    }

    /** Where a run is: which network, at what stage, N of M; the answers so far. */
    record Loading(Network network, Stage stage, int newChats, int done, int total, Map<Network, Answer> answers) {}
    enum Stage { RESTARTING, ASKING, ARRIVING, DONE }

    /** What a click would do for these networks, in the order they run; a network with nothing to do is left out. */
    default List<Plan> plans(Collection<Network> inView) { return List.of(); }

    /** Whether any of these networks has something left to load. */
    default boolean hasMore(Collection<Network> inView) { return false; }

    /** Runs one step for each network, one at a time, in order. Ignored (same future) while a run is under way. */
    default CompletableFuture<Map<Network, Answer>> load(List<Network> networks) { return CompletableFuture.completedFuture(Map.of()); }

    default @Nullable Loading loading() { return null; }
    default void addLoadingListener(Runnable l) { /* nothing by default */ }
    default void removeLoadingListener(Runnable l) { /* nothing by default */ }
}
