package app.unichat.ui;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Chat;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Narrow far-left column: unified inbox, one button per connected network (filters the list),
 * then archive, connect-network and settings at the bottom.
 */
public final class Rail extends VBox {
    private final ChatService svc;
    private final ChatListPane listPane;
    private final VBox networks = new VBox(6);
    private final Button inbox;
    private final Button archive;

    private final java.util.function.Consumer<Network> onAddAccount;

    public Rail(ChatService svc, ChatListPane listPane, Runnable onConnect, java.util.function.Consumer<Network> onAddAccount,
                Runnable onSettings) {
        this.onAddAccount = onAddAccount;
        this.svc = svc;
        this.listPane = listPane;
        getStyleClass().add("rail");
        setAlignment(Pos.TOP_CENTER);
        setSpacing(6);

        inbox = railButton(Icons.icon(Icons.INBOX, 20), "Bandeja unificada", () ->
                listPane.filterProperty().set(listPane.filterProperty().get().withNetwork(null).withView(ChatFilter.View.INBOX)));
        archive = railButton(Icons.icon(Icons.ARCHIVE, 20), "Archivados", () ->
                listPane.filterProperty().set(listPane.filterProperty().get().withView(ChatFilter.View.ARCHIVE)));
        Button add = railButton(Icons.icon(Icons.PLUS, 20), "Agregar o administrar cuentas (Ajustes)", onConnect);
        add.getStyleClass().add("rail-add");
        Button settings = railButton(Icons.icon(Icons.SETTINGS, 20), "Ajustes", onSettings);

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        networks.setAlignment(Pos.TOP_CENTER);
        Region sep = new Region();
        sep.getStyleClass().add("rail-separator");
        getChildren().addAll(new StackPane(inbox), sep, networks, add, grow, archive, settings);
        listPane.filterProperty().addListener((o, a, b) -> refresh());
        refresh();
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
        setActive(inbox, f.network() == null && f.view() != ChatFilter.View.ARCHIVE);
        setActive(archive, f.view() == ChatFilter.View.ARCHIVE);
        setUnreadBadge(inbox, chats.stream().filter(c -> !c.archived() && !c.bridgeBot() && !c.muted() && c.isUnread()).count());

        networks.getChildren().clear();
        List<Account> accounts = svc.accounts();
        for (Network n : Network.values()) {
            Account acc = accounts.stream().filter(a -> a.network() == n).findFirst().orElse(null);
            // Only networks you actually use: a working account, or native Matrix chats.
            boolean shown = n == Network.MATRIX
                    ? chats.stream().anyMatch(c -> c.network() == n && !c.bridgeBot())
                    : !Accounts.of(svc, n).isEmpty();
            if (!shown) continue;
            Label letter = new Label(n.badgeLetter());
            letter.getStyleClass().add("rail-network");
            letter.setStyle("-fx-background-color: " + n.color() + ";");
            String status = acc == null ? "" : switch (acc.status()) {
                case CONNECTED -> "";
                case CONNECTING -> " (conectando…)";
                case NEEDS_LOGIN -> " (inicia sesión)";
                case ERROR -> " (error)";
                case NOT_AVAILABLE -> " (no disponible)";
            };
            List<Account> netAccounts = Accounts.of(svc, n);
            String count = netAccounts.size() > 1 ? " (" + netAccounts.size() + " cuentas)" : "";
            Button b = railButton(letter, n.displayName() + count + status, () ->
                    listPane.filterProperty().set(listPane.filterProperty().get().withNetwork(n).withView(ChatFilter.View.INBOX)));
            if (n.isBridged()) b.setOnContextMenuRequested(e -> networkMenu(n, netAccounts).show(b, e.getScreenX(), e.getScreenY()));
            setActive(b, f.network() == n);
            setUnreadBadge(b, chats.stream().filter(c -> c.network() == n && !c.archived() && !c.bridgeBot() && !c.muted() && c.isUnread()).count());
            networks.getChildren().add(b);
        }
    }

    /** Right-click on a network: pick one of its accounts or add another. */
    private javafx.scene.control.ContextMenu networkMenu(Network n, List<Account> accounts) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        menu.getItems().add(ChatMenus.item("Todas las cuentas de " + n.displayName(), () ->
                listPane.filterProperty().set(listPane.filterProperty().get().withNetwork(n).withView(ChatFilter.View.INBOX))));
        if (accounts.size() > 1) {
            for (Account a : accounts) {
                menu.getItems().add(ChatMenus.item(Accounts.name(a), () ->
                        listPane.filterProperty().set(listPane.filterProperty().get().withAccount(n, a.id()).withView(ChatFilter.View.INBOX))));
            }
        }
        menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        menu.getItems().add(ChatMenus.item("Agregar otra cuenta de " + n.displayName(), () -> onAddAccount.accept(n)));
        return menu;
    }

    private static void setActive(Button b, boolean on) {
        b.getStyleClass().remove("active");
        if (on) b.getStyleClass().add("active");
    }

    private static void setUnreadBadge(Button b, long count) {
        Node g = b.getGraphic();
        StackPane wrap = g.getParent() instanceof StackPane sp && sp.getStyleClass().contains("rail-graphic") ? sp : null;
        if (wrap == null) {
            wrap = new StackPane(g);
            wrap.getStyleClass().add("rail-graphic");
            b.setGraphic(wrap);
        }
        wrap.getChildren().removeIf(n -> n.getStyleClass().contains("rail-badge"));
        String tip = b.getTooltip() == null ? "" : b.getTooltip().getText();
        b.setAccessibleText(count > 0 ? tip + ", " + count + (count == 1 ? " chat sin leer" : " chats sin leer") : tip);
        if (count > 0) {
            Label badge = new Label(count > 99 ? "99+" : Long.toString(count));
            badge.getStyleClass().add("rail-badge");
            StackPane.setAlignment(badge, Pos.TOP_RIGHT);
            badge.setTranslateX(12);
            badge.setTranslateY(-10);
            wrap.getChildren().add(badge);
        }
    }
}
