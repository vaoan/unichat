package app.unichat.ui;

import app.unichat.gif.GifProviders;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.function.Consumer;

import static app.unichat.ui.SettingsForm.group;
import static app.unichat.ui.SettingsForm.page;
import static app.unichat.ui.SettingsForm.row;
import static app.unichat.ui.SettingsForm.toggle;

/** Preferences window (⌘, / Ctrl+,). Every control writes through to {@link Settings} immediately. */
public final class SettingsWindow {
    private static Stage open;

    private SettingsWindow() {}

    private static AccountsPane accounts;

    /** The open settings window, if any (used by the snapshot diagnostics). */
    public static Stage current() { return open; }

    /** The "Cuentas" page while it is showing, else null. */
    public static AccountsPane accountsPage() { return accounts; }

    /** Selects the first page whose title starts with {@code page}, e.g. "Accesibilidad" or "Cuentas". */
    @SuppressWarnings("unchecked")
    public static void select(String page) {
        if (open == null) return;
        Node nav = open.getScene().getRoot().lookup(".settings-nav");
        if (nav instanceof ListView<?> lv) {
            ListView<String> l = (ListView<String>) lv;
            l.getItems().stream().filter(t -> t.startsWith(page)).findFirst().ifPresent(t -> l.getSelectionModel().select(t));
        }
    }

