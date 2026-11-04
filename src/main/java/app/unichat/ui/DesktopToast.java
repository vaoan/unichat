package app.unichat.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Unichat's own notification card, top-right of the screen, used on macOS: the system drops AWT tray notifications
 * from a plain {@code java} process ("app not found" in usernoted), and osascript ones can't call Unichat back when
 * clicked. Clicking the card opens its chat. One card at a time: a newer message replaces it. FX thread only.
 */
final class DesktopToast {
    private static final double WIDTH = 340, MARGIN = 14;
    private static final Duration VISIBLE = Duration.seconds(6);

    private final Consumer<String> onOpen;
    private final Label title = new Label();
    private final Label body = new Label();
    private final Button card = new Button();
    private final PauseTransition timer = new PauseTransition(VISIBLE);
    private Stage stage;
    private String chatId;

    DesktopToast(Consumer<String> onOpen) {
        this.onOpen = onOpen;
        timer.setOnFinished(e -> hide());
    }

    void show(String titleText, String bodyText, String chatId) {
        this.chatId = chatId;
        if (stage == null) stage = build();
        title.setText(titleText);
        body.setText(bodyText);
        card.setAccessibleText("Notificación de Unichat: " + titleText + ". " + bodyText);
        Rectangle2D area = Screen.getPrimary().getVisualBounds();
        stage.setX(area.getMaxX() - WIDTH - MARGIN);
        stage.setY(area.getMinY() + MARGIN);
        // Showing a window takes the keyboard: hand it straight back. While someone typed, their next space or
        // Enter "pressed" the card and opened its chat (the chat changed under them).
        javafx.stage.Window had = javafx.stage.Window.getWindows().stream()
                .filter(w -> w != stage && w.isFocused()).findFirst().orElse(null);
        stage.show();
        if (had != null) had.requestFocus();
        timer.playFromStart();
    }

    private Stage build() {
        Label app = new Label("Unichat");
        app.getStyleClass().add("desktop-toast-app");
        title.getStyleClass().add("desktop-toast-title");
        body.getStyleClass().add("desktop-toast-body");
        body.setWrapText(true);
        body.setMaxHeight(60);
        VBox content = new VBox(2, app, title, body);
        content.setMaxWidth(Double.MAX_VALUE);
        // A button, so screen readers and the keyboard can open the chat too.
        card.setGraphic(content);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.getStyleClass().add("desktop-toast");
        card.setPrefWidth(WIDTH);
        card.setMaxWidth(WIDTH);
        card.setCursor(Cursor.HAND);
        // Clicked with the mouse only: it must never be the target of keys typed for the chat.
        card.setFocusTraversable(false);
        card.setOnAction(e -> {
            hide();
            onOpen.accept(chatId);
        });
        card.addEventFilter(javafx.scene.input.KeyEvent.ANY, javafx.event.Event::consume);
        // Keep it while the pointer is on it, like system notifications.
        card.setOnMouseEntered(e -> timer.pause());
        card.setOnMouseExited(e -> timer.playFromStart());

        StackPane root = new StackPane(card);
        root.getStyleClass().add("desktop-toast-root");
        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        Theme.register(scene);
        Stage s = new Stage(StageStyle.TRANSPARENT);
        s.setAlwaysOnTop(true);
        s.setResizable(false);
        s.setTitle("Notificación de Unichat");
        s.setScene(scene);
        return s;
    }

    private void hide() {
        timer.stop();
        if (stage != null) stage.hide();
    }
}
