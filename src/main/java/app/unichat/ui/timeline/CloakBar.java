package app.unichat.ui.timeline;

import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Icons;
import app.unichat.ui.Nodes;
import app.unichat.ui.TaskBar;
import app.unichat.cloak.CloakJob;
import app.unichat.support.I18n;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;

/**
 * On top of the window while a picture is being protected (and Mist installed the first time), like the upload bar:
 * which picture and how hard, how far, the time left and a way to stop it. Once it's done the upload bar takes over;
 * a failure stays until closed.
 */
public final class CloakBar extends TaskBar {
    private final CloakJob job;
    private final Button cancel;
    private final Button close;
    private final FxCoalescer redraw = new FxCoalescer(this::refresh);
    private final Runnable listener = redraw::request;

    public CloakBar(CloakJob job) {
        super("cloak-bar");
        this.job = job;
        what.setWrapText(true); // a long name at a big text size takes two lines rather than losing its end
        stage.setWrapText(true);
        lead.getChildren().setAll(Icons.icon(Icons.SHIELD, 20));
        cancel = Icons.iconButton(Icons.CLOSE, I18n.t("cloak.bar.cancel"), job::cancel);
        close = Icons.iconButton(Icons.CLOSE, I18n.t("cloak.bar.close"), job::dismiss);
        actions(cancel, close);
        // Listens only while in a window: it's rebuilt when the language changes, and the old bar must let go.
        sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) job.removeListener(listener);
            else job.addListener(listener);
        });
        refresh();
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
        big.setText(s.fraction() >= 0 && !failed ? percent(s.fraction()) : "");
        small.setText(failed || s.phase() != CloakJob.Phase.PROTECTING ? "" : eta(etaMs(s)));
        tidy();
        Nodes.show(cancel, !failed);
        Nodes.show(close, failed);
        setAccessibleText(what.getText() + ". " + stage.getText()
                + (big.getText().isEmpty() ? "" : ", " + big.getText()) + (small.getText().isEmpty() ? "" : ". " + small.getText()));
    }

    /** From the pace so far; unknown until a little has been done. */
    static long etaMs(CloakJob.Status s) {
        if (s.fraction() < 0.02 || s.startedMs() <= 0) return -1;
        long spent = System.currentTimeMillis() - s.startedMs();
        return (long) (spent * (1 - s.fraction()) / s.fraction());
    }
}
