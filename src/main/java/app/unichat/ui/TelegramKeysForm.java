package app.unichat.ui;

import app.unichat.server.TelegramKeys;
import app.unichat.support.I18n;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Asks for the person's own Telegram API keys, with how to get them: Telegram requires them and Unichat ships none.
 * Used when connecting Telegram for the first time and in Ajustes → Redes.
 */
final class TelegramKeysForm extends VBox {
    private final TextField id = new TextField(TelegramKeys.apiId());
    private final PasswordField hash = new PasswordField();
    private final Label error = new Label();
    final Button save;

    /** {@code onSaved} runs on the FX thread once valid keys are stored. */
    TelegramKeysForm(String saveText, Runnable onSaved) {
        super(10);
        getStyleClass().add("telegram-keys");
        Label intro = help(I18n.t("settings.telegram.intro"));
        Label steps = help(I18n.t("settings.telegram.steps"));
        Hyperlink open = new Hyperlink(I18n.t("settings.telegram.open"));
        open.setAccessibleText(I18n.t("settings.telegram.openAccessible"));
        open.setOnAction(e -> Browser.open(TelegramKeys.URL)); // the address is in the steps above too
        hash.setText(TelegramKeys.apiHash());
        id.setPromptText(I18n.t("settings.telegram.idPrompt"));
        id.setAccessibleText(I18n.t("settings.telegram.idAccessible"));
        id.getStyleClass().add("telegram-api-id");
        hash.setPromptText(I18n.t("settings.telegram.hashPrompt"));
        hash.setAccessibleText(I18n.t("settings.telegram.hashAccessible"));
        hash.getStyleClass().add("telegram-api-hash");
        Label idLabel = new Label(I18n.t("settings.telegram.idLabel"));
        idLabel.setLabelFor(id);
        Label hashLabel = new Label(I18n.t("settings.telegram.hashLabel"));
        hashLabel.setLabelFor(hash);
        error.getStyleClass().add("login-error");
        error.setWrapText(true);
        error.setManaged(false);
        save = new Button(saveText);
        save.getStyleClass().add("primary-button");
        save.setOnAction(e -> {
            String problem = TelegramKeys.problem(id.getText(), hash.getText());
            error.setText(problem == null ? "" : problem);
            error.setManaged(problem != null);
            setAccessibleHelp(problem);
            if (problem != null) {
                (problem.contains("api_id") ? id : hash).requestFocus();
                return;
            }
            TelegramKeys.save(id.getText(), hash.getText());
            onSaved.run();
        });
        id.setOnAction(e -> save.fire());
        hash.setOnAction(e -> save.fire());
        HBox actions = new HBox(10, save);
        actions.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(intro, steps, open, idLabel, id, hashLabel, hash, error, actions);
    }

    private static Label help(String text) {
        Label l = Labels.help(text);
        l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return l;
    }
}
