package app.unichat.ui;

import app.unichat.matrix.MatrixChatService;
import app.unichat.server.LocalServer;
import app.unichat.service.ChatService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/** First screen: sign in to a Matrix homeserver (with bridges) or open the offline demo. */
public final class LoginView extends StackPane {
    public LoginView(Consumer<ChatService> onLoggedIn, Runnable onDemo, Consumer<String> openLink) {
        getStyleClass().add("login-root");

        javafx.scene.Node logo = Icons.logo(84);
        Label title = new Label("Bienvenido a Unichat");
        title.getStyleClass().add("login-title");
        Label sub = new Label("Todos tus chats —WhatsApp, Telegram, Signal, Instagram, Messenger, Discord, Slack y más— en una sola bandeja.");
        sub.setWrapText(true);
        sub.getStyleClass().add("login-subtitle");

        TextField server = new TextField();
        server.setPromptText("Servidor (ej. matrix.midominio.com o http://localhost:8008)");
        TextField user = new TextField();
        user.setPromptText("Usuario");
        PasswordField pass = new PasswordField();
        pass.setPromptText("Contraseña");
        for (TextField f : new TextField[]{server, user, pass}) f.getStyleClass().add("login-field");

        Label error = new Label();
        error.getStyleClass().add("login-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(18, 18);
        spin.setVisible(false);
        Button login = new Button("Iniciar sesión");
        login.getStyleClass().add("primary-button");
        login.setDefaultButton(true);
        login.setMaxWidth(Double.MAX_VALUE);
        login.setOnAction(e -> {
            if (server.getText().isBlank() || user.getText().isBlank() || pass.getText().isEmpty()) {
                showError(error, "Completa servidor, usuario y contraseña.");
                return;
            }
            login.setDisable(true);
            spin.setVisible(true);
            error.setVisible(false);
            error.setManaged(false);
            MatrixChatService.login(server.getText().trim(), user.getText().trim(), pass.getText())
                    .whenComplete((svc, err) -> Platform.runLater(() -> {
                        login.setDisable(false);
                        spin.setVisible(false);
                        if (err != null) showError(error, AccountsPane.rootMessage(err));
                        else onLoggedIn.accept(svc);
                    }));
        });

        // Recommended path: Unichat installs and runs its own server on this computer (no Docker, no passwords).
        LocalServer local = LocalServer.get();
        Button useLocal = new Button("Usar este equipo (recomendado)");
        useLocal.getStyleClass().add("primary-button");
        useLocal.setMaxWidth(Double.MAX_VALUE);
        Label localStatus = new Label("Unichat instala y mantiene su propio servidor. No necesitas Docker ni contraseñas.");
        localStatus.getStyleClass().add("login-subtitle");
        localStatus.setWrapText(true);
        useLocal.setOnAction(e -> {
            useLocal.setDisable(true);
            login.setDisable(true);
            error.setVisible(false);
            error.setManaged(false);
            Thread.ofVirtual().name("local-server-setup").start(() -> {
                try {
                    local.ensureRunning(msg -> Platform.runLater(() -> localStatus.setText(msg)));
                    String[] cred = local.credentials();
                    Platform.runLater(() -> localStatus.setText("Iniciando sesión…"));
                    ChatService svc = MatrixChatService.login(LocalServer.URL, cred[0], cred[1]).join();
                    app.unichat.settings.Settings.get().setLocalServer(true);
                    Platform.runLater(() -> onLoggedIn.accept(svc));
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        useLocal.setDisable(false);
                        login.setDisable(false);
                        localStatus.setText("");
                        showError(error, AccountsPane.rootMessage(ex));
                    });
                }
            });
        });
        if (local != null) {
            login.getStyleClass().setAll("button", "secondary-button");
            login.setDefaultButton(false);
            useLocal.setDefaultButton(true);
        }

        Button demo = new Button("Probar el modo demo");
        demo.getStyleClass().add("secondary-button");
        demo.setMaxWidth(Double.MAX_VALUE);
        demo.setOnAction(e -> onDemo.run());

        Hyperlink help = new Hyperlink("¿No tienes servidor? Mira docs/BRIDGES.md para montarlo con Docker.");
        help.getStyleClass().add("login-help");
        help.setOnAction(e -> openLink.accept("https://github.com/vaoan/unichat/blob/main/docs/BRIDGES.md"));

        StackPane loginRow = new StackPane(login, spin);
        StackPane.setAlignment(spin, Pos.CENTER_RIGHT);
        VBox card = new VBox(12, logo, title, sub);
        if (local != null) card.getChildren().addAll(useLocal, localStatus, new Label("o con tu propio servidor"));
        card.getChildren().addAll(server, user, pass, error, loginRow, new Label("o"), demo, help);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().addAll("login-card", "card");
        card.setMaxSize(420, VBox.USE_PREF_SIZE);
        getChildren().add(card);
    }

    private static void showError(Label l, String text) {
        l.setText(text);
        l.setVisible(true);
        l.setManaged(true);
    }
}
