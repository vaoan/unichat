package app.unichat.ui.media;

import app.unichat.ui.Icons;
import app.unichat.ui.Showing;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

/**
 * A small ring over a picture that's loading: an arc as long as the share that arrived, or a short arc going round
 * while that isn't known (only while it can be seen). Sized like an icon, so it grows with the text. Track and arc share
 * one centre in a Group placed by that centre, turned about it: centred by bounds, the arc sat off its track (a quarter
 * circle's bounds, and a thicker stroke than the track's, aren't the circle's).
 */
final class ProgressRing extends StackPane {
    private static final double SIZE = 26, UNKNOWN_ARC = -90;
    private final Arc arc = new Arc();
    private final Group dial = new Group();
    private final Rotate turn = new Rotate();
    private final Timeline spin = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(turn.angleProperty(), 0)),
            new KeyFrame(Duration.seconds(1), new KeyValue(turn.angleProperty(), 360, Interpolator.LINEAR)));
    private double progress = -1;
    private boolean shown;

    ProgressRing() {
        getStyleClass().add("media-loading");
        Circle track = new Circle();
        track.getStyleClass().add("media-loading-track");
        arc.getStyleClass().add("media-loading-arc");
        arc.setType(ArcType.OPEN);
        arc.setStartAngle(90);
        var radius = Icons.SCALE.multiply(SIZE / 2 - 2);
        track.radiusProperty().bind(radius);
        arc.radiusXProperty().bind(radius);
        arc.radiusYProperty().bind(radius);
        prefWidthProperty().bind(Icons.SCALE.multiply(SIZE));
        prefHeightProperty().bind(Icons.SCALE.multiply(SIZE));
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setMouseTransparent(true);
        setAccessibleRole(javafx.scene.AccessibleRole.PROGRESS_INDICATOR);
        arc.getTransforms().add(turn);
        dial.getChildren().addAll(track, arc);
        dial.setManaged(false);
        getChildren().add(dial);
        spin.setCycleCount(Animation.INDEFINITE);
        Showing.whileShowing(this, () -> {
            shown = true;
            follow();
        }, () -> {
            shown = false;
            spin.stop();
        });
        follow();
    }

    @Override
    protected void layoutChildren() {
        dial.setLayoutX(getWidth() / 2);
        dial.setLayoutY(getHeight() / 2);
    }

    /** 0..1, or below 0 when how much isn't known. */
    void setProgress(double p) {
        progress = p < 0 ? -1 : Math.min(1, p);
        follow();
    }

    private void follow() {
        if (progress < 0) {
            arc.setLength(UNKNOWN_ARC);
            if (shown && spin.getStatus() != Animation.Status.RUNNING) spin.play();
        } else {
            spin.stop();
            turn.setAngle(0);
            arc.setLength(-360 * progress);
        }
    }
}
