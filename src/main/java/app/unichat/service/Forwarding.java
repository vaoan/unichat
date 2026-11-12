package app.unichat.service;

import app.unichat.model.Message;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Forwarding messages to other chats, of any network. No bridge forwards natively from Matrix (it has no forward event),
 * so a forward is a copy sent as one's own: what the message shows, never what it answered or who wrote it, unless a
 * note says so.
 */
public final class Forwarding {
    private Forwarding() {}

    /** What a forward of {@code from} carries, into a new message of one's own. */
    public static void copy(Message from, Message into) {
        into.setType(from.type());
        into.setBody(from.body());
        into.setHtml(from.html());
        into.setMediaUrl(from.mediaUrl());
        into.setFileName(from.fileName());
        into.setMimeType(from.mimeType());
        into.setFileSize(from.fileSize());
        into.setSize(from.width(), from.height());
        into.setMediaInfo(from.mediaInfo());
    }

    /**
     * Sends {@code messages}, in their order, to each of {@code chatIds}, one at a time. {@code note} gives the line that
     * says where a message came from (null: none); it goes before a message only when it differs from the one before,
     * so a run from the same person carries one. Gives how many messages couldn't go; the rest still go.
     */
    public static CompletableFuture<Integer> send(ChatMessaging svc, List<Message> messages, List<String> chatIds,
                                                  Function<Message, String> note) {
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (String chatId : chatIds) {
            String[] last = {null};
            for (Message m : messages) {
                String line = note.apply(m);
                boolean noted = line != null && !line.equals(last[0]);
                last[0] = line;
                // The line as plain text, like a forward: it holds names strangers chose ("*Ana*" isn't bold), and pings no one.
                chain = chain.thenCompose(failed -> (noted ? svc.forward(chatId, plain(chatId, line)) : CompletableFuture.<Void>completedFuture(null))
                        .handle((v, err) -> logged(err))
                        .thenCompose(v -> counted(svc.forward(chatId, m))).thenApply(b -> failed + b));
            }
        }
        return chain;
    }

    private static Message plain(String chatId, String text) {
        Message m = new Message("forward-note", chatId, "", "", true, java.time.Instant.now());
        m.setBody(text);
        return m;
    }

    /** A line that couldn't go is logged: the messages still go, and only they count. */
    private static Void logged(Throwable err) {
        if (err != null) System.err.println("Forward note failed: " + err);
        return null;
    }

    /** 1 when {@code sent} failed, 0 when it went. */
    private static CompletableFuture<Integer> counted(CompletableFuture<Void> sent) {
        return sent.handle((v, err) -> {
            if (err != null) System.err.println("Forward failed: " + err);
            return err == null ? 0 : 1;
        });
    }
}
