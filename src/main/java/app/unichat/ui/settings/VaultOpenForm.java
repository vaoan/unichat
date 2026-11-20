package app.unichat.ui.settings;

import app.unichat.cloud.Cloud;
import app.unichat.cloud.VaultView;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import app.unichat.ui.Labels;
import app.unichat.ui.Nodes;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static app.unichat.ui.settings.SettingsForm.actions;
import static app.unichat.ui.settings.SettingsForm.stacked;

/**
 * "Abrir la bóveda": one field for each way in the vault has that can be typed here (the recovery code, the password),
 * each with its own button. A wrong one says so and can be tried again at once; a field is emptied as soon as what
 * was typed in it goes to be tried, and it never reaches a log.
 */
final class VaultOpenForm extends VBox {
    private final Label error = Labels.help("");
    private final List<Button> buttons = new ArrayList<>();

    /** @param opened run on the FX thread once the vault is open here */
    VaultOpenForm(Cloud cloud, Set<VaultView.Way> ways, Runnable opened) {
        error.getStyleClass().add("login-error");
        Nodes.show(error, false);
        if (ways.contains(VaultView.Way.RECOVERY)) {
            TextField code = new TextField();
            code.setPromptText("ABCD-EFGH-…");
            code.getStyleClass().add("vault-code");
            getChildren().add(way(cloud, VaultView.Way.RECOVERY, code, opened));
        }
        if (ways.contains(VaultView.Way.PASSWORD)) {
            PasswordField password = new PasswordField();
            password.getStyleClass().add("vault-password");
            getChildren().add(way(cloud, VaultView.Way.PASSWORD, password, opened));
        }
        if (getChildren().isEmpty()) getChildren().add(actions(Labels.help(I18n.t("settings.vault.open.noWay"))));
        getChildren().add(actions(error));
    }

    private javafx.scene.Node way(Cloud cloud, VaultView.Way way, TextField field, Runnable opened) {
        String name = way.name();
        field.setAccessibleText(I18n.t("settings.vault.open.field." + name));
        Button open = new Button(I18n.t("settings.vault.open.with." + name));
        open.getStyleClass().add("primary-button");
        buttons.add(open);
        Runnable go = () -> {
            if (field.getText().isEmpty()) return;
            char[] secret = field.getText().toCharArray();
            field.clear();
            Nodes.show(error, false);
            buttons.forEach(b -> b.setDisable(true));
            cloud.vault().openVault(way, secret).whenComplete((ok, err) -> Platform.runLater(() -> {
                buttons.forEach(b -> b.setDisable(false));
                if (err == null) {
                    opened.run();
                    return;
                }
                error.setText(Errors.message(Errors.unwrap(err)));
                Nodes.show(error, true);
                field.requestFocus();
            }));
        };
        open.setOnAction(e -> go.run());
        field.setOnAction(e -> go.run()); // Enter in the field
        field.setPrefColumnCount(22);
        return stacked(I18n.t("settings.vault.open.field." + name), I18n.t("settings.vault.open.help." + name),
            new FlowPane(8, 6, field, open));
    }

    /** What the person sees when it didn't open (for tests); empty while nothing failed. */
    String error() {
        return error.isVisible() ? error.getText() : "";
    }
}
