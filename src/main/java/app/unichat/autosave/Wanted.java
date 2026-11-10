package app.unichat.autosave;

import app.unichat.model.Message;
import app.unichat.model.MessageStatus;
import app.unichat.model.SaveKind;
import app.unichat.service.ChatPreferences;

import java.util.Set;

/**
 * What a chat saves: of what others send ({@code theirs}) and of what I send ({@code mine}), each with its own kinds.
 * One rule for saving as it arrives and for exploring the past. Pure.
 */
record Wanted(Set<SaveKind> theirs, Set<SaveKind> mine) {
    static Wanted of(ChatPreferences prefs, String chatId) {
        return new Wanted(prefs.autoSaves(chatId, false), prefs.autoSaves(chatId, true));
    }

    /**
     * The kinds to save of {@code m}, by who sent it; empty: nothing. What I send counts once the server has it: while
     * it's being sent it has a temporary id, and saving it then saved it twice (that id, then its real one).
     */
    Set<SaveKind> of(Message m) {
        if (!m.outgoing()) return theirs;
        return m.status() == MessageStatus.SENDING || m.status() == MessageStatus.FAILED ? Set.of() : mine;
    }

    boolean none() {
        return theirs.isEmpty() && mine.isEmpty();
    }
}
