package app.unichat.ui;

import app.unichat.login.CookieLogin;
import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.server.TelegramKeys;
import app.unichat.service.BridgeAccess;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The "Cuentas" sheet: every connected account one per row, "Agregar cuenta" → pick a network (one per row,
 * any number of accounts per network) → a login wizard that drives the bridge bot behind the scenes and shows
 * its QR code, asks for cookies/e-mail/codes, and confirms when the account is connected.
 */
public final class AccountsPane extends VBox {
    private final ChatService svc;
    private final Runnable close;
    private final Consumer<String> toast;
    private final Consumer<String> openChatId;
    private final StackPane body = new StackPane();
    private final Label title = new Label();
    private final Button back;
    private Runnable onBack;
    private LoginWizard wizard;
    private final LoginChoosers choosers;
    private final LoginWizard.Navigation navigation = new LoginWizard.Navigation() {
        @Override public void openBotChat(String chatId) {
            closeSheet();
            openChatId.accept(chatId);
        }
        @Override public void showPicker() { AccountsPane.this.showPicker(); }
        @Override public void showList() { AccountsPane.this.showList(); }
        @Override public void startLogin(Network n) { AccountsPane.this.startLogin(n); }
        @Override public void setBack(Runnable action) { onBack = action; }
    };

    /** {@code close} null = embedded in the settings window (no close button, no card frame). */
    public AccountsPane(ChatService svc, Runnable close, Consumer<String> toast, Consumer<String> openChatId) {
        this.svc = svc;
        this.close = close;
        this.toast = toast;
        this.openChatId = openChatId;
        this.choosers = new LoginChoosers(svc, toast, this::setView, this::getScene, () -> wizard);
        getStyleClass().add("accounts-sheet");
        if (close != null) {
            getStyleClass().add("card");
            setMaxSize(640, 640);
        }
        setSpacing(14);

        title.getStyleClass().add("sheet-title");
        back = Icons.iconButton(Icons.BACK, "Volver", () -> { if (onBack != null) onBack.run(); });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox head = new HBox(6, back, title, grow);
        if (close != null) head.getChildren().add(Icons.iconButton(Icons.CLOSE, "Cerrar", this::closeSheet));
        head.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(head, body);
        showList();
    }

    /** Stops listening to the service; called when the sheet closes. */
    public void dispose() {
        if (wizard != null) wizard.dispose();
        wizard = null;
        if (bridgeLogin != null) bridgeLogin.dispose();
        bridgeLogin = null;
    }

    /** The dynamic login on screen (local server), if any. */
    private BridgeLoginPane bridgeLogin;

    private void closeSheet() {
        dispose();
        if (close != null) close.run();
    }

    /** Re-reads accounts if the list is showing (accountsChanged). The wizard updates itself. */
    public void refresh() {
        if (wizard == null && onBack == null) showList();
    }

    private void setView(String heading, Node content, Runnable backAction) {
        title.setText(heading);
        onBack = backAction;
        Nodes.show(back, backAction != null);
        body.getChildren().setAll(content);
    }

    // ------------------------------------------------------------------ 1. accounts list

    public void showList() {
        dispose();
        VBox rows = new VBox(0);
        rows.getStyleClass().add("settings-group");
        List<Account> accounts = svc.accounts().stream().filter(a -> a.status() != AccountStatus.NEEDS_LOGIN)
                .sorted(java.util.Comparator.comparing((Account a) -> a.network().ordinal()).thenComparing(Accounts::name))
                .toList();
        if (accounts.isEmpty()) {
            Label empty = Labels.help("Aún no tienes cuentas conectadas. Agrega la primera: puedes conectar varias de cada red.");
            HBox row = new HBox(empty);
            row.getStyleClass().add("settings-row");
            rows.getChildren().add(row);
        }
        for (Account a : accounts) rows.getChildren().add(accountRow(a));
        Button add = new Button("Agregar cuenta");
        add.setGraphic(Icons.icon(Icons.PLUS, 16));
        add.getStyleClass().add("primary-button");
        add.setOnAction(e -> showPicker());
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Label sub = new Label(accounts.size() == 1 ? "1 cuenta conectada" : accounts.size() + " cuentas conectadas");
        sub.getStyleClass().add("sheet-subtitle");
        VBox content = new VBox(12, sub, scroll, add);
        setView("Cuentas", content, null);
        Platform.runLater(add::requestFocus);
    }

