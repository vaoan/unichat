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
 * Whether the message list follows the newest message: a chevron on a line in the chat's header, beside the star, while it does
 * (a badge over the messages took the chat's room); reading older messages, the way down at the list's bottom right,
 * with how many arrived below meanwhile.
 */
final class FollowIndicator {
    private final Label live = new Label();
    private final Button down;
    private final StackPane node;

    FollowIndicator(Runnable toNewest) {
        // Not a green dot: that reads as "online", which Unichat can't know.
        live.setGraphic(Icons.icon(Icons.AT_NEWEST, 16));
        live.getStyleClass().add("following-badge");
        live.setMinWidth(Region.USE_PREF_SIZE);
        live.setFocusTraversable(false);
        down = Icons.iconButton(Icons.LOW, I18n.t("timeline.jumpDown"), toNewest);
        down.getStyleClass().add("jump-down");
        down.setMinWidth(Region.USE_PREF_SIZE);
        node = new StackPane(down);
        node.setPickOnBounds(false);
        node.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        node.getStyleClass().add("follow-indicator");
        StackPane.setAlignment(down, Pos.BOTTOM_RIGHT);
        show(true, 0);
    }

    /** The way down, at the list's bottom right. */
    Node node() { return node; }

    /** The sign for the chat's header. */
    Node live() { return live; }

    Button jumpDown() { return down; }

    /** {@code newBelow}: messages that arrived under what's being read. */
    void show(boolean following, int newBelow) {
        live.setVisible(following); // still managed: the header's buttons don't move when it goes
        down.setVisible(!following);
        down.setManaged(!following);
        if (following) {
            live.setTooltip(new Tooltip(I18n.t("timeline.following") + ". " + I18n.t("timeline.followingHelp")));
            live.setAccessibleText(I18n.t("timeline.following") + ". " + I18n.t("timeline.followingHelp"));
            return;
        }
        down.setText(newBelow > 0 ? I18n.n("timeline.newBelow", newBelow) : null);
        down.setAccessibleText(newBelow > 0 ? I18n.n("timeline.jumpDownNew", newBelow) : I18n.t("timeline.jumpDown"));
    }
}
