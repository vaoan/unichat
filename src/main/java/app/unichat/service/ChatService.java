package app.unichat.service;

import app.unichat.model.Account;
import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Message;
import app.unichat.model.Network;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Backend the UI talks to. Implementations: {@link MockChatService} (demo data) and
 * {@link app.unichat.matrix.MatrixChatService} (Matrix homeserver + mautrix bridges).
 *
 * <p>Threading: methods may be called from the JavaFX thread and must not block it (network work
 * goes to a background executor and returns a future). Listener callbacks may arrive on any
 * thread; the UI marshals them with {@code Platform.runLater}.
 *
 * <p>Model objects returned here are live: the service mutates them and then notifies listeners.
 */
public interface ChatService {

    interface Listener {
        /** Chat list membership, order or any chat field changed. */
        default void chatsChanged() {}
        /** Messages of one chat were added, changed or removed. */
        default void messagesChanged(String chatId) {}
        default void accountsChanged() {}
        default void connectionChanged(ConnectionState state) {}
        /** A new incoming message that should produce a desktop notification (respects mute). */
        default void incomingMessage(Chat chat, Message message) {}
        /** A chat reminder came due. */
        default void reminderDue(Chat chat) {}
    }

    void addListener(Listener listener);
    void removeListener(Listener listener);

    /** Starts syncing. Safe to call once; data appears through listener callbacks. */
    void start();
    /** Stops background work (app exit). Does not log out. */
    void stop();
    /** Ends the session on the server and forgets stored credentials. */
    CompletableFuture<Void> logout();

    ConnectionState connectionState();
    String myUserId();
    String myDisplayName();

    /** All chats, including archived and bridge-bot chats; the UI filters and sorts. */
    List<Chat> chats();
    Chat chat(String chatId);

    /** Messages loaded so far for a chat, oldest first. */
    List<Message> messages(String chatId);
    /** Loads earlier history; completes with false when the start of the chat was reached. */
    CompletableFuture<Boolean> loadOlder(String chatId);

    CompletableFuture<Void> sendText(String chatId, String text, String replyToId);
    CompletableFuture<Void> sendFile(String chatId, Path file, String replyToId);
    CompletableFuture<Void> editMessage(String chatId, String messageId, String newText);
    CompletableFuture<Void> deleteMessage(String chatId, String messageId);
    /** Adds the reaction if the user has not reacted with this emoji, otherwise removes it. */
    CompletableFuture<Void> toggleReaction(String chatId, String messageId, String emoji);
    void sendTyping(String chatId, boolean typing);

    /** Sends a read receipt for the newest message and clears unread/marked-unread. */
    void markRead(String chatId);
    CompletableFuture<Void> setMarkedUnread(String chatId, boolean unread);
    CompletableFuture<Void> setFavorite(String chatId, boolean favorite);
    CompletableFuture<Void> setLowPriority(String chatId, boolean lowPriority);
    CompletableFuture<Void> setArchived(String chatId, boolean archived);
    CompletableFuture<Void> setMuted(String chatId, boolean muted);
    CompletableFuture<Void> setPinned(String chatId, boolean pinned);
    /** null clears the reminder. */
    CompletableFuture<Void> setReminder(String chatId, Instant at);
    CompletableFuture<Void> leaveChat(String chatId);

    /**
     * Returns a local file for a media/avatar URL (mxc://, http(s)://, file:), downloading and
     * caching it if needed. Thumbnails: pass width/height > 0 to request a scaled version.
     */
    CompletableFuture<Path> media(String url, int width, int height);

    /** Connected network accounts, one per bridge login. */
    List<Account> accounts();
    /** Networks the backend can connect (bridges installed on the server). */
    List<Network> availableNetworks();
    /**
     * Opens (or creates) the management chat with the network's bridge bot and starts its login
     * flow; completes with that chat's id. The UI shows the chat so the user can follow the
     * bot's instructions (QR code, phone number, token...).
     */
    CompletableFuture<String> connectNetwork(Network network);
    CompletableFuture<Void> disconnectNetwork(Network network);
}
