package app.unichat.ui.login;

import app.unichat.ui.Labels;
import app.unichat.ui.Nodes;
import app.unichat.login.BrowserSession;
import app.unichat.login.CookieLogin;
import app.unichat.login.CookieSpec;
import app.unichat.login.DiscordToken;
import app.unichat.login.SessionCookies;
import app.unichat.model.Network;
import app.unichat.service.ChatMessaging;
import app.unichat.support.I18n;
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

    private final ChatMessaging svc;
    private final Consumer<String> toast;
    private final Views views;
    private final Supplier<Scene> scene;
    private final Supplier<LoginWizard> wizard;

    LoginChoosers(ChatMessaging svc, Consumer<String> toast, Views views, Supplier<Scene> scene, Supplier<LoginWizard> wizard) {
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
        Label heading = new Label(I18n.t("login.discord.connect"));
        heading.getStyleClass().add("wizard-instructions");
        heading.setWrapText(true);

        Button web = new Button(I18n.t("login.discord.signIn"));
        web.getStyleClass().add("primary-button");
        web.setMaxWidth(Double.MAX_VALUE);
        web.setAccessibleText(I18n.t("login.discord.signInA11y"));
        // The bridge's own QR login always ended in a captcha it can't show; the web page's QR (inside this window) works.
        Label webHelp = Labels.help(I18n.t("login.discord.help"));


        Label status = Labels.help("");

        VBox content = new VBox(12, heading, web, webHelp, status);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");

        Consumer<String> send = command -> {
            views.show(I18n.t("login.connectTitle", n.displayName()), w.root, cancel);
            w.sessionSent();
            svc.sendText(botChatId, command, null).exceptionally(err -> {
                Platform.runLater(() -> {
                    if (wizard.get() != w || w.finished) return;
                    w.sessionFailed();
                    views.show(I18n.t("login.connectTitle", n.displayName()), scroll, cancel);
                    status.setText(I18n.t("login.sendFailed", LoginTexts.rootMessage(err)));
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
        views.show(I18n.t("login.connectTitle", n.displayName()), scroll, cancel);
    }

    /**
     * Friendly login for cookie networks: reuse a browser session, log in inside the app, or load a cookies.txt.
     * Cookies are read only for {@code n}, sent only to the user's own bridge bot, and never logged.
     */
    void showCookieSources(Network n, String botChatId, Runnable cancel) {
        LoginWizard w = wizard.get();
        String net = n.displayName();
        Label searching = Labels.help(I18n.t("login.cookies.searching", net));
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(22, 22);
        HBox searchRow = new HBox(10, spin, searching);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        VBox found = new VBox(10);
        VBox top = new VBox(8, searchRow);
        if (Os.mac()) {
            Label notice = Labels.help(I18n.t("login.cookies.macNotice"));
            top.getChildren().add(notice);
        }

        Label status = new Label();
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.getStyleClass().add("settings-help");
        Nodes.show(status, false);

        ScrollPane[] chooser = new ScrollPane[1];
        Consumer<SessionCookies> sendSession = cookies -> {
            views.show(I18n.t("login.connectTitle", net), w.root, cancel);
            w.sessionSent();
            svc.sendText(botChatId, cookies.toBridgeJson(), null).exceptionally(err -> {
                Platform.runLater(() -> {
                    toast.accept(I18n.t("login.sendFailed", LoginTexts.rootMessage(err)));
                    // undo the waiting state so the user can retry from the chooser
                    if (wizard.get() == w && !w.finished) {
                        w.sessionFailed();
                        views.show(I18n.t("login.connectTitle", net), chooser[0], cancel);
                    }
                });
                return null;
            });
        };

        Label heading = new Label(I18n.t("login.cookies.choose", net));
        heading.setWrapText(true);
        heading.getStyleClass().add("wizard-instructions");

        Button other = otherAccountButton(n, sendSession);
        Button file = CookieFileButton.create(n, path -> CookieLogin.fromCookieTxt(path, n), sendSession, text -> showStatus(status, text));

        Button paste = new Button(I18n.t("login.cookies.paste"));
        paste.getStyleClass().add("link-button");
        paste.setOnAction(e -> {
            w.chooserActive = false;
            views.show(I18n.t("login.connectTitle", net), w.root, cancel);
            w.update();
        });

        VBox content = new VBox(12, heading, top, found, other, file, status, paste);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");
        chooser[0] = scroll;
        w.chooserActive = true;
        views.show(I18n.t("login.connectTitle", net), scroll, cancel);

        CookieLogin.findBrowserSessions(n).whenComplete((sessions, err) -> Platform.runLater(() -> {
            if (wizard.get() != w) return; // user left the screen meanwhile
            top.getChildren().remove(searchRow);
            showSessions(net, err != null || sessions == null ? List.of() : sessions, found, sendSession);
        }));
    }

    private Button otherAccountButton(Network n, Consumer<SessionCookies> sendSession) {
        Button other = new Button(I18n.t("login.cookies.otherAccount"));
        other.getStyleClass().add("secondary-button");
        other.setMaxWidth(Double.MAX_VALUE);
        other.setOnAction(e -> {
            if (LoginWebView.isOpen() || scene.get() == null) return;
            LoginWebView.open(scene.get().getWindow(), n, CookieSpec.forNetwork(n), sendSession, () -> { });
        });
        other.setDisable(LoginWebView.isOpen());
        return other;
    }

    private static void showSessions(String net, List<BrowserSession> list, VBox found, Consumer<SessionCookies> sendSession) {
        if (list.isEmpty()) {
            Label none = Labels.help(I18n.t("login.cookies.noneFound"));
            found.getChildren().add(none);
        }
        for (BrowserSession s : list) {
            String label = LoginTexts.sessionLabel(net, s);
            Label l = new Label(label);
            l.setWrapText(true);
            l.getStyleClass().add("settings-label");
            HBox.setHgrow(l, Priority.ALWAYS);
            l.setMaxWidth(Double.MAX_VALUE);
            Button use = new Button(I18n.t("login.cookies.useSession"));
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
        Nodes.show(status, true);
    }
}
