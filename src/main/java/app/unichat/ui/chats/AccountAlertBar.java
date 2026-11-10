package app.unichat.ui.chats;

import app.unichat.ui.NetworkIcons;
import app.unichat.model.Network;
import app.unichat.support.I18n;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The accounts that aren't working, at the foot of the chat list so the chats above don't jump when a bridge flickers.
 * What needs the person gets a row and a button; what fixes itself shares one quiet line.
 */
public final class AccountAlertBar extends VBox {
    private static final int MAX_ICONS = 3;

    private final Consumer<Network> relink;
    private List<AccountAlerts.Alert> shown = List.of();

    public AccountAlertBar(Consumer<Network> relink) {
        this.relink = relink;
        getStyleClass().add("account-alerts");
        setAccessibleRole(AccessibleRole.TEXT);
        setMinHeight(Region.USE_PREF_SIZE);
        setVisible(false);
        setManaged(false);
    }

    /** Rebuilds the bar; screen readers hear it when it changes. */
    public void show(List<AccountAlerts.Alert> alerts) {
        if (alerts.equals(shown)) return;
        shown = alerts;
        getChildren().clear();
        alerts.stream().filter(AccountAlerts.Alert::relink).forEach(a -> getChildren().add(relinkRow(a)));
        List<AccountAlerts.Alert> recovering = alerts.stream().filter(a -> !a.relink()).toList();
        if (!recovering.isEmpty()) getChildren().add(recoveringRow(recovering));
        setVisible(!alerts.isEmpty());
        setManaged(!alerts.isEmpty());
        String spoken = alerts.stream().map(AccountAlerts.Alert::text).collect(Collectors.joining(" "));
        setAccessibleText(spoken.isEmpty() ? null : spoken);
        if (!spoken.isEmpty()) notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
    }

    private HBox relinkRow(AccountAlerts.Alert a) {
        Label text = line(I18n.t("alerts.sessionClosed", a.who()), a.text());
        Button fix = new Button(I18n.t("alerts.link"));
        fix.getStyleClass().add("alert-action");
        fix.setMinWidth(Region.USE_PREF_SIZE);
        fix.setAccessibleText(I18n.t("alerts.relinkA11y", a.network().displayName()));
        fix.setOnAction(e -> relink.accept(a.network()));
        return row("relink", NetworkIcons.tile(a.network(), 16, "alert-network"), text, fix);
    }

    private HBox recoveringRow(List<AccountAlerts.Alert> recovering) {
        HBox icons = new HBox(2);
        icons.setMinWidth(Region.USE_PREF_SIZE);
        recovering.stream().limit(MAX_ICONS).forEach(a -> icons.getChildren().add(NetworkIcons.tile(a.network(), 14, "alert-network")));
        String details = recovering.stream().map(AccountAlerts.Alert::text).collect(Collectors.joining("\n"));
        return row("recovering", icons, line(AccountAlerts.recoveringLine(recovering), details));
    }

    private static Label line(String text, String details) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMinWidth(0);
        l.setMinHeight(Region.USE_PREF_SIZE);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setTooltip(new Tooltip(details));
        HBox.setHgrow(l, Priority.ALWAYS);
        return l;
    }

    private static HBox row(String kind, javafx.scene.Node... nodes) {
        HBox row = new HBox(8, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("account-alert", kind);
        return row;
    }
}
