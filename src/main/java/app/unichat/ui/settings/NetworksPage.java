package app.unichat.ui.settings;

import app.unichat.ui.Labels;
import app.unichat.ui.Nodes;
import app.unichat.ui.chats.FolderMoves;
import app.unichat.ui.login.TelegramKeysForm;
import app.unichat.model.Network;
import app.unichat.server.LocalServer;
import app.unichat.service.StickerMode;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.voice.VoiceEnhancer;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Function;

import static app.unichat.ui.settings.SettingsForm.chips;
import static app.unichat.ui.settings.SettingsForm.group;
import static app.unichat.ui.settings.SettingsForm.page;
import static app.unichat.ui.settings.SettingsForm.row;
import static app.unichat.ui.settings.SettingsForm.toggle;

/** Ajustes → Redes. */
final class NetworksPage {
    private NetworksPage() {}

    /** The base folder of what chats save as it arrives; a chat can have its own (its options). */
    private static Node autoSaveFolder(app.unichat.service.ChatService svc, java.util.function.Consumer<String> toast) {
        Label where = new Label();
        where.setWrapText(true);
        Button change = new Button(I18n.t("chatOptions.changeFolder"));
        change.getStyleClass().add("secondary-button");
        Button reset = new Button(I18n.t("settings.networks.autoSaveFolderReset"));
        reset.getStyleClass().add("secondary-button");
        Runnable redraw = () -> {
            where.setText(app.unichat.autosave.SaveFolders.shown(app.unichat.autosave.SaveFolders.base()));
            Nodes.show(reset, app.unichat.autosave.SaveFolders.baseChosen());
        };
        change.setOnAction(e -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle(I18n.t("settings.networks.autoSaveFolder"));
            java.io.File f = dc.showDialog(change.getScene().getWindow());
            if (f == null) return;
            FolderMoves.tell(app.unichat.autosave.AutoSave.of(svc).setBase(f.toPath()), redraw, toast);
        });
        reset.setOnAction(e -> FolderMoves.tell(app.unichat.autosave.AutoSave.of(svc).setBase(null), redraw, toast));
        redraw.run();
        return new VBox(6, where, new javafx.scene.layout.FlowPane(8, 6, change, reset));
    }

    /** Options that only make sense for one network; each network gets its own group. */
    /**
     * What holds for every network first (where saved things go, voice notes, the converter), then each network's own,
     * in order of name, under headings that say which is which.
     */
    static Node build(app.unichat.service.ChatService svc, java.util.function.Consumer<String> toast) {
        Label intro = Labels.help(I18n.t("settings.networks.intro"));
        return page(I18n.t("settings.nav.networks"), intro,
                heading(I18n.t("settings.networks.forAll")),
                group(I18n.t("settings.networks.groupSaving"),
                        SettingsForm.stacked(I18n.t("settings.networks.autoSaveFolder"), I18n.t("settings.networks.autoSaveFolderHelp"),
                                autoSaveFolder(svc, toast))),
                group(I18n.t("settings.networks.groupVoice"),
                        row(I18n.t("settings.networks.enhance"), I18n.t("settings.networks.enhanceHelp"),
                                toggle(VoiceEnhancer.enabled(), VoiceEnhancer::setEnabled))),
                ConverterSettings.section(),
                heading(I18n.t("settings.networks.perNetwork")),
                group("Discord",
                        row(I18n.t("settings.networks.discordDelete"), I18n.t("settings.networks.discordDeleteHelp"), discordMediaChoice())),
                telegramGroup(),
                group("WhatsApp",
                        row(I18n.t("settings.networks.animated"), I18n.t("settings.networks.animatedHelp"),
                                stickerModeChoice(Network.WHATSAPP))));
    }

    private static Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-section-title");
        return l;
    }

    /** The person's own Telegram keys; only the local server uses them (other servers keep theirs in their setup). */
    private static Node telegramGroup() {
        if (!Settings.get().localServer()) {
            Region none = new Region();
            none.setManaged(false); // no gap in the page where it would be
            return none;
        }
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
