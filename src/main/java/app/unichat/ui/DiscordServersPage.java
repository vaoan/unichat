package app.unichat.ui;

import app.unichat.service.BridgeAccess;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Which Discord servers come into Unichat: search, one switch per server, bridged ones first. A server's
 * channels appear as people write in them (no room is created for every channel at once).
 */
final class DiscordServersPage extends StackPane {
    private final BridgeAccess bridge;
    private final Consumer<String> toast;
    private final TextField search = new TextField();
    private final Label count = new Label();
    private final VBox rows = new VBox(0);
    private final Set<String> on = new HashSet<>();
    private List<BridgeAccess.DiscordGuild> sorted = List.of();

    DiscordServersPage(BridgeAccess bridge, Consumer<String> toast) {
        this.bridge = bridge;
        this.toast = toast;
        LoadingView loading = new LoadingView(I18n.t("discord.servers.loading"));
        getChildren().setAll(loading);
        bridge.discordGuilds().whenComplete((guilds, err) -> Platform.runLater(() -> {
            if (err != null) loading.fail(I18n.t("discord.servers.readFailed", LoginTexts.rootMessage(err)));
            else show(guilds);
        }));
    }

    private void show(List<BridgeAccess.DiscordGuild> guilds) {
        Label help = Labels.help(I18n.t("discord.servers.help"));
        search.setPromptText(I18n.t("discord.servers.search"));
        search.setAccessibleText(I18n.t("discord.servers.searchA11y"));
        count.getStyleClass().add("settings-help");
        rows.getStyleClass().add("settings-group");
        for (var g : guilds) if (g.bridged()) on.add(g.id());
        sorted = guilds.stream()
                .sorted(Comparator.comparing((BridgeAccess.DiscordGuild g) -> !g.bridged())
                        .thenComparing(g -> g.name().toLowerCase(Locale.ROOT)))
                .toList();
        showCount();
        search.textProperty().addListener((o, a, b) -> render());
        render();
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("login-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().setAll(new VBox(10, help, search, count, scroll));
        Platform.runLater(search::requestFocus);
    }

    private void showCount() {
        count.setText(I18n.t("discord.servers.count", on.size(), sorted.size()));
    }

    private void render() {
        String q = ChatFilter.normalize(search.getText());
        rows.getChildren().clear();
        for (var g : sorted) {
            if (q.isEmpty() || ChatFilter.normalize(g.name()).contains(q)) rows.getChildren().add(serverRow(g));
        }
        if (rows.getChildren().isEmpty()) {
            Label none = new Label(sorted.isEmpty() ? I18n.t("discord.servers.none") : I18n.t("discord.servers.noMatch"));
            none.getStyleClass().add("settings-help");
            HBox row = new HBox(none);
            row.getStyleClass().add("settings-row");
            rows.getChildren().add(row);
        }
    }

    private Node serverRow(BridgeAccess.DiscordGuild g) {
        Label name = new Label(g.name());
        name.getStyleClass().add("settings-label");
        name.setWrapText(true);
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setMaxWidth(Double.MAX_VALUE);
        CheckBox sw = new CheckBox();
        sw.getStyleClass().add("switch");
        sw.setSelected(on.contains(g.id()));
        sw.setAccessibleText(I18n.t("discord.servers.viewA11y", g.name()));
        sw.setOnAction(e -> {
            boolean want = sw.isSelected();
            sw.setDisable(true);
            bridge.setDiscordGuildBridged(g.id(), want).whenComplete((v, ex) -> Platform.runLater(() -> {
                sw.setDisable(false);
                if (ex != null) {
                    sw.setSelected(!want);
                    toast.accept(I18n.t("discord.servers.changeFailed", g.name(), LoginTexts.rootMessage(ex)));
                } else {
                    if (want) on.add(g.id()); else on.remove(g.id());
                    showCount();
                    toast.accept(want ? I18n.t("discord.servers.on", g.name()) : I18n.t("discord.servers.off", g.name()));
                }
            }));
        });
        HBox row = new HBox(12, name, sw);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        return row;
    }
}
