package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.model.Network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The servers/groups of one network (Matrix spaces) with their channels/topics, and the chats that
 * hang from no group in "Chats directos y otros". The bridge's personal folder is not a group: it's every chat.
 * Pure (no JavaFX) so it's unit tested.
 */
public final class SpaceTree {
    public static final String LOOSE = "Chats directos y otros";
    static final String LOOSE_ID = "loose";

    private SpaceTree() {}

    /** {@code chat} null: a child you haven't joined yet (its name comes from the space hierarchy), or the loose group. */
    public record Node(String id, Chat chat, String name, List<Node> children) {
        public boolean loose() { return LOOSE_ID.equals(id); }
        public boolean joined() { return chat != null; }
        public boolean container() { return loose() || (chat != null && chat.space()); }
    }

    public static List<Node> build(List<Chat> all, Network network, Map<String, String> names) {
        return build(all, network, null, names);
    }

    /**
     * {@code accountId}: only that account's chats (the account chips), and only groups that still have one of
     * them; null = every account. {@code names}: room id → name for children not joined yet (from {@code /hierarchy}).
     */
    public static List<Node> build(List<Chat> all, Network network, String accountId, Map<String, String> names) {
        Map<String, Chat> byId = new HashMap<>();
        Map<String, List<String>> declaredChildren = new HashMap<>();
        for (Chat c : all) {
            if (c.network() != network || c.bridgeBot()) continue;
            if (accountId != null && !c.space() && c.accountId() != null && !accountId.equals(c.accountId())) continue;
            byId.put(c.id(), c);
        }
        for (Chat c : byId.values()) {
            for (String p : c.spaceParents()) declaredChildren.computeIfAbsent(p, k -> new ArrayList<>()).add(c.id());
        }
        List<Chat> spaces = byId.values().stream().filter(c -> c.space() && !c.personalSpace())
                .sorted(Comparator.comparing(Chat::title, String.CASE_INSENSITIVE_ORDER)).toList();
        Set<String> childOfGroup = new HashSet<>();
        for (Chat s : spaces) childOfGroup.addAll(childIds(s, declaredChildren));

        Set<String> placed = new HashSet<>();
        List<Node> roots = new ArrayList<>();
        for (Chat s : spaces) {
            if (!childOfGroup.contains(s.id())) roots.add(node(s, byId, declaredChildren, names, new LinkedHashSet<>(), placed));
        }
        // Spaces that are only each other's children (a cycle) still need a way in.
        for (Chat s : spaces) {
            if (!placed.contains(s.id())) roots.add(node(s, byId, declaredChildren, names, new LinkedHashSet<>(), placed));
        }
        if (accountId != null) roots.removeIf(r -> !hasJoinedChat(r)); // another account's group
        List<Node> loose = byId.values().stream()
                .filter(c -> !c.space() && !c.archived() && !placed.contains(c.id()))
                .sorted(ChatFilter.ORDER)
                .map(c -> new Node(c.id(), c, c.title(), List.of())).toList();
        if (!loose.isEmpty()) roots.add(new Node(LOOSE_ID, null, LOOSE, loose));
        return roots;
    }

    /**
     * The server (Discord server, Telegram forum, any group space that isn't the bridge's own folder) that
     * {@code chat} belongs to, or null: either side may declare it (the space lists the chat, or the chat names its
     * parent). The closest one when they nest.
     */
    public static Chat serverOf(Chat chat, java.util.Collection<Chat> all) {
        if (chat == null || chat.space()) return null;
        for (Chat s : all) {
            if (!s.space() || s.personalSpace() || s.network() != chat.network()) continue;
            if (s.spaceChildren().contains(chat.id()) || chat.spaceParents().contains(s.id())) return s;
        }
        return null;
    }

    private static List<String> childIds(Chat space, Map<String, List<String>> declaredChildren) {
        Set<String> ids = new LinkedHashSet<>(space.spaceChildren());
        ids.addAll(declaredChildren.getOrDefault(space.id(), List.of()));
        return new ArrayList<>(ids);
    }

    private static Node node(Chat space, Map<String, Chat> byId, Map<String, List<String>> declared, Map<String, String> names,
                             Set<String> path, Set<String> placed) {
        path.add(space.id());
        placed.add(space.id());
        List<Node> children = new ArrayList<>();
        for (String id : childIds(space, declared)) {
            if (path.contains(id)) continue; // a cycle: don't walk back up
            Chat c = byId.get(id);
            if (c == null) {
                children.add(new Node(id, null, names.getOrDefault(id, "Chat sin unirte"), List.of()));
            } else if (c.space()) {
                if (!c.personalSpace()) children.add(node(c, byId, declared, names, new LinkedHashSet<>(path), placed));
            } else if (!c.archived()) {
                placed.add(id);
                children.add(new Node(id, c, c.title(), List.of()));
            }
        }
        return new Node(space.id(), space, space.title(), children);
    }

    private static boolean hasJoinedChat(Node n) {
        if (n.chat() != null && !n.chat().space()) return true;
        for (Node c : n.children()) if (hasJoinedChat(c)) return true;
        return false;
    }

    /** Unread messages in a node: the same "demands attention" rule as the inbox (no muted/archived/low priority). */
    public static int unread(Node n) {
        int sum = 0;
        if (n.chat() != null && !n.chat().space() && ChatFilter.inboxUnread(n.chat())) sum += Math.max(1, n.chat().unreadCount());
        for (Node c : n.children()) sum += unread(c);
        return sum;
    }
}
