package app.unichat.service;

import app.unichat.model.Chat;
import app.unichat.model.Message;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Reading chats and their messages. */
public interface ChatReader {
    /** All chats, including archived and bridge-bot chats; the UI filters and sorts. */
    List<Chat> chats();
    Chat chat(String chatId);
    /** Messages loaded so far for a chat, oldest first. */
    List<Message> messages(String chatId);
    /** Loads earlier history; completes with false when the start of the chat was reached. */
    CompletableFuture<Boolean> loadOlder(String chatId);
    /**
     * One message by its id, when it isn't among those loaded (the original of a reply, far back in a big chat); not
     * added to the chat. Completes with null when it can't be had.
     */
    default CompletableFuture<Message> message(String chatId, String messageId) {
        return CompletableFuture.completedFuture(null);
    }
}
