package app.unichat.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Three dots lighting up in turn while someone types. A few steps a second, not a smooth animation, and only while on
 * screen: this sits in list rows that come and go, and there can be several at once. Reduced motion: still dots.
 */
final class TypingDots extends HBox {
    static final double BRIGHT = 1, DIM = 0.3;
    private static final int DOTS = 3;
    /** One step per dot and one with all of them dim: a beat of rest before it starts over. */
    private static final int STEPS = DOTS + 1;
    private static final double DOT_SIZE = 5;

    private final Timeline ticks;
    private int step;

    TypingDots() {
        super(3);
        getStyleClass().add("typing-dots");
        setAlignment(Pos.CENTER);
        setMouseTransparent(true);
        for (int i = 0; i < DOTS; i++) {
            Region dot = new Region();
            dot.getStyleClass().add("typing-dot");
            dot.setMinSize(DOT_SIZE, DOT_SIZE);
            dot.setMaxSize(DOT_SIZE, DOT_SIZE);
            dot.setPrefSize(DOT_SIZE, DOT_SIZE);
            dot.setOpacity(BRIGHT);
            getChildren().add(dot);
        }
        ticks = new Timeline(new KeyFrame(Duration.millis(220), e -> tick()));
        ticks.setCycleCount(Animation.INDEFINITE);
        sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) ticks.stop();
            else if (!Theme.reduceMotion()) ticks.play();
        });
    }

    /**
     * Shows {@code text} in {@code label} with the dots after it, in place of its "…"; null clears both. A screen reader
     * still hears the sentence as it is.
     */
    static void show(Label label, String text) {
        if (text == null) {
            label.setGraphic(null);
            label.setText("");
            label.setAccessibleText(null);
            return;
        }
        label.setText(text.endsWith("…") ? text.substring(0, text.length() - 1) : text);
        label.setAccessibleText(text);
        label.setContentDisplay(ContentDisplay.RIGHT);
        if (!(label.getGraphic() instanceof TypingDots)) label.setGraphic(new TypingDots());
    }

    boolean isRunning() { return ticks.getStatus() == Animation.Status.RUNNING; }

    static double opacity(int dot, int step) { return dot == step ? BRIGHT : DIM; }

    private void tick() {
        // Hidden behind a closed window or a scrolled-away row: no repaint for nobody.
        if (!AnimatedImage.onScreen(this)) return;
        step = (step + 1) % STEPS;
        for (int i = 0; i < DOTS; i++) getChildren().get(i).setOpacity(opacity(i, step));
    }
}
