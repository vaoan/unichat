package app.unichat.matrix;

import app.unichat.model.Chat;
import app.unichat.service.ChatKeys;
import app.unichat.service.PreferencesFile;
import app.unichat.service.PreferencesFile.Effective;
import app.unichat.service.PreferencesFile.ServerValues;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Between what the server says about each room and {@link PreferencesFile}, which decides: every chat shows what the
 * file says. The mute rule is the one thing that goes back (unread counts only come from the server), so this also
 * tracks which rule the server has and the writes on their way. Guarded by the processor's lock.
 */
final class RoomPreferences {
    private PreferencesFile file = PreferencesFile.inMemory();
    /** Rooms with a silent push rule; means nothing until {@link #rulesHeard}. */
    private Set<String> mutedRooms = new HashSet<>();
    private boolean rulesHeard;
    /** Mute writes queued or running, by room. */
    private final Map<String, Integer> writing = new HashMap<>();
    private final Map<String, Retry> retries = new HashMap<>();

    void use(PreferencesFile preferences) { file = preferences; }

    void hearRules(Set<String> muted) {
        mutedRooms = muted;
        rulesHeard = true;
    }

    /** What the cached sync said counts for nothing: only a real full sync comes next. */
    void forgetHeard(Collection<MatrixRoom> rooms) {
        mutedRooms = new HashSet<>();
        rulesHeard = false;
        for (MatrixRoom room : rooms) {
            room.serverTags = null;
            room.serverReminder = null;
            room.reminderKnown = false;
        }
    }

    /**
     * Brings every chat in line with the file; true if a chat changed. {@code serverAnswered} is false for a replayed
     * cache: old news is only previewed and must not change the file; it counts once the catch-up has what came after it.
     */
    boolean settle(Collection<MatrixRoom> rooms, boolean serverAnswered) {
        boolean[] changed = {false};
        file.batch(() -> {
            for (MatrixRoom room : rooms) {
                String key = keyOf(room);
                Effective e = serverAnswered ? file.reconcile(key, heard(room)) : file.preview(key, heard(room));
                if (show(room.chat, e)) changed[0] = true;
            }
        });
        return changed[0];
    }

    /** The room's key now; a chat whose bridge state just arrived takes its entry and folders along. */
    String keyOf(MatrixRoom room) {
        String key = ChatKeys.of(room.bridgeProtocol, room.bridgeChannelId, room.id);
        String old = room.chat.prefKey();
        if (old != null && !old.equals(key)) file.rekey(old, key);
        room.chat.setPrefKey(key);
        return key;
    }

    /**
     * After a change from outside /sync. A preview, so the rest of the chat stays as shown if the server hasn't answered
     * yet; once it has, every value heard is in the file and this is just the file.
     */
    void show(MatrixRoom room) { show(room.chat, file.preview(keyOf(room), heard(room))); }

    private static boolean show(Chat c, Effective e) {
        boolean same = c.archived() == e.archived() && c.muted() == e.muted() && c.favorite() == e.favorite()
                && c.lowPriority() == e.lowPriority() && c.pinned() == e.pinned() && Objects.equals(c.reminderAt(), e.reminder());
        if (same) return false;
        c.setArchived(e.archived());
        c.setMuted(e.muted());
        c.setFavorite(e.favorite());
        c.setLowPriority(e.lowPriority());
        c.setPinned(e.pinned());
        c.setReminderAt(e.reminder());
        return true;
    }

    private ServerValues heard(MatrixRoom room) {
        return new ServerValues(tag(room, SyncProcessor.TAG_ARCHIVED), rulesHeard ? mutedRooms.contains(room.id) : null,
                tag(room, "m.favourite"), tag(room, "m.lowpriority"), tag(room, SyncProcessor.TAG_PINNED),
                room.serverReminder, room.reminderKnown);
    }

    private static Boolean tag(MatrixRoom room, String name) {
        return room.serverTags == null ? null : room.serverTags.contains(name);
    }

    // ---------------------------------------------------------------- the mute rule

    /**
     * Rooms whose rule differs from the file, to the value the file has. After reconciling, a value that merely follows
     * the server can only differ if this server never said it (copied from another installation): that needs applying
     * too, and what the phone does here is already in the file, so it's never pushed back. Left out: rooms being
     * written, and rooms whose last write failed until their retry time.
     */
    Map<String, Boolean> mutesToApply(Collection<MatrixRoom> rooms, Instant now) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (!rulesHeard) return out;
        for (MatrixRoom room : rooms) {
            if (room.invite || writing.containsKey(room.id)) continue;
            Retry retry = retries.get(room.id);
            if (retry != null && !retry.due(now)) continue;
            boolean wanted = file.current(keyOf(room)).muted();
            if (wanted != mutedRooms.contains(room.id)) out.put(room.id, wanted);
        }
        return out;
    }

    void writeStarted(String roomId) { writing.merge(roomId, 1, Integer::sum); }

    /** A write that worked counts as heard, so the sync before its echo doesn't ask for it again. */
    void writeFinished(String roomId, boolean muted, boolean ok, Instant now) {
        writing.computeIfPresent(roomId, (k, n) -> n > 1 ? n - 1 : null);
        if (ok) {
            if (muted) mutedRooms.add(roomId); else mutedRooms.remove(roomId);
            retries.remove(roomId);
            return;
        }
        retries.put(roomId, Retry.after(retries.get(roomId), now));
    }
}
