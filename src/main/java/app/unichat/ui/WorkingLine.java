package app.unichat.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/**
 * A line above the message box while something takes a while and nothing else shows it (a voice note being cleaned up):
 * what it is and the seconds so far. Several at once share it; it goes when the last one ends. FX thread only.
 */
public final class WorkingLine {
    private final Label text = new Label();
    private final HBox node;
    /** The seconds so far, counted only while someone can see them ({@link Showing#animate}). */
    private final Timeline ticking = new Timeline(new KeyFrame(Duration.seconds(1), e -> say()));
    private final Showing.Animated tick;
    private String what = "";
    private long since;
    private int running;

    public WorkingLine() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(14, 14);
        Nodes.show(spinner, !Theme.reduceMotion());
        text.getStyleClass().add("settings-help");
        node = new HBox(6, spinner, text);
        node.setAlignment(Pos.CENTER_LEFT);
        node.getStyleClass().add("ai-status");
        Nodes.show(node, false);
        ticking.setCycleCount(Timeline.INDEFINITE);
        tick = Showing.animate(node, ticking);
    }

    public Node node() { return node; }

    public void start(String what) {
        running++;
        this.what = what;
        since = System.currentTimeMillis();
        text.setText(what + "…");
        text.setAccessibleText(what);
        ticking.stop(); // counts again from this start
        tick.want(true);
        Nodes.show(node, true);
    }

    public void stop() {
        running = Math.max(0, running - 1);
        if (running > 0) return;
        tick.want(false);
        Nodes.show(node, false);
    }

    private void say() {
        text.setText(what + "… " + (System.currentTimeMillis() - since) / 1000 + " s");
    }

    boolean shown() { return node.isVisible(); }
}
