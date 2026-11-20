package app.unichat.ui.settings;

import app.unichat.cloud.Cloud;
import app.unichat.cloud.VaultView;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import app.unichat.ui.Labels;
import app.unichat.ui.Nodes;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

import static app.unichat.ui.settings.SettingsForm.actions;
import static app.unichat.ui.settings.SettingsForm.group;
import static app.unichat.ui.settings.SettingsForm.stacked;

/**
 * Ajustes → Nube → Bóveda, shown once an account is connected: make the vault (its recovery code shown once and asked
 * back), open it on a new computer or go on without it, and, open, its ways in and computers, a new recovery code, a
 * new key, and forgetting it here. Everything goes through {@link Cloud} on its own thread; this page never sees the
 * vault's key, and a secret typed here is cleared once it goes.
 */
final class VaultPage {
    private final Cloud cloud;
    private final VBox body = new VBox();
    /** The state last drawn; a round finding another redraws the page, unless a step is under way (a code on screen). */
    private Cloud.VaultState shown;
    private boolean inStep;

    private VaultPage(Cloud cloud) {
        this.cloud = cloud;
        body.getStyleClass().add("vault-page");
    }

    /** The section, kept current while it shows. */
    static Node build(Cloud cloud) {
        VaultPage page = new VaultPage(cloud);
        Runnable listener = () -> Platform.runLater(page::stateMaybeChanged);
        app.unichat.ui.Showing.whileShowing(page.body, () -> cloud.addListener(listener), () -> cloud.removeListener(listener));
        page.reload();
        return group(I18n.t("settings.vault.title"), page.body);
    }

    private void stateMaybeChanged() {
        Cloud.Status s = cloud.status();
        if (!inStep && shown != null && s.connected() && s.vault() != shown) reload();
    }

    /** Asks the cloud for the vault as it stands here, then draws it. */
    private void reload() {
        inStep = false;
        if (shown == null) show(actions(Labels.help(I18n.t("settings.vault.reading"))));
        cloud.vaultView().whenComplete((v, err) -> Platform.runLater(() -> {
            if (err != null) {
                show(line(I18n.t("settings.vault.unreadable", Errors.message(Errors.unwrap(err)))), retry());
                return;
            }
            shown = v.state();
            draw(v);
        }));
    }

    private void draw(VaultView v) {
        switch (v.state()) {
            case NONE -> drawNone();
            case LOCKED -> drawLocked(v, I18n.t("settings.vault.locked"), true);
            case REMOVED -> drawLocked(v, I18n.t("settings.vault.removed"), false);
            case DECLINED -> drawDeclined(v);
            case OPEN -> drawOpen(v);
        }
    }

    /** No vault yet: what it is, and "Crear bóveda". */
    private void drawNone() {
        Button create = primary(I18n.t("settings.vault.create"));
        Label error = error();
        create.setOnAction(e -> {
            create.setDisable(true);
            cloud.createVault().whenComplete((code, err) -> Platform.runLater(() -> {
                create.setDisable(false);
                if (err != null) {
                    say(error, Errors.message(Errors.unwrap(err)));
                    return;
                }
                cloud.syncNow();
                showCode(code, I18n.t("settings.vault.created"));
            }));
        });
        show(stacked(I18n.t("settings.vault.offTitle"), I18n.t("settings.vault.offHelp"), new VBox(8, create, error)));
    }

    /** A code to keep, shown once and asked back; then the vault as it is now. */
    private void showCode(String code, String before) {
        inStep = true;
        show(line(before), new RecoveryCodeSheet(code, () -> cloud.codeKept().whenComplete((ok, err) -> Platform.runLater(() -> {
            if (err == null) reload();
            else show(actions(KeyChangeFlow.warning(I18n.t("settings.vault.code.keptFailed", Errors.message(Errors.unwrap(err))))), retry());
        })), this::reload));
    }

    /** Not open here: how to open it, and (locked, not removed) going on without it. */
    private void drawLocked(VaultView v, String why, boolean offerDecline) {
        List<Node> rows = new ArrayList<>();
        rows.add(line(why));
        rows.add(new VaultOpenForm(cloud, v.ways(), this::opened));
        if (offerDecline) {
            Button decline = secondary(I18n.t("settings.vault.decline"));
            decline.setOnAction(e -> {
                decline.setDisable(true);
                cloud.declineVault(true).whenComplete((ok, err) -> Platform.runLater(() -> {
                    cloud.syncNow();
                    reload();
                }));
            });
            rows.add(stacked(I18n.t("settings.vault.declineTitle"), I18n.t("settings.vault.declineHelp"), decline));
        }
        show(rows.toArray(new Node[0]));
    }

