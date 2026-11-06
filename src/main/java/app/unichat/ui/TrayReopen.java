package app.unichat.ui;

/**
 * When being activated should bring the window back: clicking the Dock icon or opening the app again while it lives in
 * the menu bar activates it, and nothing else tells JavaFX so (it has no "reopen" event). Pure.
 */
public final class TrayReopen {
    /** Activation this soon after hiding is the window going away, not the person coming back. */
    private static final long JUST_HID_MS = 800;

    private TrayReopen() {}

    public static boolean shouldShow(boolean windowShowing, long msSinceHidden) {
        return !windowShowing && msSinceHidden >= JUST_HID_MS;
    }
}
