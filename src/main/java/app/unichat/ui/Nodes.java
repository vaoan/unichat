package app.unichat.ui;

import javafx.scene.Node;
import org.jspecify.annotations.Nullable;

/** What many nodes need done the same way. */
public final class Nodes {
    private Nodes() {}

    /** Shown and taking up room, or neither. */
    public static void show(Node n, boolean on) {
        n.setVisible(on);
        n.setManaged(on);
    }

    /** Has style class {@code cls} only while {@code on}. */
    public static void style(Node n, String cls, boolean on) {
        // Untouched when already so: any change to the classes makes JavaFX apply the CSS to the node again.
        if (n.getStyleClass().contains(cls) == on) return;
        if (on) n.getStyleClass().add(cls);
        else n.getStyleClass().removeAll(cls);
    }

    /** The window {@code n} is in: null before it is in a scene shown in one. */
    public static javafx.stage.@Nullable Window window(Node n) {
        javafx.scene.Scene scene = n.getScene();
        return scene == null ? null : scene.getWindow();
    }
}
