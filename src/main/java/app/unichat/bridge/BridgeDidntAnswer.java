package app.unichat.bridge;

import java.io.IOException;

/**
 * A bridge restarted with a new config ({@code BridgeProvisioner.reconfigure}) didn't answer: it stopped running, or said
 * nothing within the wait. Its own type, so whoever asked for the change can put the old setting back.
 */
public final class BridgeDidntAnswer extends IOException {
    private static final long serialVersionUID = 1L;

    public BridgeDidntAnswer(String message) {
        super(message);
    }
}
