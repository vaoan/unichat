package app.unichat.ui;

import app.unichat.server.LogFiles;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
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
        return page(I18n.t("settings.nav.privacy"), group(I18n.t("settings.privacy.groupIndicators"),
                row(I18n.t("settings.privacy.readReceipts"), I18n.t("settings.privacy.readReceiptsHelp"),
                        readReceiptsChoice(s)),
                row(I18n.t("settings.privacy.typing"), I18n.t("settings.privacy.typingHelp"),
                        toggle(s.sendTypingNotifications(), s::setSendTypingNotifications))),
                group(I18n.t("settings.privacy.groupLocal"), row(I18n.t("settings.privacy.dataFolder"), s.dir().toString(), new Label()),
                        row(I18n.t("settings.privacy.mediaSpace"), I18n.t("settings.privacy.mediaSpaceHelp"),
                                mediaCacheChoice())),
                logsGroup(s),
                group(I18n.t("settings.privacy.groupUpdates"), row(I18n.t("settings.privacy.checkUpdates"), I18n.t("settings.privacy.checkUpdatesHelp"),
                        toggle(s.checkUpdates(), v -> {
                            s.setCheckUpdates(v);
                            app.unichat.update.UpdateChecker.get().settingChanged();
                        })),
                        row(I18n.t("settings.privacy.linkPreviews"), I18n.t("settings.privacy.linkPreviewsHelp"),
                                toggle(s.linkPreviews(), s::setLinkPreviews))));
    }

    private static Node readReceiptsChoice(Settings s) {
        return chips(I18n.t("settings.privacy.readReceipts"), List.of(Settings.ReadReceipts.values()),
                v -> I18n.t("settings.privacy.readReceipts." + v.name()), s.readReceipts(), s::setReadReceipts);
    }

    private static Node mediaCacheChoice() {
        Settings s = Settings.get();
        Function<Integer, String> label = mb -> mb < 1024 ? mb + " MB" : mb / 1024 + " GB";
        return chips(I18n.t("settings.privacy.mediaSpaceAccessible"), List.of(500, 1024, 5120), label,
                mb -> I18n.t("settings.privacy.mediaSpaceUse", label.apply(mb)), s.mediaCacheMb(), s::setMediaCacheMb);
    }

    /** Logs never hold personal data (Redactor) and are deleted after the chosen days (LogFiles). */
    private static Node logsGroup(Settings s) {
        Function<Integer, String> label = d -> I18n.n("common.days", d);
        Node days = chips(I18n.t("settings.privacy.logsAfter"), List.of(1, 7, 30), label,
                d -> I18n.t("settings.privacy.logsAfterDays", label.apply(d)), s.logRetentionDays(), d -> {
                    s.setLogRetentionDays(d);
                    Thread.ofVirtual().start(() -> LogFiles.cleanOld(LogFiles.roots(),
                            java.time.Duration.ofDays(d), java.time.Instant.now()));
                });
        Button clear = new Button(I18n.t("settings.privacy.clearButton"));
        clear.getStyleClass().add("secondary-button");
        clear.setOnAction(e -> {
            clear.setDisable(true);
            Thread.ofVirtual().start(() -> {
                LogFiles.clearAll(LogFiles.roots());
                javafx.application.Platform.runLater(() -> {
                    clear.setText(I18n.t("settings.privacy.cleared"));
                    clear.setDisable(false);
                });
            });
        });
        return group(I18n.t("settings.privacy.groupLogs"),
                row(I18n.t("settings.privacy.logsAfter"), I18n.t("settings.privacy.logsAfterHelp"), days),
                row(I18n.t("settings.privacy.clearNow"), I18n.t("settings.privacy.clearNowHelp"), clear));
    }
}
