package app.unichat.ui.timeline;

import app.unichat.support.I18n;
import app.unichat.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The two ends of a range drawn on their rows: a bar "Desde aquí" above the first message and "Hasta aquí" under the
 * last, each with a ✕ that takes it away; what's between them is in, as the band of rows shows.
 */
final class RangeMarkers {
    private RangeMarkers() {}

    /** {@code row} with its markers, or as it is when it's neither end. */
    static Node around(Node row, boolean start, boolean end, Runnable removeStart, Runnable removeEnd) {
        if (!start && !end) return row;
        VBox box = new VBox(2);
        if (start) box.getChildren().add(marker(I18n.t("select.rangeStartMarker"), "range-marker-start", removeStart));
        box.getChildren().add(row);
        if (end) box.getChildren().add(marker(I18n.t("select.rangeEndMarker"), "range-marker-end", removeEnd));
        return box;
    }

    private static Node marker(String text, String side, Runnable remove) {
        Label l = new Label(text);
        l.getStyleClass().add("range-marker-label");
        Region line = new Region();
        line.getStyleClass().add("range-marker-line");
        HBox.setHgrow(line, Priority.ALWAYS);
        Button x = Icons.iconButton(Icons.CLOSE, I18n.t("select.rangeRemove", text), remove);
        x.setFocusTraversable(false); // the bar has the same ✕ for the keyboard
        HBox m = new HBox(6, l, line, x);
        m.setAlignment(Pos.CENTER_LEFT);
        m.getStyleClass().addAll("range-marker", side);
        return m;
    }
}
