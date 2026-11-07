package app.unichat.ui;

import app.unichat.model.Network;
import app.unichat.server.LocalServer;
import app.unichat.service.StickerMode;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
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
        Button install = new Button(I18n.t("settings.networks.install"));
        install.getStyleClass().add("secondary-button");
        install.setMinWidth(Region.USE_PREF_SIZE);
        Runnable refresh = () -> {
            boolean found = Ffmpeg.find() != null;
            state.setText(found ? I18n.t("settings.networks.ffmpegInstalled")
                    : !Ffmpeg.canInstall() ? I18n.t("settings.networks.ffmpegNoDownload")
                    : Ffmpeg.autoInstall() ? I18n.t("settings.networks.ffmpegAuto", Formats.fileSize(Ffmpeg.downloadSize()))
                    : I18n.t("settings.networks.ffmpegMissing"));
            install.setVisible(!found && Ffmpeg.canInstall());
            install.setManaged(install.isVisible());
        };
        refresh.run();
        install.setOnAction(e -> {
            install.setDisable(true);
            state.setText(I18n.t("settings.networks.downloading"));
            WebmStickers.WORKER.execute(() -> {
                String error = null;
                try {
                    Ffmpeg.install(done -> javafx.application.Platform.runLater(() -> state.setText(I18n.t("settings.networks.downloadingPercent", (int) (done * 100)))));
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
                String failed = error;
                javafx.application.Platform.runLater(() -> {
                    install.setDisable(false);
                    refresh.run();
                    if (failed != null) state.setText(I18n.t("settings.networks.installFailed", failed));
                });
            });
        });
        return group(I18n.t("settings.networks.groupMedia"),
                row(I18n.t("settings.networks.enhance"), I18n.t("settings.networks.enhanceHelp"),
                        toggle(VoiceEnhancer.enabled(), VoiceEnhancer::setEnabled)),
                row(I18n.t("settings.networks.autoInstall"), I18n.t("settings.networks.autoInstallHelp"),
                        toggle(Ffmpeg.autoInstall(), v -> {
                            Ffmpeg.setAutoInstall(v);
                            refresh.run();
                        })),
                row(I18n.t("settings.networks.converter"), null, new VBox(6, state, install)));
    }

    /** Options that only make sense for one network; each network gets its own group. */
    static Node build() {
        Label intro = Labels.help(I18n.t("settings.networks.intro"));
        return page(I18n.t("settings.nav.networks"), intro, telegramGroup(), stickerVideoGroup(), group("WhatsApp",
                row(I18n.t("settings.networks.animated"), I18n.t("settings.networks.animatedHelp"),
                        stickerModeChoice(Network.WHATSAPP))),
                group("Discord",
                        row(I18n.t("settings.networks.discordDelete"), I18n.t("settings.networks.discordDeleteHelp"), discordMediaChoice())));
    }

    /** The person's own Telegram keys; only the local server uses them (other servers keep theirs in their setup). */
    private static Node telegramGroup() {
        if (!Settings.get().localServer()) return new javafx.scene.layout.Region();
        Label done = new Label();
        done.getStyleClass().add("settings-help");
        TelegramKeysForm form = new TelegramKeysForm(I18n.t("settings.networks.telegramSave"), () -> {
            done.setText(I18n.t("settings.networks.telegramSaved"));
            LocalServer local = LocalServer.get();
            if (local == null) return;
            Thread t = new Thread(() -> {
                try {
                    // Telegram restarts with the new keys when it's in use; otherwise they wait for its first login.
                    local.restartBridge("telegram", msg -> { });
                } catch (Exception e) {
                    System.err.println("Telegram restart failed: " + e);
                    javafx.application.Platform.runLater(() -> done.setText(I18n.t("settings.networks.telegramRestartFailed")));
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
        return chips(I18n.t("settings.networks.discordWhen"), List.of(7, 30, 90, 0),
                d -> d == 0 ? I18n.t("common.never") : I18n.n("common.days", d),
                d -> d == 0 ? I18n.t("settings.networks.discordNever")
                        : I18n.t("settings.networks.discordAfter", I18n.n("common.days", d)),
                s.discordMediaDays(), s::setDiscordMediaDays);
    }

    private static Node stickerModeChoice(Network n) {
        Settings s = Settings.get();
        String key = StickerMode.key(n);
        Function<StickerMode, String> label = m -> switch (m) {
            case STICKER -> I18n.t("settings.networks.modeSticker");
            case GIF -> I18n.t("settings.networks.modeGif");
            case VIDEO -> I18n.t("settings.networks.modeVideo");
        };
        return chips(I18n.t("settings.networks.stickerHow", n.displayName()), List.of(StickerMode.values()), label,
                m -> I18n.t("settings.networks.stickerAs", n.displayName(), label.apply(m)),
                StickerMode.of(n), m -> s.set(key, m.name()));
    }
}
