package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * The side panel's Emojis tab: your favourites and the ones used last on top, then every emoji by group. Favourites
 * change at once (right-click → add); recents only when the tab is shown again, so nothing moves under the pointer.
 */
final class EmojiGrid extends VBox {
    /** As many as two or three rows. */
    static final int RECENTS = 24;

    private final MediaLibrary library;
    private final Consumer<String> onPick;
    private final Consumer<String> toast;
    private final double wrap;
    private final VBox favorites = new VBox(6);
    private final VBox recents = new VBox(6);
    private List<String> shownFavorites = List.of();
    private List<String> shownRecents = List.of();

    EmojiGrid(MediaLibrary library, Consumer<String> onPick, double wrap, Consumer<String> toast) {
        super(6);
        this.library = library;
        this.onPick = onPick;
        this.toast = toast;
        this.wrap = wrap;
        VBox all = EmojiPicker.grid(onPick, wrap, library, toast);
        all.setPadding(javafx.geometry.Insets.EMPTY);
        setPadding(new javafx.geometry.Insets(10));
        getChildren().addAll(favorites, recents, all);
        library.addListener(() -> Platform.runLater(this::showFavorites));
        refresh();
    }

    /** When the tab is shown. */
    void refresh() {
        showFavorites();
        List<String> now = urls(library.recents(MediaLibrary.Kind.EMOJI));
        now = now.subList(0, Math.min(RECENTS, now.size()));
        if (now.equals(shownRecents)) return;
        shownRecents = now;
        section(recents, I18n.t("library.recents"), now, null);
    }

    private void showFavorites() {
        List<String> now = urls(library.favorites(MediaLibrary.Kind.EMOJI));
        if (now.equals(shownFavorites) && !favorites.getChildren().isEmpty()) return;
        shownFavorites = now;
        // With none yet, the section stays as a hint: favourites existed only behind a right-click nobody found.
        section(favorites, I18n.t("library.favorites"), now, I18n.t("emoji.favoritesHint"));
    }

    private void section(VBox box, String title, List<String> emoji, String whenEmpty) {
        box.getChildren().clear();
        if (emoji.isEmpty() && whenEmpty == null) return;
        Label t = new Label(title);
        t.getStyleClass().add("emoji-category");
        box.getChildren().add(t);
        if (emoji.isEmpty()) {
            box.getChildren().add(Labels.help(whenEmpty));
            return;
        }
        FlowPane grid = new FlowPane(2, 2);
        grid.setPrefWrapLength(wrap);
        for (String e : emoji) grid.getChildren().add(EmojiPicker.button(e, onPick, library, toast));
        box.getChildren().add(grid);
    }

    private static List<String> urls(List<MediaLibrary.Item> items) {
        return items.stream().map(MediaLibrary.Item::url).toList();
    }
}
