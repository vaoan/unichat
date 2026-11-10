package app.unichat.autosave;

import app.unichat.model.Message;
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

    /** The kinds to save of {@code m}, by who sent it; empty: nothing. */
    Set<SaveKind> of(Message m) {
        return m.outgoing() ? mine : theirs;
    }

    boolean none() {
        return theirs.isEmpty() && mine.isEmpty();
    }
}
