package app.unichat.ui;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Updating a list without repainting it. Replacing all its items, or {@code ListView.refresh()}, piles every cell and
 * hands them out again in another order: each row then draws another item (avatar, photo, icons), the whole list
 * repaints although nothing in it moved.
 */
public final class ListRows {
    /** A cell that can draw its item again, keeping it. */
    @FunctionalInterface
    public interface Redrawable {
        void redraw();
    }

    private ListRows() {}

    /** {@code next} into {@code items}: replaced whole only when the rows themselves (by {@code id}) or their order changed. */
    public static <T> void show(ObservableList<T> items, List<T> next, Function<T, ?> id) {
        boolean same = items.size() == next.size();
        for (int i = 0; same && i < next.size(); i++) same = Objects.equals(id.apply(items.get(i)), id.apply(next.get(i)));
        if (!same) {
            items.setAll(next);
            return;
        }
        for (int i = 0; i < next.size(); i++) if (items.get(i) != next.get(i)) items.set(i, next.get(i));
    }

    /**
     * {@code list.scrollTo(row)}, kept inside the list. For a row in the last screenful, which can't be at the top,
     * JavaFX's {@code VirtualFlow} sets a position past the end (above 1); its next layout, measuring the rows again,
     * reads that as a row further up, and the row asked for stayed below the list (rows taller than the list's guess:
     * Linux's fonts on CI, text at 130 % on the Mac). Past the end is the end.
     */
    public static void scrollTo(ListView<?> list, int row) {
        list.scrollTo(row);
        if (list.lookup(".virtual-flow") instanceof VirtualFlow<?> flow && flow.getPosition() > 1) flow.setPosition(1);
    }

    /** Each row on screen draws its item again (its fields changed), in place. */
    public static void redraw(ListView<?> list) {
        for (Node n : list.lookupAll(".list-cell")) if (n instanceof Redrawable r) r.redraw();
    }
}
