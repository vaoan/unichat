package app.unichat.ui;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/** Preferences window (⌘, / Ctrl+,). Every control writes through to {@link Settings} immediately. */
public final class SettingsWindow {
    private static Stage open;

    private SettingsWindow() {}

    /** The open settings window, if any (used by the snapshot diagnostics). */
    public static Stage current() { return open; }

    /** Selects a page by its title, e.g. "Accesibilidad". */
    @SuppressWarnings("unchecked")
    public static void select(String page) {
        if (open == null) return;
        Node nav = open.getScene().getRoot().lookup(".settings-nav");
        if (nav instanceof ListView<?> lv) ((ListView<String>) lv).getSelectionModel().select(page);
    }

    private static final String[] ACCENTS = {"#5B5BD6", "#0090FF", "#12A594", "#46A758", "#F76B15", "#E5484D", "#D6409F", "#8E4EC6", "#1C2024"};

    public static void show(Window owner, ChatService svc, Runnable onAppearanceChanged, Runnable onLogout,
                            Runnable onAddAccount, Consumer<String> toast) {
        if (open != null) {
            open.toFront();
            return;
        }
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("Ajustes");

        BorderPane content = new BorderPane();
        content.getStyleClass().add("settings-content");
        ListView<String> nav = new ListView<>();
        nav.getItems().addAll("Cuenta", "Redes conectadas", "Apariencia", "Accesibilidad", "Notificaciones", "Privacidad", "Atajos de teclado", "Acerca de");
        nav.getStyleClass().add("settings-nav");
        nav.setPrefWidth(200);
        nav.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> {
            Node page = switch (s == null ? "Cuenta" : s) {
                case "Redes conectadas" -> networksPage(svc, () -> { stage.close(); onAddAccount.run(); });
                case "Apariencia" -> appearancePage(onAppearanceChanged);
                case "Accesibilidad" -> accessibilityPage(onAppearanceChanged);
                case "Notificaciones" -> notificationsPage();
                case "Privacidad" -> privacyPage();
                case "Atajos de teclado" -> shortcutsPage();
                case "Acerca de" -> aboutPage();
                default -> accountPage(svc, () -> { stage.close(); onLogout.run(); });
            };
            ScrollPane sp = new ScrollPane(page);
            sp.setFitToWidth(true);
            sp.getStyleClass().add("settings-scroll");
            content.setCenter(sp);
        });
        BorderPane root = new BorderPane(content);
        root.setLeft(nav);
        root.getStyleClass().add("settings-root");
        Scene scene = new Scene(root, 820, 580);
        Theme.register(scene);
        stage.setScene(scene);
        stage.setOnHidden(e -> open = null);
        open = stage;
        nav.getSelectionModel().select(0);
        stage.show();
    }

    private static VBox page(String title, Node... nodes) {
        Label t = new Label(title);
        t.getStyleClass().add("settings-title");
        VBox v = new VBox(16);
        v.getStyleClass().add("settings-page");
        v.getChildren().add(t);
        v.getChildren().addAll(nodes);
        return v;
    }

    private static VBox group(String title, Node... rows) {
        Label t = new Label(title);
        t.getStyleClass().add("settings-group-title");
        VBox box = new VBox(0, rows);
        box.getStyleClass().add("settings-group");
        return new VBox(6, t, box);
    }

    private static Node row(String label, String help, Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("settings-label");
        VBox text = new VBox(2, l);
        if (help != null) {
            Label h = new Label(help);
            h.getStyleClass().add("settings-help");
            h.setWrapText(true);
            text.getChildren().add(h);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        if (control.getAccessibleText() == null) control.setAccessibleText(label);
        if (control instanceof Region r && !(control instanceof Slider)) r.setMinWidth(Region.USE_PREF_SIZE);
        if (help != null && control.getAccessibleHelp() == null) control.setAccessibleHelp(help);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.SOMETIMES);
        HBox row = new HBox(12, text, grow, control);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        return row;
    }

    private static CheckBox toggle(boolean value, Consumer<Boolean> save) {
        CheckBox c = new CheckBox();
        c.getStyleClass().add("switch");
        c.setSelected(value);
        c.selectedProperty().addListener((o, a, b) -> save.accept(b));
        return c;
    }

    // ------------------------------------------------------------------ pages

    private static Node accountPage(ChatService svc, Runnable logout) {
        Avatar a = new Avatar(64);
        a.set(svc.myDisplayName(), svc.myUserId(), null, svc);
        Label name = new Label(svc.myDisplayName());
        name.getStyleClass().add("settings-title");
        Label id = new Label(svc.myUserId());
        id.getStyleClass().add("settings-help");
        Label backend = new Label("demo".equals(Settings.get().backend())
                ? "Modo demo: los datos son de ejemplo y no salen de este equipo."
                : "Servidor: " + Settings.get().homeserver());
        backend.getStyleClass().add("settings-help");
        backend.setWrapText(true);
        HBox who = new HBox(16, a, new VBox(4, name, id, backend));
        who.setAlignment(Pos.CENTER_LEFT);
        Button out = new Button("Cerrar sesión");
        out.getStyleClass().add("danger-button");
        out.setOnAction(e -> logout.run());
        return page("Cuenta", who, group("Sesión", row("Cerrar sesión en este equipo",
                "Se borra el token guardado. Tus redes siguen conectadas en el servidor.", out)));
    }

    private static Node networksPage(ChatService svc, Runnable add) {
        VBox list = new VBox(0);
        list.getStyleClass().add("settings-group");
        List<Account> accounts = svc.accounts();
        if (accounts.isEmpty()) {
            Label l = new Label("Todavía no conectaste ninguna red.");
            l.getStyleClass().add("settings-help");
            list.getChildren().add(new HBox(l));
        }
        for (Account acc : accounts) {
            Label letter = new Label(acc.network().badgeLetter());
            letter.getStyleClass().add("network-tile-icon");
            letter.setStyle("-fx-background-color: " + acc.network().color() + "; -fx-min-width: 28; -fx-min-height: 28;");
            Label status = new Label(switch (acc.status()) {
                case CONNECTED -> "Conectado";
                case CONNECTING -> "Conectando…";
                case NEEDS_LOGIN -> "Inicia sesión";
                case ERROR -> "Error";
                case NOT_AVAILABLE -> "No disponible";
            });
            status.getStyleClass().addAll("status-pill", acc.status() == AccountStatus.CONNECTED ? "ok" : "warn");
            Button disconnect = new Button("Desconectar");
            disconnect.getStyleClass().add("secondary-button");
            disconnect.setOnAction(e -> {
                disconnect.setDisable(true);
                svc.disconnectNetwork(acc.network());
            });
            HBox control = new HBox(10, status, disconnect);
            control.setAlignment(Pos.CENTER_RIGHT);
            HBox row = (HBox) row(acc.network().displayName(), acc.label(), control);
            row.getChildren().add(0, letter);
            list.getChildren().add(row);
        }
        Button addBtn = new Button("Conectar una red");
        addBtn.getStyleClass().add("primary-button");
        addBtn.setOnAction(e -> add.run());
        return page("Redes conectadas", list, addBtn);
    }

    private static Node appearancePage(Runnable changed) {
        Settings s = Settings.get();
        ToggleGroup tg = new ToggleGroup();
        HBox themes = new HBox(6);
        for (Settings.Theme t : Settings.Theme.values()) {
            ToggleButton b = new ToggleButton(switch (t) {
                case SYSTEM -> "Sistema";
                case LIGHT -> "Claro";
                case DARK -> "Oscuro";
            });
            b.getStyleClass().add("chip");
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setToggleGroup(tg);
            b.setSelected(s.theme() == t);
            b.setOnAction(e -> {
                if (!b.isSelected()) b.setSelected(true);
                s.setTheme(t);
                changed.run();
            });
            themes.getChildren().add(b);
        }
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
                        row("Tamaño de texto", "También con " + (MainView.MAC ? "⌘" : "Ctrl") + " + / − / 0.", font),
                        row("Lista compacta", "Filas más bajas para ver más chats.", toggle(s.compactList(), v -> { s.setCompactList(v); changed.run(); })),
                        row("Insignias de red", "Muestra la red de cada chat sobre su avatar.", toggle(s.showNetworkBadges(), v -> { s.setShowNetworkBadges(v); changed.run(); }))),
                group("Escritura",
                        row("Enter envía el mensaje", "Desactívalo para que Enter cree una línea nueva y "
                                + (MainView.MAC ? "⌘" : "Ctrl") + "+Enter envíe.", toggle(s.sendOnEnter(), s::setSendOnEnter))));
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
    private static HBox modeChips(Settings.Mode current, Consumer<Settings.Mode> save) {
        ToggleGroup tg = new ToggleGroup();
        HBox box = new HBox(6);
        for (Settings.Mode m : Settings.Mode.values()) {
            ToggleButton b = new ToggleButton(switch (m) {
                case SYSTEM -> "Sistema";
                case ON -> "Activado";
                case OFF -> "Desactivado";
            });
            b.getStyleClass().add("chip");
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setToggleGroup(tg);
            b.setSelected(current == m);
            b.setOnAction(e -> {
                if (!b.isSelected()) b.setSelected(true);
                save.accept(m);
            });
            box.getChildren().add(b);
        }
        return box;
    }

    private static Node accessibilityPage(Runnable changed) {
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
                        row("Tamaño de texto", "De 85 % a 160 %. Atajo: " + (MainView.MAC ? "⌘" : "Ctrl") + " + / − / 0.", fontSlider(changed)),
                        row("Alto contraste", "Colores sólidos, bordes marcados y burbujas delimitadas." + sys
                                + (Theme.systemHighContrast() ? "activado." : "desactivado."),
                                modeChips(s.highContrast(), m -> { s.setHighContrast(m); changed.run(); })),
                        row("Fuente más legible", "Usa Verdana, con letras más abiertas y diferenciadas.",
                                toggle(s.readableFont(), v -> { s.setReadableFont(v); changed.run(); })),
                        row("Más interlineado", "Separa más las líneas de los mensajes.",
                                toggle(s.wideSpacing(), v -> { s.setWideSpacing(v); changed.run(); }))),
                group("Movimiento y foco",
                        row("Reducir movimiento", "Quita animaciones y deja los avisos más tiempo en pantalla." + sys
                                + (Theme.systemReducedMotion() ? "activado." : "desactivado."),
                                modeChips(s.reduceMotion(), m -> { s.setReduceMotion(m); changed.run(); })),
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

    private static Node notificationsPage() {
        Settings s = Settings.get();
        return page("Notificaciones", group("Escritorio",
                row("Notificaciones", "Avisos del sistema para mensajes nuevos (los chats silenciados no avisan).",
                        toggle(s.notificationsEnabled(), s::setNotificationsEnabled)),
                row("Mostrar contenido", "Si lo desactivas, solo se ve quién escribió.", toggle(s.notificationPreview(), s::setNotificationPreview)),
                row("Sonido", null, toggle(s.notificationSound(), s::setNotificationSound)),
                row("Seguir en la bandeja al cerrar", "Cerrar la ventana la oculta en el área de notificaciones.",
                        toggle(s.minimizeToTray(), s::setMinimizeToTray))));
    }

    private static Node privacyPage() {
        Settings s = Settings.get();
        return page("Privacidad", group("Indicadores",
                row("Confirmaciones de lectura", "Los demás ven cuándo leíste sus mensajes (si la red lo permite).",
                        toggle(s.sendReadReceipts(), s::setSendReadReceipts)),
                row("Indicador de escritura", "Los demás ven cuando estás escribiendo.",
                        toggle(s.sendTypingNotifications(), s::setSendTypingNotifications))),
                group("Datos locales", row("Carpeta de datos", s.dir().toString(), new Label())));
    }

    private static Node shortcutsPage() {
        String m = MainView.MAC ? "⌘" : "Ctrl+";
        String[][] keys = {
                {m + "K", "Buscar chats y comandos"},
                {"F6 / Shift+F6", "Saltar entre barra lateral, lista, mensajes y campo de texto"},
                {"↑ / ↓ (en mensajes)", "Recorrer mensajes"},
                {"R · E · Enter (en mensajes)", "Responder · Editar · Más acciones"},
                {"1 … 6 (en mensajes)", "Reacciones rápidas ❤️ 👍 😂 😮 😢 🙏"},
                {m + "+ / " + m + "− / " + m + "0", "Agrandar · Achicar · Restablecer texto"},
                {"Alt+↑ / Alt+↓", "Chat anterior / siguiente"},
                {"Alt+Shift+↑ / ↓", "Anterior / siguiente sin leer"},
                {m + "E", "Archivar o desarchivar el chat"},
                {m + "Shift+U", "Marcar como leído / no leído"},
                {m + "1 … " + m + "4", "Todos / No leídos / Favoritos / Prioridad baja"},
                {m + "I", "Mostrar u ocultar la información del chat"},
                {m + ",", "Ajustes"},
                {"↑ (campo vacío)", "Editar tu último mensaje"},
                {"Esc", "Cancelar respuesta o edición"},
                {"Doble clic en un mensaje", "Reaccionar con ❤️"},
        };
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(10);
        for (int i = 0; i < keys.length; i++) {
            Label k = new Label(keys[i][0]);
            k.getStyleClass().add("kbd");
            grid.add(k, 0, i);
            grid.add(new Label(keys[i][1]), 1, i);
        }
        return page("Atajos de teclado", grid);
    }

    private static Node aboutPage() {
        Label l = new Label("Unichat 1.0.0\nBandeja de mensajes unificada para escritorio (Java + JavaFX), "
                + "construida sobre Matrix y los puentes mautrix.\n\nInspirada en Beeper; no está afiliada ni comparte código con Beeper.");
        l.setWrapText(true);
        l.getStyleClass().add("settings-help");
        return page("Acerca de", l);
    }
}
