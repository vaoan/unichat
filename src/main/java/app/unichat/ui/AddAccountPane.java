package app.unichat.ui;

import app.unichat.model.Account;
import app.unichat.model.AccountStatus;
import app.unichat.model.Network;
import app.unichat.service.ChatService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/** "Conectar una red" sheet: one tile per network the server has a bridge for. */
public final class AddAccountPane extends VBox {
    public AddAccountPane(ChatService svc, Consumer<String> openChatId, Runnable close, Consumer<String> toast) {
        getStyleClass().addAll("add-account", "card");
        setMaxSize(620, 520);
        setSpacing(14);

        Label title = new Label("Conectar una red");
        title.getStyleClass().add("sheet-title");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox head = new HBox(title, grow, Composer.iconButton(Icons.CLOSE, "Cerrar", close));
        head.setAlignment(Pos.CENTER_LEFT);
        Label sub = new Label("Unichat se conecta a cada red a través de un puente (bridge) en tu servidor Matrix. "
                + "Al elegir una, se abre el chat con su bot para iniciar sesión (código QR, número o token).");
        sub.setWrapText(true);
        sub.setMinHeight(Region.USE_PREF_SIZE);
        sub.getStyleClass().add("sheet-subtitle");

        FlowPane grid = new FlowPane(10, 10);
        grid.getStyleClass().add("network-grid");
        List<Network> available = svc.availableNetworks();
        List<Account> accounts = svc.accounts();
        for (Network n : Network.values()) {
            if (!n.isBridged()) continue;
            Account acc = accounts.stream().filter(a -> a.network() == n).findFirst().orElse(null);
            boolean avail = available.contains(n);
            Label letter = new Label(n.badgeLetter());
            letter.getStyleClass().add("network-tile-icon");
            letter.setStyle("-fx-background-color: " + n.color() + ";");
            Label name = new Label(n.displayName());
            name.getStyleClass().add("network-tile-name");
            Label state = new Label(!avail ? "Puente no instalado"
                    : acc == null ? "Conectar"
                    : acc.status() == AccountStatus.CONNECTED ? "Conectado · " + acc.label()
                    : "Pendiente de iniciar sesión");
            state.getStyleClass().add("network-tile-state");
            if (acc != null && acc.status() == AccountStatus.CONNECTED) state.getStyleClass().add("ok");
            VBox tile = new VBox(6, letter, name, state);
            tile.setAlignment(Pos.CENTER);
            tile.getStyleClass().add("network-tile");
            if (!avail) tile.getStyleClass().add("disabled");
            Button b = new Button();
            b.setGraphic(tile);
            b.getStyleClass().add("network-tile-button");
            b.setDisable(!avail);
            b.setOnAction(e -> {
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
            });
            if (acc != null) {
                javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(
                        ChatMenus.danger(ChatMenus.item("Desconectar " + n.displayName(), () ->
                                svc.disconnectNetwork(n).thenRun(() -> Platform.runLater(() -> toast.accept(n.displayName() + " desconectado"))))));
                b.setContextMenu(menu);
            }
            grid.getChildren().add(b);
        }
        Label tip = new Label("Clic derecho sobre una red conectada para desconectarla. La guía de instalación de puentes está en docs/BRIDGES.md.");
        tip.setWrapText(true);
        tip.setMinHeight(Region.USE_PREF_SIZE);
        tip.getStyleClass().add("sheet-footnote");
        getChildren().addAll(head, sub, grid, tip);
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
