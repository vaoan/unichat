package app.unichat.service;

import app.unichat.model.Chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Muted means archived (the user's rule): silencing a chat archives it, archiving silences it for good, and
 * bringing it back (unarchive or unmute) makes it notify again. A server/group takes its channels along; one
 * quiet channel doesn't move its server. Chats muted elsewhere (on the phone) get archived when they arrive.
 */
public final class QuietRule {
    private QuietRule() {}

    /** The chat and, for a server/group, every chat inside it (its joined channels, sub-groups included). */
    public static List<String> targets(ChatService svc, String chatId) {
        Set<String> out = new LinkedHashSet<>();
        collect(svc, chatId, out);
        return new ArrayList<>(out);
    }

    private static void collect(ChatService svc, String id, Set<String> out) {
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
}
