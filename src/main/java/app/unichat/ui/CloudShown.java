package app.unichat.ui;

import app.unichat.cloud.Cloud;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What the cloud is doing, shown on the tray and on the settings gear: a {@link Cloud} listener. It runs on whichever
 * thread told the status (a round's, a vault job's), so each side reads it once its own thread runs the change: a
 * status read before posting could be posted after a newer one and stay shown until the next.
 */
public final class CloudShown implements Runnable {
    private final Supplier<Cloud.Status> status;
    private final Consumer<Cloud.Status> tray;
    private final Consumer<Cloud.Status> gear;
    private final Executor trayThread;
    private final Executor gearThread;

    /**
     * @param tray shows it on the tray, on {@code trayThread} (AWT's)
     * @param gear shows it on the gear, on {@code gearThread} (the UI thread)
     */
    public CloudShown(Supplier<Cloud.Status> status, Consumer<Cloud.Status> tray, Executor trayThread, Consumer<Cloud.Status> gear,
                      Executor gearThread) {
        this.status = status;
        this.tray = tray;
        this.trayThread = trayThread;
        this.gear = gear;
        this.gearThread = gearThread;
    }

    @Override
    public void run() {
        trayThread.execute(() -> tray.accept(status.get()));
        gearThread.execute(() -> gear.accept(status.get()));
    }
}
