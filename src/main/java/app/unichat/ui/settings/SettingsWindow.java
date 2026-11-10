package app.unichat.ui.settings;

import app.unichat.ui.Browser;
import app.unichat.ui.Labels;
import app.unichat.ui.MainView;
import app.unichat.ui.Shortcuts;
import app.unichat.ui.Theme;
import app.unichat.ui.chats.Avatar;
import app.unichat.ui.desktop.DockIcon;
import app.unichat.ui.desktop.NativeBar;
import app.unichat.ui.desktop.Notifier;
import app.unichat.ui.login.AccountsPane;
import app.unichat.gif.GifProviders;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
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

import java.util.List;
import java.util.function.Consumer;

import static app.unichat.ui.settings.SettingsForm.group;
import static app.unichat.ui.settings.SettingsForm.page;
import static app.unichat.ui.settings.SettingsForm.row;
import static app.unichat.ui.settings.SettingsForm.toggle;

/** Preferences window (⌘, / Ctrl+,). Every control writes through to {@link Settings} immediately. */
public final class SettingsWindow {
    private static Stage open;

    private SettingsWindow() {}

    private static AccountsPane accounts;

    /** The open settings window, if any (used by the snapshot diagnostics). */
    public static Stage current() { return open; }

    /** The "Cuentas" page while it is showing, else null. */
    public static AccountsPane accountsPage() { return accounts; }

    /** The pages, in order: an id, the old Spanish title (still accepted by {@link #select}) and the key of its title. */
    private record Page(String id, String spanish, String key) {
        String title() { return I18n.t(key); }
    }

    private static final List<Page> PAGES = List.of(
            new Page("accounts", "Cuentas", "settings.nav.accounts"),
            new Page("profile", "Perfil", "settings.nav.profile"),
            new Page("appearance", "Apariencia", "settings.nav.appearance"),
            new Page("accessibility", "Accesibilidad", "settings.nav.accessibility"),
            new Page("notifications", "Notificaciones", "settings.nav.notifications"),
            new Page("privacy", "Privacidad", "settings.nav.privacy"),
            new Page("networks", "Redes", "settings.nav.networks"),
            new Page("cloud", "Nube", "settings.nav.cloud"),
            new Page("speech", "Voz a texto", "settings.nav.speech"),
            new Page("ai", "Escribir con IA", "settings.nav.ai"),
            new Page("gifs", "GIFs", "settings.nav.gifs"),
            new Page("shortcuts", "Atajos de teclado", "settings.nav.shortcuts"),
            new Page("about", "Acerca de", "settings.nav.about"));

    /** The id of the page showing ("appearance"), or null when the window is closed. */
    @SuppressWarnings("unchecked")
    public static String currentPage() {
        if (open == null) return null;
        Node nav = open.getScene().getRoot().lookup(".settings-nav");
        int i = nav instanceof ListView<?> lv ? ((ListView<String>) lv).getSelectionModel().getSelectedIndex() : -1;
        return i < 0 ? null : PAGES.get(i).id();
    }

