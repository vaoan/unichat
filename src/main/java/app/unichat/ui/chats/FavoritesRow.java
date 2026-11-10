package app.unichat.ui.chats;

import app.unichat.support.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * The favorites, on one line and never wider than the column: tiles narrow (down to their minimum, the avatar and a little
 * room) before any is left out, and then the last place says how many more there are ("+N") and opens the Favorites view.
 * No second line, no sideways scroll.
 */
final class FavoritesRow extends Pane {
    private static final double GAP = 4;
    private final VBox more;
    private final Label count = new Label();
    private List<Node> tiles = List.of();

    FavoritesRow(Runnable showAll) {
        getStyleClass().add("favorites");
        count.getStyleClass().add("favorite-more-count");
        StackPane circle = new StackPane(count);
        circle.getStyleClass().add("favorite-more-icon");
        circle.setMinSize(44, 44);
        circle.setMaxSize(44, 44);
        Label name = new Label(I18n.t("chatList.favoritesMore"));
        name.getStyleClass().add("favorite-name");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setAlignment(Pos.CENTER);
        name.setEllipsisString("…");
        more = new VBox(4, circle, name);
        more.setAlignment(Pos.TOP_CENTER);
        more.getStyleClass().addAll("favorite-tile", "favorite-more");
        more.setFocusTraversable(true);
        more.setAccessibleRole(AccessibleRole.BUTTON);
        more.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY) showAll.run(); });
        more.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) showAll.run(); });
        more.setVisible(false);
        getChildren().add(more);
    }

    List<Node> tiles() { return tiles; }

    void setTiles(List<Node> next) {
        tiles = List.copyOf(next);
        getChildren().setAll(tiles);
        getChildren().add(more);
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double avail = getWidth() - in.getLeft() - in.getRight();
        int n = tiles.size();
        if (n == 0) {
            more.setVisible(false);
            return;
        }
        double pref = tiles.get(0).prefWidth(-1), min = tiles.get(0).minWidth(-1);
        int shown = n;
        double w = pref;
        if (n * pref + (n - 1) * GAP > avail) {
            w = (avail - (n - 1) * GAP) / n;
            if (w < min) { // even narrow they don't fit: as many as do, and the last place for the rest
                shown = Math.max(0, (int) Math.floor((avail + GAP) / (min + GAP)) - 1);
                w = Math.min(pref, (avail - shown * GAP) / (shown + 1));
            }
        }
        double h = 0;
        for (int i = 0; i < n; i++) {
            Node t = tiles.get(i);
            boolean on = i < shown;
            if (t.isVisible() != on) t.setVisible(on);
            if (on) h = Math.max(h, t.prefHeight(w));
        }
        boolean rest = shown < n;
        if (more.isVisible() != rest) more.setVisible(rest);
        if (rest) {
            count.setText("+" + (n - shown));
            more.setAccessibleText(I18n.n("chatList.favoritesMoreA11y", n - shown));
            h = Math.max(h, more.prefHeight(w));
        }
        double x = in.getLeft();
        for (int i = 0; i < shown; i++) {
            tiles.get(i).resizeRelocate(x, in.getTop(), w, h);
            x += w + GAP;
        }
        if (rest) more.resizeRelocate(x, in.getTop(), w, h);
    }

    @Override
    protected double computePrefHeight(double width) {
        double h = 0;
        for (Node t : tiles) h = Math.max(h, t.prefHeight(-1));
        return h + getInsets().getTop() + getInsets().getBottom();
    }

    /** It never asks for width: the column decides, and the tiles fit in it. */
    @Override
    protected double computePrefWidth(double height) { return getInsets().getLeft() + getInsets().getRight(); }

    @Override
    protected double computeMinWidth(double height) { return computePrefWidth(height); }
}
