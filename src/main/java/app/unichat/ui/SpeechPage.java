package app.unichat.ui;

import app.unichat.settings.Settings;
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
        Label intro = Labels.help("Transcribe las notas de voz que recibes (menú del mensaje, \"Transcribir\" o la tecla T). "
                + "Todo ocurre en este computador: el audio no se envía a ningún servicio y no cuesta nada.");

        Label modelState = Labels.help("");
        modelState.setMaxWidth(240);
        Runnable refreshModel = () -> modelState.setText(SttSettings.model().isInstalled(Transcriber.modelsDir())
                ? "Descargado." : "Se descarga la primera vez que transcribes.");
        refreshModel.run();
        Node models = chips("Modelo de Whisper", List.of(WhisperModel.values()),
                m -> m == WhisperModel.BASE ? "Rápido · 60 MB" : "Preciso · 190 MB",
                SttSettings.model(), m -> {
                    SttSettings.setModel(m);
                    refreshModel.run();
                });
        Node languages = chips("Idioma de las notas de voz", List.of("auto", "es", "en", "pt"),
                l -> switch (l) {
                    case "es" -> "Español";
                    case "en" -> "English";
                    case "pt" -> "Português";
                    default -> "Detectar";
                }, s.sttLanguage(), s::setSttLanguage);

        TextField command = new TextField(s.sttCommand());
        command.setPromptText(Transcriber.EXAMPLE_COMMAND);
        command.setPrefColumnCount(16);
        command.setAccessibleText("Comando propio para transcribir");
        command.textProperty().addListener((o, a, b) -> s.setSttCommand(b.strip()));
        Node engines = chips("Cómo transcribir", List.of(Transcriber.Engine.values()),
                e -> e == Transcriber.Engine.WHISPER ? "Whisper (incluido)" : "Comando propio",
                SttSettings.engine(), SttSettings::setEngine);
        boolean bundled = Transcriber.whisperBinary() != null;
        return page("Voz a texto", intro,
                group("Motor", row("Cómo transcribir", bundled ? "Whisper viene con Unichat y funciona sin internet una vez "
                        + "descargado el modelo." : "Esta instalación no trae Whisper: usa un comando propio.", engines)),
                group("Whisper",
                        row("Modelo", "El preciso entiende bastante mejor el español y tarda más o menos el doble. "
                                + "El rápido sirve en computadores lentos.", new VBox(4, models, modelState)),
                        row("Idioma", "Fijarlo evita errores en notas cortas; \"Detectar\" sirve si recibes varios.", languages)),
                group("Comando propio",
                        row("Comando", "Cualquier programa que reciba el audio y escriba el texto en su salida. {archivo}: "
                                + "el audio (WAV, MP3 o FLAC). {idioma}: es, en… o auto. Claude Code y Codex no "
                                + "transcriben audio; sirven, por ejemplo, whisper, mlx_whisper o faster-whisper.", command)));
    }
}
