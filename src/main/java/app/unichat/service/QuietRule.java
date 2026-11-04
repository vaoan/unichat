package app.unichat.service;

import app.unichat.model.Chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Muted means archived (the user's rule): silencing a chat archives it, archiving silences it for good, and
 * bringing it back (unarchive or unmute) makes it notify again. A server/group takes its channels along; one
 * quiet channel doesn't move its server. Chats muted elsewhere (on the phone) get archived when they arrive.
 */
public final class QuietRule {
    private QuietRule() {}

    /** The chat and, for a server/group, every chat inside it (its joined channels, sub-groups included). */
    public static List<String> targets(ChatReader svc, String chatId) {
        Set<String> out = new LinkedHashSet<>();
        collect(svc, chatId, out);
        return new ArrayList<>(out);
    }

    private static void collect(ChatReader svc, String id, Set<String> out) {
        if (!out.add(id)) return;
        Chat c = svc.chat(id);
        if (c == null || !c.space()) return;
        for (String child : c.spaceChildren()) if (svc.chat(child) != null) collect(svc, child, out);
        for (Chat x : svc.chats()) if (x.spaceParents().contains(id)) collect(svc, x.id(), out);
    }

    /**
     * Chats to archive after a sync: muted now, not archived, and not muted before ({@code mutedBefore}), so a chat
     * the user is bringing back isn't re-archived while its mute is still being lifted.
     */
    public static List<String> toArchive(Set<String> mutedBefore, List<Chat> now) {
        List<String> out = new ArrayList<>();
        for (Chat c : now) if (c.muted() && !c.archived() && !mutedBefore.contains(c.id())) out.add(c.id());
        return out;
    }

    /**
     * Chats that arrived inside an archived server/group (a channel the bridge created after the server was archived):
     * they go quiet too, or they'd count as unread while hidden inside their server. Only chats not in
     * {@code knownBefore} (empty on the first sync: all), so a server the user is bringing back isn't fought over.
     */
    public static List<String> toInherit(Set<String> knownBefore, List<Chat> now) {
        Map<String, Chat> byId = new HashMap<>();
        Map<String, Set<String>> parents = new HashMap<>();
        for (Chat c : now) {
            byId.put(c.id(), c);
            parents.computeIfAbsent(c.id(), k -> new HashSet<>()).addAll(c.spaceParents());
            if (c.space()) for (String child : c.spaceChildren()) parents.computeIfAbsent(child, k -> new HashSet<>()).add(c.id());
        }
        List<String> out = new ArrayList<>();
        for (Chat c : now) {
            if (c.archived() || knownBefore.contains(c.id())) continue;
            if (archivedAncestor(c.id(), byId, parents, new HashSet<>())) out.add(c.id());
        }
        return out;
    }

    private static boolean archivedAncestor(String id, Map<String, Chat> byId, Map<String, Set<String>> parents, Set<String> seen) {
        for (String p : parents.getOrDefault(id, Set.of())) {
            if (!seen.add(p)) continue;
            Chat pc = byId.get(p);
            if (pc != null && pc.archived()) return true;
            if (archivedAncestor(p, byId, parents, seen)) return true;
        }
        return false;
    }
}
