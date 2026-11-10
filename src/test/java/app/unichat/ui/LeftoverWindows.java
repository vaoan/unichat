package app.unichat.ui;

import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * After every test (registered for all, `META-INF/services`): a window or context menu the test opened and left
 * showing is closed (and said on stderr). Ones left open on Linux (its run order differs) were taken by later tests
 * looking for "the menu showing" or "the media viewer" and failed them, turning CI red. Windows a test class keeps
 * across its tests were there before each test: they're left alone.
 */
public final class LeftoverWindows implements BeforeEachCallback, AfterEachCallback {
    private static final ExtensionContext.Namespace HERE = ExtensionContext.Namespace.create(LeftoverWindows.class);

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        Set<Window> before = onFx(() -> {
            Set<Window> open = Collections.newSetFromMap(new IdentityHashMap<>());
            Window.getWindows().stream().filter(Window::isShowing).forEach(open::add);
            return open;
        });
        if (before != null) context.getStore(HERE).put("before", before);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterEach(ExtensionContext context) throws Exception {
        Set<Window> before = (Set<Window>) context.getStore(HERE).get("before");
        List<String> left = onFx(() -> {
            List<Window> extra = Window.getWindows().stream()
                    .filter(w -> w.isShowing() && (before == null || !before.contains(w))).toList();
            // Closing a test's last window would end JavaFX (implicit exit) for every test after it.
            if (!extra.isEmpty()) Platform.setImplicitExit(false);
            extra.forEach(Window::hide);
            return extra.stream().map(LeftoverWindows::describe).toList();
        });
        // Closed, not failed: most UI tests leave their main window to the next run of theirs; what matters is that no
        // later test finds it (a viewer, a menu) and takes it for its own. Said, so a leak is still seen.
        if (left != null && !left.isEmpty()) System.err.println("LeftoverWindows: closed after " + context.getDisplayName() + ": " + left);
    }

    private static String describe(Window w) {
        if (w instanceof ContextMenu m) return "menu " + m.getItems().stream().map(i -> String.valueOf(i.getText())).toList();
        if (w instanceof Stage s) return "window \"" + s.getTitle() + "\"";
        return w.getClass().getSimpleName();
    }

    /**
     * {@code c} on the FX thread, or null when there's none: JavaFX never started in this run (not a UI test), or it
     * exited (a test's last window closed with implicit exit on), when {@code runLater} drops the task silently and a
     * wait for it would never end.
     */
    private static <T> T onFx(Callable<T> c) throws Exception {
        boolean fxThread = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && "JavaFX Application Thread".equals(t.getName()));
        if (!fxThread) return null;
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
                try {
                    result.complete(c.call());
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (IllegalStateException noToolkit) {
            return null;
        }
        return result.get();
    }
}