    /** Gone on without it here: a short line, and "Abrir la bóveda" for when the person wants it. */
    private void drawDeclined(VaultView v) {
        Button open = primary(I18n.t("settings.vault.openIt"));
        open.setOnAction(e -> {
            inStep = true;
            show(line(I18n.t("settings.vault.declined")), new VaultOpenForm(cloud, v.ways(), this::opened), actions(cancel(this::reload)));
        });
        show(stacked(I18n.t("settings.vault.declinedTitle"), I18n.t("settings.vault.declined"), open));
    }

    private void opened() {
        cloud.syncNow();
        reload();
    }

    /** Open here: its ways in, its computers, and what can be done with it. */
    private void drawOpen(VaultView v) {
        List<Node> rows = new ArrayList<>();
        rows.add(line(I18n.t("settings.vault.open")));
        if (v.codeUnconfirmed()) rows.add(actions(KeyChangeFlow.warning(I18n.t("settings.vault.code.notKept")))); // "Hacer un código nuevo" is with the ways in
        rows.add(ways(v));
        rows.add(computers(v));
        Button change = secondary(I18n.t("settings.vault.changeKey"));
        change.setOnAction(e -> changeKey(null));
        Button forget = new Button(I18n.t("settings.vault.forget"));
        forget.getStyleClass().add("danger-button");
        forget.setOnAction(e -> forget());
        Button off = new Button(I18n.t("settings.vault.turnOff"));
        off.getStyleClass().add("danger-button");
        off.setOnAction(e -> turnOff());
        rows.add(stacked(I18n.t("settings.vault.keyTitle"), I18n.t("settings.vault.keyHelp"), new FlowPane(8, 6, change, forget, off)));
        show(rows.toArray(new Node[0]));
    }

    /** The ways in, by method (never by a slot's own label); the recovery code always, a password to add. */
    private Node ways(VaultView v) {
        VBox list = new VBox(6);
        for (VaultView.Way w : VaultView.Way.values()) {
            if (v.ways().contains(w)) list.getChildren().add(new Label(I18n.t("settings.vault.way." + w.name())));
        }
        FlowPane buttons = new FlowPane(8, 6, newCode());
        if (!v.ways().contains(VaultView.Way.PASSWORD)) {
            Button add = secondary(I18n.t("settings.vault.password.add"));
            add.setOnAction(e -> addPassword());
            buttons.getChildren().add(add);
        }
        list.getChildren().add(buttons);
        return stacked(I18n.t("settings.vault.ways"), I18n.t("settings.vault.waysHelp"), list);
    }

    private Button newCode() {
        Button b = secondary(I18n.t("settings.vault.newCode"));
        b.setOnAction(e -> {
            b.setDisable(true);
            cloud.newRecoveryCode().whenComplete((code, err) -> Platform.runLater(() -> {
                b.setDisable(false);
                if (err != null) show(line(Errors.message(Errors.unwrap(err))), retry());
                else showCode(code, I18n.t("settings.vault.newCodeMade"));
            }));
        });
        return b;
    }

    private void addPassword() {
        inStep = true;
        show(new VaultPasswordForm(cloud, this::reload, this::reload));
    }

    /** Each computer by its name (text from the account), when first seen here, and what can be done with it. */
    private Node computers(VaultView v) {
        VBox list = new VBox(10);
        for (VaultView.Computer c : v.computers()) {
            String name = CloudTexts.computer(c.label());
            Label who = new Label(c.here() ? I18n.t("settings.vault.computer.here", name) : KeyChangeFlow.name(c));
            who.setWrapText(true);
            who.getStyleClass().add("vault-computer");
            VBox entry = new VBox(4, who);
            if (c.keyChanged()) entry.getChildren().add(KeyChangeFlow.accept(cloud, c, I18n.t("settings.vault.change.keyChanged", name), this::reload));
            KeyChangeFlow.flags(entry, c);
            if (!c.here()) {
                Button remove = new Button(I18n.t("settings.vault.computer.remove"));
                remove.getStyleClass().add("danger-button");
                remove.setAccessibleText(I18n.t("settings.vault.computer.removeOf", name));
                remove.setOnAction(e -> changeKey(c));
                entry.getChildren().add(remove);
            }
            list.getChildren().add(entry);
        }
        return stacked(I18n.t("settings.vault.computers"), I18n.t("settings.vault.computersHelp"), list);
    }