    private Node accountRow(Account a) {
        Label icon = networkIcon(a.network(), 34);
        Label name = new Label(Accounts.name(a));
        name.getStyleClass().add("settings-label");
        Label detail = new Label(a.network().displayName() + (Accounts.name(a).equals(a.label()) ? "" : " · " + a.label()));
        detail.getStyleClass().add("settings-help");
        VBox text = new VBox(2, name, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label status = statusPill(a.status());
        Button more = Icons.iconButton(Icons.MORE, "Opciones de " + Accounts.name(a), null);
        more.setOnAction(e -> accountMenu(a).show(more, Side.BOTTOM, 0, 4));
        HBox row = new HBox(12, icon, text, status, more);
        if (a.network() == Network.DISCORD && svc instanceof BridgeAccess m) {
            // Discord bridges no server by default: choosing them is part of connecting Discord.
            Button servers = new Button("Servidores");
            servers.getStyleClass().add("secondary-button");
            servers.setMinWidth(Region.USE_PREF_SIZE);
            servers.setAccessibleText("Elegir qué servidores de Discord ver en Unichat");
            servers.setOnAction(e -> showDiscordGuilds(m));
            row.getChildren().add(2, servers);
        }
        if (a.network() == Network.TELEGRAM && a.status() == AccountStatus.CONNECTED) {
            // Telegram lists your chats only once, at login: this goes through the list again.
            Button sync = new Button("Sincronizar chats");
            sync.getStyleClass().add("secondary-button");
            sync.setMinWidth(Region.USE_PREF_SIZE);
            sync.setAccessibleText("Volver a traer tus chats de Telegram");
            sync.setOnAction(e -> {
                sync.setDisable(true);
                toast.accept("Sincronizando tus chats de Telegram… puede tardar unos minutos");
                svc.syncChats(Network.TELEGRAM).whenComplete((r, err) -> Platform.runLater(() -> {
                    sync.setDisable(false);
                    toast.accept(err == null ? "Chats de Telegram sincronizados" : "No se pudieron sincronizar los chats de Telegram");
                }));
            });
            row.getChildren().add(2, sync);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        row.setAccessibleText(Accounts.name(a) + ", " + a.network().displayName() + ", " + status.getText());
        return row;
    }

    /**
     * Which Discord servers come into Unichat: search, one switch per server, bridged ones first. A server's
     * channels appear as people write in them (no room is created for every channel at once).
     */
    private void showDiscordGuilds(BridgeAccess m) {
        dispose();
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(36, 36);
        Label wait = new Label("Buscando tus servidores de Discord…");
        wait.getStyleClass().add("settings-help");
        VBox loading = new VBox(14, spin, wait);
        loading.setAlignment(Pos.CENTER);
        setView("Servidores de Discord", loading, this::showList);
        m.discordGuilds().whenComplete((guilds, err) -> Platform.runLater(() -> {
            if (err != null) {
                spin.setVisible(false);
                wait.setText("No se pudo leer la lista: " + LoginTexts.rootMessage(err));
                return;
            }
            Label help = Labels.help("Elige los servidores que quieres ver. Sus canales aparecen en Unichat a medida que alguien escribe en ellos. Los mensajes directos y grupos privados llegan siempre.");
            TextField search = new TextField();
            search.setPromptText("Buscar servidor");
            search.setAccessibleText("Buscar servidor de Discord");
            Label count = new Label();
            count.getStyleClass().add("settings-help");
            VBox rows = new VBox(0);
            rows.getStyleClass().add("settings-group");
            java.util.Set<String> on = new java.util.HashSet<>();
            for (var g : guilds) if (g.bridged()) on.add(g.id());
            List<BridgeAccess.DiscordGuild> sorted = guilds.stream()
                    .sorted(java.util.Comparator.comparing((BridgeAccess.DiscordGuild g) -> !g.bridged())
                            .thenComparing(g -> g.name().toLowerCase(java.util.Locale.ROOT)))
                    .toList();
            Runnable render = () -> {
                String q = ChatFilter.normalize(search.getText());
                rows.getChildren().clear();
                for (var g : sorted) {
                    if (!q.isEmpty() && !ChatFilter.normalize(g.name()).contains(q)) continue;
                    Label name = new Label(g.name());
                    name.getStyleClass().add("settings-label");
                    name.setWrapText(true);
                    HBox.setHgrow(name, Priority.ALWAYS);
                    name.setMaxWidth(Double.MAX_VALUE);
                    javafx.scene.control.CheckBox sw = new javafx.scene.control.CheckBox();
                    sw.getStyleClass().add("switch");
                    sw.setSelected(on.contains(g.id()));
                    sw.setAccessibleText("Ver " + g.name() + " en Unichat");
                    sw.setOnAction(e -> {
                        boolean want = sw.isSelected();
                        sw.setDisable(true);
                        m.setDiscordGuildBridged(g.id(), want).whenComplete((v, ex) -> Platform.runLater(() -> {
                            sw.setDisable(false);
                            if (ex != null) {
                                sw.setSelected(!want);
                                toast.accept("No se pudo cambiar " + g.name() + ": " + LoginTexts.rootMessage(ex));
                            } else {
                                if (want) on.add(g.id()); else on.remove(g.id());
                                count.setText(on.size() + " de " + sorted.size() + " servidores en Unichat");
                                toast.accept(want ? g.name() + ": sus canales irán apareciendo" : g.name() + " ya no se traerá");
                            }
                        }));
                    });
                    HBox row = new HBox(12, name, sw);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.getStyleClass().add("settings-row");
                    rows.getChildren().add(row);
                }
                if (rows.getChildren().isEmpty()) {
                    Label none = new Label(sorted.isEmpty() ? "Tu cuenta no está en ningún servidor." : "Ningún servidor coincide.");
                    none.getStyleClass().add("settings-help");
                    HBox row = new HBox(none);
                    row.getStyleClass().add("settings-row");
                    rows.getChildren().add(row);
                }
            };
            count.setText(on.size() + " de " + sorted.size() + " servidores en Unichat");
            search.textProperty().addListener((o, a, b) -> render.run());
            render.run();
            ScrollPane scroll = new ScrollPane(rows);
            scroll.setFitToWidth(true);
            scroll.getStyleClass().add("login-scroll");
            VBox.setVgrow(scroll, Priority.ALWAYS);
            VBox content = new VBox(10, help, search, count, scroll);
            setView("Servidores de Discord", content, this::showList);
            Platform.runLater(search::requestFocus);
        }));
    }

    private ContextMenu accountMenu(Account a) {
        ContextMenu m = new ContextMenu(
                ChatMenus.item("Cambiar nombre…", () -> showRename(a)),
                ChatMenus.item("Agregar otra cuenta de " + a.network().displayName(), () -> startLogin(a.network())),
                new SeparatorMenuItem(),
                ChatMenus.danger(ChatMenus.item("Desconectar…", () -> showDisconnect(a))));
        return m;
    }

    /** Disconnecting logs the account out on the bridge, so it always asks first. */
    private void showDisconnect(Account a) {
        Label icon = networkIcon(a.network(), 48);
        Label q = new Label("¿Desconectar " + a.network().displayName() + " · " + Accounts.name(a) + "?");
        q.getStyleClass().add("sheet-title");
        q.setWrapText(true);
        Label info = Labels.help("Se cierra la sesión en el puente y dejarás de recibir mensajes de esta cuenta. "
                + "Tus chats ya sincronizados se quedan. Para volver a conectarla tendrás que iniciar sesión de nuevo"
                + (LoginTexts.qrNetwork(a.network()) ? " (escanear el QR)." : "."));
        Button cancel = new Button("Cancelar");
        cancel.getStyleClass().add("secondary-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> showList());
        Button confirm = new Button("Desconectar");
        confirm.getStyleClass().add("danger-button");
        confirm.setOnAction(e -> {
            confirm.setDisable(true);
            svc.disconnectAccount(a).whenComplete((v, err) -> Platform.runLater(() -> {
                toast.accept(err == null ? Accounts.name(a) + " desconectada" : "No se pudo desconectar: " + LoginTexts.rootMessage(err));
                showList();
            }));
        });
        HBox buttons = new HBox(10, cancel, confirm);
        VBox content = new VBox(14, icon, q, info, buttons);
        setView("Desconectar cuenta", content, this::showList);
        Platform.runLater(cancel::requestFocus);
    }

    private void showRename(Account a) {
        TextField field = new TextField(Settings.get().accountAlias(a.id()) == null ? "" : Settings.get().accountAlias(a.id()));
        field.setPromptText("Ej. Personal, Trabajo");
        field.setAccessibleText("Nombre de la cuenta");
        Label hint = Labels.help("Solo se usa en Unichat para distinguir tus cuentas de " + a.network().displayName()
                + ". Déjalo vacío para usar " + a.label() + ".");
        Button save = new Button("Guardar");
        save.getStyleClass().add("primary-button");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            Settings.get().setAccountAlias(a.id(), field.getText());
            showList();
        });
        VBox content = new VBox(12, field, hint, save);
        setView("Nombre de la cuenta", content, this::showList);
        Platform.runLater(field::requestFocus);
    }

    // ------------------------------------------------------------------ 2. network picker

    public void showPicker() {
        dispose();
        VBox rows = new VBox(0);
        rows.getStyleClass().add("settings-group");
        List<Network> available = svc.availableNetworks();
        for (Network n : Network.values()) {
            if (!n.isBridged()) continue;
            boolean ok = available.contains(n);
            int count = Accounts.of(svc, n).size();
            Label icon = networkIcon(n, 34);
            Label name = new Label(n.displayName());
            name.getStyleClass().add("settings-label");
            Label method = new Label(ok ? LoginTexts.method(n) : "Puente no instalado en tu servidor");
            method.getStyleClass().add("settings-help");
            VBox text = new VBox(2, name, method);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(12, icon, text);
            if (count > 0) {
                Label c = new Label(count == 1 ? "1 conectada" : count + " conectadas");
                c.getStyleClass().addAll("status-pill", "ok");
                c.setMinWidth(Region.USE_PREF_SIZE);
                row.getChildren().add(c);
            }
            row.getChildren().add(Icons.icon(Icons.BACK, 14));
            row.getChildren().get(row.getChildren().size() - 1).setRotate(180);
            row.setAlignment(Pos.CENTER_LEFT);
            Button b = new Button();
            b.setGraphic(row);
            b.setMaxWidth(Double.MAX_VALUE);
            row.prefWidthProperty().bind(b.widthProperty().subtract(24));
            b.getStyleClass().add("picker-row");
            b.setDisable(!ok);
            b.setAccessibleText(n.displayName() + ", " + method.getText() + (count > 0 ? ", " + count + " conectadas" : ""));
            b.setOnAction(e -> startLogin(n));
            rows.getChildren().add(b);
        }
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Label sub = new Label("Elige la red. Puedes agregar tantas cuentas de cada una como quieras.");
        sub.setWrapText(true);
        sub.getStyleClass().add("sheet-subtitle");
        setView("Agregar cuenta", new VBox(12, sub, scroll), this::showList);
    }

    // ------------------------------------------------------------------ 3. login wizard

    /** Opens the wizard for {@code n} directly (rail click on an unconnected network). */
    public void startLogin(Network n) {
        dispose();
        // Local server: the bridge itself says how to log in (always up to date). Otherwise: the bot chat.
        if (svc instanceof BridgeAccess m && m.bridgeLogin(n) != null) {
            if (n == Network.TELEGRAM && !TelegramKeys.saved()) {
                // Telegram won't run without the person's own keys: ask here, not as an error afterwards.
                setView("Conectar Telegram", new TelegramKeysForm("Guardar y continuar", () -> startBridgeLogin(m, n)), this::showPicker);
                return;
            }
            startBridgeLogin(m, n);
            return;
        }
        startBotLogin(n);
    }

    private void startBridgeLogin(BridgeAccess m, Network n) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(36, 36);
        Label wait = Labels.help("Preparando " + n.displayName() + "… La primera vez se descarga su conector; tarda unos segundos.");
        VBox loading = new VBox(14, spin, wait);
        loading.setAlignment(Pos.CENTER);
        setView("Conectar " + n.displayName(), loading, this::showPicker);
        Thread.ofVirtual().name("prepare-bridge").start(() -> {
            try {
                m.prepareBridge(n);
                Platform.runLater(() -> {
                    if (getScene() == null) return;
                    bridgeLogin = new BridgeLoginPane(n, m.bridgeLogin(n), () -> getScene() == null ? null : getScene().getWindow(),
                            () -> {
                                m.loginFinished(n);
                                dispose();
                                showList();
                                toast.accept(n.displayName() + " conectado");
                            },
                            () -> {
                                dispose();
                                startBotLogin(n);
                            });
                    setView("Conectar " + n.displayName(), bridgeLogin, () -> {
                        dispose();
                        showPicker();
                    });
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    spin.setVisible(false);
                    wait.setText("No se pudo preparar " + n.displayName() + ": " + LoginTexts.rootMessage(e));
                });
            }
        });
    }

