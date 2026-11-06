package app.unichat.service;

import app.unichat.model.Network;
import app.unichat.model.MediaLibrary;
import app.unichat.model.VoiceNote;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Sending and changing messages. */
public interface ChatMessaging {
    CompletableFuture<Void> sendText(String chatId, String text, String replyToId);
    CompletableFuture<Void> sendFile(String chatId, Path file, String replyToId);
    CompletableFuture<Void> editMessage(String chatId, String messageId, String newText);
    CompletableFuture<Void> deleteMessage(String chatId, String messageId);
    /** Adds the reaction if the user has not reacted with this emoji, otherwise removes it. */
    CompletableFuture<Void> toggleReaction(String chatId, String messageId, String emoji);
    void sendTyping(String chatId, boolean typing);
    /** Sends a favourite/recent sticker; {@code item.info()} lets the bridge resend the native sticker. */
    default CompletableFuture<Void> sendSticker(String chatId, MediaLibrary.Item item) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Stickers no disponibles"));
    }
    /** Sends a GIF (mp4 or gif file) from the GIF tab. */
    /** A recorded voice note; a network without them gets it as an audio file. */
    default CompletableFuture<Void> sendVoice(String chatId, VoiceNote note, String replyToId) {
        return sendFile(chatId, note.file(), replyToId);
    }

    default CompletableFuture<Void> sendGif(String chatId, java.nio.file.Path file, String mime, int width, int height) {
        return sendFile(chatId, file, null);
    }
    /** Answers the bridge's current login step (pasted cookies, e-mail, verification code…). */
    default CompletableFuture<Void> sendLoginInput(Network network, String botChatId, String text) {
        return sendText(botChatId, text, null);
    }
}
