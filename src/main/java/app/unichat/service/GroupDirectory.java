package app.unichat.service;

import app.unichat.model.Network;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Finding and joining groups and servers. */
public interface GroupDirectory {
    /** Joins a room (a channel or topic of a space you're not in yet). */
    CompletableFuture<Void> joinChat(String roomId);
    /** Servers/groups of {@code network} you could add ({@link #addGroup}); empty when the network has none. */
    CompletableFuture<List<ChatService.GroupOffer>> groupOffers(Network network);
    /** Brings one of {@link #groupOffers} into Unichat with all its channels. */
    default CompletableFuture<Void> addGroup(Network network, String offerId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("addGroup"));
    }
    /** Asks the bridge for every channel of server {@code spaceId} (not only those with messages); no-op elsewhere. */
    CompletableFuture<Void> loadAllChannels(String spaceId);
    /** Names of a space's direct children, including ones you haven't joined (room id → name). */
    CompletableFuture<java.util.Map<String, String>> spaceNames(String spaceId);
}
