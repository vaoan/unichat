package app.unichat.ui.chats;

import app.unichat.support.I18n;
import app.unichat.ui.Icons;
import javafx.beans.property.ObjectProperty;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The chat list's quick filters, one segmented bar on one line: "All" as text, then Unread, Favorites and Low priority as
 * icons (each named in its tooltip and for screen readers). One part is always chosen, and the bar follows the filter
 * however it changed (rail, shortcuts, leaving the archive).
 */
final class FilterBar extends HBox {
    private static final double ICON = 16;

    private final ToggleGroup group = new ToggleGroup();
    private final ObjectProperty<ChatFilter> filter;
    private final List<ToggleButton> parts;

    FilterBar(ObjectProperty<ChatFilter> filter) {
        this.filter = filter;
        getStyleClass().add("filter-bar");
        ToggleButton all = part(f -> f.withUnreadOnly(false).withView(ChatFilter.View.INBOX));
        all.setText(I18n.t("chatList.chip.all"));
        parts = List.of(all,
                iconPart(Icons.UNREAD, "chatList.chip.unread", f -> f.withUnreadOnly(true).withView(ChatFilter.View.INBOX)),
                iconPart(Icons.STAR, "chatList.chip.favorites", f -> f.withUnreadOnly(false).withView(ChatFilter.View.FAVORITES)),
                iconPart(Icons.LOW, "chatList.chip.lowPriority",
                        f -> f.withUnreadOnly(false).withView(ChatFilter.View.LOW_PRIORITY)));
        getChildren().setAll(parts);
        follow(filter.get());
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) was.setSelected(true); // clicking the chosen part again keeps it
        });
        filter.addListener((o, was, now) -> follow(now));
    }

    private ToggleButton iconPart(String icon, String nameKey, UnaryOperator<ChatFilter> change) {
        ToggleButton b = part(change);
        String name = I18n.t(nameKey);
        b.setGraphic(Icons.icon(icon, ICON));
        b.setTooltip(new Tooltip(name));
        b.setAccessibleText(name);
        b.getStyleClass().add("filter-icon");
        return b;
    }

    private ToggleButton part(UnaryOperator<ChatFilter> change) {
        ToggleButton b = new ToggleButton();
        b.getStyleClass().add("filter-part");
        b.setToggleGroup(group);
        b.setMinWidth(USE_PREF_SIZE); // never "Tod…"
        b.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE); // the text and the icons, one height
        setHgrow(b, Priority.ALWAYS);
        b.setOnAction(e -> filter.set(change.apply(filter.get())));
        return b;
    }

    /** Chooses the part that shows {@code f}; the archive has none, so the last one stays. */
    private void follow(ChatFilter f) {
        int i = switch (f.view()) {
            case INBOX -> f.unreadOnly() ? 1 : 0;
            case FAVORITES -> 2;
            case LOW_PRIORITY -> 3;
            case ARCHIVE -> -1;
        };
        if (i >= 0 && !parts.get(i).isSelected()) parts.get(i).setSelected(true);
    }
}
