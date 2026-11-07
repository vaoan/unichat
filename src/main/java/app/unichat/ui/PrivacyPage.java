package app.unichat.ui;

import app.unichat.server.LogFiles;
import app.unichat.settings.Settings;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.util.List;
import java.util.function.Function;

import static app.unichat.ui.SettingsForm.chips;
import static app.unichat.ui.SettingsForm.group;
import static app.unichat.ui.SettingsForm.page;
import static app.unichat.ui.SettingsForm.row;
import static app.unichat.ui.SettingsForm.toggle;

/** Ajustes → Privacidad: what others see of you, and what stays on this computer. */
final class PrivacyPage {
    private PrivacyPage() {}

    static Node build() {
        Settings s = Settings.get();
        return page("Privacidad", group("Indicadores",
                row("Confirmaciones de lectura", "Los demás ven cuándo leíste sus mensajes (si la red lo permite).",
                        toggle(s.sendReadReceipts(), s::setSendReadReceipts)),
                row("Indicador de escritura", "Los demás ven cuando estás escribiendo.",
                        toggle(s.sendTypingNotifications(), s::setSendTypingNotifications))),
                group("Datos locales", row("Carpeta de datos", s.dir().toString(), new Label()),
                        row("Espacio para fotos y videos", "Los medios se descargan solo cuando los abres. Al llenarse se borra primero lo más pesado y lo que llevas "
                                + "más tiempo sin ver; se puede volver a bajar. Tus stickers y GIFs guardados se conservan siempre.",
                                mediaCacheChoice())),
                logsGroup(s));
    }

    private static Node mediaCacheChoice() {
        Settings s = Settings.get();
        Function<Integer, String> label = mb -> mb < 1024 ? mb + " MB" : mb / 1024 + " GB";
        return chips("Espacio en disco para fotos y videos descargados", List.of(500, 1024, 5120), label,
                mb -> "Usar hasta " + label.apply(mb) + " para fotos y videos descargados", s.mediaCacheMb(), s::setMediaCacheMb);
    }

    /** Logs never hold personal data (Redactor) and are deleted after the chosen days (LogFiles). */
    private static Node logsGroup(Settings s) {
        Function<Integer, String> label = d -> d == 1 ? "1 día" : d + " días";
        Node days = chips("Borrar registros después de", List.of(1, 7, 30), label,
                d -> "Borrar registros después de " + label.apply(d), s.logRetentionDays(), d -> {
                    s.setLogRetentionDays(d);
                    Thread.ofVirtual().start(() -> LogFiles.cleanOld(LogFiles.roots(),
                            java.time.Duration.ofDays(d), java.time.Instant.now()));
                });
        Button clear = new Button("Borrar registros ahora");
        clear.getStyleClass().add("secondary-button");
        clear.setOnAction(e -> {
            clear.setDisable(true);
            Thread.ofVirtual().start(() -> {
                LogFiles.clearAll(LogFiles.roots());
                javafx.application.Platform.runLater(() -> {
                    clear.setText("Registros borrados");
                    clear.setDisable(false);
                });
            });
        });
        return group("Registros (logs)",
                row("Borrar registros después de", "Sirven para diagnosticar errores. No guardan nombres, números, "
                        + "mensajes ni contraseñas.", days),
                row("Borrar ahora", "De Unichat, del servidor y de los puentes.", clear));
    }
}
