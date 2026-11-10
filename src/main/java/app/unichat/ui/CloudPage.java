package app.unichat.ui;

import app.unichat.cloud.Cloud;
import app.unichat.cloud.CloudAccount;
import app.unichat.cloud.CloudAccount.Service;
import app.unichat.cloud.PCloudLogin;
import app.unichat.settings.Settings;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static app.unichat.ui.SettingsForm.chips;
import static app.unichat.ui.SettingsForm.group;
import static app.unichat.ui.SettingsForm.page;
import static app.unichat.ui.SettingsForm.row;
import static app.unichat.ui.SettingsForm.stacked;
import static app.unichat.ui.SettingsForm.toggle;

/**
 * Ajustes → Nube: the person's own storage (pCloud, Icedrive, any WebDAV). Connecting tries the login first and says
 * why it didn't work; once connected, what's kept the same everywhere and what goes up, and how the last round went.
 */
final class CloudPage {
    /** The app's sync, given when the window is built; null in a session without one (the demo). */
    private static Cloud cloud;

    private CloudPage() {}

    static void use(Cloud c) {
        cloud = c;
    }

    static Node build(Consumer<String> toast) {
        Label intro = Labels.help(I18n.t("settings.cloud.intro"));
        if (cloud == null) return page(I18n.t("settings.nav.cloud"), intro, Labels.help(I18n.t("settings.cloud.unavailable")));
        VBox account = new VBox();
        Runnable[] redraw = {null};
        redraw[0] = () -> account.getChildren().setAll(CloudAccount.saved() == null ? connectForm(toast, redraw[0]) : connected(redraw[0]));
        redraw[0].run();
        return page(I18n.t("settings.nav.cloud"), intro, account,
                group(I18n.t("settings.cloud.what"),
                        row(I18n.t("settings.cloud.settings"), I18n.t("settings.cloud.settingsHelp"),
                                toggle(CloudAccount.settings(), CloudAccount::setSettings)),
                        row(I18n.t("settings.cloud.media"), I18n.t("settings.cloud.mediaHelp"),
                                toggle(CloudAccount.media(), CloudAccount::setMedia))));
    }

    /**
     * Which service. pCloud: "Conectar con pCloud" signs in in the browser (works with two-step verification); the rest:
     * address (typed only for "Otro"), login and password or key, tried by "Conectar".
     */
    private static Node connectForm(Consumer<String> toast, Runnable redraw) {
        Service[] chosen = {Service.PCLOUD};
        Label status = Labels.help("");
        Nodes.show(status, false);
        Consumer<CloudAccount> tryIt = a -> {
            say(status, I18n.t("settings.cloud.trying"));
            Cloud.verify(a).whenComplete((ok, err) -> Platform.runLater(() -> {
                if (err != null) {
                    say(status, I18n.t("settings.cloud.failed", Errors.message(Errors.unwrap(err))));
                    return;
                }
                ok.save();
                toast.accept(I18n.t("settings.cloud.connected", I18n.t("settings.cloud.service." + ok.service().name())));
                cloud.syncNow();
                redraw.run();
            }));
        };
        Node pcloud = pcloudForm(status, tryIt);
        Node dav = davForm(chosen, status, tryIt);
        Node services = chips(I18n.t("settings.cloud.service"), List.of(Service.values()),
                s -> I18n.t("settings.cloud.service." + s.name()), Service.PCLOUD, s -> {
                    chosen[0] = s;
                    Nodes.show(pcloud, s == Service.PCLOUD);
                    Nodes.show(dav, s != Service.PCLOUD);
                    if (dav.lookup(".cloud-url") instanceof TextField url) {
                        url.setDisable(s != Service.OTHER);
                        url.setText(s.url == null ? "" : s.url);
                    }
                    if (dav.lookup(".cloud-secret-help") instanceof Label help) help.setText(I18n.t("settings.cloud.secretHelp." + s.name()));
                });
        Nodes.show(dav, false);
        return group(I18n.t("settings.cloud.account"), stacked(I18n.t("settings.cloud.service"), null, services), pcloud, dav,
                actions(status));
    }

    /** "Conectar con pCloud": the browser opens pCloud's page; "Cancelar" while it waits there. */
    private static Node pcloudForm(Label status, Consumer<CloudAccount> tryIt) {
        TextField clientId = new TextField(Settings.get().getString("cloud.pcloudClientId", ""));
        clientId.setAccessibleText(I18n.t("settings.cloud.clientId"));
        Node clientRow = stacked(I18n.t("settings.cloud.clientId"), I18n.t("settings.cloud.clientIdHelp", PCloudLogin.REDIRECT), clientId);
        Nodes.show(clientRow, PCloudLogin.clientId().isEmpty() || !Settings.get().getString("cloud.pcloudClientId", "").isEmpty());
        Button connect = new Button(I18n.t("settings.cloud.connectPcloud"));
        connect.getStyleClass().add("primary-button");
        Button cancel = new Button(I18n.t("folders.dialog.cancel"));
        cancel.getStyleClass().add("secondary-button");
        Nodes.show(cancel, false);
        java.util.concurrent.CompletableFuture<?>[] waiting = {null};
        connect.setOnAction(e -> {
            Settings.get().set("cloud.pcloudClientId", clientId.getText().isBlank() ? null : clientId.getText().strip());
            if (PCloudLogin.clientId().isEmpty()) {
                say(status, I18n.t("settings.cloud.noClientId"));
                return;
            }
            try {
                var login = PCloudLogin.start(PCloudLogin.clientId(), uri -> Browser.open(uri.toString()));
                waiting[0] = login;
                connect.setDisable(true);
                Nodes.show(cancel, true);
                say(status, I18n.t("settings.cloud.inBrowser"));
                login.whenComplete((a, err) -> Platform.runLater(() -> {
                    connect.setDisable(false);
                    Nodes.show(cancel, false);
                    if (err instanceof java.util.concurrent.CancellationException) say(status, "");
                    else if (err != null) say(status, I18n.t("settings.cloud.failed", Errors.message(Errors.unwrap(err))));
                    else tryIt.accept(a);
                }));
            } catch (java.io.IOException ex) {
                say(status, I18n.t("settings.cloud.portBusy", PCloudLogin.PORT));
            }
        });
        cancel.setOnAction(e -> {
            if (waiting[0] != null) waiting[0].cancel(false);
        });
        Label help = Labels.help(I18n.t("settings.cloud.pcloudHelp"));
        return new VBox(clientRow, actions(new VBox(8, help, new FlowPane(8, 6, connect, cancel))));
    }

