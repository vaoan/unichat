package app.unichat.service;

import app.unichat.model.MediaLibrary;
import app.unichat.model.VoiceNote;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Sending and changing messages. */
public interface ChatMessaging {
    CompletableFuture<Void> sendText(String chatId, String text, String replyToId);
    default CompletableFuture<Void> sendFile(String chatId, Path file, String replyToId) {
        return sendFile(chatId, file, replyToId, SendAs.AS_IS, p -> { });
    }
    /**
     * {@code as}: as it is, as a photo or as a document ({@link SendAs}); {@code progress} gets how much of it went up,
     * 0 to 1, on any thread.
     */
    CompletableFuture<Void> sendFile(String chatId, Path file, String replyToId, SendAs as,
                                     java.util.function.DoubleConsumer progress);
    CompletableFuture<Void> editMessage(String chatId, String messageId, String newText);
    CompletableFuture<Void> deleteMessage(String chatId, String messageId);
    /** Adds the reaction if the user has not reacted with this emoji, otherwise removes it. */
    CompletableFuture<Void> toggleReaction(String chatId, String messageId, String emoji);
    void sendTyping(String chatId, boolean typing);
    /** Sends a favourite/recent sticker; {@code item.info()} lets the bridge resend the native sticker. */
    CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item, String replyToId);
    /** A recorded voice note; a network without them gets it as an audio file. */
    CompletableFuture<Void> sendVoice(String chatId, VoiceNote note, String replyToId);

    /** Sends a GIF (mp4 or gif file) from the GIF tab. */
    default CompletableFuture<Void> sendGif(String chatId, java.nio.file.Path file, String mime, int width, int height, String replyToId) {
        return sendFile(chatId, file, replyToId);
    }
}
