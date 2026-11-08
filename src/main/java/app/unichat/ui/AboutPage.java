package app.unichat.ui;

import app.unichat.settings.Settings;
import app.unichat.support.AppVersion;
import app.unichat.support.ErrorReport;
import app.unichat.support.I18n;
import app.unichat.update.UpdateChecker;
import javafx.application.Platform;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

import static app.unichat.ui.SettingsForm.group;
import static app.unichat.ui.SettingsForm.page;
import static app.unichat.ui.SettingsForm.row;

/** Ajustes → Acerca de: the version and updates, help with errors, the risk of unofficial bridges, the license. */
final class AboutPage {
    private AboutPage() {}

    static Node build() {
        Label risk = Labels.help(I18n.t("accounts.riskNotice"));
        risk.getStyleClass().add("risk-notice");
        return page(I18n.t("settings.nav.about"),
                Labels.help(I18n.t("settings.about.text")),
                updatesGroup(), errorsGroup(),
                group(I18n.t("settings.about.groupAccounts"), risk),
                Labels.help(I18n.t("settings.about.license")));
    }

    private static Node updatesGroup() {
        Label status = Labels.help("");
        status.setAccessibleRole(AccessibleRole.TEXT);
        Hyperlink download = new Hyperlink();
        VBox result = new VBox(2, status, download);
        result.getStyleClass().add("settings-row");
        Nodes.show(result, false);
        Nodes.show(download, false);
        Button check = new Button(I18n.t("settings.about.checkButton"));
        check.getStyleClass().add("secondary-button");
        check.setAccessibleText(check.getText());
        check.setOnAction(e -> {
            check.setDisable(true);
            say(status, I18n.t("settings.about.checking"));
            Nodes.show(result, true);
            Nodes.show(download, false);
            UpdateChecker.get().checkNow().thenAccept(r -> Platform.runLater(() -> {
                check.setDisable(false);
                switch (r.outcome()) {
                    case UP_TO_DATE -> say(status, I18n.t("settings.about.upToDate", AppVersion.current()));
                    case FAILED -> say(status, I18n.t("settings.about.checkFailed"));
                    case NEWER -> {
                        say(status, I18n.t("update.available", r.release().shown()));
                        download.setText(I18n.t("settings.about.downloadLink", r.release().shown()));
                        download.setOnAction(ev -> Browser.open(r.release().url()));
                        Nodes.show(download, true);
                    }
                }
            }));
        });
        return group(I18n.t("settings.about.groupUpdates"),
                row(I18n.t("settings.about.check", AppVersion.current()), I18n.t("settings.about.checkHelp"), check), result);
    }

    private static Node errorsGroup() {
        Path logs = Settings.dataDir().resolve("logs");
        Button open = new Button(I18n.t("settings.about.openLogs"));
        open.getStyleClass().add("secondary-button");
        open.setAccessibleText(open.getText());
        open.setOnAction(e -> Browser.openFolder(logs, () -> {
            open.setText(I18n.t("settings.about.openLogsFailed"));
            open.setAccessibleText(open.getText());
        }));
        Button copy = new Button(I18n.t("settings.about.copyReport"));
        copy.getStyleClass().add("secondary-button");
        copy.setAccessibleText(copy.getText());
        copy.setOnAction(e -> {
            copy.setDisable(true);
            Thread.ofVirtual().start(() -> {
                String report = ErrorReport.of(logs.resolve("unichat.log"));
                Platform.runLater(() -> {
                    Clipboards.copy(report);
                    copy.setText(I18n.t("settings.about.copied"));
                    copy.setAccessibleText(copy.getText());
                    copy.notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
                    copy.setDisable(false);
                });
            });
        });
        return group(I18n.t("settings.about.groupErrors"),
                row(I18n.t("settings.about.logs"), logs.toString(), open),
                row(I18n.t("settings.about.report"), I18n.t("settings.about.reportHelp"), copy));
    }

    /** Shows {@code text} and has a screen reader say it. */
    private static void say(Label l, String text) {
        l.setText(text);
        l.setAccessibleText(text);
        l.notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
    }
}
