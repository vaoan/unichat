package app.unichat.ui;

import app.unichat.settings.Settings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static app.unichat.ui.SettingsForm.chips;
import static app.unichat.ui.SettingsForm.group;
import static app.unichat.ui.SettingsForm.page;
import static app.unichat.ui.SettingsForm.row;
import static app.unichat.ui.SettingsForm.toggle;

/** Ajustes → Apariencia and Accesibilidad: how Unichat looks, what they share being the text size and the appearance callback. */
final class AppearancePages {
    private static final String[] ACCENTS = {"#5B5BD6", "#0090FF", "#12A594", "#46A758", "#F76B15", "#E5484D", "#D6409F", "#8E4EC6", "#1C2024"};

    private AppearancePages() {}

    static Node appearance(Runnable changed) {
        Settings s = Settings.get();
        Node themes = plainChips("Modo", List.of(Settings.Theme.values()), t -> switch (t) {
            case SYSTEM -> "Sistema";
            case LIGHT -> "Claro";
            case DARK -> "Oscuro";
        }, s.theme(), t -> {
            s.setTheme(t);
            changed.run();
        });
        FlowPane accents = new FlowPane(8, 8);
        for (String hex : ACCENTS) {
            Button sw = new Button();
            sw.getStyleClass().add("accent-swatch");
            if (hex.equalsIgnoreCase(s.accent())) sw.getStyleClass().add("selected");
            sw.setStyle("-fx-background-color: " + hex + ";");
            sw.setAccessibleText("Color de acento " + hex);
            sw.setOnAction(e -> {
                s.setAccent(hex);
                accents.getChildren().forEach(n -> n.getStyleClass().remove("selected"));
                sw.getStyleClass().add("selected");
                changed.run();
            });
            accents.getChildren().add(sw);
        }
        Slider font = fontSlider(changed);
        return page("Apariencia",
                group("Tema", row("Modo", "Sigue al sistema o fuerza claro/oscuro.", themes),
                        row("Color de acento", null, accents)),
                group("Lista de chats",
                        row("Tamaño de texto", "También con " + Shortcuts.zoomKeys() + ".", font),
                        row("Lista compacta", "Filas más bajas para ver más chats.", toggle(s.compactList(), v -> { s.setCompactList(v); changed.run(); })),
                        row("Insignias de red", "Muestra la red de cada chat sobre su avatar.", toggle(s.showNetworkBadges(), v -> { s.setShowNetworkBadges(v); changed.run(); }))),
                group("Escritura",
                        row("Enter envía el mensaje", "Desactívalo para que Enter cree una línea nueva y "
                                + Shortcuts.key("Enter") + " envíe.", toggle(s.sendOnEnter(), s::setSendOnEnter))));
    }

    private static Slider fontSlider(Runnable changed) {
        Settings s = Settings.get();
        Slider font = new Slider(Settings.MIN_FONT_SCALE, Settings.MAX_FONT_SCALE, s.fontScale());
        font.setMajorTickUnit(0.15);
        font.setSnapToTicks(false);
        font.setPrefWidth(200);
        font.setMinWidth(160);
        font.setMaxWidth(220);
        font.valueChangingProperty().addListener((o, a, changing) -> {
            if (!changing) {
                s.setFontScale(font.getValue());
                changed.run();
            }
        });
        font.setOnMouseReleased(e -> {
            s.setFontScale(font.getValue());
            changed.run();
        });
        // Keyboard users change the slider with arrows: apply on every step.
        font.setOnKeyReleased(e -> {
            s.setFontScale(font.getValue());
            changed.run();
        });
        font.setBlockIncrement(0.05);
        font.setAccessibleRoleDescription("control deslizante");
        return font;
    }

    /** Three chips: Sistema / Activado / Desactivado. */
    private static Node modeChips(String accessible, Settings.Mode current, Consumer<Settings.Mode> save) {
        return plainChips(accessible, List.of(Settings.Mode.values()), m -> switch (m) {
            case SYSTEM -> "Sistema";
            case ON -> "Activado";
            case OFF -> "Desactivado";
        }, current, save);
    }

    /** Chips read out as just their label. */
    private static <T> Node plainChips(String accessible, List<T> values, Function<T, String> label, T current, Consumer<T> save) {
        return chips(accessible, values, label, label, current, save);
    }

    static Node accessibility(Runnable changed) {
        Settings s = Settings.get();
        String sys = " Ahora el sistema dice: ";
        Button test = new Button("Probar voz");
        test.getStyleClass().add("secondary-button");
        test.setOnAction(e -> Speaker.say("Mensaje de Mamá, WhatsApp: ¿Vienes el domingo a almorzar?"));
        HBox speak = new HBox(10, test, toggle(s.speakMessages(), s::setSpeakMessages));
        speak.setAlignment(Pos.CENTER_RIGHT);
        speak.getChildren().get(1).setAccessibleText("Leer en voz alta los mensajes nuevos");
        return page("Accesibilidad",
                group("Visión",
                        row("Tamaño de texto", "De 85 % a 160 %. Atajo: " + Shortcuts.zoomKeys() + ".", fontSlider(changed)),
                        row("Alto contraste", "Colores sólidos, bordes marcados y burbujas delimitadas." + sys
                                + (Theme.systemHighContrast() ? "activado." : "desactivado."),
                                modeChips("Alto contraste", s.highContrast(), m -> { s.setHighContrast(m); changed.run(); })),
                        row("Fuente más legible", "Usa Verdana, con letras más abiertas y diferenciadas.",
                                toggle(s.readableFont(), v -> { s.setReadableFont(v); changed.run(); })),
                        row("Más interlineado", "Separa más las líneas de los mensajes.",
                                toggle(s.wideSpacing(), v -> { s.setWideSpacing(v); changed.run(); }))),
                group("Movimiento y foco",
                        row("Reducir movimiento", "Quita animaciones y deja los avisos más tiempo en pantalla." + sys
                                + (Theme.systemReducedMotion() ? "activado." : "desactivado."),
                                modeChips("Reducir movimiento", s.reduceMotion(), m -> { s.setReduceMotion(m); changed.run(); })),
                        row("Indicador de foco reforzado", "Un borde grueso muestra dónde está el teclado.",
                                toggle(s.strongFocus(), v -> { s.setStrongFocus(v); changed.run(); })),
                        row("Acciones de mensaje siempre visibles", "Reaccionar, responder y más, sin pasar el ratón por encima.",
                                toggle(s.alwaysShowMessageActions(), v -> { s.setAlwaysShowMessageActions(v); changed.run(); }))),
                group("Audio",
                        row("Leer mensajes nuevos en voz alta", "Usa la voz del sistema. Respeta «Mostrar contenido» de Notificaciones y los chats silenciados.", speak)),
                group("Lectores de pantalla",
                        row("Narrador, VoiceOver y Orca", "Chats, mensajes, botones y reacciones tienen descripción hablada. "
                                + "F6 salta entre barra lateral, lista, mensajes y campo de texto; en los mensajes usa las flechas y R, E, Enter, 1–6.",
                                new Label())));
    }
}
