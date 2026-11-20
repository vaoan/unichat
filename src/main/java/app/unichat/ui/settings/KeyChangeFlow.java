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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.function.Consumer;

import static app.unichat.ui.settings.SettingsForm.actions;
import static app.unichat.ui.settings.SettingsForm.stacked;

/**
 * Changing the vault's key, alone or to take a computer out ("Quitar este equipo": a removed computer still holds the
 * key, so only a new one shuts it out): first every computer that will get the new key, flagging one that came after
 * the last removal and one whose key changed (it gets nothing), to be confirmed; then the new recovery code, the ways
 * in that went (to add again) and the computers refused. A computer whose key changed is accepted (only if the person
 * says it's theirs) before the change, while the key it shows is one the vault key sealed.
 * Computers' names come from the account: shown as text only.
 */
final class KeyChangeFlow {
    private KeyChangeFlow() {}

    /** Asks before changing the key; {@code removing} is the computer taken out with it, or null. */
    static Node confirm(Cloud cloud, VaultView view, VaultView.Computer removing, Consumer<Cloud.KeyChanged> done, Runnable cancel) {
        java.util.Set<String> confirmed = new java.util.HashSet<>(); // those listed here as getting it: no other is offered it
        VBox who = new VBox(6);
        who.getChildren().add(Labels.help(I18n.t("settings.vault.change.receive")));
        for (VaultView.Computer c : view.computers()) {
            if (removing != null && c.machine().equals(removing.machine())) continue;
            if (c.keyChanged()) {
                // Accepted here, it's listed as getting the key: added to those offered it.
                who.getChildren().add(accept(cloud, c, I18n.t("settings.vault.change.refused", CloudTexts.computer(c.label())),
                    () -> confirmed.add(c.machine())));
                continue;
            }
            confirmed.add(c.machine());
            Label line = new Label(c.here() ? I18n.t("settings.vault.computer.here", CloudTexts.computer(c.label())) : name(c));
            line.setWrapText(true);
            line.getStyleClass().add("vault-receiver");
            who.getChildren().add(line);
            flags(who, c);
        }
        if (removing != null) who.getChildren().add(Labels.help(I18n.t("settings.vault.change.removing", CloudTexts.computer(removing.label()))));
        Label error = Labels.help("");
        error.getStyleClass().add("login-error");
        Nodes.show(error, false);
        Button go = new Button(I18n.t(removing == null ? "settings.vault.change.confirm" : "settings.vault.change.confirmRemove"));
        go.getStyleClass().add("danger-button");
        Button no = new Button(I18n.t("folders.dialog.cancel"));
        no.getStyleClass().add("secondary-button");
        no.setOnAction(e -> cancel.run());
        go.setOnAction(e -> {
            go.setDisable(true);
            no.setDisable(true);
            Nodes.show(error, false);
            (removing == null ? cloud.rotateKey(confirmed) : cloud.removeAndChangeKey(removing.machine(), confirmed)).whenComplete((r, err) -> Platform.runLater(() -> {
                go.setDisable(false);
                no.setDisable(false);
                if (err == null) {
                    done.accept(r);
                    return;
                }
                error.setText(Errors.message(Errors.unwrap(err)));
                Nodes.show(error, true);
            }));
        });
        String title = removing == null ? I18n.t("settings.vault.change.title")
            : I18n.t("settings.vault.change.titleRemove", CloudTexts.computer(removing.label()));
        return new VBox(stacked(title, I18n.t("settings.vault.change.help"), who), actions(error, new FlowPane(8, 6, go, no)));
    }

