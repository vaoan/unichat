package app.unichat.ui;

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
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

    /**
     * Gives {@code n} the focus once it's in a scene. A card in {@link #scrolling} enters the scene only when the
     * scroll pane's skin is made, at the next pulse: a {@code requestFocus} before that did nothing, and the pulse's
     * focus clean-up gave the focus to the sheet's first focusable node instead of its safe answer.
     */
    public static void focusOnShow(Node n) {
        if (n.getScene() != null) {
            n.requestFocus();
            return;
        }
        ChangeListener<Scene> once = new ChangeListener<>() {
            @Override public void changed(javafx.beans.value.ObservableValue<? extends Scene> o, Scene was, Scene now) {
                if (now == null) return;
                n.sceneProperty().removeListener(this);
                n.requestFocus();
            }
        };
        n.sceneProperty().addListener(once);
    }
}
