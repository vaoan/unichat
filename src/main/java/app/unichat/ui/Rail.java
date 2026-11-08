package app.unichat.ui;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import app.unichat.support.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Narrow far-left column: unified inbox and folders, one button per connected network (filters the list),
 * then archive (dimmed) and connect-network, and settings at the bottom.
 */
public final class Rail extends VBox {
    private final ChatService svc;
    private final ChatListPane listPane;
    /** The rail's side padding (css .rail): the networks' scroll spans it. */
    private static final double RAIL_SIDE = 10;
    private final VBox networks = new VBox(6);
    private final ScrollPane scroll = new ScrollPane(networks);
    /** Kept between refreshes: this runs on every change of any chat, several times a second on a busy account. */
    private final java.util.Map<Network, Button> networkButtons = new java.util.EnumMap<>(Network.class);
    private final Button inbox;
    private final Button archive;
    private final Button folders;

    private final java.util.function.Consumer<Network> onAddAccount;

    public Rail(ChatService svc, ChatListPane listPane, Runnable onConnect, java.util.function.Consumer<Network> onAddAccount,
                Runnable onSettings) {
        this.onAddAccount = onAddAccount;
        this.svc = svc;
        this.listPane = listPane;
        getStyleClass().add("rail");
        setAlignment(Pos.TOP_CENTER);
        setSpacing(6);

        inbox = railButton(Icons.icon(Icons.INBOX, 20), I18n.t("rail.inbox"), () ->
                listPane.goTo(listPane.filterProperty().get().switchTo(null, null)));
        // Every network's archived chats; dimmed so it doesn't compete with the networks. Search still finds them.
        archive = railButton(Icons.icon(Icons.ARCHIVE, 18), I18n.t("rail.archived"), () ->
                listPane.goTo(listPane.filterProperty().get().withNetwork(null).withView(ChatFilter.View.ARCHIVE)));
        archive.getStyleClass().add("rail-archive");
        Button add = railButton(Icons.icon(Icons.PLUS, 20), I18n.t("rail.addAccounts"), onConnect);
        add.getStyleClass().add("rail-add");
        folders = railButton(Icons.icon(Icons.FOLDER, 20), I18n.t("rail.folders"), listPane::showFolders);
        Button settings = railButton(Icons.icon(Icons.SETTINGS, 20), I18n.t("rail.settings"), onSettings);

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        networks.setAlignment(Pos.TOP_CENTER);
        Region sep = new Region();
        sep.getStyleClass().add("rail-separator");
        getChildren().addAll(new StackPane(inbox), folders, sep, networkScroll(), archive, add, grow, settings);
        listPane.filterProperty().addListener((o, a, b) -> refresh());
        listPane.foldersOpenProperty().addListener((o, a, b) -> refresh());
        refresh();
    }

