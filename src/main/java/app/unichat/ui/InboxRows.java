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
 * The rows of the inbox list: chats plus servers/groups (Matrix spaces), each server where its newest channel
 * would be. An opened server lists its channels under it (they leave their own place meanwhile, so nothing shows
 * twice); channels not joined yet come as placeholder chats. Pure (no JavaFX) so it's unit tested.
 */
public final class InboxRows {
    /**
     * {@code depth}: indentation of rows inside an opened server (absent = 0). {@code unjoined}: placeholder rows for
     * channels you aren't in. {@code unread}/{@code latest}: per server, unread messages and its newest channel.
     */
    public record Rows(List<Chat> rows, Map<String, Integer> depth, Set<String> unjoined, Map<String, Integer> unread,
                       Map<String, Chat> latest) {}

    private InboxRows() {}

    public static Rows build(List<Chat> all, ChatFilter f, Set<String> expanded, Map<String, String> names) {
        Set<Network> networks = new LinkedHashSet<>();
        if (f.network() != null) networks.add(f.network());
        else for (Chat c : all) if (c.space() && !c.personalSpace()) networks.add(c.network());

        Map<String, Integer> unread = new HashMap<>();
        Map<String, Chat> latest = new HashMap<>();
        Map<String, Instant> activity = new HashMap<>();
        List<SpaceTree.Node> servers = new ArrayList<>();
        for (Network n : networks) {
            for (SpaceTree.Node node : SpaceTree.build(all, n, f.accountId(), names)) {
                if (node.loose() || node.chat() == null) continue;
                describe(node, unread, latest, activity);
                if (matches(f, node, unread.get(node.id()))) servers.add(node);
            }
        }

        // Channels of an opened server are listed under it, not in their own place.
        Set<String> underServer = new HashSet<>();
        for (SpaceTree.Node s : servers) if (expanded.contains(s.id())) collectShown(s, expanded, underServer);

        List<Chat> top = new ArrayList<>();
        for (Chat c : f.apply(all)) if (!underServer.contains(c.id())) top.add(c);
        Map<String, SpaceTree.Node> serverById = new HashMap<>();
        for (SpaceTree.Node s : servers) {
            top.add(s.chat());
            serverById.put(s.id(), s);
        }
        top.sort(Comparator.comparing(Chat::pinned).reversed()
                .thenComparing((Chat c) -> activity.getOrDefault(c.id(), c.lastActivity()), Comparator.reverseOrder())
                .thenComparing((Chat c) -> !c.space())
                .thenComparing(Chat::title));

        List<Chat> rows = new ArrayList<>();
        Map<String, Integer> depth = new HashMap<>();
        Set<String> unjoined = new HashSet<>();
        for (Chat c : top) {
            rows.add(c);
            SpaceTree.Node s = serverById.get(c.id());
            if (s != null && expanded.contains(s.id())) addChildren(s, 1, expanded, rows, depth, unjoined);
        }
        return new Rows(rows, depth, unjoined, unread, latest);
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

    private static void collectShown(SpaceTree.Node s, Set<String> expanded, Set<String> out) {
        for (SpaceTree.Node c : s.children()) {
            out.add(c.id());
            if (c.container() && expanded.contains(c.id())) collectShown(c, expanded, out);
        }
    }

    private static void addChildren(SpaceTree.Node s, int level, Set<String> expanded, List<Chat> rows,
                                    Map<String, Integer> depth, Set<String> unjoined) {
        for (SpaceTree.Node c : s.children()) {
            Chat row = c.chat();
            if (row == null) {
                row = new Chat(c.id());
                row.setTitle(c.name());
                row.setNetwork(s.chat().network());
                unjoined.add(c.id());
            }
            rows.add(row);
            depth.put(c.id(), level);
            if (c.container() && expanded.contains(c.id())) addChildren(c, level + 1, expanded, rows, depth, unjoined);
        }
    }
}
