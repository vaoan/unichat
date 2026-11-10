package app.unichat.service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/** What you change about a chat. */
public interface ChatPreferences {
    /** Sends a read receipt for the newest message and clears unread/marked-unread. */
    void markRead(String chatId);
    CompletableFuture<Void> setMarkedUnread(String chatId, boolean unread);
    CompletableFuture<Void> setFavorite(String chatId, boolean favorite);
    CompletableFuture<Void> setLowPriority(String chatId, boolean lowPriority);
    /** What to save of what others send in this chat (Unichat's own choice: no network has it); empty: nothing. */
    CompletableFuture<Void> setAutoSave(String chatId, java.util.Set<app.unichat.model.SaveKind> kinds);
    java.util.Set<app.unichat.model.SaveKind> autoSaves(String chatId);
    /** The profile hides what was shared in this chat (Unichat's own choice). */
    CompletableFuture<Void> setMediaHidden(String chatId, boolean hidden);
    boolean mediaHidden(String chatId);
    CompletableFuture<Void> setArchived(String chatId, boolean archived);
    CompletableFuture<Void> setMuted(String chatId, boolean muted);
    CompletableFuture<Void> setPinned(String chatId, boolean pinned);
    /** null clears the reminder. */
    CompletableFuture<Void> setReminder(String chatId, Instant at);
    CompletableFuture<Void> leaveChat(String chatId);
}
