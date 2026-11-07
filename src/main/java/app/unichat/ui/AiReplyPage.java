package app.unichat.ui;

import app.unichat.assist.AiEngine;
import app.unichat.assist.AiSettings;
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
        Label intro = new Label("Agrega dos botones junto al cuadro de texto, con el programa de IA que tengas instalado: uno escribe un "
                + "borrador de respuesta y otro corrige la ortografía y la redacción de lo que escribiste, cambiando lo mínimo. "
                + "El resultado queda en el cuadro para que lo leas y lo cambies: nunca se envía solo.");
        intro.setWrapText(true);
        Label privacy = new Label("Al pulsar el botón de borrador, ese chat se envía al servicio de Claude Code o de Codex, con tu cuenta. "
                + "Solo se envían los mensajes cargados de ese chat, y solo cuando lo pulsas. Fotos y audios van como una mención, sin su contenido. "
                + "Al corregir, solo se envía lo que escribiste en el cuadro.");
        privacy.setWrapText(true);
        privacy.getStyleClass().add("settings-help");

        Label found = new Label();
        found.getStyleClass().add("settings-help");
        found.setWrapText(true);
        Runnable refresh = () -> {
            AiEngine e = AiSettings.engine();
            found.setText(e.find() == null ? e.label() + " no está instalado en este computador." : "Instalado.");
        };
        refresh.run();
        Node engines = SettingsWindow.chips("Programa de IA", List.of(AiEngine.values()), AiEngine::label, AiSettings.engine(), e -> {
            AiSettings.setEngine(e);
            refresh.run();
        });

        TextArea instructions = new TextArea(AiSettings.instructions());
        instructions.setWrapText(true);
        instructions.setPrefRowCount(6);
        instructions.setAccessibleText("Instrucciones para la IA");
        instructions.textProperty().addListener((o, a, t) -> AiSettings.setInstructions(t));
        Label instructionsHelp = new Label("Se envían con cada borrador, antes de la conversación. Al corregir no se usan.");
        instructionsHelp.getStyleClass().add("settings-help");

        return SettingsWindow.page("Escribir con IA", intro, privacy,
                SettingsWindow.group("General",
                        SettingsWindow.row("Mostrar los botones", "Aparecen en todos los chats.", SettingsWindow.toggle(AiSettings.enabled(), on -> {
                            AiSettings.setEnabled(on);
                            AiReplyButton.SHOWN.set(on);
                        })),
                        SettingsWindow.row("Programa", "Usa tu propia sesión y tu plan.", new VBox(4, engines, found)),
                        SettingsWindow.row("Carpeta que puede leer", "Por ejemplo la de tu hoja de vida. Claude Code solo puede leer esta carpeta. "
                                + "Codex no puede escribir, pero sí leer el resto del disco.", folderControl())),
                new VBox(6, new Label("Instrucciones"), instructions, instructionsHelp));
    }

    private static Node folderControl() {
        Label name = new Label();
        Button remove = new Button("Quitar");
        Runnable show = () -> {
            Path f = AiSettings.folder();
            name.setText(f == null ? "Ninguna" : f.toString());
            remove.setDisable(f == null);
        };
        show.run();
        Button choose = new Button("Elegir…");
        choose.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Carpeta que la IA puede leer");
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
