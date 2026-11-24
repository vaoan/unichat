package app.unichat.matrix;

import app.unichat.model.Message;
import app.unichat.support.BoundedMap;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static app.unichat.matrix.MatrixClient.CLIENT;
import static app.unichat.support.Urls.enc;

/** Messages asked for on their own (the one a reply answers), kept: a reply shows on every redraw. */
final class FetchedMessages {
    private final MatrixCore core;
    /** The last few hundred, by id. */
    private final Map<String, CompletableFuture<@Nullable Message>> fetched = BoundedMap.byUse(500);

    FetchedMessages(MatrixCore core) {
        this.core = core;
    }

    /** Asked once per message; null when there's none or the server didn't answer (then asked again next time). */
    CompletableFuture<@Nullable Message> message(String chatId, String messageId) {
        if (chatId == null || messageId == null || messageId.startsWith(SyncProcessor.LOCAL_PREFIX)) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (fetched) {
            CompletableFuture<@Nullable Message> known = fetched.get(messageId);
            if (known != null) return known;
            CompletableFuture<@Nullable Message> f = new CompletableFuture<>();
            fetched.put(messageId, f);
            core.io.execute(() -> {
                Message m = fetch(chatId, messageId);
                // Not had (the server didn't answer): asked again next time it's drawn. Forgotten before anyone
                // waiting hears of it, so whoever asks next asks the server.
                if (m == null) synchronized (fetched) {
                    fetched.remove(messageId, f);
                }
                f.complete(m);
            });
            return f;
        }
    }

    /** One message from the server; null when it isn't one or the server failed to answer (an error answer too). */
    private @Nullable Message fetch(String chatId, String messageId) {
        try {
            JsonNode ev = core.client.get(CLIENT + "/rooms/" + enc(chatId) + "/event/" + enc(messageId));
            synchronized (core.proc) {
                return core.proc.standalone(chatId, ev);
            }
        } catch (IOException | MatrixException e) {
            return null;
        }
    }
}
