package app.unichat.cloud;

import app.unichat.settings.Settings;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Media's turn in a round, by its switch: what's saved goes up, then the check of everything uploaded when it's due (a
 * day since the last whole one, or "Verificar ahora" pressed). Both ask before each file whether anyone can see
 * Unichat: nobody, and the rest waits for a round once seen again; a check cut short or not begun stays due. Runs on
 * the round's thread; only the presses come from elsewhere.
 */
final class MediaTurn {
    static final String LAST_VERIFY = "cloud.lastVerify";

    private final MediaUpload media;
    private final BooleanSupplier seen;
    /** "Verificar ahora" presses no whole check has answered yet: one pressed while a check runs gets one of its own. */
    private final AtomicInteger asked = new AtomicInteger();
    /** Whether this turn left media waiting for being seen. */
    private boolean waited;

    MediaTurn(MediaUpload media, BooleanSupplier seen) {
        this.media = media;
        this.seen = seen;
    }

    /** "Verificar ahora": the next turn checks everything, whenever the last check was. */
    void verifyAsked() {
        asked.incrementAndGet();
    }

    /** The turn; how many files went up (or back up). */
    int run(Storage dav, Instant now, MediaUpload.Progress uploading, MediaUpload.Progress verifying) throws IOException {
        waited = false;
        if (!CloudAccount.media()) return 0;
        if (!seen.getAsBoolean()) {
            waited = waits(now);
            return 0;
        }
        int sent = media.run(dav, uploading, this::mayGoOn);
        if (waited || !due(now)) return sent;
        int pressed = asked.get();
        sent += media.verify(dav, verifying, this::mayGoOn);
        if (waited) return sent;
        asked.compareAndSet(pressed, 0);
        Settings.get().set(LAST_VERIFY, now.toString());
        return sent;
    }

    /** Whether the last turn left something waiting for being seen. */
    boolean waited() {
        return waited;
    }

    /** For a round that never reached its turn: whether media would be waiting for being seen now. */
    boolean waitsNow(Instant now) {
        try {
            return CloudAccount.media() && !seen.getAsBoolean() && waits(now);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean mayGoOn() {
        if (seen.getAsBoolean()) return true;
        waited = true; // asked only with a file left: something waits
        return false;
    }

    /** Something to send or a check due: what being unseen holds back. */
    private boolean waits(Instant now) throws IOException {
        return media.waiting() > 0 || due(now);
    }

    private boolean due(Instant now) {
        if (asked.get() > 0) return true;
        String last = Settings.get().getString(LAST_VERIFY, null);
        return last == null || Instant.parse(last).plus(Cloud.VERIFY_EVERY).isBefore(now);
    }
}
