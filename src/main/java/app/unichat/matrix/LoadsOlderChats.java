package app.unichat.matrix;

import app.unichat.model.Network;
import app.unichat.service.OlderChats;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** {@link OlderChats} handed to an {@link OlderChatLoader}, so the service only says which loader is its. */
@SuppressWarnings("PMD.ImplicitFunctionalInterface") // a role a service takes on, never a lambda
interface LoadsOlderChats extends OlderChats {
    OlderChatLoader olderChatLoader();

    @Override default List<Plan> plans(Collection<Network> inView) { return olderChatLoader().plans(inView); }

    @Override default boolean hasMore(Collection<Network> inView) { return olderChatLoader().hasMore(inView); }

    /** Instagram is in {@code networks} only when ticked in the sheet: the sheet is what leaves it out (Task 8's test). */
    @Override default CompletableFuture<Map<Network, Answer>> load(List<Network> networks) {
        return olderChatLoader().load(networks);
    }

    @Override default @Nullable Loading loading() { return olderChatLoader().loading(); }

    @Override default void addLoadingListener(Runnable l) { olderChatLoader().addListener(l); }

    @Override default void removeLoadingListener(Runnable l) { olderChatLoader().removeListener(l); }
}
