package app.unichat.bridge;

import java.io.IOException;

/**
 * A bridge change ({@code BridgeProvisioner.reconfigure}) refused before anything stopped: no account, a limit key its
 * version lacks. Its message is in the person's words, so whoever asked shows it as it is; any other failure is said
 * by a text of its own, with the detail in the log.
 */
public final class BridgeRefused extends IOException {
    private static final long serialVersionUID = 1L;

    public BridgeRefused(String message) {
        super(message);
    }
}
