package app.unichat.ui;

import java.util.concurrent.atomic.AtomicInteger;

/** Redraws asked for and not run yet (Throttle's, FxCoalescer's): tests wait for 0 rather than for a time. */
final class Redraws {
    static final AtomicInteger PENDING = new AtomicInteger();

    private Redraws() {}
}
