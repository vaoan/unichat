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
        n.getStyleClass().remove(cls);
        if (on) n.getStyleClass().add(cls);
    }
}
