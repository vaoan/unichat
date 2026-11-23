package app.unichat.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Whether anyone can see Unichat: some window of it is on screen (not in the tray, not minimised) and the screen isn't
 * away (locked, or the display asleep). Work that only matters to someone looking pauses on it; what tells the person
 * something (sync, reminders, notifications, the menu-bar count) never listens to it.
 *
 * <p>Listeners are called on the setter's thread, in order, only when {@link #now()} changes, and must not block: each
 * hands its work to its own thread.
 */
public final class Seen {
    private static final Seen INSTANCE = new Seen();

    private final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();
    private boolean windows = true;
    private boolean away;

    private Seen() {}

    public static Seen get() { return INSTANCE; }

    public synchronized boolean now() { return windows && !away; }

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
        boolean before = now();
        if (someWindowShown != null) windows = someWindowShown;
        if (screenAway != null) away = screenAway;
        boolean after = now();
        if (after == before) return;
        for (Consumer<Boolean> l : listeners) {
            try {
                l.accept(after);
            } catch (RuntimeException e) {
                System.err.println("Seen listener failed: " + e);
            }
        }
    }
}
