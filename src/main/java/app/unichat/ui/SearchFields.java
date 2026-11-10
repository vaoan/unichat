package app.unichat.ui;

import app.unichat.support.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

/**
 * The ✕ of every search box: shown while there's text, it clears it and leaves the focus in the box. From the keyboard,
 * Esc does the same ({@link #clearOnEscape}, called by {@link WindowShortcuts} before Esc closes anything), so the
 * button stays out of the Tab order.
 */
public final class SearchFields {
    private static final String CLEARABLE = "clearable";

    private SearchFields() {}

    /** {@code field} in a box of its own with its ✕. */
    public static StackPane clearable(TextField field) {
        StackPane box = new StackPane(field);
        addClear(box, field);
        return box;
    }

    /** Adds the ✕ to the box that already holds {@code field} (with its magnifying glass, say). */
    public static void addClear(StackPane box, TextField field) {
        Button clear = Icons.iconButton(Icons.CLOSE, I18n.t("search.clear"), () -> {
            field.clear();
            field.requestFocus();
        });
        clear.getStyleClass().add("search-clear");
        clear.setFocusTraversable(false);
        clear.visibleProperty().bind(field.textProperty().isNotEmpty());
        StackPane.setAlignment(clear, Pos.CENTER_RIGHT);
        StackPane.setMargin(clear, new Insets(0, 4, 0, 0));
        box.getChildren().add(clear);
        field.getStyleClass().add(CLEARABLE);
    }

    /** Esc in a search box with text: empties it and says so (true). Esc in an empty one goes on as usual. */
    static boolean clearOnEscape(Node focused) {
        if (!(focused instanceof TextField f) || !f.getStyleClass().contains(CLEARABLE) || f.getText().isEmpty()) return false;
        f.clear();
        return true;
    }
}
