package app.unichat.ui;

import app.unichat.model.Message;
import app.unichat.service.MediaSource;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;

/** What a reaction shows: its emoji, or the picture of a custom one (Discord's, Telegram's); never that picture's address. */
final class ReactionEmoji {
    /** For a custom emoji that can't be shown: Telegram's animated ones are a format JavaFX can't draw. */
    static final String PLACEHOLDER = "✨";
    private static final double SIZE = 18;

    private ReactionEmoji() {}

    static Label of(String key, MediaSource media) {
        Label label = new Label(Message.isCustomReaction(key) ? "" : key);
        label.getStyleClass().add("reaction-emoji");
        if (!Message.isCustomReaction(key)) return label;
        label.setMinSize(SIZE, SIZE);
        ImageCache.load(key, media).whenComplete((image, err) -> Platform.runLater(() -> {
            if (image == null) {
                label.setText(PLACEHOLDER);
                return;
            }
            ImageView view = new ImageView(image);
            view.setFitWidth(SIZE);
            view.setFitHeight(SIZE);
            view.setPreserveRatio(true);
            label.setGraphic(view);
        }));
        return label;
    }
}