    private void startBotLogin(Network n) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(36, 36);
        Label wait = new Label("Preparando el inicio de sesión de " + n.displayName() + "…");
        wait.getStyleClass().add("settings-help");
        VBox loading = new VBox(14, spin, wait);
        loading.setAlignment(Pos.CENTER);
        setView("Conectar " + n.displayName(), loading, this::showPicker);
        Instant since = Instant.now().minusSeconds(2);
        svc.addAccount(n).whenComplete((botChatId, err) -> Platform.runLater(() -> {
            if (err != null) {
                wait.setText("No se pudo iniciar: " + LoginTexts.rootMessage(err));
                spin.setVisible(false);
                return;
            }
            wizard = new LoginWizard(svc, toast, navigation, n, botChatId, since);
            Runnable cancel = () -> {
                wizard.cancel();
                showPicker();
            };
            if (CookieLogin.isCookieNetwork(n)) {
                choosers.showCookieSources(n, botChatId, cancel);
            } else if (n == Network.DISCORD) {
                choosers.showDiscordChoice(n, botChatId, cancel);
            } else {
                setView("Conectar " + n.displayName(), wizard.root, cancel);
            }
        }));
    }

    static Label networkIcon(Network n, double size) {
        return NetworkIcons.tile(n, size, "network-tile-icon");
    }

    private static Label statusPill(AccountStatus s) {
        Label l = new Label(switch (s) {
            case CONNECTED -> "Conectada";
            case CONNECTING -> "Conectando…";
            case NEEDS_LOGIN -> "Inicia sesión";
            case ERROR -> "Error";
            case NOT_AVAILABLE -> "No disponible";
        });
        l.getStyleClass().addAll("status-pill", s == AccountStatus.CONNECTED ? "ok" : "warn");
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }
}