    /**
     * The networks scroll when they don't fit (many networks, a short window, large text), so the buttons below them
     * stay in sight; a thin bar in the rail's margin says there are more. As tall as its content otherwise. Focus
     * brings a button into view. It spans the rail's padding so a button's badge isn't cut off.
     */
    private ScrollPane networkScroll() {
        networks.setPadding(new Insets(2, RAIL_SIDE, 2, RAIL_SIDE));
        scroll.getStyleClass().add("rail-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setFocusTraversable(false);
        scroll.setMinHeight(0);
        scroll.prefViewportHeightProperty().bind(networks.heightProperty());
        scroll.setMaxHeight(Region.USE_PREF_SIZE);
        VBox.setMargin(scroll, new Insets(0, -RAIL_SIDE, 0, -RAIL_SIDE));
        return scroll;
    }

    /** Scrolls the networks so {@code b} shows whole (keyboard focus). */
    private void reveal(Button b) {
        double content = networks.getHeight(), view = scroll.getViewportBounds().getHeight();
        if (content <= view) return;
        double top = b.getBoundsInParent().getMinY(), bottom = b.getBoundsInParent().getMaxY();
        double shown = scroll.getVvalue() * (content - view);
        if (top < shown) scroll.setVvalue(top / (content - view));
        else if (bottom > shown + view) scroll.setVvalue((bottom - view) / (content - view));
    }

    private Button railButton(Node graphic, String tip, Runnable action) {
        Button b = new Button();
        b.setGraphic(graphic);
        b.getStyleClass().add("rail-button");
        b.setTooltip(new Tooltip(tip));
        b.setAccessibleText(tip);
        b.setOnAction(e -> action.run());
        return b;
    }

    public void refresh() {
        ChatFilter f = listPane.filterProperty().get();
        List<Chat> chats = svc.chats();
        boolean inFolders = listPane.foldersOpen();
        setActive(inbox, !inFolders && f.network() == null && f.view() != ChatFilter.View.ARCHIVE);
        setActive(archive, !inFolders && f.view() == ChatFilter.View.ARCHIVE);
        setActive(folders, inFolders);
        setUnreadBadge(inbox, chats.stream().filter(ChatFilter::inboxUnread).count());

        List<Node> shownButtons = new java.util.ArrayList<>();
        List<Account> accounts = svc.accounts();
        for (Network n : Network.values()) {
            // The account to speak for the network: a broken one before working ones.
            Account acc = accounts.stream().filter(a -> a.network() == n)
                    .max(java.util.Comparator.comparingInt((Account a) -> switch (a.status()) {
                        case NEEDS_LOGIN -> 3;
                        case ERROR -> 2;
                        case CONNECTING -> 1;
                        default -> 0;
                    })).orElse(null);
            // Only networks you actually use: a working account, or native Matrix chats.
            boolean shown = n == Network.MATRIX
                    ? chats.stream().anyMatch(c -> c.network() == n && !c.bridgeBot() && !c.space())
                    : !Accounts.of(accounts, n).isEmpty()
                    // A session that closed still has its chats: keep the network in sight (marked), not gone.
                    || (acc != null && chats.stream().anyMatch(c -> c.network() == n && !c.bridgeBot() && !c.space()));
            if (!shown) continue;
            String status = acc == null ? "" : switch (acc.status()) {
                case CONNECTED -> "";
                case CONNECTING -> I18n.t("rail.status.connecting");
                case NEEDS_LOGIN -> I18n.t("rail.status.needsLogin");
                case ERROR -> I18n.t("rail.status.error");
                case NOT_AVAILABLE -> I18n.t("rail.status.notAvailable");
            };
            List<Account> netAccounts = Accounts.of(accounts, n);
            String count = netAccounts.size() > 1 ? I18n.n("rail.accounts", netAccounts.size()) : "";
            Button b = networkButtons.computeIfAbsent(n, x -> {
                Button nb = railButton(NetworkIcons.tile(x, 26, "rail-network"), "", () ->
                        listPane.goTo(listPane.filterProperty().get().switchTo(x, null)));
                nb.focusedProperty().addListener((o, was, is) -> { if (is) reveal(nb); });
                return nb;
            });
            b.getTooltip().setText(n.displayName() + count + status);
            if (n.isBridged()) b.setOnContextMenuRequested(e -> networkMenu(n, netAccounts).show(b, e.getScreenX(), e.getScreenY()));
            setActive(b, f.network() == n);
            setMarked(b, "rail-broken", acc != null && acc.status() == AccountStatus.NEEDS_LOGIN);
            setMarked(b, "rail-recovering", acc != null && acc.status() != AccountStatus.CONNECTED && acc.status() != AccountStatus.NEEDS_LOGIN);
            setUnreadBadge(b, chats.stream().filter(c -> c.network() == n && ChatFilter.inboxUnread(c)).count());
            shownButtons.add(b);
        }
        if (!networks.getChildren().equals(shownButtons)) networks.getChildren().setAll(shownButtons);
    }

    /** Right-click on a network: pick one of its accounts or add another. */
    private javafx.scene.control.ContextMenu networkMenu(Network n, List<Account> accounts) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        menu.getItems().add(ChatMenus.item(I18n.t("rail.allAccounts", n.displayName()), () ->
                listPane.goTo(listPane.filterProperty().get().switchTo(n, null))));
        if (accounts.size() > 1) {
            for (Account a : accounts) {
                menu.getItems().add(ChatMenus.item(Accounts.name(a), () ->
                        listPane.goTo(listPane.filterProperty().get().switchTo(n, a.id()))));
            }
        }
        menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        menu.getItems().add(ChatMenus.item(I18n.t("rail.addAnother", n.displayName()), () -> onAddAccount.accept(n)));
        return menu;
    }

    private static void setActive(Button b, boolean on) {
        setMarked(b, "active", on);
    }

    private static void setMarked(Button b, String styleClass, boolean on) {
        if (on == b.getStyleClass().contains(styleClass)) return;
        if (on) b.getStyleClass().add(styleClass);
        else b.getStyleClass().remove(styleClass);
    }

    /**
     * Shows {@code count} on the button. The inbox button lives for the whole session, so the second call must reuse
     * the wrapper the first one made (it is the button's graphic): wrapping again kept the old number inside forever.
     */
    static void setUnreadBadge(Button b, long count) {
        Node g = b.getGraphic();
        StackPane wrap = g instanceof StackPane sp && sp.getStyleClass().contains("rail-graphic") ? sp : null;
        if (wrap == null) {
            wrap = new StackPane(g);
            wrap.getStyleClass().add("rail-graphic");
            b.setGraphic(wrap);
        }
        wrap.getChildren().removeIf(n -> n.getStyleClass().contains("rail-badge"));
        String tip = b.getTooltip() == null ? "" : b.getTooltip().getText();
        b.setAccessibleText(count > 0 ? tip + ", " + I18n.n("rail.unread", count) : tip);
        if (count > 0) {
            Label badge = new Label(Formats.badge(count));
            badge.getStyleClass().add("rail-badge");
            StackPane.setAlignment(badge, Pos.TOP_RIGHT);
            badge.setTranslateX(12);
            badge.setTranslateY(-10);
            wrap.getChildren().add(badge);
        }
    }
}
