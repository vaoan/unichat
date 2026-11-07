package app.unichat.ui;

import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;

/** A picture in the sticker and GIF tabs that sends when clicked or with Enter or Space. */
final class MediaTile {
    private MediaTile() {}

    /** Dimmed while {@code accepted} is false; {@code act} runs anyway, to say why nothing was sent. */
    static StackPane of(ImageView view, int width, int height, String styleClass, String accessibleText, boolean accepted, Runnable act) {
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.setPreserveRatio(true);
        StackPane tile = new StackPane(view);
        tile.getStyleClass().add(styleClass);
        tile.setMinSize(width, height);
        tile.setMaxSize(width, height);
        tile.setFocusTraversable(true);
        tile.setAccessibleRole(AccessibleRole.BUTTON);
        tile.setAccessibleText(accessibleText);
        tile.setOpacity(accepted ? 1 : 0.4);
        tile.setCursor(accepted ? Cursor.HAND : Cursor.DEFAULT);
        tile.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) act.run();
        });
        tile.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                e.consume();
                act.run();
            }
        });
        return tile;
    }
}
