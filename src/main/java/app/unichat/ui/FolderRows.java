package app.unichat.ui;

import app.unichat.service.Folder;
import app.unichat.model.Chat;
import app.unichat.model.Network;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What a folder shows: everything the person put in it that exists here. Pure (no JavaFX). */
final class FolderRows {
    private FolderRows() {}

    /**
     * The chats of one refresh with their servers' channels, shared by every folder: a tree covers every server of a
     * network, so it's built once and only if a folder holds one of its servers.
     */
    static final class Servers {
        private final List<Chat> all;
        private final Map<Network, List<SpaceTree.Node>> trees = new EnumMap<>(Network.class);
        private final Map<String, InboxRows.Rows> channels = new HashMap<>();

        Servers(List<Chat> all) { this.all = all; }

        InboxRows.Rows channels(Chat server) {
            return channels.computeIfAbsent(server.id(), id -> InboxRows.channels(tree(server.network()), server));
        }

        private List<SpaceTree.Node> tree(Network network) {
            return trees.computeIfAbsent(network, n -> SpaceTree.build(all, n, null, Map.of()));
        }

        int treesBuilt() { return trees.size(); }
    }

    /** Bridge bots, the personal space and invitations (no lasting identity yet) don't go in folders. */
    static boolean canHold(Chat c) {
        return c.prefKey() != null && !c.bridgeBot() && !c.personalSpace() && !c.invite();
    }

    static List<Chat> chatsIn(Folder folder, List<Chat> all) { return chatsIn(folder, new Servers(all)); }

    /** Archived chats stay: if it's in a folder, the person wants it there. Same order as the inbox. */
    static List<Chat> chatsIn(Folder folder, Servers servers) {
        Set<String> keys = new HashSet<>(folder.chatKeys());
        Map<String, Instant> activity = new HashMap<>();
        List<Chat> in = servers.all.stream().filter(c -> canHold(c) && keys.contains(c.prefKey())).toList();
        for (Chat c : in) {
            Chat newest = c.space() ? servers.channels(c).latest().get(c.id()) : null;
            if (newest != null) activity.put(c.id(), newest.lastActivity());
        }
        return in.stream().sorted(InboxRows.order(activity)).toList();
    }

    static long unread(Folder folder, List<Chat> all) { return unread(folder, new Servers(all)); }

    /** Chats with something unread, as the inbox counts them. A channel reached twice (alone and through its server) counts once. */
    static long unread(Folder folder, Servers servers) {
        Set<String> unread = new HashSet<>();
        for (Chat c : chatsIn(folder, servers)) {
            if (ChatFilter.inboxUnread(c)) unread.add(c.id());
            if (c.space() && !c.archived() && !c.muted() && !c.lowPriority()) {
                for (Chat channel : servers.channels(c).rows()) if (ChatFilter.inboxUnread(channel)) unread.add(channel.id());
            }
        }
        return unread.size();
    }
}
