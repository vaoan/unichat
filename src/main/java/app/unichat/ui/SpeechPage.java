package app.unichat.ui;

import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.stt.SttSettings;
import app.unichat.stt.Transcriber;
import app.unichat.stt.WhisperModel;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.List;

import static app.unichat.ui.SettingsForm.chips;
import static app.unichat.ui.SettingsForm.group;
import static app.unichat.ui.SettingsForm.page;
import static app.unichat.ui.SettingsForm.row;

/** Ajustes → Voz a texto. */
final class SpeechPage {
    private SpeechPage() {}

    /** Voice notes to text with what's on this computer: the bundled Whisper or the user's own command. */
    static Node build() {
        Settings s = Settings.get();
        Label intro = Labels.help(I18n.t("settings.speech.intro"));

        Label modelState = Labels.help("");
        modelState.setMaxWidth(240);
        Runnable refreshModel = () -> modelState.setText(SttSettings.model().isInstalled(Transcriber.modelsDir())
                ? I18n.t("settings.speech.downloaded") : I18n.t("settings.speech.downloadsFirst"));
        refreshModel.run();
        Node models = chips(I18n.t("settings.speech.model"), List.of(WhisperModel.values()),
                m -> m == WhisperModel.BASE ? I18n.t("settings.speech.modelFast") : I18n.t("settings.speech.modelAccurate"),
                SttSettings.model(), m -> {
                    SttSettings.setModel(m);
                    refreshModel.run();
                });
        Node languages = chips(I18n.t("settings.speech.language"), List.of("auto", "es", "en", "pt"),
                l -> switch (l) {
                    case "es" -> I18n.t("settings.speech.langEs");
                    case "en" -> I18n.t("settings.speech.langEn");
                    case "pt" -> I18n.t("settings.speech.langPt");
                    default -> I18n.t("settings.speech.langAuto");
                }, s.sttLanguage(), s::setSttLanguage);

        TextField command = new TextField(s.sttCommand());
        command.setPromptText(Transcriber.EXAMPLE_COMMAND);
        command.setPrefColumnCount(16);
        command.setAccessibleText(I18n.t("settings.speech.ownCommand"));
        command.textProperty().addListener((o, a, b) -> s.setSttCommand(b.strip()));
        Node engines = chips(I18n.t("settings.speech.how"), List.of(Transcriber.Engine.values()),
                e -> e == Transcriber.Engine.WHISPER ? I18n.t("settings.speech.engineWhisper") : I18n.t("settings.speech.engineCommand"),
                SttSettings.engine(), SttSettings::setEngine);
        boolean bundled = Transcriber.whisperBinary() != null;
        return page(I18n.t("settings.nav.speech"), intro,
                group(I18n.t("settings.speech.groupEngine"), row(I18n.t("settings.speech.how"),
                        I18n.t(bundled ? "settings.speech.bundled" : "settings.speech.notBundled"), engines)),
                group("Whisper",
                        row(I18n.t("settings.speech.modelRow"), I18n.t("settings.speech.modelHelp"), new VBox(4, models, modelState)),
                        row(I18n.t("settings.speech.languageRow"), I18n.t("settings.speech.languageHelp"), languages)),
                group(I18n.t("settings.speech.groupCommand"),
                        row(I18n.t("settings.speech.command"), I18n.t("settings.speech.commandHelp"), command)));
    }
}
