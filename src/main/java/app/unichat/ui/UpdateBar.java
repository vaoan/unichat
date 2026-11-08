package app.unichat.ui;

import app.unichat.support.I18n;
import app.unichat.update.Release;
import app.unichat.update.UpdateChecker;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * "Unichat 1.2 está disponible", at the foot of the chat list like {@link AccountAlertBar}: never over the chats, never
 * a window. "Descargar" opens the release page; the cross hides that version for good (a later one shows again).
 */
final class UpdateBar extends VBox {
    private final UpdateChecker checker;
    private final Consumer<String> openLink;
    private final Consumer<Release> listener = r -> Platform.runLater(() -> show(r));
    private Release shown;

    UpdateBar(UpdateChecker checker, Consumer<String> openLink) {
        this.checker = checker;
        this.openLink = openLink;
        getStyleClass().add("account-alerts");
        setAccessibleRole(AccessibleRole.TEXT);
        setMinHeight(Region.USE_PREF_SIZE);
        Nodes.show(this, false);
        checker.addListener(listener);
        show(checker.pending());
    }

    void dispose() {
        checker.removeListener(listener);
    }

    private void show(Release r) {
        if (Objects.equals(r, shown)) return;
        shown = r;
        getChildren().clear();
        Nodes.show(this, r != null);
        if (r == null) {
            setAccessibleText(null);
            return;
        }
        String text = I18n.t("update.available", r.shown());
        Label line = new Label(text);
        line.setWrapText(true);
        line.setMinWidth(0);
        line.setMinHeight(Region.USE_PREF_SIZE);
        line.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(line, Priority.ALWAYS);
        Button download = new Button(I18n.t("update.download"));
        download.getStyleClass().add("alert-action");
        download.setMinWidth(Region.USE_PREF_SIZE);
        download.setAccessibleText(I18n.t("update.downloadA11y", r.shown()));
        download.setOnAction(e -> openLink.accept(r.url()));
        Button later = Icons.iconButton(Icons.CLOSE, I18n.t("update.dismiss", r.shown()), () -> checker.dismiss(r));
        later.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(8, Icons.icon(Icons.DOWNLOAD, 16), line, download, later);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("account-alert", "update");
        getChildren().add(row);
        setAccessibleText(text);
        notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
    }
}
