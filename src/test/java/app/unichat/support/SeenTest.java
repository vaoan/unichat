package app.unichat.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeenTest {
    private final Seen seen = Seen.get();

    @AfterEach
    void back() {
        Seen.reset(true);
    }

    @Test
    void startsAsSet() {
        Seen.reset(true);
        assertTrue(seen.now());
        Seen.reset(false);
        assertFalse(seen.now());
    }

    @Test
    void windowsHiddenIsUnseen() {
        Seen.reset(true);
        seen.windows(false);
        assertFalse(seen.now(), "every window in the tray");
        seen.windows(true);
        assertTrue(seen.now(), "a window shown again");
    }

    @Test
    void screenAwayIsUnseen() {
        Seen.reset(true);
        seen.screenAway(true);
        assertFalse(seen.now(), "locked, or the display asleep, with the window shown");
        seen.screenAway(false);
        assertTrue(seen.now(), "unlocked with the window shown");
    }

    @Test
    void unlockingWithTheWindowInTheTrayStaysUnseen() {
        Seen.reset(true);
        seen.windows(false);
        seen.screenAway(true);
        seen.screenAway(false);
        assertFalse(seen.now(), "the screen is back but no window is");
    }

    @Test
    void listenersHearOnlyChanges() {
        Seen.reset(true);
        List<Boolean> heard = new ArrayList<>();
        Consumer<Boolean> l = heard::add;
        seen.addListener(l);
        try {
            seen.windows(false);
            seen.windows(false);
            seen.screenAway(true); // still unseen: no change
            seen.windows(true);    // still away: no change
            seen.screenAway(false);
        } finally {
            seen.removeListener(l);
        }
        seen.windows(false); // no longer listening
        assertEquals(List.of(false, true), heard);
    }

    @Test
    void aListenerThatSetsItAgainLeavesEveryoneOnTheFinalValueInOrder() {
        Seen.reset(true);
        List<String> heard = new ArrayList<>();
        Consumer<Boolean> first = b -> {
            heard.add("first " + b);
            if (!b) seen.windows(true); // a window shown again while the others hear it was hidden
        };
        Consumer<Boolean> second = b -> heard.add("second " + b);
        seen.addListener(first);
        seen.addListener(second);
        try {
            seen.windows(false);
        } finally {
            seen.removeListener(first);
            seen.removeListener(second);
        }
        assertEquals(List.of("first false", "second false", "first true", "second true"), heard);
        assertTrue(seen.now());
    }

    @Test
    void aListenerThatFailsLeavesTheOthersHearing() {
        Seen.reset(true);
        List<Boolean> heard = new ArrayList<>();
        Consumer<Boolean> failing = b -> { throw new IllegalStateException("listener failed (test)"); };
        Consumer<Boolean> other = heard::add;
        seen.addListener(failing);
        seen.addListener(other);
        try {
            seen.windows(false);
        } finally {
            seen.removeListener(failing);
            seen.removeListener(other);
        }
        assertEquals(List.of(false), heard);
    }

    @Test
    void nowAnswersWhileAListenerIsBeingCalledOnAnotherThread() throws Exception {
        Seen.reset(true);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Consumer<Boolean> holding = b -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        seen.addListener(holding);
        Thread setter = Thread.ofVirtual().start(() -> seen.windows(false));
        try {
            inside.await();
            // A thread holding its own lock asks now() while the setter waits on that lock inside a listener.
            assertFalse(java.util.concurrent.CompletableFuture.supplyAsync(seen::now).get(), "answered, not waiting on the listener");
        } finally {
            release.countDown();
            setter.join(); // lesson-ok: test-future-join (a Thread, which JUnit can interrupt)
            seen.removeListener(holding);
        }
    }

    @Test
    void theScreenSourceOfNoneNeverSaysAway() {
        Seen.reset(true);
        ScreenAway.none().start(seen::screenAway);
        assertTrue(seen.now());
    }
}
