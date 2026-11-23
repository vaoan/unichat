package app.unichat.support;

import java.util.function.Consumer;

/** Tells when the screen is locked or the display asleep (true) and back (false); chosen at start by the OS. */
public interface ScreenAway {
    /** Starts telling {@code away}, on a thread of its own; a source that fails to start says why and tells nothing. */
    void start(Consumer<Boolean> away);

    /** Where there's no way to know (Linux, CI, tests): the screen is always here. */
    static ScreenAway none() {
        return away -> { };
    }
}
