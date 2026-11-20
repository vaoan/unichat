package app.unichat.ui.settings;

import app.unichat.cloud.Cloud;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import app.unichat.ui.Labels;
import app.unichat.ui.Nodes;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.Arrays;

import static app.unichat.ui.settings.SettingsForm.actions;
import static app.unichat.ui.settings.SettingsForm.stacked;

/** "Agregar contraseña": typed twice, at least 12 characters; both fields emptied once it goes to be added. */
final class VaultPasswordForm extends VBox {
    /** @param added run on the FX thread once the password opens the vault */
    VaultPasswordForm(Cloud cloud, Runnable added, Runnable cancel) {
        PasswordField first = new PasswordField(), again = new PasswordField();
        first.setAccessibleText(I18n.t("settings.vault.password.field"));
        again.setAccessibleText(I18n.t("settings.vault.password.again"));
        first.getStyleClass().add("vault-password");
        again.getStyleClass().add("vault-password-again");
        Label error = Labels.help("");
        error.getStyleClass().add("login-error");
        Nodes.show(error, false);
        Button add = new Button(I18n.t("settings.vault.password.add"));
        add.getStyleClass().add("primary-button");
        add.setDefaultButton(true);
        Button no = new Button(I18n.t("folders.dialog.cancel"));
        no.getStyleClass().add("secondary-button");
        no.setOnAction(e -> cancel.run());
        add.setOnAction(e -> {
            char[] password = first.getText().toCharArray(), repeated = again.getText().toCharArray();
            first.clear();
            again.clear();
            boolean same = Arrays.equals(password, repeated);
            Arrays.fill(repeated, '\0');
            if (!same) {
                Arrays.fill(password, '\0');
                error.setText(I18n.t("settings.vault.password.differ"));
                Nodes.show(error, true);
                first.requestFocus();
                return;
            }
            add.setDisable(true);
            Nodes.show(error, false);
            cloud.addPassword(password).whenComplete((ok, err) -> Platform.runLater(() -> {
                add.setDisable(false);
                if (err == null) {
                    added.run();
                    return;
                }
                error.setText(Errors.message(Errors.unwrap(err)));
                Nodes.show(error, true);
                first.requestFocus();
            }));
        });
        getChildren().addAll(stacked(I18n.t("settings.vault.password.field"), I18n.t("settings.vault.password.help"), new VBox(8, first, again)),
            actions(error, new FlowPane(8, 6, add, no)));
    }
}
