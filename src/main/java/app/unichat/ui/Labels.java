package app.unichat.ui;

import javafx.scene.control.Label;

/** Labels with a look of their own. */
final class Labels {
    private Labels() {}

    /** The small explaining text under settings and in login steps. */
    static Label help(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("settings-help");
        return l;
    }
}
