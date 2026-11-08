package app.unichat.ui;

import app.unichat.assist.AiEngine;
import app.unichat.assist.AiSettings;
import app.unichat.support.I18n;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

/** Ajustes → Escribir con IA. */
final class AiReplyPage {
    private AiReplyPage() {}

    static Node build() {
        Label intro = new Label(I18n.t("settings.ai.intro"));
        intro.setWrapText(true);
        Label privacy = Labels.help(I18n.t("settings.ai.privacy"));

        Label found = Labels.help("");
        Runnable refresh = () -> {
            AiEngine e = AiSettings.engine();
            found.setText(e.find() == null ? I18n.t("settings.ai.notInstalled", e.label()) : I18n.t("settings.ai.installed"));
        };
        refresh.run();
        Node engines = SettingsForm.chips(I18n.t("settings.ai.program"), List.of(AiEngine.values()), AiEngine::label, AiSettings.engine(), e -> {
            AiSettings.setEngine(e);
            refresh.run();
        });

        TextArea instructions = new TextArea(AiSettings.instructions());
        instructions.setWrapText(true);
        instructions.setPrefRowCount(6);
        instructions.setAccessibleText(I18n.t("settings.ai.instructionsAccessible"));
        instructions.textProperty().addListener((o, a, t) -> AiSettings.setInstructions(t));
        Label instructionsHelp = new Label(I18n.t("settings.ai.instructionsHelp"));
        instructionsHelp.getStyleClass().add("settings-help");

        return SettingsForm.page(I18n.t("settings.nav.ai"), intro, privacy,
                SettingsForm.group(I18n.t("settings.ai.groupGeneral"),
                        SettingsForm.row(I18n.t("settings.ai.show"), I18n.t("settings.ai.showHelp"), SettingsForm.toggle(AiSettings.enabled(), on -> {
                            AiSettings.setEnabled(on);
                            AiReplyButton.SHOWN.set(on);
                        })),
                        SettingsForm.row(I18n.t("settings.ai.programRow"), I18n.t("settings.ai.programHelp"), new VBox(4, engines, found)),
                        SettingsForm.row(I18n.t("settings.ai.folder"), I18n.t("settings.ai.folderHelp"), folderControl()),
                        SettingsForm.row(I18n.t("settings.ai.voiceNotes"), I18n.t("settings.ai.voiceNotesHelp"),
                                SettingsForm.toggle(AiSettings.hearVoiceNotes(), AiSettings::setHearVoiceNotes))),
                new VBox(6, new Label(I18n.t("settings.ai.instructions")), instructions, instructionsHelp));
    }

    private static Node folderControl() {
        Label name = new Label();
        Button remove = new Button(I18n.t("settings.ai.remove"));
        Runnable show = () -> {
            Path f = AiSettings.folder();
            name.setText(f == null ? I18n.t("settings.ai.none") : f.toString());
            remove.setDisable(f == null);
        };
        show.run();
        Button choose = new Button(I18n.t("settings.ai.choose"));
        choose.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(I18n.t("settings.ai.chooserTitle"));
            Path current = AiSettings.folder();
            if (current != null && current.toFile().isDirectory()) chooser.setInitialDirectory(current.toFile());
            File picked = chooser.showDialog(choose.getScene().getWindow());
            if (picked != null) {
                AiSettings.setFolder(picked.toPath());
                show.run();
            }
        });
        remove.setOnAction(e -> {
            AiSettings.setFolder(null);
            show.run();
        });
        name.setWrapText(true);
        name.setMaxWidth(240);
        HBox buttons = new HBox(6, choose, remove);
        return new VBox(4, name, buttons);
    }
}