    /**
     * After the change: the new code to keep, what went and who was refused, and "Listo".
     *
     * @param addPassword "Agregar de nuevo" for a password that went
     */
    static Node result(Cloud cloud, Cloud.KeyChanged r, Runnable addPassword, Runnable finished) {
        VBox box = new VBox();
        VBox code = new VBox();
        Runnable kept = () -> cloud.codeKept().whenComplete((ok, err) -> Platform.runLater(() -> {
            Label said = err == null ? Labels.help(I18n.t("settings.vault.code.keptLine"))
                : warning(I18n.t("settings.vault.code.keptFailed", Errors.message(Errors.unwrap(err))));
            code.getChildren().setAll(actions(said));
        }));
        code.getChildren().add(new RecoveryCodeSheet(r.code(), kept,
            () -> code.getChildren().setAll(actions(warning(I18n.t("settings.vault.code.notKept"))))));
        box.getChildren().add(code);
        for (String way : r.dropped()) {
            Label line = Labels.help(I18n.t("settings.vault.change.dropped", I18n.t("settings.vault.way." + way)));
            if ("PASSWORD".equals(way)) {
                Button again = new Button(I18n.t("settings.vault.change.addAgain"));
                again.getStyleClass().add("secondary-button");
                again.setAccessibleText(I18n.t("settings.vault.change.addAgainOf", I18n.t("settings.vault.way." + way)));
                again.setOnAction(e -> addPassword.run());
                box.getChildren().add(actions(line, again));
            } else {
                box.getChildren().add(actions(line));
            }
        }
        for (String refused : r.refused().values()) {
            box.getChildren().add(actions(warning(I18n.t("settings.vault.change.refusedAfter", CloudTexts.computer(refused)))));
        }
        if (!r.left().isEmpty()) box.getChildren().add(actions(Labels.help(I18n.n("settings.vault.change.left", r.left().size()))));
        if (r.resealFailed()) box.getChildren().add(actions(Labels.help(I18n.t("settings.vault.change.resealFailed"))));
        Button done = new Button(I18n.t("settings.vault.change.done"));
        done.getStyleClass().add("secondary-button");
        done.setOnAction(e -> finished.run());
        box.getChildren().add(actions(done));
        return box;
    }

    /**
     * {@code why} (its key changed), and its new key accepted only once the person says that computer is theirs: the vault
     * key sealed the key its slot shows, so the next change of key offers it one.
     */
    static VBox accept(Cloud cloud, VaultView.Computer c, String why, Runnable accepted) {
        String name = CloudTexts.computer(c.label());
        VBox row = new VBox(6, warning(why));
        Button accept = new Button(I18n.t("settings.vault.change.accept"));
        accept.getStyleClass().add("secondary-button");
        accept.setAccessibleText(I18n.t("settings.vault.change.acceptOf", name));
        accept.setOnAction(e -> {
            Button yes = new Button(I18n.t("settings.vault.change.acceptYes"));
            yes.getStyleClass().add("danger-button");
            Button no = new Button(I18n.t("folders.dialog.cancel"));
            no.getStyleClass().add("secondary-button");
            no.setOnAction(x -> row.getChildren().setAll(warning(why), accept));
            yes.setOnAction(x -> {
                yes.setDisable(true);
                cloud.acceptKey(c.machine()).whenComplete((ok, err) -> Platform.runLater(() -> {
                    row.getChildren().setAll(Labels.help(err == null ? I18n.t("settings.vault.change.accepted", name) : Errors.message(Errors.unwrap(err))));
                    if (err == null) accepted.run();
                }));
            });
            row.getChildren().setAll(Labels.help(I18n.t("settings.vault.change.acceptAsk", name)), new FlowPane(8, 6, yes, no));
        });
        row.getChildren().add(accept);
        return row;
    }

    /** What to look at in a computer before it gets the key: new since the last removal, or never seen here. */
    static void flags(VBox into, VaultView.Computer c) {
        String name = CloudTexts.computer(c.label());
        if (c.newSinceRemoval()) into.getChildren().add(warning(I18n.t("settings.vault.change.newSince", name)));
        else if (c.unseen()) into.getChildren().add(warning(I18n.t("settings.vault.change.unseen", name)));
    }

    /** A computer's name and when this computer first saw it. */
    static String name(VaultView.Computer c) {
        String name = CloudTexts.computer(c.label());
        if (c.firstSeen() == null) return name;
        String when = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(I18n.locale()).withZone(ZoneId.systemDefault())
            .format(c.firstSeen());
        return I18n.t("settings.vault.computer.seen", name, when);
    }

    static Label warning(String text) {
        Label l = Labels.help(text);
        l.getStyleClass().add("risk-notice");
        return l;
    }
}
