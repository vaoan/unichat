package app.unichat.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Whether anyone can see Unichat: some window of it is on screen (not in the tray, not minimised) and the screen isn't
 * away (locked, or the display asleep). Work that only matters to someone looking pauses on it; what tells the person
 * something (sync, reminders, notifications, the menu-bar count) never listens to it.
 *
 * <p>Listeners are called on the setter's thread, in order, only when {@link #now()} changes. They must not block nor
 * take a lock that a thread calling {@code Seen} might hold: each hands its work to its own thread. A listener that sets
 * {@code Seen} again isn't heard inside its own call: once every listener has heard the value at hand, all of them hear
 * the newest, so each ends on the final value in the order it took effect.
 */
public final class Seen {
    private static final Seen INSTANCE = new Seen();

    private final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();
    private boolean windows = true;
    private boolean away;
    /** {@code windows && !away}, read without the lock the listeners are called under. */
    private volatile boolean seenNow = true;
    /** What the listeners last heard, and whether they're being told now (a set from inside a listener). */
    private boolean heard = true;
    private boolean notifying;

    private Seen() {}

    public static Seen get() { return INSTANCE; }

    public boolean now() { return seenNow; }

    public void addListener(Consumer<Boolean> l) { listeners.add(l); }

    public void removeListener(Consumer<Boolean> l) { listeners.remove(l); }

    /** Whether some Unichat window is showing, not minimised and not a popup. */
    public void windows(boolean someWindowShown) { set(someWindowShown, null); }

    /** Whether the screen is locked or the display asleep. */
    public void screenAway(boolean screenAway) { set(null, screenAway); }

    /** Tests and headless runs: {@code seen} with the screen here; listeners hear it if it's a change. */
    public static void reset(boolean seen) { INSTANCE.set(seen, false); }

    /** Under the lock with the listeners, so two threads setting at once are heard in the order they took effect. */
    private synchronized void set(Boolean someWindowShown, Boolean screenAway) {
        if (someWindowShown != null) windows = someWindowShown;
        if (screenAway != null) away = screenAway;
        seenNow = windows && !away;
        if (notifying) return; // a listener set it: told below, once everyone heard the value at hand
        notifying = true; // NOPMD: UnusedAssignment, read by the listeners the change below fires
        try {
            while (seenNow != heard) {
                boolean now = seenNow;
                heard = now;
                for (Consumer<Boolean> l : listeners) tell(l, now);
            }
        } finally {
            notifying = false;
        }
    }

    private static void tell(Consumer<Boolean> l, boolean now) {
        try {
            l.accept(now);
        } catch (RuntimeException e) {
            System.err.println("Seen listener failed: " + e);
            e.printStackTrace(System.err); // the log (AppLog): what printStackTrace() wrote to as well
        }
    }
}
