package app.unichat.ui;

import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Region;

/** Rows of chips choosing one option. */
final class Chips {
    private Chips() {}

    /** A chip that stays on when clicked again: one option is always chosen. */
    static ToggleButton sticky(String text, ToggleGroup group, boolean selected, Runnable action) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("chip");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setToggleGroup(group);
        b.setSelected(selected);
        b.setOnAction(e -> {
            if (!b.isSelected()) b.setSelected(true);
            action.run();
        });
        return b;
    }
}
