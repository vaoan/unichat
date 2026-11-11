package app.unichat.ui.timeline;

import app.unichat.support.I18n;
import app.unichat.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Whether the message list follows the newest message, always at its bottom right: "Al día" while it does (writing
 * brings it there), and reading older messages the way down, with how many arrived below meanwhile.
 */
final class FollowIndicator {
    private final Label live = new Label();
    private final Button down;
    private final StackPane node;

    FollowIndicator(Runnable toNewest) {
        Region dot = new Region();
        dot.getStyleClass().add("live-dot");
        live.setGraphic(dot);
        live.getStyleClass().add("following-badge");
        live.setMinWidth(Region.USE_PREF_SIZE);
        down = Icons.iconButton(Icons.LOW, I18n.t("timeline.jumpDown"), toNewest);
        down.getStyleClass().add("jump-down");
        down.setMinWidth(Region.USE_PREF_SIZE);
        node = new StackPane(live, down);
        node.setPickOnBounds(false);
        node.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        node.getStyleClass().add("follow-indicator");
        StackPane.setAlignment(live, Pos.BOTTOM_RIGHT);
        StackPane.setAlignment(down, Pos.BOTTOM_RIGHT);
        show(true, 0);
    }

    Node node() { return node; }

    Button jumpDown() { return down; }

    /** {@code newBelow}: messages that arrived under what's being read. */
    void show(boolean following, int newBelow) {
        live.setVisible(following);
        live.setManaged(following);
        down.setVisible(!following);
        down.setManaged(!following);
        if (following) {
            live.setText(I18n.t("timeline.following"));
            live.setTooltip(new Tooltip(I18n.t("timeline.followingHelp")));
            live.setAccessibleText(I18n.t("timeline.following") + ". " + I18n.t("timeline.followingHelp"));
            return;
        }
        down.setText(newBelow > 0 ? I18n.n("timeline.newBelow", newBelow) : null);
        down.setAccessibleText(newBelow > 0 ? I18n.n("timeline.jumpDownNew", newBelow) : I18n.t("timeline.jumpDown"));
    }
}
