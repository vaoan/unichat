package app.unichat.ui;

import app.unichat.server.TelegramKeys;
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
        Label intro = help("Telegram pide que cada persona use sus propias claves. Son gratis, se sacan una sola vez y se "
                + "quedan guardadas solo en este computador. No las compartas con nadie.");
        Label steps = help("1. Entra a my.telegram.org con tu número de Telegram.\n"
                + "2. Abre \"API development tools\" y crea una aplicación (el nombre no importa).\n"
                + "3. Copia aquí el \"App api_id\" y el \"App api_hash\".");
        Hyperlink open = new Hyperlink("Abrir my.telegram.org");
        open.setAccessibleText("Abrir my.telegram.org en el navegador");
        open.setOnAction(e -> java.awt.EventQueue.invokeLater(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(TelegramKeys.URL));
            } catch (Exception ignored) {
                // No browser: the address is in the steps above.
            }
        }));
        hash.setText(TelegramKeys.apiHash());
        id.setPromptText("Por ejemplo 1234567");
        id.setAccessibleText("App api_id de Telegram");
        id.getStyleClass().add("telegram-api-id");
        hash.setPromptText("32 caracteres");
        hash.setAccessibleText("App api_hash de Telegram");
        hash.getStyleClass().add("telegram-api-hash");
        Label idLabel = new Label("App api_id");
        idLabel.setLabelFor(id);
        Label hashLabel = new Label("App api_hash");
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
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        l.getStyleClass().add("settings-help");
        return l;
    }
}
