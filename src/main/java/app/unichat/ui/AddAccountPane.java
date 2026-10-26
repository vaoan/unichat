package app.unichat.ui;

import app.unichat.model.Account;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * "Conectar una red" sheet: one tile per network the server has a bridge for. A network that already has
 * accounts opens a menu to add another one or disconnect a specific one.
 */
public final class AddAccountPane extends VBox {
    private final ChatService svc;
    private final Consumer<String> openChatId;
    private final Runnable close;
    private final Consumer<String> toast;
    private final Consumer<Network> addAccount;

    public AddAccountPane(ChatService svc, Consumer<String> openChatId, Runnable close, Consumer<String> toast,
                          Consumer<Network> addAccount) {
        this.svc = svc;
        this.openChatId = openChatId;
        this.close = close;
        this.toast = toast;
        this.addAccount = addAccount;
        getStyleClass().addAll("add-account", "card");
        setMaxSize(620, 540);
        setSpacing(14);

        Label title = new Label("Conectar una red");
        title.getStyleClass().add("sheet-title");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox head = new HBox(title, grow, Composer.iconButton(Icons.CLOSE, "Cerrar", close));
        head.setAlignment(Pos.CENTER_LEFT);
        Label sub = new Label("Unichat se conecta a cada red a través de un puente (bridge) en tu servidor Matrix. "
                + "Al elegir una, se abre el chat con su bot para iniciar sesión (código QR, número o token). "
                + "Puedes conectar varias cuentas de la misma red.");
        sub.setWrapText(true);
        sub.setMinHeight(Region.USE_PREF_SIZE);
        sub.getStyleClass().add("sheet-subtitle");

        FlowPane grid = new FlowPane(10, 10);
        grid.getStyleClass().add("network-grid");
        List<Network> available = svc.availableNetworks();
        for (Network n : Network.values()) {
            if (n.isBridged()) grid.getChildren().add(tile(n, available.contains(n)));
        }
        Label tip = new Label("La guía de instalación de puentes está en docs/BRIDGES.md.");
        tip.setWrapText(true);
        tip.setMinHeight(Region.USE_PREF_SIZE);
        tip.getStyleClass().add("sheet-footnote");
        getChildren().addAll(head, sub, grid, tip);
    }

    private Button tile(Network n, boolean available) {
        List<Account> accounts = Accounts.of(svc, n);
        Label letter = new Label(n.badgeLetter());
        letter.getStyleClass().add("network-tile-icon");
        letter.setStyle("-fx-background-color: " + n.color() + ";");
        Label name = new Label(n.displayName());
        name.getStyleClass().add("network-tile-name");
        String stateText = !available ? "Puente no instalado"
                : accounts.isEmpty() ? "Conectar"
                : accounts.size() == 1 ? "Conectado · " + Accounts.name(accounts.get(0))
                : accounts.size() + " cuentas conectadas";
        Label state = new Label(stateText);
        state.getStyleClass().add("network-tile-state");
        if (!accounts.isEmpty()) state.getStyleClass().add("ok");
        VBox box = new VBox(6, letter, name, state);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("network-tile");
        if (!available) box.getStyleClass().add("disabled");

        Button b = new Button();
        b.setGraphic(box);
        b.getStyleClass().add("network-tile-button");
        b.setDisable(!available);
        b.setAccessibleText(n.displayName() + ", " + stateText
                + (accounts.isEmpty() ? "" : ". Abre un menú para agregar otra cuenta o desconectar"));
        b.setOnAction(e -> {
            if (accounts.isEmpty()) connect(b, n);
            else accountMenu(n, accounts).show(b, Side.BOTTOM, 0, 4);
        });
        return b;
    }

    private void connect(Button b, Network n) {
        b.setDisable(true);
        svc.connectNetwork(n).whenComplete((chatId, err) -> Platform.runLater(() -> {
            b.setDisable(false);
            if (err != null) {
                toast.accept("No se pudo conectar " + n.displayName() + ": " + rootMessage(err));
                return;
            }
            close.run();
            openChatId.accept(chatId);
        }));
    }

    private ContextMenu accountMenu(Network n, List<Account> accounts) {
        ContextMenu menu = new ContextMenu();
        menu.getItems().add(ChatMenus.item("Agregar otra cuenta de " + n.displayName(), () -> {
            close.run();
            addAccount.accept(n);
        }));
        menu.getItems().add(new SeparatorMenuItem());
        for (Account a : accounts) {
            menu.getItems().add(ChatMenus.danger(ChatMenus.item("Desconectar " + Accounts.name(a), () ->
                    svc.disconnectAccount(a).whenComplete((v, err) -> Platform.runLater(() ->
                            toast.accept(err == null ? Accounts.name(a) + " desconectada" : "No se pudo desconectar: " + rootMessage(err)))))));
        }
        return menu;
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
