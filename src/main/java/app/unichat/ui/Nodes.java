package app.unichat.ui;

import javafx.scene.Node;

/** What many nodes need done the same way. */
final class Nodes {
    private Nodes() {}

    /** Shown and taking up room, or neither. */
    static void show(Node n, boolean on) {
        n.setVisible(on);
        n.setManaged(on);
    }

    /** Has style class {@code cls} only while {@code on}. */
    static void style(Node n, String cls, boolean on) {
        // Untouched when already so: any change to the classes makes JavaFX apply the CSS to the node again.
        if (n.getStyleClass().contains(cls) == on) return;
        if (on) n.getStyleClass().add(cls);
        else n.getStyleClass().removeAll(cls);
    }
}
