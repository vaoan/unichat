package app.unichat.ui;

import app.unichat.model.Network;
import app.unichat.server.LocalServer;
import app.unichat.service.StickerMode;
import app.unichat.settings.Settings;
import app.unichat.video.Ffmpeg;
import app.unichat.video.WebmStickers;
import app.unichat.voice.VoiceEnhancer;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Function;

import static app.unichat.ui.SettingsForm.chips;
import static app.unichat.ui.SettingsForm.group;
import static app.unichat.ui.SettingsForm.page;
import static app.unichat.ui.SettingsForm.row;
import static app.unichat.ui.SettingsForm.toggle;

/** Ajustes → Redes. */
final class NetworksPage {
    private NetworksPage() {}

    /** ffmpeg, for the stickers that arrive as WebM: the person's own, or a download when they need it. */
    private static Node stickerVideoGroup() {
        Label state = Labels.help("");
        state.setMaxWidth(240);
        Button install = new Button("Instalar ahora");
        install.getStyleClass().add("secondary-button");
        install.setMinWidth(Region.USE_PREF_SIZE);
        Runnable refresh = () -> {
            boolean found = Ffmpeg.find() != null;
            state.setText(found ? "Instalado." : !Ffmpeg.canInstall() ? "No hay una descarga para este computador: instala ffmpeg."
                    : Ffmpeg.autoInstall() ? "Se descarga la primera vez que llegue uno · " + Formats.fileSize(Ffmpeg.downloadSize())
                    : "Sin instalar: esos stickers se ven como archivo.");
            install.setVisible(!found && Ffmpeg.canInstall());
            install.setManaged(install.isVisible());
        };
        refresh.run();
        install.setOnAction(e -> {
            install.setDisable(true);
            state.setText("Descargando…");
            WebmStickers.WORKER.execute(() -> {
                String error = null;
                try {
                    Ffmpeg.install(done -> javafx.application.Platform.runLater(() -> state.setText("Descargando… " + (int) (done * 100) + " %")));
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
                String failed = error;
                javafx.application.Platform.runLater(() -> {
                    install.setDisable(false);
                    refresh.run();
                    if (failed != null) state.setText("No se pudo instalar: " + failed);
                });
            });
        });
        return group("Stickers, videos y notas de voz",
                row("Mejorar el audio de tus notas de voz", "Quita el ruido de fondo y empareja el volumen antes de enviar, "
                        + "en este computador: el audio no pasa por ningún servicio. Si no se puede, se envía tal como se grabó.",
                        toggle(VoiceEnhancer.enabled(), VoiceEnhancer::setEnabled)),
                row("Instalar el conversor automáticamente", "Algunos stickers y videos llegan en formatos que Unichat no reproduce "
                        + "por sí solo, y mejorar el audio también lo necesita. ffmpeg lo hace en este computador; se usa el tuyo si ya lo tienes.",
                        toggle(Ffmpeg.autoInstall(), v -> {
                            Ffmpeg.setAutoInstall(v);
                            refresh.run();
                        })),
                row("Conversor", null, new VBox(6, state, install)));
    }

    /** Options that only make sense for one network; each network gets its own group. */
    static Node build() {
        Label intro = Labels.help("Opciones que solo existen en una red.");
        return page("Redes", intro, telegramGroup(), stickerVideoGroup(), group("WhatsApp",
                row("Stickers animados", "WhatsApp no acepta los stickers animados de otras redes tal cual. Sticker: se "
                        + "convierte en sticker animado. GIF: llega como un GIF que se repite. Video: llega como un video corto.",
                        stickerModeChoice(Network.WHATSAPP))),
                group("Discord",
                        row("Borrar fotos y videos de canales después de", "Discord guarda en tu disco una copia de cada "
                                + "imagen y video de los canales de servidores. Los avatares, emojis y los demás chats no "
                                + "se borran nunca.", discordMediaChoice())));
    }

    /** The person's own Telegram keys; only the local server uses them (other servers keep theirs in their setup). */
    private static Node telegramGroup() {
        if (!Settings.get().localServer()) return new javafx.scene.layout.Region();
        Label done = new Label();
        done.getStyleClass().add("settings-help");
        TelegramKeysForm form = new TelegramKeysForm("Guardar claves", () -> {
            done.setText("Guardadas.");
            LocalServer local = LocalServer.get();
            if (local == null) return;
            Thread t = new Thread(() -> {
                try {
                    // Telegram restarts with the new keys when it's in use; otherwise they wait for its first login.
                    local.restartBridge("telegram", msg -> { });
                } catch (Exception e) {
                    System.err.println("Telegram restart failed: " + e);
                    javafx.application.Platform.runLater(() -> done.setText("No se pudo reiniciar Telegram; se aplican al abrir Unichat de nuevo."));
                }
            }, "telegram-keys");
            t.setDaemon(true);
            t.start();
        });
        form.getChildren().add(done);
        return group("Telegram", form);
    }

    private static Node discordMediaChoice() {
        Settings s = Settings.get();
        return chips("Cuándo borrar las fotos y videos de canales de Discord", List.of(7, 30, 90, 0), d -> d == 0 ? "Nunca" : d + " días",
                d -> d == 0 ? "No borrar nunca las fotos y videos de Discord"
                        : "Borrar fotos y videos de Discord después de " + d + " días",
                s.discordMediaDays(), s::setDiscordMediaDays);
    }

    private static Node stickerModeChoice(Network n) {
        Settings s = Settings.get();
        String key = StickerMode.key(n);
        Function<StickerMode, String> label = m -> switch (m) {
            case STICKER -> "Sticker";
            case GIF -> "GIF";
            case VIDEO -> "Video";
        };
        return chips("Cómo enviar stickers animados a " + n.displayName(), List.of(StickerMode.values()), label,
                m -> "Enviar stickers animados de " + n.displayName() + " como " + label.apply(m),
                StickerMode.of(n), m -> s.set(key, m.name()));
    }
}