    /** Address, login and password or key for a WebDAV service; "Conectar" tries them. */
    private static Node davForm(Service[] chosen, Label status, Consumer<CloudAccount> tryIt) {
        TextField url = new TextField();
        url.getStyleClass().add("cloud-url");
        url.setPromptText("https://…");
        TextField user = new TextField();
        user.setPromptText(I18n.t("settings.cloud.userPrompt"));
        PasswordField secret = new PasswordField();
        Label secretHelp = Labels.help("");
        secretHelp.getStyleClass().add("cloud-secret-help");
        Button connect = new Button(I18n.t("settings.cloud.connect"));
        connect.getStyleClass().add("primary-button");
        connect.setOnAction(e -> {
            CloudAccount a = new CloudAccount(chosen[0], url.getText().strip(), user.getText().strip(), secret.getText());
            if (a.url().isEmpty() || a.user().isEmpty() || a.secret().isEmpty()) {
                say(status, I18n.t("settings.cloud.missing"));
                return;
            }
            tryIt.accept(a);
        });
        url.setAccessibleText(I18n.t("settings.cloud.url"));
        user.setAccessibleText(I18n.t("settings.cloud.user"));
        secret.setAccessibleText(I18n.t("settings.cloud.secret"));
        return new VBox(stacked(I18n.t("settings.cloud.url"), null, url),
                stacked(I18n.t("settings.cloud.user"), null, user),
                stacked(I18n.t("settings.cloud.secret"), null, new VBox(4, secret, secretHelp)),
                actions(connect));
    }

    /** Who's connected, how the last round went (kept current while shown), "Sincronizar ahora" and "Desconectar". */
    private static Node connected(Runnable redraw) {
        CloudAccount a = CloudAccount.saved();
        Label who = new Label(I18n.t("settings.cloud.as", I18n.t("settings.cloud.service." + a.service().name()), a.user()));
        who.setWrapText(true);
        who.setMinHeight(Region.USE_PREF_SIZE);
        Label state = new Label();
        state.setWrapText(true);
        state.setMinHeight(Region.USE_PREF_SIZE);
        Runnable show = () -> state.setText(statusText(cloud.status(), Instant.now()));
        Runnable listener = () -> Platform.runLater(show);
        cloud.addListener(listener);
        state.sceneProperty().addListener((o, was, scene) -> {
            if (scene == null) cloud.removeListener(listener);
        });
        show.run();
        Button now = new Button(I18n.t("settings.cloud.syncNow"));
        now.getStyleClass().add("secondary-button");
        now.setOnAction(e -> {
            state.setText(I18n.t("settings.cloud.syncing"));
            cloud.syncNow();
        });
        Button off = new Button(I18n.t("settings.cloud.disconnect"));
        off.getStyleClass().add("secondary-button");
        off.setOnAction(e -> {
            CloudAccount.forget();
            redraw.run();
        });
        return group(I18n.t("settings.cloud.account"), stacked(I18n.t("settings.cloud.connectedTitle"), null,
                new VBox(8, who, state, new FlowPane(8, 6, now, off))));
    }

    /** "Al día · hace 2 min · 28 archivos subidos", or the last round's error. Pure. */
    static String statusText(Cloud.Status s, Instant now) {
        if (!s.connected()) return I18n.t("settings.cloud.notYet");
        String when = s.lastSync() == null ? I18n.t("settings.cloud.never")
                : I18n.t("settings.cloud.ago", Math.max(0, Duration.between(s.lastSync(), now).toMinutes()));
        String files = I18n.n("settings.cloud.uploaded", s.uploaded()) + (s.waiting() > 0 ? " · " + I18n.n("settings.cloud.waiting", s.waiting()) : "");
        if (s.error() != null) return I18n.t("settings.cloud.error", s.error()) + "\n" + when + " · " + files;
        return I18n.t("settings.cloud.ok") + " · " + when + " · " + files;
    }

    /** A row with no name of its own: the form's buttons. */
    private static Node actions(Node content) {
        VBox row = new VBox(content);
        row.getStyleClass().add("settings-row");
        return row;
    }

    private static void say(Label status, String text) {
        status.setText(text);
        Nodes.show(status, true);
    }
}