    /**
     * "Cambiar la llave", or taking {@code removing} out with it: the computers read again (the page may have been drawn
     * long ago, and only those listed now get the key), confirmed, then what came of it.
     */
    private void changeKey(VaultView.Computer removing) {
        inStep = true;
        show(line(I18n.t("settings.vault.reading")));
        cloud.vaultView().whenComplete((now, err) -> Platform.runLater(() -> {
            if (err != null || now.state() != Cloud.VaultState.OPEN) {
                show(line(err != null ? I18n.t("settings.vault.unreadable", Errors.message(Errors.unwrap(err))) : I18n.t("cloud.error.vaultNotOpen")), retry());
                return;
            }
            show(KeyChangeFlow.confirm(cloud, now, removing, r -> {
                cloud.syncNow();
                show(KeyChangeFlow.result(cloud, r, this::addPassword, this::reload));
            }, this::reload));
        }));
    }

    /** "Olvidar la bóveda en este equipo", asked once. */
    private void forget() {
        inStep = true;
        Button yes = new Button(I18n.t("settings.vault.forgetYes"));
        yes.getStyleClass().add("danger-button");
        Label error = error();
        yes.setOnAction(e -> {
            yes.setDisable(true);
            cloud.forgetHere().whenComplete((ok, err) -> Platform.runLater(() -> {
                yes.setDisable(false);
                if (err != null) {
                    say(error, Errors.message(Errors.unwrap(err)));
                    return;
                }
                cloud.syncNow();
                reload();
            }));
        });
        show(stacked(I18n.t("settings.vault.forgetTitle"), I18n.t("settings.vault.forgetHelp"), new VBox(8, new FlowPane(8, 6, yes, cancel(this::reload)), error)));
    }

    /** "Apagar la bóveda", asked twice: first whether, then what happens, with the button saying the action. */
    private void turnOff() {
        inStep = true;
        Button next = secondary(I18n.t("settings.vault.turnOffNext"));
        next.setOnAction(e -> turnOffSure());
        show(stacked(I18n.t("settings.vault.turnOffTitle"), I18n.t("settings.vault.turnOffAsk"), new FlowPane(8, 6, next, cancel(this::reload))));
    }

    private void turnOffSure() {
        Button yes = new Button(I18n.t("settings.vault.turnOffYes"));
        yes.getStyleClass().add("danger-button");
        yes.setOnAction(e -> turningOff());
        show(stacked(I18n.t("settings.vault.turnOffSure"), I18n.t("settings.vault.turnOffWhat"), new FlowPane(8, 6, yes, cancel(this::reload))));
    }

    /** While it runs, a line; failed, why (the vault is as it was) and "Intentar de nuevo"; done, the page without a vault. */
    private void turningOff() {
        show(line(I18n.t("settings.vault.turnOffWorking")));
        cloud.turnOffVault().whenComplete((ok, err) -> Platform.runLater(() -> {
            if (err == null) {
                cloud.syncNow();
                reload();
                return;
            }
            Button again = secondary(I18n.t("settings.vault.retry"));
            again.setOnAction(e -> turningOff());
            show(actions(KeyChangeFlow.warning(I18n.t("settings.vault.turnOffFailed"))), line(Errors.message(Errors.unwrap(err))),
                new FlowPane(8, 6, again, cancel(this::reload)));
        }));
    }

    private void show(Node... rows) {
        body.getChildren().setAll(rows);
    }

    private Node retry() {
        Button again = secondary(I18n.t("settings.vault.retry"));
        again.setOnAction(e -> reload());
        return actions(again);
    }

    private static Node line(String text) {
        return actions(Labels.help(text));
    }

    private static Label error() {
        Label l = Labels.help("");
        l.getStyleClass().add("login-error");
        Nodes.show(l, false);
        return l;
    }

    private static void say(Label l, String text) {
        l.setText(text);
        Nodes.show(l, true);
    }

    private static Button cancel(Runnable r) {
        Button b = secondary(I18n.t("folders.dialog.cancel"));
        b.setOnAction(e -> r.run());
        return b;
    }

    private static Button primary(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("primary-button");
        return b;
    }

    private static Button secondary(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("secondary-button");
        return b;
    }
}
