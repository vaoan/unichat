package app.unichat.ui;

import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;

/** What a card shown in the window's overlay shares. */
public final class Sheets {
    private Sheets() {}

    /**
     * {@code card} in a scroll pane {@code width} wide, as tall as it needs up to the window: it scrolls when taller (a
     * long text, big text). The overlay keeps a Region within its height, never a Group.
     */
    public static ScrollPane scrolling(Region card, double width) {
        card.setPrefWidth(width);
        ScrollPane scroll = new ScrollPane(card);
        scroll.setFitToWidth(true);
        scroll.setMaxWidth(width + 24);
        scroll.setMaxHeight(Region.USE_PREF_SIZE);
        scroll.getStyleClass().addAll("side-panel-scroll", "chat-options-scroll");
        return scroll;
    }
}
