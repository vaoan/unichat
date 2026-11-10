package app.unichat.ui;

import javafx.scene.control.ContextMenu;
import javafx.stage.Window;

/**
 * A menu that opens makes Unichat the active app and its window the focused one. On a Mac, a right-click on a window
 * of an app in the background shows its menu without activating it, and then JavaFX never gets the click outside nor
 * the Esc that close it: the menu stayed open whatever the person did (a menu-bar app like Unichat is often in the
 * background). Active, JavaFX's own auto-hide does the rest.
 */
final class MenuDismiss {
    private MenuDismiss() {}

    static void watch(ContextMenu menu) {
        Window owner = menu.getOwnerWindow();
        if (owner == null || owner.isFocused()) return;
        DockIcon.activate();
        owner.requestFocus();
    }
}
