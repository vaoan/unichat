package app.unichat.ui;

import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;

/**
 * A spinner and what it's waiting for, until the wait ends or fails: a login being prepared, the window being built
 * again after it was let go ({@link AwayRelease}). A screen reader reads what it waits for.
 */
public final class LoadingView extends VBox {
    private final ProgressIndicator spin = new ProgressIndicator();
    private final Label wait;

    public LoadingView(String text) {
        super(14);
        getStyleClass().add("loading-view");
        spin.setMaxSize(36, 36);
        Showing.spinWhileSeen(spin);
        wait = Labels.help(text);
        getChildren().addAll(spin, wait);
        setAlignment(Pos.CENTER);
        setAccessibleRole(AccessibleRole.TEXT);
        accessibleTextProperty().bind(wait.textProperty());
    }

    /** What it says it's waiting for. */
    public String text() {
        return wait.getText();
    }

    /** The wait is over and it went wrong: says why, with no spinner. */
    public void fail(String message) {
        spin.setVisible(false);
        wait.setText(message);
    }
}