    /** Selects the page named {@code page}: its id ("accessibility"), its Spanish title or the start of its shown title. */
    @SuppressWarnings("unchecked")
    public static void select(String page) {
        if (open == null) return;
        Node nav = open.getScene().getRoot().lookup(".settings-nav");
        if (nav instanceof ListView<?> lv) {
            ListView<String> l = (ListView<String>) lv;
            for (int i = 0; i < PAGES.size(); i++) {
                Page p = PAGES.get(i);
                if (p.id().equalsIgnoreCase(page) || p.spanish().equalsIgnoreCase(page) || p.title().startsWith(page)) {
                    l.getSelectionModel().select(i);
                    return;
                }
            }
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
        stage.setTitle(I18n.t("settings.title"));

        BorderPane content = new BorderPane();
        content.getStyleClass().add("settings-content");
        ListView<String> nav = new ListView<>();
        PAGES.forEach(p -> nav.getItems().add(p.title()));
        nav.getStyleClass().add("settings-nav");
        nav.getSelectionModel().selectedIndexProperty().addListener((o, a, idx) -> {
            if (accounts != null) accounts.dispose();
            accounts = null;
            String s = idx.intValue() < 0 ? null : PAGES.get(idx.intValue()).id();
            if ("accounts".equals(s) || s == null) {
                accounts = new AccountsPane(svc, toast, id -> {
                    stage.close();
                    openChatId.accept(id);
                });
                accounts.getStyleClass().add("settings-page");
                content.setCenter(accounts);
                return;
            }
            Node page = switch (s) {
                case "appearance" -> AppearancePages.appearance(onAppearanceChanged);
                case "accessibility" -> AppearancePages.accessibility(onAppearanceChanged);
                case "notifications" -> notificationsPage();
                case "privacy" -> PrivacyPage.build();
                case "networks" -> NetworksPage.build(svc, toast);
                case "cloud" -> CloudPage.build(toast);
                case "speech" -> SpeechPage.build();
                case "ai" -> AiReplyPage.build();
                case "gifs" -> gifsPage();
                case "shortcuts" -> shortcutsPage();
                case "about" -> AboutPage.build();
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
                ? I18n.t("settings.profile.demo")
                : I18n.t("settings.profile.server", Settings.get().homeserver()));
        HBox who = new HBox(16, a, new VBox(4, name, id, backend));
        who.setAlignment(Pos.CENTER_LEFT);
        Button out = new Button(I18n.t("settings.profile.logout"));
        out.getStyleClass().add("danger-button");
        out.setOnAction(e -> logout.run());
        return page(I18n.t("settings.nav.profile"), who, group(I18n.t("settings.profile.session"), row(I18n.t("settings.profile.logoutRow"),
                I18n.t("settings.profile.logoutHelp"), out)));
    }

    private static Node notificationsPage() {
        Settings s = Settings.get();
        Node[] rows = {
                row(I18n.t("settings.nav.notifications"), I18n.t("settings.notifications.help"),
                        toggle(s.notificationsEnabled(), v -> {
                            s.setNotificationsEnabled(v);
                            Notifier.notificationsChanged();
                        })),
                row(I18n.t("settings.notifications.preview"), I18n.t("settings.notifications.previewHelp"), toggle(s.notificationPreview(), s::setNotificationPreview)),
                row(I18n.t("settings.notifications.sound"), null, toggle(s.notificationSound(), s::setNotificationSound)),
                row(I18n.t("settings.notifications.archived"), I18n.t("settings.notifications.archivedHelp"),
                        toggle(s.notifyArchived(), s::setNotifyArchived)),
                row(I18n.t("settings.notifications.tray"), I18n.t("settings.notifications.trayHelp"),
                        toggle(s.minimizeToTray(), s::setMinimizeToTray)),
                dockRow(s),
                shortcutRow(s),
        };
        return page(I18n.t("settings.nav.notifications"), group(I18n.t("settings.notifications.desktop"), java.util.Arrays.stream(rows).filter(java.util.Objects::nonNull).toArray(Node[]::new)),
                group(I18n.t("settings.notifications.downloads"),
                        row(I18n.t("settings.notifications.downloadsRing"), I18n.t("settings.notifications.downloadsRingHelp"),
                                toggle(s.getBool(Notifier.TRAY_PROGRESS, true), v -> s.setBool(Notifier.TRAY_PROGRESS, v))),
                        row(I18n.t("settings.notifications.downloadsDone"), I18n.t("settings.notifications.downloadsDoneHelp"),
                                toggle(s.getBool(Notifier.NOTIFY_DONE, true), v -> s.setBool(Notifier.NOTIFY_DONE, v)))));
    }

    /** Without a Dock icon a Mac app isn't in ⌘Tab; null elsewhere. */
    private static Node shortcutRow(Settings s) {
        if (!MainView.MAC) return null;
        return row(I18n.t("settings.notifications.globalShortcut"), I18n.t("settings.notifications.globalShortcutHelp"),
                toggle(s.globalShortcut(), v -> {
                    s.setGlobalShortcut(v);
                    NativeBar.applyGlobalShortcut(v);
                }));
    }

    /** Only a Mac has one; null elsewhere. */
    private static Node dockRow(Settings s) {
        if (!MainView.MAC) return null;
        return row(I18n.t("settings.notifications.dock"), I18n.t("settings.notifications.dockHelp"),
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
        Label intro = Labels.help(I18n.t("settings.gifs.intro"));
        groups.add(intro);
        for (GifProviders.Known k : GifProviders.known()) {
            String enabledKey = "gif." + k.id() + ".enabled", keyKey = "gif." + k.id() + ".key";
            PasswordField key = new PasswordField();
            key.setText(s.getString(keyKey, ""));
            key.setPromptText(I18n.t("settings.gifs.keyPrompt"));
            key.setAccessibleText(I18n.t("settings.gifs.keyOf", k.name()));
            key.textProperty().addListener((o, a, b) -> s.set(keyKey, b.strip()));
            javafx.scene.control.Hyperlink get = new javafx.scene.control.Hyperlink(I18n.t("settings.gifs.getKey"));
            get.setAccessibleText(I18n.t("settings.gifs.getKeyOf", k.name()));
            get.setOnAction(e -> Browser.open(k.keyUrl())); // the address is in the tooltip too
            get.setTooltip(new javafx.scene.control.Tooltip(k.keyUrl()));
            groups.add(group(k.name(),
                    row(I18n.t("settings.gifs.use", k.name()), null, toggle("true".equals(s.getString(enabledKey, "false")), v -> s.set(enabledKey, v.toString()))),
                    row(I18n.t("settings.gifs.key"), null, key),
                    row(I18n.t("settings.gifs.noKey"), k.keyUrl(), get)));
        }
        return page(I18n.t("settings.nav.gifs"), groups.toArray(new Node[0]));
    }

    private static Node shortcutsPage() {
        String[][] keys = {
                {Shortcuts.key("K"), I18n.t("settings.shortcuts.searchChats")},
                {Shortcuts.key("L"), I18n.t("settings.shortcuts.composer")},
                {"F6 / Shift+F6", I18n.t("settings.shortcuts.jumpZones")},
                {I18n.t("settings.shortcuts.walkKeys"), I18n.t("settings.shortcuts.walk")},
                {I18n.t("settings.shortcuts.actionKeys"), I18n.t("settings.shortcuts.actions")},
                {I18n.t("settings.shortcuts.reactKeys"), I18n.t("settings.shortcuts.react")},
                {Shortcuts.zoomKeys(), I18n.t("settings.shortcuts.zoom")},
                {"Alt+↑ / Alt+↓", I18n.t("settings.shortcuts.prevNext")},
                {"Alt+Shift+↑ / ↓", I18n.t("settings.shortcuts.prevNextUnread")},
                {Shortcuts.key("E"), I18n.t("settings.shortcuts.archive")},
                {Shortcuts.key("Shift+U"), I18n.t("settings.shortcuts.markRead")},
                {Shortcuts.key("1") + " … " + Shortcuts.key("4"), I18n.t("settings.shortcuts.filters")},
                {Shortcuts.key("Shift+I"), I18n.t("settings.shortcuts.info")},
                {Shortcuts.key(","), I18n.t("settings.shortcuts.settings")},
                {Shortcuts.key("I"), I18n.t("settings.shortcuts.aiDraft")},
                {Shortcuts.key("O"), I18n.t("settings.shortcuts.aiFix")},
                {I18n.t("settings.shortcuts.selectKeys"), I18n.t("settings.shortcuts.select")},
                {I18n.t("settings.shortcuts.editLastKeys"), I18n.t("settings.shortcuts.editLast")},
                {"Esc", I18n.t("settings.shortcuts.cancel")},
                {"Esc", I18n.t("settings.shortcuts.clearSearch")},
                {I18n.t("settings.shortcuts.doubleClickKeys"), I18n.t("settings.shortcuts.doubleClick")},
        };
        if (MainView.MAC) {
            keys = java.util.Arrays.copyOf(keys, keys.length + 1);
            keys[keys.length - 1] = new String[]{"⌃⌥U", I18n.t("settings.shortcuts.bringFront")};
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
        return page(I18n.t("settings.nav.shortcuts"), grid);
    }
}
