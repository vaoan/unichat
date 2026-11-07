package app.unichat.ui;

import app.unichat.matrix.MatrixLogin;
import app.unichat.server.LocalServer;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
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
        Label title = new Label(I18n.t("login.welcome"));
        title.getStyleClass().add("login-title");
        Label sub = new Label(I18n.t("login.subtitle"));
        sub.setWrapText(true);
        sub.getStyleClass().add("login-subtitle");

        TextField server = new TextField();
        server.setPromptText(I18n.t("login.server.prompt"));
        TextField user = new TextField();
        user.setPromptText(I18n.t("login.user.prompt"));
        PasswordField pass = new PasswordField();
        pass.setPromptText(I18n.t("login.password.prompt"));
        for (TextField f : new TextField[]{server, user, pass}) f.getStyleClass().add("login-field");

        Label error = new Label();
        error.getStyleClass().add("login-error");
        error.setWrapText(true);
        Nodes.show(error, false);

        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(18, 18);
        spin.setVisible(false);
        Button login = new Button(I18n.t("login.signIn"));
        login.getStyleClass().add("primary-button");
        login.setDefaultButton(true);
        login.setMaxWidth(Double.MAX_VALUE);
        login.setOnAction(e -> {
            if (server.getText().isBlank() || user.getText().isBlank() || pass.getText().isEmpty()) {
                showError(error, I18n.t("login.fillAll"));
                return;
            }
            login.setDisable(true);
            spin.setVisible(true);
            Nodes.show(error, false);
            MatrixLogin.login(server.getText().trim(), user.getText().trim(), pass.getText())
                    .whenComplete((svc, err) -> Platform.runLater(() -> {
                        login.setDisable(false);
                        spin.setVisible(false);
                        if (err != null) showError(error, LoginTexts.rootMessage(err));
                        else onLoggedIn.accept(svc);
                    }));
        });

        // Recommended path: Unichat installs and runs its own server on this computer (no Docker, no passwords).
        LocalServer local = LocalServer.get();
        Button useLocal = new Button(I18n.t("login.useLocal"));
        useLocal.getStyleClass().add("primary-button");
        useLocal.setMaxWidth(Double.MAX_VALUE);
        Label localStatus = new Label(I18n.t("login.localInfo"));
        localStatus.getStyleClass().add("login-subtitle");
        localStatus.setWrapText(true);
        useLocal.setOnAction(e -> startLocalServer(local, useLocal, login, localStatus, error, onLoggedIn));
        if (local != null) {
            login.getStyleClass().setAll("button", "secondary-button");
            login.setDefaultButton(false);
            useLocal.setDefaultButton(true);
        }

        Button demo = demoButton(onDemo);
        Hyperlink help = helpLink(openLink);

        StackPane loginRow = new StackPane(login, spin);
        StackPane.setAlignment(spin, Pos.CENTER_RIGHT);
        VBox card = new VBox(12, logo, title, sub);
        if (local != null) card.getChildren().addAll(useLocal, localStatus, new Label(I18n.t("login.orOwnServer")));
        card.getChildren().addAll(server, user, pass, error, loginRow, new Label(I18n.t("login.or")), demo, help);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().addAll("login-card", "card");
        card.setMaxSize(420, VBox.USE_PREF_SIZE);
        getChildren().add(card);
    }

    private static void startLocalServer(LocalServer local, Button useLocal, Button login, Label localStatus, Label error,
                                         Consumer<ChatService> onLoggedIn) {
        useLocal.setDisable(true);
        login.setDisable(true);
        Nodes.show(error, false);
        Thread.ofVirtual().name("local-server-setup").start(() -> {
            try {
                local.ensureRunning(msg -> Platform.runLater(() -> localStatus.setText(msg)));
                String[] cred = local.credentials();
                Platform.runLater(() -> localStatus.setText(I18n.t("login.signingIn")));
                ChatService svc = MatrixLogin.login(LocalServer.URL, cred[0], cred[1]).join();
                Settings.get().setLocalServer(true);
                Platform.runLater(() -> onLoggedIn.accept(svc));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    useLocal.setDisable(false);
                    login.setDisable(false);
                    localStatus.setText("");
                    showError(error, LoginTexts.rootMessage(ex));
                });
            }
        });
    }

    private static Button demoButton(Runnable onDemo) {
        Button demo = new Button(I18n.t("login.tryDemo"));
        demo.getStyleClass().add("secondary-button");
        demo.setMaxWidth(Double.MAX_VALUE);
        demo.setOnAction(e -> onDemo.run());
        return demo;
    }

    private static Hyperlink helpLink(Consumer<String> openLink) {
        Hyperlink help = new Hyperlink(I18n.t("login.noServerHelp"));
        help.getStyleClass().add("login-help");
        help.setOnAction(e -> openLink.accept("https://github.com/vaoan/unichat/blob/main/docs/BRIDGES.md"));
        return help;
    }

    private static void showError(Label l, String text) {
        l.setText(text);
        Nodes.show(l, true);
    }
}