    public static void show(Window owner, ChatService svc, Runnable onAppearanceChanged, Runnable onLogout,
                            Consumer<String> openChatId, Consumer<String> toast) {
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
        nav.getItems().addAll("Cuentas", "Perfil", "Apariencia", "Accesibilidad", "Notificaciones", "Privacidad", "Redes", "Voz a texto", "Escribir con IA", "GIFs", "Atajos de teclado", "Acerca de");
        nav.getStyleClass().add("settings-nav");
        nav.setPrefWidth(200);
        nav.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> {
            if (accounts != null) accounts.dispose();
            accounts = null;
            if ("Cuentas".equals(s) || s == null) {
                accounts = new AccountsPane(svc, toast, id -> {
                    stage.close();
                    openChatId.accept(id);
                });
                accounts.getStyleClass().add("settings-page");
                content.setCenter(accounts);
                return;
            }
            Node page = switch (s) {
                case "Apariencia" -> AppearancePages.appearance(onAppearanceChanged);
                case "Accesibilidad" -> AppearancePages.accessibility(onAppearanceChanged);
                case "Notificaciones" -> notificationsPage();
                case "Privacidad" -> PrivacyPage.build();
                case "Redes" -> NetworksPage.build();
                case "Voz a texto" -> SpeechPage.build();
                case "Escribir con IA" -> AiReplyPage.build();
                case "GIFs" -> gifsPage();
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
        stage.setOnHidden(e -> {
            if (accounts != null) accounts.dispose();
            accounts = null;
            open = null;
        });
        open = stage;
        nav.getSelectionModel().select(0);
        stage.show();
    }

    private static Node accountPage(ChatService svc, Runnable logout) {
        Avatar a = new Avatar(64);
        a.set(svc.myDisplayName(), svc.myUserId(), null, svc);
        Label name = new Label(svc.myDisplayName());
        name.getStyleClass().add("settings-title");
        Label id = new Label(svc.myUserId());
        id.getStyleClass().add("settings-help");
        Label backend = Labels.help("demo".equals(Settings.get().backend())
                ? "Modo demo: los datos son de ejemplo y no salen de este equipo."
                : "Servidor: " + Settings.get().homeserver());
        HBox who = new HBox(16, a, new VBox(4, name, id, backend));
        who.setAlignment(Pos.CENTER_LEFT);
        Button out = new Button("Cerrar sesión");
        out.getStyleClass().add("danger-button");
        out.setOnAction(e -> logout.run());
        return page("Perfil", who, group("Sesión", row("Cerrar sesión en este equipo",
                "Se borra el token guardado. Tus redes siguen conectadas en el servidor.", out)));
    }

    private static Node notificationsPage() {
        Settings s = Settings.get();
        Node[] rows = {
                row("Notificaciones", "Avisos del sistema para mensajes nuevos (los chats silenciados no avisan).",
                        toggle(s.notificationsEnabled(), s::setNotificationsEnabled)),
                row("Mostrar contenido", "Si lo desactivas, solo se ve quién escribió.", toggle(s.notificationPreview(), s::setNotificationPreview)),
                row("Sonido", null, toggle(s.notificationSound(), s::setNotificationSound)),
                row("Chats archivados", "Avisar también de los mensajes nuevos en chats archivados.",
                        toggle(s.notifyArchived(), s::setNotifyArchived)),
                row("Seguir en la bandeja al cerrar", "Cerrar la ventana la oculta en el área de notificaciones.",
                        toggle(s.minimizeToTray(), s::setMinimizeToTray)),
                dockRow(s),
                shortcutRow(s),
        };
        return page("Notificaciones", group("Escritorio", java.util.Arrays.stream(rows).filter(java.util.Objects::nonNull).toArray(Node[]::new)));
    }

    /** Without a Dock icon a Mac app isn't in ⌘Tab; null elsewhere. */
    private static Node shortcutRow(Settings s) {
        if (!MainView.MAC) return null;
        return row("Abrir con ⌃⌥U desde cualquier app", "Control + Opción + U trae Unichat al frente, aunque esté en la barra de menú. "
                + "Sirve como ⌘Tab, que en Mac solo muestra las apps con icono en el Dock.",
                toggle(s.globalShortcut(), v -> {
                    s.setGlobalShortcut(v);
                    NativeBar.applyGlobalShortcut(v);
                }));
    }

    /** Only a Mac has one; null elsewhere. */
    private static Node dockRow(Settings s) {
        if (!MainView.MAC) return null;
        return row("Mostrar en el Dock", "Apagado, Unichat vive en la barra de menú: no aparece en el Dock ni en ⌘Tab, y se abre desde su icono "
                        + "arriba a la derecha o con el atajo. Encendido, tiene icono en el Dock y en ⌘Tab mientras la ventana esté abierta. Si ya salía entre las apps "
                        + "recientes del Dock, quítalo una vez con clic derecho.",
                        toggle(s.showInDock(), v -> {
                            s.setShowInDock(v);
                            DockIcon.show();
                        }));
    }

    /**
     * GIF search providers: each one on/off with the user's own free key. Only the search text goes to them.
     * Adding a provider = an adapter + a line in GifProviders.
     */
    private static Node gifsPage() {
        Settings s = Settings.get();
        java.util.List<Node> groups = new java.util.ArrayList<>();
        Label intro = Labels.help("Activa uno o más proveedores para buscar GIFs en el panel lateral. Cada uno necesita su propia "
                + "clave gratuita. Solo se les envía el texto que buscas.");
        groups.add(intro);
        for (GifProviders.Known k : GifProviders.known()) {
            String enabledKey = "gif." + k.id() + ".enabled", keyKey = "gif." + k.id() + ".key";
            PasswordField key = new PasswordField();
            key.setText(s.getString(keyKey, ""));
            key.setPromptText("Pega aquí tu clave");
            key.setAccessibleText("Clave de " + k.name());
            key.textProperty().addListener((o, a, b) -> s.set(keyKey, b.strip()));
            javafx.scene.control.Hyperlink get = new javafx.scene.control.Hyperlink("Conseguir una clave gratis");
            get.setAccessibleText("Conseguir una clave gratis de " + k.name());
            get.setOnAction(e -> Browser.open(k.keyUrl())); // the address is in the tooltip too
            get.setTooltip(new javafx.scene.control.Tooltip(k.keyUrl()));
            groups.add(group(k.name(),
                    row("Usar " + k.name(), null, toggle("true".equals(s.getString(enabledKey, "false")), v -> s.set(enabledKey, v.toString()))),
                    row("Clave", null, key),
                    row("¿No tienes clave?", k.keyUrl(), get)));
        }
        return page("GIFs", groups.toArray(new Node[0]));
    }

    private static Node shortcutsPage() {
        String[][] keys = {
                {Shortcuts.key("K"), "Buscar chats y comandos"},
                {"F6 / Shift+F6", "Saltar entre barra lateral, lista, mensajes y campo de texto"},
                {"↑ / ↓ (en mensajes)", "Recorrer mensajes"},
                {"R · E · Enter (en mensajes)", "Responder · Editar · Más acciones"},
                {"1 … 6 (en mensajes)", "Reacciones rápidas ❤️ 👍 😂 😮 😢 🙏"},
                {Shortcuts.zoomKeys(), "Agrandar · Achicar · Restablecer texto"},
                {"Alt+↑ / Alt+↓", "Chat anterior / siguiente"},
                {"Alt+Shift+↑ / ↓", "Anterior / siguiente sin leer"},
                {Shortcuts.key("E"), "Archivar o desarchivar el chat"},
                {Shortcuts.key("Shift+U"), "Marcar como leído / no leído"},
                {Shortcuts.key("1") + " … " + Shortcuts.key("4"), "Todos / No leídos / Favoritos / Prioridad baja"},
                {Shortcuts.key("Shift+I"), "Mostrar u ocultar la información del chat"},
                {Shortcuts.key(","), "Ajustes"},
                {Shortcuts.key("I"), "Escribir un borrador con IA, si los botones de IA están activos"},
                {Shortcuts.key("O"), "Corregir la ortografía y la redacción con IA, si los botones de IA están activos"},
                {"Shift+↑ / Shift+↓ (campo de texto)", "Seleccionar desde el cursor hasta el principio / el final"},
                {"↑ (campo vacío)", "Editar tu último mensaje"},
                {"Esc", "Cancelar respuesta o edición"},
                {"Doble clic en un mensaje", "Reaccionar con ❤️"},
        };
        if (MainView.MAC) {
            keys = java.util.Arrays.copyOf(keys, keys.length + 1);
            keys[keys.length - 1] = new String[]{"⌃⌥U", "Traer Unichat al frente desde cualquier app"};
        }
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(10);
        for (int i = 0; i < keys.length; i++) {
            Label k = new Label(keys[i][0]);
            k.getStyleClass().add("kbd");
            k.setMinWidth(Region.USE_PREF_SIZE);
            grid.add(k, 0, i);
            grid.add(new Label(keys[i][1]), 1, i);
        }
        return page("Atajos de teclado", grid);
    }

    private static Node aboutPage() {
        Label l = Labels.help("Unichat 1.0.0\nBandeja de mensajes unificada para escritorio (Java + JavaFX), "
                + "construida sobre Matrix y los puentes mautrix.\n\nInspirada en Beeper; no está afiliada ni comparte código con Beeper.");
        return page("Acerca de", l);
    }
}
