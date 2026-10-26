package app.unichat.model;

import java.util.List;

/** A person on a bridged network, from the bridge's contact list or search; {@code dmRoomId} null = no chat yet. */
public record Contact(String id, String name, List<String> identifiers, String mxid, String dmRoomId) {
    public Contact {
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
    }
}
