package app.unichat.ui.timeline;

import app.unichat.settings.Settings;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;

/** A message's row in the timeline, with its action buttons: visible on hover, when selected, or always if so chosen. */
final class MessageLine extends HBox {
    private final Node actions;
    private boolean hovered;

    MessageLine(Node actions) {
        super(6);
        this.actions = actions;
        setFillHeight(false); // or the hover actions stretch to a tall message's whole height
        showActions(false);
        setOnMouseEntered(e -> {
            hovered = true;
            showActions(inSelectedCell());
        });
        setOnMouseExited(e -> {
            hovered = false;
            showActions(inSelectedCell());
        });
    }

    /** Never while picking messages ({@link MessageSelection}): a click there only picks. */
    void showActions(boolean selected) {
        actions.setVisible(!picking() && (selected || Settings.get().alwaysShowMessageActions() || hovered));
    }

    private boolean picking() {
        return getParent() instanceof ListCell<?> cell && cell.getListView() != null && cell.getListView().getStyleClass().contains("picking");
    }

    private boolean inSelectedCell() {
        return getParent() instanceof ListCell<?> cell && cell.isSelected();
    }
}
