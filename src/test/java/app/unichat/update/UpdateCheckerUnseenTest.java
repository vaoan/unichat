package app.unichat.update;

import app.unichat.settings.Settings;
import app.unichat.support.Seen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdateCheckerUnseenTest {
    private static final String ANSWER = "{\"tag_name\":\"v999.0.0\",\"html_url\":\"https://github.com/vaoan/unichat/releases/tag/v999.0.0\"}";
    private static final long HOUR = Duration.ofHours(1).toMillis();

    private final AtomicLong clock = new AtomicLong(1_900_000_000_000L);
    private final AtomicInteger calls = new AtomicInteger();
    private @Nullable Consumer<Boolean> listener;

    private UpdateChecker checker() {
        return new UpdateChecker(clock::get, () -> {
            calls.incrementAndGet();
            return ANSWER;
        });
    }

    @BeforeEach
    void due() {
        Settings.get().setCheckUpdates(true);
        Settings.get().set(UpdateChecker.LAST_CHECK, "0");
    }

    @AfterEach
    void cleanUp() {
        if (listener != null) Seen.get().removeListener(listener);
        Seen.reset(true);
        Settings.get().set(UpdateChecker.LAST_CHECK, "0");
    }

    @Test
    void missedChecksBecomeOneWhenSeen() throws Exception {
        UpdateChecker c = checker();
        listener = c.listenToSeen();
        // A day passes during the unseen ticks (22 hours old, then 23, 24, 25): without the gate one would ask.
        Settings.get().set(UpdateChecker.LAST_CHECK, Long.toString(clock.get() - 22 * HOUR));
        Seen.reset(false);
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(HOUR);
            c.tick().get();
        }
        assertEquals(0, calls.get(), "ticks don't ask while unseen");
        Seen.reset(true);
        c.idle().get(); // the look queued on seen has run, and asked without any tick of ours
        assertEquals(1, calls.get(), "the check made on seen itself");
        c.tick().get();
        assertEquals(1, calls.get(), "exactly one check on seen");
    }

    @Test
    void aCheckNotDueWhenSeenIsntMade() throws Exception {
        UpdateChecker c = checker();
        listener = c.listenToSeen();
        Settings.get().set(UpdateChecker.LAST_CHECK, Long.toString(clock.get()));
        Seen.reset(false);
        clock.addAndGet(HOUR);
        Seen.reset(true);
        c.tick().get(); // the worker runs in order: the look made on seen came first
        assertEquals(0, calls.get());
    }

    @Test
    void aManualCheckIsntHeld() throws Exception {
        UpdateChecker c = checker();
        Seen.reset(false);
        UpdateChecker.Result r = c.checkNow().get();
        assertEquals(1, calls.get());
        assertEquals(UpdateChecker.Outcome.NEWER, r.outcome());
    }
}
