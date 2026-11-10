package app.unichat.ui;

import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

/**
 * Moving between Unichat's own windows (the main one, photos, videos, settings): ⌘` / ⌘⇧` (Ctrl on Windows) from any of
 * them, the Mac's shortcut for the windows of one app, which macOS only gives apps with a Window menu. While any window
 * besides the main one is open there's a Dock icon ({@link DockIcon}), so ⌘Tab reaches the app. Menus that open are
 * handed to {@link MenuDismiss}.
 */
public final class AppWindows {
    private static final EventHandler<KeyEvent> KEYS = e -> {
        if (isCycleKey(e)) {
            e.consume();
            next(Window.getWindows(), e.isShiftDown());
        }
    };

    private AppWindows() {}

    /** Watches every window from now on; {@code main} is the one that doesn't count for the Dock icon. */
    public static void install(Stage main) {
        for (Window w : Window.getWindows()) watch(w);
        Window.getWindows().addListener((ListChangeListener<Window>) c -> {
            while (c.next()) for (Window w : c.getAddedSubList()) watch(w);
            DockIcon.setOtherWindows(Window.getWindows().stream().anyMatch(w -> w != main && counts(w)));
        });
    }

    private static void watch(Window w) {
        if (w instanceof javafx.scene.control.ContextMenu menu) MenuDismiss.watch(menu);
        if (!(w instanceof Stage)) return;
        if (w.getScene() != null) attach(w.getScene());
        w.sceneProperty().addListener((o, a, s) -> {
            if (a != null) a.removeEventFilter(KeyEvent.KEY_PRESSED, KEYS);
            if (s != null) attach(s);
        });
    }

    private static void attach(Scene s) {
        s.removeEventFilter(KeyEvent.KEY_PRESSED, KEYS); // never twice
        s.addEventFilter(KeyEvent.KEY_PRESSED, KEYS);
    }

    /** ⌘` on a US keyboard; on a Spanish Mac keyboard macOS puts it on the key beside Z (<), or º. */
    static boolean isCycleKey(KeyEvent e) {
        if (!e.isShortcutDown() || e.isAltDown()) return false;
        return e.getCode() == KeyCode.BACK_QUOTE || e.getCode() == KeyCode.LESS
                || List.of("`", "<", ">", "º", "ª").contains(e.getText());
    }

    /** Brings the next (or previous) of {@code windows} that's open forward; which one, or null. */
    static Stage next(List<Window> windows, boolean backwards) {
        List<Window> open = windows.stream().filter(AppWindows::counts).toList();
        if (open.size() < 2) return null;
        int at = 0;
        for (int i = 0; i < open.size(); i++) if (open.get(i).isFocused()) at = i;
        if (!(open.get(step(at, open.size(), backwards)) instanceof Stage to)) return null;
        to.setIconified(false);
        to.toFront();
        to.requestFocus();
        return to;
    }

    static int step(int at, int count, boolean backwards) {
        return Math.floorMod(at + (backwards ? -1 : 1), count);
    }

    /** A real window on screen: not a menu, tooltip or popup. */
    private static boolean counts(Window w) {
        return w.isShowing() && !(w instanceof PopupWindow);
    }
}
