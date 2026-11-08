package app.unichat.matrix;

import app.unichat.support.I18n;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * What the bridges said about our messages ({@code com.beeper.message_send_status}), for whoever waits on one. A file
 * sent to a bridged chat is done when the network has it, not when our server does: sending the next one before that
 * piled hundreds of uploads in Telegram's bridge, and when its connection dropped every one of them was lost.
 */
final class SendStatuses {
    /** Statuses heard before anyone asked (the sync can bring one before our send returns its event id). */
    private static final int REMEMBERED = 2000;

    private final Map<String, CompletableFuture<Void>> waiting = new LinkedHashMap<>();
    private final Map<String, String> heard = new LinkedHashMap<>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > REMEMBERED;
        }
    };

    /** From the sync: the bridge's {@code status} for {@code eventId} (SUCCESS, FAIL_RETRIABLE, FAIL_PERMANENT…). */
    void heard(String eventId, String status) {
        if (!isFinal(status)) return; // PENDING: the bridge is still on it
        CompletableFuture<Void> f;
        synchronized (this) {
            heard.put(eventId, status);
            f = waiting.remove(eventId);
        }
        if (f != null) settle(f, status);
    }

    static boolean isFinal(String status) {
        return status != null && (status.equals("SUCCESS") || status.startsWith("FAIL"));
    }

    /**
     * Completes when the bridge says the network has {@code eventId}, fails when it says it couldn't; after
     * {@code patience} with no word it completes too (a bridge that doesn't report, or a status lost on the way).
     */
    CompletableFuture<Void> await(String eventId, Duration patience) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        String known;
        synchronized (this) {
            known = heard.get(eventId);
            if (known == null) waiting.put(eventId, f);
        }
        if (known != null) {
            settle(f, known);
            return f;
        }
        return f.completeOnTimeout(null, patience.toMillis(), TimeUnit.MILLISECONDS).whenComplete((v, err) -> {
            synchronized (this) {
                waiting.remove(eventId, f);
            }
        });
    }

    private static void settle(CompletableFuture<Void> f, String status) {
        if (status.startsWith("FAIL")) f.completeExceptionally(new MatrixException(0, null, I18n.t("send.networkRefused")));
        else f.complete(null);
    }
}
