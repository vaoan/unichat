package app.unichat.ui;

import app.unichat.login.BrowserSession;
import app.unichat.login.CookieLogin;
import app.unichat.login.CookieSpec;
import app.unichat.login.DiscordToken;
import app.unichat.login.SessionCookies;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.support.Os;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import javafx.scene.Scene;
import java.util.function.Supplier;

/** The first screen of a bot-chat login: how to get the session (browser, web window, cookies.txt, Discord's page). */
final class LoginChoosers {
    /** Shows a step of the login sheet. */
    interface Views {
        void show(String heading, Node content, Runnable backAction);
    }

    private final ChatService svc;
    private final Consumer<String> toast;
    private final Views views;
    private final Supplier<Scene> scene;
    private final Supplier<LoginWizard> wizard;

    LoginChoosers(ChatService svc, Consumer<String> toast, Views views, Supplier<Scene> scene, Supplier<LoginWizard> wizard) {
        this.svc = svc;
        this.toast = toast;
        this.views = views;
        this.scene = scene;
        this.wizard = wizard;
    }

    /**
     * Discord's QR login through the bridge ends in a captcha the bridge can't show (seen every time). Logging in on
     * discord.com inside Unichat lets the user solve it; the account token is then handed to the bridge (which
     * deletes that message).
     */
    void showDiscordChoice(Network n, String botChatId, Runnable cancel) {
        LoginWizard w = wizard.get();
        Label heading = new Label("Conecta tu cuenta de Discord");
        heading.getStyleClass().add("wizard-instructions");
        heading.setWrapText(true);

        Button web = new Button("Iniciar sesión en Discord");
        web.getStyleClass().add("primary-button");
        web.setMaxWidth(Double.MAX_VALUE);
        web.setAccessibleText("Iniciar sesión en Discord en una ventana de Unichat");
        // The bridge's own QR login always ended in a captcha it can't show; the web page's QR (inside this window) works.
        Label webHelp = new Label("Se abre Discord aquí mismo. Entra con tu correo y contraseña o escanea el QR de la página con la app de Discord; si te pide un captcha, lo resuelves ahí.");
        webHelp.setWrapText(true);
        webHelp.getStyleClass().add("settings-help");


        Label status = new Label();
        status.setWrapText(true);
        status.getStyleClass().add("settings-help");

        VBox content = new VBox(12, heading, web, webHelp, status);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");

        Consumer<String> send = command -> {
            views.show("Conectar " + n.displayName(), w.root, cancel);
            w.sessionSent();
            svc.sendLoginInput(n, botChatId, command).exceptionally(err -> {
                Platform.runLater(() -> {
                    if (wizard.get() != w || w.finished) return;
                    w.sessionFailed();
                    views.show("Conectar " + n.displayName(), scroll, cancel);
                    status.setText("No se pudo enviar: " + AccountsPane.rootMessage(err));
                });
                return null;
            });
        };
        web.setOnAction(e -> {
            if (LoginWebView.isOpen() || scene.get() == null) return;
            LoginWebView.openForToken(scene.get().getWindow(), n, DiscordToken.LOGIN_URL,
                    DiscordToken.EXTRACT_JS, DiscordToken::isToken,
                    token -> send.accept("!" + n.protocolId() + " login-token user " + token), () -> { });
        });
        w.chooserActive = true;
        views.show("Conectar " + n.displayName(), scroll, cancel);
    }

