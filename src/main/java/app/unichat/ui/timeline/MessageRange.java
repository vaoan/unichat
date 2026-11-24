package app.unichat.ui.timeline;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A stretch of a chat between two markers, "Desde aquí" and "Hasta aquí": everything between them is in, and nothing in
 * between can be left out. The first click is both ends; a click outside moves the end on its side, one inside the end
 * nearer it. Either end can be taken away (the range is the other one alone) and the next click puts it back. Ids in the
 * chat's order; pure.
 */
final class MessageRange {
    private @Nullable String start;
    private @Nullable String end;
    /** The chat's order when the ends were last placed: a message between them deleted later still leaves the rest in. */
    private List<String> order = List.of();

    @Nullable String start() { return start; }

    @Nullable String end() { return end; }

    boolean hasStart() { return start != null; }

    boolean hasEnd() { return end != null; }

    /** A click (or Space, Enter) on {@code id}, in {@code chat} as it is now. */
    void click(String id, List<String> chat) {
        order = List.copyOf(chat);
        int at = order.indexOf(id);
        if (at < 0) return;
        if (start == null && end == null) {
            start = id;
            end = id;
            return;
        }
        if (start == null || end == null) { // one taken away: put back, on whichever side it falls
            String other = start != null ? start : end;
            boolean above = at <= order.indexOf(other);
            start = above ? id : other;
            end = above ? other : id;
            return;
        }
        int s = order.indexOf(start);
        int e = order.indexOf(end);
        if (s < 0 || e < 0) { // its ends were deleted meanwhile: it starts again here
            start = id;
            end = id;
            return;
        }
        if (at < s) start = id;
        else if (at > e) end = id;
        else if (at - s <= e - at) start = id; // inside: the nearer end comes to it
        else end = id;
    }

    void removeStart() {
        start = null;
    }

    void removeEnd() {
        end = null;
    }

    /** The messages in it, of {@code chat} as it is now, in its order: those between the ends, still there. */
    List<String> of(List<String> chat) {
        String a = start != null ? start : end;
        String b = end != null ? end : start;
        if (a == null) return List.of();
        int s = order.indexOf(a);
        int e = order.indexOf(b);
        if (s < 0 || e < 0) return List.of();
        Set<String> between = new HashSet<>(order.subList(Math.min(s, e), Math.max(s, e) + 1));
        return chat.stream().filter(between::contains).toList();
    }

    void clear() {
        start = null;
        end = null;
        order = List.of();
    }
}
