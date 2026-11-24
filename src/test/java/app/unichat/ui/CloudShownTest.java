package app.unichat.ui;

import app.unichat.cloud.Cloud;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;

/** The tray and the gear show the cloud's latest status, whichever thread told it and whichever posting ran last. */
class CloudShownTest {
    @Test
    void theLatestStatusIsShownThoughTheOlderOnesPostingRunsLast() {
        Cloud.Status older = new Cloud.Status(true, null, null, 1, 5);
        Cloud.Status newer = new Cloud.Status(true, null, null, 2, 4);
        AtomicReference<Cloud.Status> status = new AtomicReference<>(older);
        // both threads held: what's posted waits
        List<Runnable> awt = new ArrayList<>();
        List<Runnable> fx = new ArrayList<>();
        AtomicReference<Cloud.Status> onTray = new AtomicReference<>();
        AtomicReference<Cloud.Status> onGear = new AtomicReference<>();
        CloudShown shown = new CloudShown(status::get, onTray::set, awt::add, onGear::set, fx::add);
        shown.run(); // a vault job told the older one
        status.set(newer);
        shown.run(); // a round told the newer one meanwhile, and posted first
        Collections.reverse(awt);
        Collections.reverse(fx);
        awt.forEach(Runnable::run);
        fx.forEach(Runnable::run);
        assertSame(newer, onTray.get(), "the tray's lines: the status now, not the one read before posting");
        assertSame(newer, onGear.get(), "the gear's ring: the same");
    }
}
