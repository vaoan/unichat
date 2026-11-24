package app.unichat.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A person on a bridged network, from the bridge's contact list or search; {@code mxid} null = not known on Matrix,
 * {@code dmRoomId} null = no chat yet.
 */
public record Contact(String id, String name, List<String> identifiers, @Nullable String mxid, @Nullable String dmRoomId) {
    public Contact {
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
    }
}
