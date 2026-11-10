package app.unichat.ui;

import app.unichat.cloak.CloakJob;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On top of the window while a picture is being protected (and Mist installed the first time), like the upload bar:
 * which picture and how hard, how far, the time left and a way to stop it. Once it's done the upload bar takes over;
 * a failure stays until closed.
 */
final class CloakBar extends HBox {
    private final CloakJob job;
    private final Label what = new Label();
    private final Label stage = new Label();
    private final ProgressBar bar = new ProgressBar(0);
    private final Label percent = new Label();
    private final Label eta = new Label();
    private final Button cancel;
    private final Button close;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Runnable listener = this::schedule;

    CloakBar(CloakJob job) {
        super(10);
        this.job = job;
        getStyleClass().addAll("audio-bar", "upload-bar", "cloak-bar");
        setAlignment(Pos.CENTER_LEFT);
        what.getStyleClass().add("audio-who");
        what.setMinWidth(0);
        stage.getStyleClass().add("audio-time");
        stage.setMinWidth(0);
        stage.setWrapText(true);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setMinWidth(80);
        VBox middle = new VBox(3, what, bar, stage);
        middle.setAlignment(Pos.CENTER_LEFT);
        middle.setMinWidth(80);
        HBox.setHgrow(middle, Priority.ALWAYS);
        percent.getStyleClass().add("upload-percent");
        eta.getStyleClass().add("audio-time");
        VBox numbers = new VBox(0, percent, eta);
        numbers.setAlignment(Pos.CENTER_LEFT);
        numbers.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        cancel = Icons.iconButton(Icons.CLOSE, I18n.t("cloak.bar.cancel"), job::cancel);
        close = Icons.iconButton(Icons.CLOSE, I18n.t("cloak.bar.close"), job::dismiss);
        getChildren().addAll(Icons.icon(Icons.SHIELD, 20), middle, numbers, cancel, close);
        // Listens only while in a window: it's rebuilt when the language changes, and the old bar must let go.
        sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) job.removeListener(listener);
            else job.addListener(listener);
        });
        refresh();
    }

    private void schedule() {
        if (scheduled.compareAndSet(false, true)) Platform.runLater(() -> {
            scheduled.set(false);
            refresh();
        });
    }

    private void refresh() {
        CloakJob.Status s = job.status();
        switch (s.phase()) {
            case IDLE, CANCELLED -> {
                Nodes.show(this, false);
                if (s.phase() == CloakJob.Phase.CANCELLED) job.dismiss();
                return;
            }
            case DONE -> {
                Nodes.show(this, false); // the upload bar shows it going
                job.dismiss();
                return;
            }
            default -> Nodes.show(this, true);
        }
        String level = I18n.t("cloak.level." + s.level().name());
        boolean failed = s.phase() == CloakJob.Phase.FAILED;
        what.setText(I18n.t(failed ? "cloak.bar.failed" : "cloak.bar.protecting", s.file(), level));
        stage.setText(failed ? s.error()
                : s.phase() == CloakJob.Phase.INSTALLING ? I18n.t(s.stepKey()) : I18n.t("cloak.bar.working"));
        bar.setProgress(failed ? 0 : s.fraction() < 0 ? ProgressBar.INDETERMINATE_PROGRESS : s.fraction());
        Nodes.show(bar, !failed);
        percent.setText(s.fraction() >= 0 && !failed ? UploadBar.percent(s.fraction()) : "");
        eta.setText(failed || s.phase() != CloakJob.Phase.PROTECTING ? "" : UploadBar.eta(etaMs(s)));
        Nodes.show(cancel, !failed);
        Nodes.show(close, failed);
        setAccessibleText(what.getText() + ". " + stage.getText()
                + (percent.getText().isEmpty() ? "" : ", " + percent.getText()) + (eta.getText().isEmpty() ? "" : ". " + eta.getText()));
    }

    /** From the pace so far; unknown until a little has been done. */
    static long etaMs(CloakJob.Status s) {
        if (s.fraction() < 0.02 || s.startedMs() <= 0) return -1;
        long spent = System.currentTimeMillis() - s.startedMs();
        return (long) (spent * (1 - s.fraction()) / s.fraction());
    }
}
