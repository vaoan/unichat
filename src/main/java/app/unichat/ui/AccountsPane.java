package app.unichat.ui;

import app.unichat.login.CookieLogin;
import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.server.TelegramKeys;
import app.unichat.service.BridgeAccess;
import app.unichat.service.ChatService;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
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
import java.util.function.Consumer;

/**
 * The "Cuentas" sheet: every connected account one per row, "Agregar cuenta" → pick a network (one per row,
 * any number of accounts per network) → a login wizard that drives the bridge bot behind the scenes and shows
 * its QR code, asks for cookies/e-mail/codes, and confirms when the account is connected.
 */
public final class AccountsPane extends VBox {
    private final ChatService svc;
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
            dispose();
            openChatId.accept(chatId);
        }
        @Override public void showPicker() { AccountsPane.this.showPicker(); }
        @Override public void showList() { AccountsPane.this.showList(); }
        @Override public void startLogin(Network n) { AccountsPane.this.startLogin(n); }
        @Override public void setBack(Runnable action) { onBack = action; }
    };

    public AccountsPane(ChatService svc, Consumer<String> toast, Consumer<String> openChatId) {
        this.svc = svc;
        this.toast = toast;
        this.openChatId = openChatId;
        this.choosers = new LoginChoosers(svc, toast, this::setView, this::getScene, () -> wizard);
        getStyleClass().add("accounts-sheet");
        setSpacing(14);

        title.getStyleClass().add("sheet-title");
        back = Icons.iconButton(Icons.BACK, I18n.t("common.back"), () -> { if (onBack != null) onBack.run(); });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox head = new HBox(6, back, title, grow);
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
            Label empty = Labels.help(I18n.t("accounts.empty"));
            HBox row = new HBox(empty);
            row.getStyleClass().add("settings-row");
            rows.getChildren().add(row);
        }
        for (Account a : accounts) rows.getChildren().add(accountRow(a));
        Button add = new Button(I18n.t("accounts.add"));
        add.setGraphic(Icons.icon(Icons.PLUS, 16));
        add.getStyleClass().add("primary-button");
        add.setOnAction(e -> showPicker());
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Label sub = new Label(I18n.n("accounts.count", accounts.size()));
        sub.getStyleClass().add("sheet-subtitle");
        VBox content = new VBox(12, sub, scroll, add);
        setView(I18n.t("accounts.title"), content, null);
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
        Button more = Icons.iconButton(Icons.MORE, I18n.t("accounts.optionsA11y", Accounts.name(a)), null);
        more.setOnAction(e -> accountMenu(a).show(more, Side.BOTTOM, 0, 4));
        HBox row = new HBox(12, icon, text, status, more);
        if (a.network() == Network.DISCORD && svc instanceof BridgeAccess m) {
            // Discord bridges no server by default: choosing them is part of connecting Discord.
            Button servers = new Button(I18n.t("accounts.servers"));
            servers.getStyleClass().add("secondary-button");
            servers.setMinWidth(Region.USE_PREF_SIZE);
            servers.setAccessibleText(I18n.t("accounts.serversA11y"));
            servers.setOnAction(e -> showDiscordServers(m));
            row.getChildren().add(2, servers);
        }
        if (a.network() == Network.TELEGRAM && a.status() == AccountStatus.CONNECTED) {
            // Telegram lists your chats only once, at login: this goes through the list again.
            Button sync = new Button(I18n.t("accounts.syncChats"));
            sync.getStyleClass().add("secondary-button");
            sync.setMinWidth(Region.USE_PREF_SIZE);
            sync.setAccessibleText(I18n.t("accounts.syncChatsA11y"));
            sync.setOnAction(e -> {
                sync.setDisable(true);
                toast.accept(I18n.t("accounts.syncing"));
                svc.syncChats(Network.TELEGRAM).whenComplete((r, err) -> Platform.runLater(() -> {
                    sync.setDisable(false);
                    toast.accept(err == null ? I18n.t("accounts.synced") : I18n.t("accounts.syncFailed"));
                }));
            });
            row.getChildren().add(2, sync);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        row.setAccessibleText(Accounts.name(a) + ", " + a.network().displayName() + ", " + status.getText());
        return row;
    }

    private void showDiscordServers(BridgeAccess m) {
        dispose();
        setView(I18n.t("accounts.discordServers"), new DiscordServersPage(m, toast), this::showList);
    }

    private ContextMenu accountMenu(Account a) {
        ContextMenu m = new ContextMenu(
                ChatMenus.item(I18n.t("accounts.rename"), () -> showRename(a)),
                ChatMenus.item(I18n.t("accounts.addAnotherOf", a.network().displayName()), () -> startLogin(a.network())),
                new SeparatorMenuItem(),
                ChatMenus.danger(ChatMenus.item(I18n.t("accounts.disconnectMenu"), () -> showDisconnect(a))));
        return m;
    }

    /** Disconnecting logs the account out on the bridge, so it always asks first. */
    private void showDisconnect(Account a) {
        Label icon = networkIcon(a.network(), 48);
        Label q = new Label(I18n.t("accounts.disconnectQ", a.network().displayName(), Accounts.name(a)));
        q.getStyleClass().add("sheet-title");
        q.setWrapText(true);
        Label info = Labels.help(I18n.t(LoginTexts.qrNetwork(a.network()) ? "accounts.disconnectInfoQr" : "accounts.disconnectInfo"));
        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.getStyleClass().add("secondary-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> showList());
        Button confirm = new Button(I18n.t("accounts.disconnect"));
        confirm.getStyleClass().add("danger-button");
        confirm.setOnAction(e -> {
            confirm.setDisable(true);
            svc.disconnectAccount(a).whenComplete((v, err) -> Platform.runLater(() -> {
                toast.accept(err == null ? I18n.t("accounts.disconnected", Accounts.name(a)) : I18n.t("accounts.disconnectFailed", LoginTexts.rootMessage(err)));
                showList();
            }));
        });
        HBox buttons = new HBox(10, cancel, confirm);
        VBox content = new VBox(14, icon, q, info, buttons);
        setView(I18n.t("accounts.disconnectTitle"), content, this::showList);
        Platform.runLater(cancel::requestFocus);
    }

    private void showRename(Account a) {
        TextField field = new TextField(Settings.get().accountAlias(a.id()) == null ? "" : Settings.get().accountAlias(a.id()));
        field.setPromptText(I18n.t("accounts.renamePrompt"));
        field.setAccessibleText(I18n.t("accounts.nameTitle"));
        Label hint = Labels.help(I18n.t("accounts.renameHint", a.network().displayName(), a.label()));
        Button save = new Button(I18n.t("common.save"));
        save.getStyleClass().add("primary-button");
        save.setDefaultButton(true);
        save.setOnAction(e -> {
            Settings.get().setAccountAlias(a.id(), field.getText());
            showList();
        });
        VBox content = new VBox(12, field, hint, save);
        setView(I18n.t("accounts.nameTitle"), content, this::showList);
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
            Label method = new Label(ok ? LoginTexts.method(n) : I18n.t("accounts.bridgeMissing"));
            method.getStyleClass().add("settings-help");
            VBox text = new VBox(2, name, method);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(12, icon, text);
            if (count > 0) {
                Label c = new Label(I18n.n("accounts.connectedCount", count));
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
            b.setAccessibleText(n.displayName() + ", " + method.getText() + (count > 0 ? ", " + I18n.t("accounts.connectedA11y", count) : ""));
            b.setOnAction(e -> startLogin(n));
            rows.getChildren().add(b);
        }
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Label sub = new Label(I18n.t("accounts.pickNetwork"));
        sub.setWrapText(true);
        sub.getStyleClass().add("sheet-subtitle");
        sub.setMinHeight(Region.USE_PREF_SIZE);
        // Unofficial bridges: said once, calmly, before the person picks a network (the same text is in Acerca de).
        Label risk = Labels.help(I18n.t("accounts.riskNotice"));
        risk.getStyleClass().add("risk-notice");
        risk.setMinHeight(Region.USE_PREF_SIZE);
        setView(I18n.t("accounts.add"), new VBox(12, sub, risk, scroll), this::showList);
    }

    // ------------------------------------------------------------------ 3. login wizard

    /** Opens the wizard for {@code n} directly (rail click on an unconnected network). */
    public void startLogin(Network n) {
        dispose();
        // Local server: the bridge itself says how to log in (always up to date). Otherwise: the bot chat.
        if (svc instanceof BridgeAccess m && m.bridgeLogin(n) != null) {
            if (n == Network.TELEGRAM && !TelegramKeys.saved()) {
                // Telegram won't run without the person's own keys: ask here, not as an error afterwards.
                setView(I18n.t("login.connectTitle", "Telegram"), new TelegramKeysForm(I18n.t("accounts.saveContinue"), () -> startBridgeLogin(m, n)), this::showPicker);
                return;
            }
            startBridgeLogin(m, n);
            return;
        }
        startBotLogin(n);
    }

    private void startBridgeLogin(BridgeAccess m, Network n) {
        LoadingView loading = new LoadingView(I18n.t("accounts.preparing", n.displayName()));
        setView(I18n.t("login.connectTitle", n.displayName()), loading, this::showPicker);
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
                                toast.accept(I18n.t("login.wizard.connected", n.displayName()));
                            },
                            () -> {
                                dispose();
                                startBotLogin(n);
                            });
                    setView(I18n.t("login.connectTitle", n.displayName()), bridgeLogin, () -> {
                        dispose();
                        showPicker();
                    });
                });
            } catch (Exception e) {
                Platform.runLater(() -> loading.fail(I18n.t("accounts.prepareFailed", n.displayName(), LoginTexts.rootMessage(e))));
            }
        });
    }

    private void startBotLogin(Network n) {
        LoadingView loading = new LoadingView(I18n.t("accounts.preparingLogin", n.displayName()));
        setView(I18n.t("login.connectTitle", n.displayName()), loading, this::showPicker);
        Instant since = Instant.now().minusSeconds(2);
        svc.addAccount(n).whenComplete((botChatId, err) -> Platform.runLater(() -> {
            if (err != null) {
                loading.fail(I18n.t("accounts.startFailed", LoginTexts.rootMessage(err)));
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
                setView(I18n.t("login.connectTitle", n.displayName()), wizard.root, cancel);
            }
        }));
    }

    static Label networkIcon(Network n, double size) {
        return NetworkIcons.tile(n, size, "network-tile-icon");
    }

    private static Label statusPill(AccountStatus s) {
        Label l = new Label(switch (s) {
            case CONNECTED -> I18n.t("accounts.status.connected");
            case CONNECTING -> I18n.t("accounts.status.connecting");
            case NEEDS_LOGIN -> I18n.t("accounts.status.needsLogin");
            case ERROR -> I18n.t("accounts.status.error");
            case NOT_AVAILABLE -> I18n.t("accounts.status.unavailable");
        });
        l.getStyleClass().addAll("status-pill", s == AccountStatus.CONNECTED ? "ok" : "warn");
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }
}
