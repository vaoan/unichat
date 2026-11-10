package app.unichat.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** What the settings pages are made of: a page, its groups and rows, and the controls that write a setting. */
final class SettingsForm {
    private SettingsForm() {}

    static VBox page(String title, Node... nodes) {
        Label t = new Label(title);
        t.getStyleClass().add("settings-title");
        VBox v = new VBox(16);
        v.getStyleClass().add("settings-page");
        v.getChildren().add(t);
        v.getChildren().addAll(nodes);
        return v;
    }

    static VBox group(String title, Node... rows) {
        Label t = new Label(title);
        t.getStyleClass().add("settings-group-title");
        VBox box = new VBox(0, rows);
        box.getStyleClass().add("settings-group");
        return new VBox(6, t, box);
    }

    static Node row(String label, String help, Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("settings-label");
        l.setWrapText(true); // beside a wide control (chips) a long name goes on two lines, never "Borrar fotos y video…"
        l.setMinHeight(Region.USE_PREF_SIZE);
        VBox text = new VBox(2, l);
        if (help != null) {
            Label h = Labels.help(help);
            text.getChildren().add(h);
        }
        if (control.getAccessibleText() == null) control.setAccessibleText(label);
        if (help != null && control.getAccessibleHelp() == null) control.setAccessibleHelp(help);
        return new SettingsRow(text, control); // beside the text, or under it when it would squeeze it
    }

    /** A row with its control under the text, for one too wide to sit beside it (beside, the label was squeezed). */
    static Node stacked(String label, String help, Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("settings-label");
        VBox box = new VBox(2, l);
        if (help != null) box.getChildren().add(Labels.help(help));
        if (control.getAccessibleText() == null) control.setAccessibleText(label);
        box.getChildren().add(control);
        VBox.setMargin(control, new javafx.geometry.Insets(6, 0, 0, 0));
        box.getStyleClass().add("settings-row");
        return box;
    }

    static CheckBox toggle(boolean value, Consumer<Boolean> save) {
        CheckBox c = new CheckBox();
        c.getStyleClass().add("switch");
        c.setSelected(value);
        c.selectedProperty().addListener((o, a, b) -> save.accept(b));
        return c;
    }

    /** One choice out of {@code values} as a row of chips, each read out as "{@code accessible}: its label". */
    static <T> Node chips(String accessible, List<T> values, Function<T, String> label, T current, Consumer<T> save) {
        return chips(accessible, values, label, v -> accessible + ": " + label.apply(v), current, save);
    }

    /** The same, with what a screen reader says for each chip when its label alone isn't enough. */
    static <T> Node chips(String accessible, List<T> values, Function<T, String> label, Function<T, String> spoken, T current, Consumer<T> save) {
        ToggleGroup tg = new ToggleGroup();
        FlowPane box = new FlowPane(6, 6);
        // Wraps instead of widening the page (row() pins controls to their preferred width).
        box.setPrefWrapLength(240);
        box.setAccessibleText(accessible);
        for (T v : values) {
            ToggleButton b = Chips.sticky(label.apply(v), tg, v.equals(current), () -> save.accept(v));
            b.setAccessibleText(spoken.apply(v));
            box.getChildren().add(b);
        }
        return box;
    }
}
