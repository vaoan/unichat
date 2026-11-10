package app.unichat.ui;

import app.unichat.support.I18n;
import app.unichat.video.Ffmpeg;
import app.unichat.video.WebmStickers;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

import static app.unichat.ui.SettingsForm.row;
import static app.unichat.ui.SettingsForm.toggle;

/**
 * Ajustes → Redes → "Conversor de video y audio": ffmpeg, said for what it is (what it's used for, in plain words), its
 * state at a glance (ready and which one, missing, or installed when first needed) and, when missing, one button with
 * the size and a progress bar.
 */
final class ConverterSettings {
    /** What the state line says, decided apart from the screen. */
    enum State { READY_OWN, READY_OURS, WILL_INSTALL, MISSING, NO_DOWNLOAD }

    private ConverterSettings() {}

    static State state(Path found, boolean ours, boolean canInstall, boolean auto) {
        if (found != null) return ours ? State.READY_OURS : State.READY_OWN;
        if (!canInstall) return State.NO_DOWNLOAD;
        return auto ? State.WILL_INSTALL : State.MISSING;
    }

    static Node section() {
        Label why = Labels.help(I18n.t("settings.converter.why"));
        Label status = new Label();
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.getStyleClass().add("converter-status");
        ProgressBar progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        Nodes.show(progress, false);
        Button install = new Button(I18n.t("settings.converter.installNow", Formats.fileSize(Ffmpeg.downloadSize())),
                Icons.icon(Icons.DOWNLOAD, 16));
        install.getStyleClass().add("secondary-button");
        install.setMinWidth(Region.USE_PREF_SIZE);
        Runnable refresh = () -> {
            Path found = Ffmpeg.find();
            State s = state(found, Ffmpeg.downloadedByUnichat(found), Ffmpeg.canInstall(), Ffmpeg.autoInstall());
            status.setText(switch (s) {
                case READY_OWN -> I18n.t("settings.converter.readyOwn", found);
                case READY_OURS -> I18n.t("settings.converter.readyOurs");
                case WILL_INSTALL -> I18n.t("settings.converter.willInstall", Formats.fileSize(Ffmpeg.downloadSize()));
                case MISSING -> I18n.t("settings.converter.missing");
                case NO_DOWNLOAD -> I18n.t("settings.converter.noDownload");
            });
            boolean ready = s == State.READY_OWN || s == State.READY_OURS;
            status.setGraphic(Icons.icon(ready ? Icons.CHECK : Icons.ERROR, 16));
            Nodes.style(status, "ready", ready);
            Nodes.show(install, !ready && Ffmpeg.canInstall());
        };
        refresh.run();
        install.setOnAction(e -> {
            install.setDisable(true);
            Nodes.show(progress, true);
            status.setText(I18n.t("settings.networks.downloading"));
            WebmStickers.WORKER.execute(() -> {
                String error = null;
                try {
                    Ffmpeg.install(done -> Platform.runLater(() -> progress.setProgress(done)));
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
                String failed = error;
                Platform.runLater(() -> {
                    install.setDisable(false);
                    Nodes.show(progress, false);
                    refresh.run();
                    if (failed != null) status.setText(I18n.t("settings.networks.installFailed", failed));
                });
            });
        });
        VBox about = new VBox(8, why, status, new HBox(install), progress);
        about.getStyleClass().add("settings-row");
        return SettingsForm.group(I18n.t("settings.converter.group"), about,
                row(I18n.t("settings.converter.auto"), I18n.t("settings.converter.autoHelp", Formats.fileSize(Ffmpeg.downloadSize())),
                        toggle(Ffmpeg.autoInstall(), v -> {
                            Ffmpeg.setAutoInstall(v);
                            refresh.run();
                        })));
    }
}
