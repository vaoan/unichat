package app.unichat.ui.settings;

import javafx.geometry.HPos;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * One option in Ajustes: its name and help, and its control. The control sits at the right while the text keeps room
 * to read (at least {@link #TEXT_EM} letters); a wide one (colour swatches, a row of choices) goes under the text
 * instead. Side by side always, a wide control squeezed "Color de acento" to a syllable a line. Decided from the
 * width it gets, at every layout: text size and window width change it.
 */
final class SettingsRow extends Pane {
    /** The narrowest the text may get beside a control, in letters (em). */
    static final double TEXT_EM = 14;
    private static final double GAP = 12, UNDER = 8;

    private final Region text;
    private final Node control;

    SettingsRow(Region text, Node control) {
        this.text = text;
        this.control = control;
        getChildren().addAll(text, control);
        getStyleClass().add("settings-row");
    }

    @Override public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    private double em() {
        return text.lookup(".settings-label") instanceof javafx.scene.control.Label l ? l.getFont().getSize() : 13;
    }

    /** Whether the control fits beside the text at {@code width} (inside the insets). */
    boolean beside(double width) {
        return width - control.prefWidth(-1) - GAP >= TEXT_EM * em();
    }

    /** A control that can grow takes the row's width under the text; one that can't keeps its own. */
    private double underWidth(double w) {
        return control.isResizable() && control.maxWidth(-1) >= w ? w : Math.min(control.prefWidth(-1), w);
    }

    private double inner(double width) {
        return width - snappedLeftInset() - snappedRightInset();
    }

    @Override protected double computePrefWidth(double height) {
        return snappedLeftInset() + snappedRightInset() + TEXT_EM * em() + GAP + control.prefWidth(-1);
    }

    @Override protected double computeMinWidth(double height) {
        return snappedLeftInset() + snappedRightInset() + Math.min(TEXT_EM * em(), control.minWidth(-1));
    }

    @Override protected double computePrefHeight(double width) {
        double w = width < 0 ? computePrefWidth(-1) - snappedLeftInset() - snappedRightInset() : inner(width);
        double c = control.prefHeight(-1);
        double h = beside(w) ? Math.max(text.prefHeight(w - control.prefWidth(-1) - GAP), c)
                : text.prefHeight(w) + UNDER + control.prefHeight(underWidth(w));
        return snappedTopInset() + snappedBottomInset() + h;
    }

    @Override protected void layoutChildren() {
        double w = inner(getWidth()), x = snappedLeftInset(), y = snappedTopInset();
        double cw = Math.min(control.prefWidth(-1), w), ch = control.prefHeight(cw);
        if (beside(w)) {
            double tw = w - cw - GAP, th = text.prefHeight(tw), h = Math.max(th, ch);
            layoutInArea(text, x, y, tw, h, 0, HPos.LEFT, VPos.CENTER);
            layoutInArea(control, x + w - cw, y, cw, h, 0, HPos.RIGHT, VPos.CENTER);
        } else {
            // Under the text it has the whole row: a row of choices then wraps only when the row is full.
            double uw = underWidth(w), th = text.prefHeight(w);
            layoutInArea(text, x, y, w, th, 0, HPos.LEFT, VPos.TOP);
            layoutInArea(control, x, y + th + UNDER, uw, control.prefHeight(uw), 0, HPos.LEFT, VPos.TOP);
        }
    }
}
