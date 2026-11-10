package app.unichat.ui;

import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.stage.Window;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * After every test (registered for all, `META-INF/services`): a context menu a test left showing is closed and fails
 * that test. One left open on Linux was picked up by a later test looking for "the menu that's showing" and fired
 * instead of its own; test windows are closed when done (src/test/CLAUDE.md), and this keeps menus to it too.
 */
public final class LeftoverMenus implements AfterEachCallback {
    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        CompletableFuture<List<String>> left = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
                List<ContextMenu> open = Window.getWindows().stream()
                        .filter(w -> w instanceof ContextMenu && w.isShowing()).map(w -> (ContextMenu) w).toList();
                open.forEach(ContextMenu::hide);
                left.complete(open.stream().map(m -> m.getItems().stream().map(i -> String.valueOf(i.getText())).toList().toString()).toList());
            });
        } catch (IllegalStateException noToolkit) {
            return; // not a UI test: JavaFX never started in this run
        }
        List<String> menus = left.get();
        if (!menus.isEmpty()) throw new AssertionError("left a context menu showing (close it when done): " + menus);
    }
}
