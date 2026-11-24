package app.unichat.ui.desktop;

import app.unichat.ui.Formats;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jspecify.annotations.Nullable;
import java.awt.CheckboxMenuItem;
import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The macOS menu-bar item drawn by the native helper (native/UnichatBar.swift, built by native/build-bar.sh and
 * shipped as Contents/app/native/UnichatBar). Same design as InstaTray's.
 * Java's TrayIcon always adds 12pt of margin on each side (45pt for a 21pt icon); the native item is exactly the
 * 22pt icon wide. The AWT PopupMenu stays the single model: its items are mirrored to the helper whenever they change, and a
 * click on the native menu fires the AWT item's own listeners. If the helper is missing or dies, the caller falls back
 * to the AWT TrayIcon.
 */
public final class NativeBar {

    private final Process process;
    private final Writer out;
    private final PopupMenu menu;
    private String lastMenu = "";
    private volatile boolean alive = true;

    private NativeBar(Process process, PopupMenu menu) {
        this.process = process;
        this.menu = menu;
        this.out = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
    }

    /**
     * Starts the helper; null if it is not there or cannot run. onExit runs (once, any thread) if it dies later;
     * {@code onOpenChat} gets the chat id of a clicked notification, {@code onShow} runs on a left click of the icon
     * or the global shortcut (any thread).
     */
    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "our own menu-bar helper from the app's folder, no arguments")
    static @Nullable NativeBar start(@Nullable Path helper, PopupMenu menu, Runnable onExit, java.util.function.Consumer<String> onOpenChat, Runnable onShow) {
        if (helper == null || !Files.isExecutable(helper)) return null;
        try {
            NativeBar bar = new NativeBar(new ProcessBuilder(helper.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start(), menu);
            bar.onOpenChat = onOpenChat;
            bar.onShow = onShow;
            current = bar;
            Thread reader = new Thread(() -> bar.readClicks(onExit), "native-bar");
            reader.setDaemon(true);
            reader.start();
            return bar;
        } catch (IOException e) {
            return null;
        }
    }

    /** The shortcut that brings Unichat to the front from any app: it isn't in ⌘Tab without a Dock icon. */
    void setGlobalShortcut(boolean on) {
        send("hotkey\t" + (on ? 1 : 0) + "\n");
    }

    /** Removes the item (Unichat is quitting): the helper exits when its input closes. */
    void close() {
        alive = false;
        try {
            out.close();
        } catch (IOException ignored) {
            // Already gone.
        }
        process.destroy();
    }

    boolean isAlive() {
        return alive;
    }

    private volatile java.util.function.Consumer<String> onOpenChat = id -> { };
    private volatile Runnable onShow = () -> { };
    /** The one running, for Ajustes to reach; null when there's none. */
    private static volatile @Nullable NativeBar current;

    /** Applies the setting at once, if the helper is running. */
    public static void applyGlobalShortcut(boolean on) {
        NativeBar b = current;
        if (b != null) b.setGlobalShortcut(on);
    }
    /** Whether macOS lets the helper post notifications: null until it says, then true or false. */
    private volatile @Nullable Boolean notificationsAllowed;

    @Nullable Boolean notificationsAllowed() {
        return notificationsAllowed;
    }

    /** A system notification; clicking it opens {@code chatId}. */
    void notify(String chatId, String title, String body, boolean sound) {
        send("notify\t" + clean(chatId) + "\t" + (sound ? "1" : "0") + "\t" + clean(title) + "\t" + clean(body) + "\n");
    }

    /** Removes {@code chatId}'s notifications from Notification Center (it was read). */
    void clear(String chatId) {
        send("clear\t" + clean(chatId) + "\n");
    }

    /** Opens the menu as a right click would, or closes it if open (control channel "menu", for tests). */
    void open() {
        send("open\n");
    }

    /** True while the window is open: a left click then opens the menu instead of bringing the window. */
    void setLeftClickMenu(boolean on) {
        send("leftmenu\t" + (on ? 1 : 0) + "\n");
    }

    /** The count beside the icon; 0 shows none. */
    void setCount(int count) {
        send("count\t" + (count > 0 ? Formats.badge(count) : "") + "\n");
    }

    /** Saving going on: a ring on the icon, filled {@code f} (spinning while -1); NaN takes it away. */
    void setProgress(double f) {
        send("progress\t" + (Double.isNaN(f) ? "" : String.format(java.util.Locale.ROOT, "%.3f", f)) + "\n");
    }

    void setTooltip(String text) {
        send("tip\t" + clean(text) + "\n");
    }

    /** Sends the menu if any label, check mark or enabled state changed since the last time. Call on the EDT. */
    void syncMenu() {
        String snapshot = snapshot(menu);
        if (snapshot.equals(lastMenu)) return;
        lastMenu = snapshot;
        send(snapshot);
    }

    /** "menu N" and one line per item; AWT separators are items labelled "-". */
    static String snapshot(PopupMenu menu) {
        StringBuilder sb = new StringBuilder("menu\t").append(menu.getItemCount()).append('\n');
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem it = menu.getItem(i);
            if ("-".equals(it.getLabel())) {
                sb.append("sep\n");
                continue;
            }
            int checked = it instanceof CheckboxMenuItem c ? (c.getState() ? 1 : 0) : -1;
            sb.append("item\t").append(i).append('\t').append(it.isEnabled() ? 1 : 0).append('\t')
                    .append(checked).append('\t').append(clean(it.getLabel())).append('\n');
        }
        return sb.toString();
    }

    /** Fires the AWT item's listeners as if it had been clicked in the AWT menu. Call on the EDT. */
    static void activate(PopupMenu menu, int index) {
        if (index < 0 || index >= menu.getItemCount()) return;
        MenuItem it = menu.getItem(index);
        if (!it.isEnabled()) return;
        if (it instanceof CheckboxMenuItem c) {
            c.setState(!c.getState());
            c.dispatchEvent(new ItemEvent(c, ItemEvent.ITEM_STATE_CHANGED, c.getLabel(),
                    c.getState() ? ItemEvent.SELECTED : ItemEvent.DESELECTED));
        } else {
            it.dispatchEvent(new ActionEvent(it, ActionEvent.ACTION_PERFORMED, it.getActionCommand()));
        }
    }

    /** Hears whether the screen is locked or asleep ({@link MacScreen}); the helper may start before or after it. */
    private static volatile java.util.function.Consumer<Boolean> screen = away -> { };
    /** The helper's last "screen" line; null before it says one and once it ends. */
    private static volatile @Nullable Boolean lastScreen;
    /**
     * Storing a screen value and telling it is one step, for {@link #onScreen} and the reader alike: else a replayed
     * lock could land after the unlock or the helper's end, and Unichat would stay unseen for good. Always taken before
     * {@code Seen}'s lock, and no {@code Seen} listener calls back here. No test can make that race happen without
     * timing it ({@code src/test/CLAUDE.md}), so this lock is what rules it out.
     */
    private static final Object SCREEN = new Object();

    /**
     * From now on {@code away} hears the helper's "screen" lines, and not away once when it ends. What the running
     * helper already said (it starts before Unichat listens, and says at once if the screen is locked) is heard at
     * once; with no helper running there's no way to know: it hears not away at once.
     */
    static void onScreen(java.util.function.Consumer<Boolean> away) {
        synchronized (SCREEN) {
            screen = away;
            NativeBar b = current;
            if (b == null || !b.alive) away.accept(false);
            else {
                Boolean said = lastScreen;
                if (said != null) away.accept(said);
            }
        }
    }

    /** "screen 1" (locked or asleep) is true, "screen 0" false; anything else null. */
    static @Nullable Boolean screenLine(String line) {
        return switch (line) {
            case "screen\t1" -> true;
            case "screen\t0" -> false;
            default -> null;
        };
    }

    private void readClicks(Runnable onExit) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("hotkey") || line.equals("show")) {
                    onShow.run();
                    continue;
                }
                if (line.startsWith("hotkey-error\t")) {
                    System.err.println("The global shortcut is taken by another app");
                    continue;
                }
                if (line.startsWith("open\t")) {
                    onOpenChat.accept(line.substring(5).strip());
                    continue;
                }
                if (line.startsWith("log\t")) { // what the helper wants on record (how bringing Unichat forward went)
                    System.err.println("Menu bar helper: " + line.substring(4));
                    continue;
                }
                if (line.startsWith("notify-auth\t")) {
                    boolean allowed = line.endsWith("1");
                    notificationsAllowed = allowed;
                    System.err.println("System notifications: " + (allowed ? "allowed" : "not allowed (Unichat's own cards instead)"));
                    continue;
                }
                Boolean away = screenLine(line);
                if (away != null) {
                    synchronized (SCREEN) {
                        lastScreen = away;
                        screen.accept(away);
                    }
                    continue;
                }
                if (!line.startsWith("click\t")) continue;
                int index;
                try {
                    index = Integer.parseInt(line.substring(6).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                EventQueue.invokeLater(() -> {
                    activate(menu, index);
                    syncMenu(); // a toggled check mark shows at once
                });
            }
        } catch (IOException ignored) {
            // the helper's pipe broke: it ended, as when it exits
        }
        alive = false;
        synchronized (SCREEN) {
            lastScreen = null;
            screen.accept(false); // gone: nothing says the screen is away any more
        }
        onExit.run();
    }

    private synchronized void send(String lines) {
        if (!alive) return;
        try {
            out.write(lines);
            out.flush();
        } catch (IOException e) {
            alive = false; // the reader thread sees EOF and runs onExit
            process.destroy();
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
