package app.unichat.ui.settings;

import app.unichat.cloud.PasskeyPage;
import app.unichat.cloud.PasskeyProbe;
import app.unichat.support.Errors;
import app.unichat.support.I18n;
import app.unichat.ui.Labels;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import static app.unichat.ui.settings.SettingsForm.stacked;

/**
 * "Probar llave de acceso (prueba)" under Ajustes → Nube → Bóveda, only when the probe was asked for
 * ({@link PasskeyProbe#asked}): opens the probe's page in the browser and says, under the button, what it found.
 */
final class PasskeyProbeRow {
    private PasskeyProbeRow() {}

    /** The row when the probe was asked for; {@code start} opens it and hands each finding on (tests give their own). */
    static Optional<Node> ifAsked(Function<Consumer<PasskeyPage.Finding>, CompletableFuture<Void>> start) {
        return PasskeyProbe.asked() ? Optional.of(build(start)) : Optional.empty();
    }

    static Node build(Function<Consumer<PasskeyPage.Finding>, CompletableFuture<Void>> start) {
        Button run = new Button(I18n.t("settings.vault.probe.run"));
        run.setAccessibleText(I18n.t("settings.vault.probe.title")); // stacked names the box it's in, not the button
        run.setAccessibleHelp(I18n.t("settings.vault.probe.help", PasskeyProbe.file()));
        Label found = Labels.help("");
        found.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        run.setOnAction(e -> {
            found.setText(I18n.t("settings.vault.probe.opened"));
            start.apply(f -> Platform.runLater(() -> found.setText(f.said())))
                    .whenComplete((ok, err) -> {
                        if (err != null && !(err instanceof java.util.concurrent.CancellationException)) {
                            Platform.runLater(() -> found.setText(Errors.message(Errors.unwrap(err))));
                        }
                    });
        });
        return stacked(I18n.t("settings.vault.probe.title"),
                I18n.t("settings.vault.probe.help", PasskeyProbe.file()), new VBox(8, run, found));
    }
}
