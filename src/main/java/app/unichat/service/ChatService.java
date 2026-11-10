package app.unichat.service;

import app.unichat.model.Chat;
import app.unichat.model.ConnectionState;
import app.unichat.model.Message;

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
public interface ChatService extends ChatSession, ChatReader, MediaSource, ChatMessaging, ChatPreferences, NetworkAccounts,
        ContactDirectory, GroupDirectory, ChatFolders {

    interface Listener {
        /** Chat list membership, order or any chat field changed. */
        default void chatsChanged() {}
        /** Messages of one chat were added, changed or removed. */
        default void messagesChanged(String chatId) {}
        default void accountsChanged() {}
        default void connectionChanged(ConnectionState state) {}
        /** A new incoming message that should produce a desktop notification (respects mute). */
        default void incomingMessage(Chat chat, Message message) {}
        /**
         * Every new message from someone else, as it arrives (not history nor a bridge's backfill), muted chats too;
         * never a bridge bot's chat. What saving everything a chat receives listens to.
         */
        default void received(Chat chat, Message message) {}
        /** A chat reminder came due. */
        default void reminderDue(Chat chat) {}
    }

    /** What "Nuevo chat" can do for an account: list its contacts, search the network. */
    record ContactSupport(boolean list, boolean search) {}

    /** A server/group you're in on {@code network} that doesn't come into Unichat yet (a Discord server). */
    record GroupOffer(String id, String name) {}
}
