package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Network;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rows of the inbox list, Telegram-forum style: a server/group (Matrix space) is one row, placed at its newest
 * channel's time with their unread total, and its channels live inside it ({@link #channels}) instead of in the
 * list. A search lists channels too, and archived channels go to the archive on their own. Pure (no JavaFX).
 */
public final class InboxRows {
    /**
     * {@code unjoined}: placeholder rows for channels you aren't in. {@code unread}/{@code latest}: per server, its
     * unread messages and its newest channel.
     */
    public record Rows(List<Chat> rows, Set<String> unjoined, Map<String, Integer> unread, Map<String, Chat> latest) {
        public static final Rows EMPTY = new Rows(List.of(), Set.of(), Map.of(), Map.of());
    }

    private InboxRows() {}

    public static Rows build(List<Chat> all, ChatFilter f, Map<String, String> names) {
        Set<Network> networks = new LinkedHashSet<>();
        if (f.network() != null) networks.add(f.network());
        else for (Chat c : all) if (c.space() && !c.personalSpace()) networks.add(c.network());

        Map<String, Integer> unread = new HashMap<>();
        Map<String, Chat> latest = new HashMap<>();
        Map<String, Instant> activity = new HashMap<>();
        Set<String> inServer = new HashSet<>();
        List<Chat> top = new ArrayList<>();
        for (Network n : networks) {
            for (SpaceTree.Node node : SpaceTree.build(all, n, f.accountId(), names)) {
                if (node.loose() || node.chat() == null) continue;
                describe(node, unread, latest, activity);
                collect(node, inServer);
                if (matches(f, node, unread.get(node.id()))) top.add(node.chat());
            }
        }
        // A channel lives in its server, except in a search and in the archive (where archived channels go).
        boolean flat = f.searching() || f.view() == ChatFilter.View.ARCHIVE;
        for (Chat c : f.apply(all)) if (flat || !inServer.contains(c.id())) top.add(c);
        top.sort(Comparator.comparing(Chat::pinned).reversed()
                .thenComparing((Chat c) -> activity.getOrDefault(c.id(), c.lastActivity()), Comparator.reverseOrder())
                .thenComparing((Chat c) -> !c.space())
                .thenComparing(Chat::title));
        return new Rows(top, Set.of(), unread, latest);
    }

    /** The channels of server {@code spaceId}, newest first, then the ones you could join (and sub-groups' channels). */
    public static Rows channels(List<Chat> all, String spaceId, String accountId, Map<String, String> names) {
        Chat space = all.stream().filter(c -> c.id().equals(spaceId)).findFirst().orElse(null);
        if (space == null) return Rows.EMPTY;
        SpaceTree.Node node = find(SpaceTree.build(all, space.network(), accountId, names), spaceId);
        if (node == null) return Rows.EMPTY;
        List<Chat> joined = new ArrayList<>();
        List<Chat> toJoin = new ArrayList<>();
        Set<String> unjoined = new HashSet<>();
        leaves(node, space.network(), joined, toJoin, unjoined);
        joined.sort(ChatFilter.ORDER);
        List<Chat> rows = new ArrayList<>(joined);
        rows.addAll(toJoin);
        Map<String, Integer> unread = new HashMap<>();
        Map<String, Chat> latest = new HashMap<>();
        describe(node, unread, latest, new HashMap<>());
        return new Rows(rows, unjoined, unread, latest);
    }

    private static SpaceTree.Node find(List<SpaceTree.Node> nodes, String id) {
        for (SpaceTree.Node n : nodes) {
            if (n.id().equals(id)) return n;
            SpaceTree.Node f = find(n.children(), id);
            if (f != null) return f;
        }
        return null;
    }

    private static void leaves(SpaceTree.Node node, Network network, List<Chat> joined, List<Chat> toJoin, Set<String> unjoined) {
        for (SpaceTree.Node c : node.children()) {
            if (c.container()) {
                leaves(c, network, joined, toJoin, unjoined);
            } else if (c.chat() != null) {
                joined.add(c.chat());
            } else {
                Chat p = new Chat(c.id());
                p.setTitle(c.name());
                p.setNetwork(network);
                toJoin.add(p);
                unjoined.add(c.id());
            }
        }
    }

    private static boolean matches(ChatFilter f, SpaceTree.Node node, int unread) {
        Chat s = node.chat();
        if (f.searching()) return ChatFilter.normalize(s.title()).contains(ChatFilter.normalize(f.query()));
        if (f.unreadOnly() && (unread == 0 || s.muted())) return false;
        return switch (f.view()) {
            case INBOX -> !s.archived() && !s.lowPriority();
            case LOW_PRIORITY -> !s.archived() && s.lowPriority();
            case ARCHIVE -> s.archived();
            case FAVORITES -> s.favorite() && !s.archived();
        };
    }

    private static void collect(SpaceTree.Node node, Set<String> out) {
        for (SpaceTree.Node c : node.children()) {
            out.add(c.id());
            if (c.container()) collect(c, out);
        }
    }

    /** Unread count, newest channel and its time for a server and every server inside it. */
    private static Chat describe(SpaceTree.Node node, Map<String, Integer> unread, Map<String, Chat> latest,
                                 Map<String, Instant> activity) {
        Chat newest = null;
        for (SpaceTree.Node c : node.children()) {
            Chat candidate = c.container() ? describe(c, unread, latest, activity) : c.chat();
            if (candidate != null && (newest == null || candidate.lastActivity().isAfter(newest.lastActivity()))) newest = candidate;
        }
        unread.put(node.id(), SpaceTree.unread(node));
        if (newest != null) {
            latest.put(node.id(), newest);
            activity.put(node.id(), newest.lastActivity());
        }
        return newest;
    }
}
