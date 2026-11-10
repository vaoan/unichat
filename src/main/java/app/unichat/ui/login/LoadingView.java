package app.unichat.ui.login;

import app.unichat.ui.Labels;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;

/** A spinner and what it's waiting for, until the wait ends or fails. */
final class LoadingView extends VBox {
    private final ProgressIndicator spin = new ProgressIndicator();
    private final Label wait;

    LoadingView(String text) {
        super(14);
        spin.setMaxSize(36, 36);
        wait = Labels.help(text);
        getChildren().addAll(spin, wait);
        setAlignment(Pos.CENTER);
    }

    /** The wait is over and it went wrong: says why, with no spinner. */
    void fail(String message) {
        spin.setVisible(false);
        wait.setText(message);
    }
}
