package app.unichat.ui.chats;

import app.unichat.model.Network;
import app.unichat.service.OlderChats;
import app.unichat.service.OlderChats.Plan;
import app.unichat.support.I18n;
import app.unichat.ui.NetworkIcons;
import app.unichat.ui.Sheets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The warning before every "Load older chats", in the window's overlay: per network, how many chats it asks for,
 * whether its bridge restarts (Discord's reconnects to every server), and the risks. Cancel is first and focused, so
 * neither a stray click nor Enter loads. Instagram runs only when its box is ticked on this click: it's off each time.
 */
public final class OlderChatsSheet {
    private static final double WIDTH = 440;

    private final Consumer<Node> show;
    private final Runnable hide;

    /** {@code show} and {@code hide}: the window's overlay, as {@code DeleteSheet} takes them. */
    public OlderChatsSheet(Consumer<Node> show, Runnable hide) {
        this.show = show;
        this.hide = hide;
    }

    /**
     * Shows {@code plans} (in the order they'd run); Load hands {@code load} the networks that will: neither a Discord
     * still in its pause nor an unticked risky one (Instagram). {@code load} is the service's {@link OlderChats#load}.
     */
    public void open(List<Plan> plans, Consumer<List<Network>> load) {
        if (plans.isEmpty()) return;
        VBox card = new VBox();
        card.getStyleClass().addAll("card", "folder-dialog", "older-chats-sheet");
        Label title = wrapping(I18n.t("olderChats.sheet.title"));
        title.getStyleClass().add("sheet-title");
        List<CheckBox> ticks = new ArrayList<>();
        List<Node> rows = new ArrayList<>();
        for (Plan p : plans) rows.add(row(p, ticks));
        Label traffic = wrapping(I18n.t("olderChats.sheet.traffic"));
        Button go = new Button(I18n.t("olderChats.sheet.load"));
        go.getStyleClass().add("primary-button");
        Runnable allowed = () -> go.setDisable(running(plans, ticks).isEmpty());
        ticks.forEach(t -> t.selectedProperty().addListener((o, was, on) -> allowed.run()));
        allowed.run();
        go.setOnAction(e -> {
            List<Network> run = running(plans, ticks);
            if (run.isEmpty()) return;
            hide.run();
            load.accept(run);
        });
        Button cancel = new Button(I18n.t("folders.dialog.cancel"));
        cancel.getStyleClass().add("secondary-button");
        cancel.setCancelButton(true);
        cancel.setAccessibleHelp(title.getText() + " " + traffic.getText()); // the focus starts here: a screen reader says what's asked
        cancel.setOnAction(e -> hide.run());
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        card.getChildren().add(title);
        card.getChildren().addAll(rows);
        card.getChildren().addAll(traffic, new HBox(10, grow, cancel, go));
        // Enter answers the button that has the focus (Cancel at first), as Space does, on every system.
        card.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && card.getScene() != null && card.getScene().getFocusOwner() instanceof Button b) {
                b.fire();
                e.consume();
            }
        });
        show.accept(Sheets.scrolling(card, WIDTH));
        Sheets.focusOnShow(cancel);
    }

    /** The networks Load would run: in order, without one in its pause or a risky one left unticked. */
    private static List<Network> running(List<Plan> plans, List<CheckBox> ticks) {
        List<Network> out = new ArrayList<>();
        for (Plan p : plans) {
            if (p.waitFirst() != null) continue;
            if (p.risky() && ticks.stream().noneMatch(t -> t.getUserData() == p.network() && t.isSelected())) continue;
            out.add(p.network());
        }
        return out;
    }

    /** A network's row: its icon and name, what it asks for, and what happens; focusable with all it says. */
    private static Node row(Plan p, List<CheckBox> ticks) {
        Network n = p.network();
        Label name = new Label(n.displayName());
        name.getStyleClass().add("older-chats-network");
        VBox texts = new VBox(2, name);
        for (String line : lines(p)) texts.getChildren().add(wrapping(line));
        HBox.setHgrow(texts, Priority.ALWAYS);
        texts.setMinWidth(0);
        Node icon = NetworkIcons.glyph(n, 20);
        HBox row = new HBox(10, icon, texts);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("older-chats-row");
        row.setFocusTraversable(true);
        row.setAccessibleRole(AccessibleRole.TEXT);
        row.setAccessibleText(n.displayName() + ". " + String.join(" ", lines(p)));
        if (!p.risky()) return row;
        CheckBox tick = new CheckBox(I18n.t("olderChats.sheet.include", n.displayName()));
        tick.setUserData(n);
        tick.setWrapText(true);
        tick.setMinHeight(Region.USE_PREF_SIZE);
        ticks.add(tick);
        Label risk = wrapping(I18n.t("olderChats.sheet.instagramRisk"));
        risk.getStyleClass().addAll("settings-help", "risk-notice", "older-chats-risk");
        risk.setMaxWidth(Double.MAX_VALUE);
        return new VBox(6, row, tick, risk);
    }

    /** What a network's step does, line by line, as the row shows and says it. */
    private static List<String> lines(Plan p) {
        Network n = p.network();
        List<String> out = new ArrayList<>();
        out.add(p.chats() < 0 ? I18n.t(n == Network.WHATSAPP ? "olderChats.sheet.everyGroup" : "olderChats.sheet.all")
                : n == Network.DISCORD ? I18n.t("olderChats.sheet.privateChats", p.chats())
                : I18n.t("olderChats.sheet.upTo", p.chats()));
        if (p.restarts()) out.add(I18n.t("olderChats.sheet.restarts", n.displayName()));
        if (p.reconnectsServers()) out.add(I18n.t("olderChats.sheet.reconnectsServers"));
        java.time.Duration wait = p.waitFirst();
        if (wait != null) out.add(I18n.n("olderChats.wait", OlderChats.minutesLeft(wait)));
        if (n == Network.WHATSAPP) out.add(I18n.t("olderChats.sheet.whatsappPairing"));
        return out;
    }

    private static Label wrapping(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }
}
