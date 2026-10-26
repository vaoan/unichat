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

    /**
     * Starts the login flow for one more account on a network that may already have one; completes
     * with the bridge-bot chat id. Bridges that support several logins add a new one.
     */
    default CompletableFuture<String> addAccount(Network network) {
        return connectNetwork(network);
    }

    /** Logs out one specific account; the default logs out the whole network. */
    default CompletableFuture<Void> disconnectAccount(Account account) {
        return disconnectNetwork(account.network());
    }

    /** Sends a favourite/recent sticker; {@code item.info()} lets the bridge resend the native sticker. */
    default CompletableFuture<Void> sendSticker(String chatId, app.unichat.model.MediaLibrary.Item item) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Stickers no disponibles"));
    }

    /** What "Nuevo chat" can do for an account: list its contacts, search the network. */
    record ContactSupport(boolean list, boolean search) {}

    default CompletableFuture<ContactSupport> contactSupport(app.unichat.model.Account account) {
        return CompletableFuture.completedFuture(new ContactSupport(false, false));
    }

    default CompletableFuture<List<app.unichat.model.Contact>> contacts(app.unichat.model.Account account) {
        return CompletableFuture.completedFuture(List.of());
    }

    /** Fails with UnsupportedOperationException when the network has no user search. */
    default CompletableFuture<List<app.unichat.model.Contact>> searchContacts(app.unichat.model.Account account, String query) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("search"));
    }

    /** A phone number, @username…; completes with null when nobody matches. */
    default CompletableFuture<app.unichat.model.Contact> resolveContact(app.unichat.model.Account account, String identifier) {
        return CompletableFuture.completedFuture(null);
    }

    /** The room of the DM with {@code contact}, creating it when there's none yet. */
    default CompletableFuture<String> openDm(app.unichat.model.Account account, app.unichat.model.Contact contact) {
        return contact.dmRoomId() != null ? CompletableFuture.completedFuture(contact.dmRoomId())
                : CompletableFuture.failedFuture(new UnsupportedOperationException("openDm"));
    }

    /** Asks a bridge to list your chats again (Telegram: chats beyond the login-time limit); the bridge's answer. */
    default CompletableFuture<String> syncChats(Network network) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("syncChats"));
    }

    /** Joins a room (a channel or topic of a space you're not in yet). */
    default CompletableFuture<Void> joinChat(String roomId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("joinChat"));
    }

    /** Names of a space's direct children, including ones you haven't joined (room id → name). */
    default CompletableFuture<java.util.Map<String, String>> spaceNames(String spaceId) {
        return CompletableFuture.completedFuture(java.util.Map.of());
    }

    /** Sends a GIF (mp4 or gif file) from the GIF tab. */
    default CompletableFuture<Void> sendGif(String chatId, java.nio.file.Path file, String mime, int width, int height) {
        return sendFile(chatId, file, null);
    }

    /** Answers the bridge's current login step (pasted cookies, e-mail, verification code…). */
    default CompletableFuture<Void> sendLoginInput(Network network, String botChatId, String text) {
        return sendText(botChatId, text, null);
    }

    /** Aborts an unfinished login started with {@link #addAccount}/{@link #connectNetwork}. */
    default CompletableFuture<Void> cancelLogin(Network network, String botChatId) {
        return CompletableFuture.completedFuture(null);
    }
}