    /**
     * Friendly login for cookie networks: reuse a browser session, log in inside the app, or load a cookies.txt.
     * Cookies are read only for {@code n}, sent only to the user's own bridge bot, and never logged.
     */
    void showCookieSources(Network n, String botChatId, Runnable cancel) {
        LoginWizard w = wizard.get();
        String net = n.displayName();
        Label searching = new Label("Buscando tu sesión de " + net + " en tus navegadores…");
        searching.setWrapText(true);
        searching.getStyleClass().add("settings-help");
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(22, 22);
        HBox searchRow = new HBox(10, spin, searching);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        VBox found = new VBox(10);
        VBox top = new VBox(8, searchRow);
        if (macNotice()) {
            Label notice = new Label("macOS puede pedirte permiso para usar tu sesión del navegador.");
            notice.setWrapText(true);
            notice.getStyleClass().add("settings-help");
            top.getChildren().add(notice);
        }

        Label status = new Label();
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.getStyleClass().add("settings-help");
        status.setVisible(false);
        status.setManaged(false);

        ScrollPane[] chooser = new ScrollPane[1];
        Consumer<SessionCookies> sendSession = cookies -> {
            views.show("Conectar " + net, w.root, cancel);
            w.sessionSent();
            svc.sendLoginInput(n, botChatId, cookies.toBridgeJson()).exceptionally(err -> {
                Platform.runLater(() -> {
                    toast.accept("No se pudo enviar: " + AccountsPane.rootMessage(err));
                    // undo the waiting state so the user can retry from the chooser
                    if (wizard.get() == w && !w.finished) {
                        w.sessionFailed();
                        views.show("Conectar " + net, chooser[0], cancel);
                    }
                });
                return null;
            });
        };

        Label heading = new Label("Elige cómo conectar tu cuenta de " + net);
        heading.setWrapText(true);
        heading.getStyleClass().add("wizard-instructions");

        Button other = otherAccountButton(n, sendSession);
        Button file = cookieFileButton(n, status, sendSession);

        Button paste = new Button("Pegar cookies manualmente");
        paste.getStyleClass().add("link-button");
        paste.setOnAction(e -> {
            w.chooserActive = false;
            views.show("Conectar " + net, w.root, cancel);
            w.update();
        });

        VBox content = new VBox(12, heading, top, found, other, file, status, paste);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");
        chooser[0] = scroll;
        w.chooserActive = true;
        views.show("Conectar " + net, scroll, cancel);

        CookieLogin.findBrowserSessions(n).whenComplete((sessions, err) -> Platform.runLater(() -> {
            if (wizard.get() != w) return; // user left the screen meanwhile
            top.getChildren().remove(searchRow);
            showSessions(net, err != null || sessions == null ? List.of() : sessions, found, sendSession);
        }));
    }

    private Button otherAccountButton(Network n, Consumer<SessionCookies> sendSession) {
        Button other = new Button("Iniciar sesión con otra cuenta");
        other.getStyleClass().add("secondary-button");
        other.setMaxWidth(Double.MAX_VALUE);
        other.setOnAction(e -> {
            if (LoginWebView.isOpen() || scene.get() == null) return;
            LoginWebView.open(scene.get().getWindow(), n, CookieSpec.forNetwork(n), sendSession, () -> { });
        });
        other.setDisable(LoginWebView.isOpen());
        return other;
    }

    private Button cookieFileButton(Network n, Label status, Consumer<SessionCookies> sendSession) {
        Button file = new Button("Elegir archivo cookies.txt…");
        file.getStyleClass().add("secondary-button");
        file.setMaxWidth(Double.MAX_VALUE);
        file.setOnAction(e -> {
            if (scene.get() == null) return;
            FileChooser fc = new FileChooser();
            fc.setTitle("Elige tu archivo cookies.txt");
            fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("cookies.txt", "*.txt"),
                    new FileChooser.ExtensionFilter("Todos los archivos", "*.*"));
            File f = fc.showOpenDialog(scene.get().getWindow());
            if (f == null) return;
            CookieLogin.TxtResult r = CookieLogin.fromCookieTxt(f.toPath(), n);
            switch (r.kind()) {
                case OK -> sendSession.accept(r.cookies());
                case MISSING -> showStatus(status, AccountsPane.missingMessage(n, r.missing()));
                default -> showStatus(status, r.message());
            }
        });
        return file;
    }

    private static void showSessions(String net, List<BrowserSession> list, VBox found, Consumer<SessionCookies> sendSession) {
        if (list.isEmpty()) {
            Label none = new Label("No encontramos una sesión en tus navegadores.");
            none.setWrapText(true);
            none.getStyleClass().add("settings-help");
            found.getChildren().add(none);
        }
        for (BrowserSession s : list) {
            String label = AccountsPane.sessionLabel(net, s);
            Label l = new Label(label);
            l.setWrapText(true);
            l.getStyleClass().add("settings-label");
            HBox.setHgrow(l, Priority.ALWAYS);
            l.setMaxWidth(Double.MAX_VALUE);
            Button use = new Button("Usar esta sesión");
            use.getStyleClass().add("primary-button");
            use.setAccessibleText(label);
            use.setOnAction(e -> sendSession.accept(s.cookies()));
            HBox card = new HBox(12, l, use);
            card.setAlignment(Pos.CENTER_LEFT);
            card.getStyleClass().add("settings-row");
            found.getChildren().add(card);
        }
    }

    private static void showStatus(Label status, String text) {
        status.setText(text);
        status.setVisible(true);
        status.setManaged(true);
    }

    private static boolean macNotice() {
        return Os.mac();
    }
}
