package app.unichat.ui.timeline;

import app.unichat.support.I18n;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * A WhatsApp Business message's buttons (pay an order, open a link, a template's replies), drawn as chips under it and
 * a line saying where to press them: the bridge can't pass a press on, so only the phone's WhatsApp can.
 */
final class BusinessButtons {
    private BusinessButtons() {}

    static Node of(List<String> buttons, double width) {
        FlowPane row = new FlowPane(6, 6);
        row.setPrefWrapLength(width);
        row.getStyleClass().add("business-buttons");
        for (String b : buttons) {
            Label chip = new Label(label(b));
            chip.getStyleClass().add("business-button");
            chip.setWrapText(true);
            chip.setMaxWidth(width);
            chip.setMinHeight(Region.USE_PREF_SIZE);
            row.getChildren().add(chip);
        }
        Label note = new Label(I18n.t("business.note"));
        note.getStyleClass().add("business-note");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);
        VBox box = new VBox(4, row, note);
        box.setMaxWidth(width);
        return box;
    }

    /** What a button says: the bridge gives some by their internal name (review_and_pay), shown in words. */
    static String label(String raw) {
        if (!raw.matches("[a-z][a-z0-9_]*")) return raw;
        String key = "business.button." + raw;
        String known = I18n.t(key);
        if (!known.equals(key)) return known;
        String words = raw.replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
