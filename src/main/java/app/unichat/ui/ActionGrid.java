package app.unichat.ui;

import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * Equal tiles in even rows, centred: all of them in one row when they fit, else half and half (six: three and three),
 * else two by two; never a row with one left over. Decided from the width actually given, at every layout, so text
 * size and panel width can't leave five and one (a TilePane's own insets and rounding did).
 */
final class ActionGrid extends Pane {
    private final double gap;

    ActionGrid(double gap) {
        this.gap = gap;
    }

    private double tileWidth() {
        return getManagedChildren().stream().mapToDouble(n -> n.prefWidth(-1)).max().orElse(0);
    }

    private double tileHeight() {
        return getManagedChildren().stream().mapToDouble(n -> n.prefHeight(-1)).max().orElse(0);
    }

    /** Columns for {@code width}: all, half, or two. */
    int columns(double width) {
        int n = getManagedChildren().size();
        double tile = tileWidth();
        for (int cols : new int[] {n, (n + 1) / 2, 2}) {
            if (cols >= 1 && cols * tile + (cols - 1) * gap <= width + 0.5) return cols;
        }
        return Math.min(2, Math.max(1, n));
    }

    private double inner(double width) {
        return width - snappedLeftInset() - snappedRightInset();
    }

    /** Its height depends on its width (one row or two): the parent must say the width when it asks the height. */
    @Override public javafx.geometry.Orientation getContentBias() {
        return javafx.geometry.Orientation.HORIZONTAL;
    }

    @Override protected double computePrefWidth(double height) {
        int n = getManagedChildren().size();
        return snappedLeftInset() + snappedRightInset() + n * tileWidth() + Math.max(0, n - 1) * gap;
    }

    @Override protected double computeMinWidth(double height) {
        return snappedLeftInset() + snappedRightInset() + 2 * tileWidth() + gap;
    }

    @Override protected double computePrefHeight(double width) {
        int n = getManagedChildren().size();
        if (n == 0) return snappedTopInset() + snappedBottomInset();
        int cols = columns(width < 0 ? computePrefWidth(-1) : inner(width));
        int rows = (n + cols - 1) / cols;
        return snappedTopInset() + snappedBottomInset() + rows * tileHeight() + (rows - 1) * gap;
    }

    @Override protected void layoutChildren() {
        var kids = getManagedChildren();
        if (kids.isEmpty()) return;
        double w = inner(getWidth()), tile = tileWidth(), tileH = tileHeight();
        int cols = columns(w);
        for (int i = 0; i < kids.size(); i++) {
            int row = i / cols, col = i % cols;
            int inRow = Math.min(cols, kids.size() - row * cols);
            double rowWidth = inRow * tile + (inRow - 1) * gap;
            double x = snappedLeftInset() + (w - rowWidth) / 2 + col * (tile + gap);
            double y = snappedTopInset() + row * (tileH + gap);
            Node n = kids.get(i);
            layoutInArea(n, x, y, tile, tileH, 0, HPos.CENTER, VPos.CENTER);
        }
    }
}
