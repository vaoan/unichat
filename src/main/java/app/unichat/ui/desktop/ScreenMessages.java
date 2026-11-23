package app.unichat.ui.desktop;

/**
 * What a Windows message says about the screen. A locked session and a display that is off are kept apart, since
 * either can end while the other holds (the display comes on to show the lock screen); the screen is away while
 * either does. Called on {@link WindowsScreen}'s one thread.
 */
final class ScreenMessages {
    static final int WM_WTSSESSION_CHANGE = 0x02B1;
    static final int WTS_SESSION_LOCK = 7;
    static final int WTS_SESSION_UNLOCK = 8;
    static final int WM_POWERBROADCAST = 0x0218;
    static final int PBT_POWERSETTINGCHANGE = 0x8013;
    /** For a message that carries no display state, or a setting that isn't the display's. */
    static final int NO_DISPLAY_STATE = -1;

    private static final int DISPLAY_OFF = 0;
    private static final int DISPLAY_DIMMED = 2;

    private boolean locked;
    private boolean displayOff;

    /**
     * Whether the screen is away after {@code msg}, or null when the message isn't about it. {@code displayState} is
     * GUID_CONSOLE_DISPLAY_STATE's data: 0 off, 1 on, 2 dimmed (Unichat still shows, so on).
     */
    Boolean away(int msg, long wParam, int displayState) {
        if (msg == WM_WTSSESSION_CHANGE && (wParam == WTS_SESSION_LOCK || wParam == WTS_SESSION_UNLOCK)) {
            locked = wParam == WTS_SESSION_LOCK;
        } else if (msg == WM_POWERBROADCAST && wParam == PBT_POWERSETTINGCHANGE
                && displayState >= DISPLAY_OFF && displayState <= DISPLAY_DIMMED) {
            displayOff = displayState == DISPLAY_OFF;
        } else {
            return null;
        }
        return locked || displayOff;
    }
}
