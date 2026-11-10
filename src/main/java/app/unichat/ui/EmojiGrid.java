package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import app.unichat.support.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every emoji, by group, in a virtual list: only the rows on screen exist and are reused while scrolling (1870
 * buttons at once scrolled at ~9 frames a second), with a search box on top (names in the language in use,
 * {@link EmojiSearch}). The side panel's tab ({@link Mode#PANEL}) starts with your favourites and the ones used last;
 * favourites change at once (right-click → add), recents when the tab is shown again, so nothing moves under the
 * pointer. The reaction picker ({@link Mode#REACTIONS}) starts with the ones you react with most. Arrow keys move between
 * emoji, scrolling as needed, since those off screen have no button to Tab to; ↓ goes from the search to them.
 */
final class EmojiGrid extends javafx.scene.layout.VBox {
    /** What comes before the whole list. */
    enum Mode { PANEL, REACTIONS, PLAIN }

    /** As many as two or three rows. */
    static final int RECENTS = 24;
    /** The reaction picker's first section. */
    static final int FREQUENT = 18;
    private static final double GAP = 2;

    /** The Emojis tab: what goes on screen, and what to run each time it's shown. */
    record Tab(javafx.scene.Parent node, Runnable refresh) {}

    private final MediaLibrary library;
    private final Mode mode;
    private final javafx.scene.control.TextField search = new javafx.scene.control.TextField();
    private final EmojiSearch names = EmojiSearch.forLanguage(I18n.locale().getLanguage());
    private final Consumer<String> onPick;
    private final Consumer<String> toast;
    private final ListView<EmojiRows.Row> list = new ListView<>();
    /** One emoji button, never shown: its width, with the text size and styles of the moment, sets the columns. */
    private final Button probe = new Button("😀");
    private Map<String, Image> pictures = Map.of();
    private List<String> shownRecents = List.of();
    private int columns;

    /** {@code library}: null only in {@link Mode#PLAIN}. */
    EmojiGrid(MediaLibrary library, Mode mode, Consumer<String> onPick, Consumer<String> toast) {
        super(6);
        this.library = library;
        this.mode = library == null ? Mode.PLAIN : mode;
        this.onPick = onPick;
        this.toast = toast;
        search.setPromptText(I18n.t("emoji.search"));
        search.setAccessibleText(I18n.t("emoji.search"));
        search.getStyleClass().add("emoji-search");
        search.textProperty().addListener(o -> show());
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.ENTER) {
                int first = EmojiRows.nextEmojiRow(list.getItems(), -1, 1);
                if (first < 0) return;
                if (e.getCode() == KeyCode.ENTER) use(((EmojiRows.Emojis) list.getItems().get(first)).emoji().get(0));
                else focusFirst(first);
                e.consume();
            }
        });
        javafx.scene.layout.StackPane searchBox = SearchFields.clearable(search);
        searchBox.getStyleClass().add("emoji-search-box");
        getStyleClass().add("emoji-picker");
        list.getStyleClass().add("emoji-list");
        list.setSelectionModel(null);
        list.setFocusTraversable(false);
        list.setCellFactory(v -> new Row());
        probe.getStyleClass().add("emoji-button");
        probe.setManaged(false);
        probe.setVisible(false);
        // Inside a box styled as the list, so it gets the very size of the list's buttons.
        StackPane probeBox = new StackPane(probe);
        probeBox.getStyleClass().add("emoji-list");
        probeBox.setManaged(false);
        probeBox.setVisible(false);
        StackPane body = new StackPane(probeBox, list);
        javafx.scene.layout.VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(searchBox, body);
        list.widthProperty().addListener(o -> fit());
        probe.widthProperty().addListener(o -> fit());
        if (library != null) library.addListener(() -> Platform.runLater(this::show));
        EmojiArt.pictures().thenAccept(p -> Platform.runLater(() -> {
            pictures = p;
            show();
        }));
        refresh();
    }

    static Tab panel(MediaLibrary library, Consumer<String> onPick, Consumer<String> toast) {
        EmojiGrid grid = new EmojiGrid(library, Mode.PANEL, onPick, toast);
        return new Tab(grid, grid::refresh);
    }

    /** Where typing goes first in a picker that opens to choose one. */
    javafx.scene.control.TextField searchField() { return search; }

    /** When the tab is shown: recents catch up. */
    void refresh() {
        if (mode == Mode.PANEL) {
            List<String> now = urls(library.recents(MediaLibrary.Kind.EMOJI));
            shownRecents = now.subList(0, Math.min(RECENTS, now.size()));
        }
        show();
    }

    @Override
    protected void layoutChildren() { // the probe isn't laid out by its box (unmanaged): sized here
        super.layoutChildren();
        probe.autosize();
    }

    private void fit() {
        double cell = probe.getWidth() + GAP;
        if (cell <= GAP || list.getWidth() <= 0) return;
        // The cells' own padding and room for the scroll bar.
        int fits = (int) Math.max(1, Math.floor((list.getWidth() - 2 * 10 - 16) / cell));
        if (fits == columns) return;
        columns = fits;
        show();
    }

    private void show() {
        if (columns == 0) return;
        ListRows.show(list.getItems(), EmojiRows.rows(sections(), columns), EmojiGrid::id);
    }

    private List<EmojiRows.Section> sections() {
        List<EmojiRows.Section> out = new ArrayList<>();
        if (!search.getText().isBlank()) {
            List<String> all = new ArrayList<>();
            for (List<String> g : EmojiPicker.CATEGORIES.values()) all.addAll(shown(g));
            out.add(new EmojiRows.Section("results", I18n.t("emoji.results"), names.find(search.getText(), all),
                    I18n.t("emoji.noResults")));
            return out;
        }
        switch (mode) {
            case PANEL -> {
                // With none yet, it stays as a hint: favourites existed only behind a right-click nobody found.
                out.add(new EmojiRows.Section("favorites", I18n.t("library.favorites"),
                        urls(library.favorites(MediaLibrary.Kind.EMOJI)), I18n.t("emoji.favoritesHint")));
                out.add(new EmojiRows.Section("recents", I18n.t("library.recents"), shownRecents, null));
            }
            case REACTIONS -> out.add(new EmojiRows.Section("frequent", I18n.t("emoji.frequent"),
                    shown(library.mostReacted(FREQUENT, List.of())), null));
            case PLAIN -> { }
        }
        for (Map.Entry<String, List<String>> g : EmojiPicker.CATEGORIES.entrySet()) {
            out.add(new EmojiRows.Section(g.getKey(), I18n.t(g.getKey()), shown(g.getValue()), null));
        }
        return out;
    }

    /** Those JavaFX draws in pieces come in once drawn as pictures; the ones that can't be stay out. */
    private List<String> shown(List<String> emoji) {
        return emoji.stream().filter(e -> !EmojiArt.inPieces(e) || pictures.containsKey(e)).toList();
    }

    private static Object id(EmojiRows.Row r) {
        return switch (r) {
            case EmojiRows.Title t -> "title:" + t.text();
            case EmojiRows.Hint h -> "hint:" + h.text();
            case EmojiRows.Emojis e -> e.key() + e.emoji();
        };
    }

    private void use(String e) {
        onPick.accept(e);
        if (mode == Mode.PANEL) library.addRecent(MediaLibrary.emoji(e));
    }

    /** ← → along the emoji, ↑ ↓ between rows: to the emoji there, scrolling it into view first if needed. */
    private void move(int row, int col, KeyCode key) {
        List<EmojiRows.Row> rows = list.getItems();
        int r = row, c = col;
        switch (key) {
            case LEFT -> {
                if (--c < 0) {
                    r = EmojiRows.nextEmojiRow(rows, row, -1);
                    c = Integer.MAX_VALUE;
                }
            }
            case RIGHT -> {
                if (++c >= ((EmojiRows.Emojis) rows.get(row)).emoji().size()) {
                    r = EmojiRows.nextEmojiRow(rows, row, 1);
                    c = 0;
                }
            }
            case UP -> r = EmojiRows.nextEmojiRow(rows, row, -1);
            case DOWN -> r = EmojiRows.nextEmojiRow(rows, row, 1);
            default -> {
                return;
            }
        }
        if (r < 0) return;
        int target = r, at = Math.min(c, ((EmojiRows.Emojis) rows.get(r)).emoji().size() - 1);
        if (!focus(target, at)) {
            list.scrollTo(target);
            Platform.runLater(() -> focus(target, at));
        }
    }

    private void focusFirst(int row) {
        if (!focus(row, 0)) {
            list.scrollTo(row);
            Platform.runLater(() -> focus(row, 0));
        }
    }

    private boolean focus(int row, int col) {
        for (Node n : list.lookupAll(".emoji-row")) {
            if (n instanceof Row cell && cell.getIndex() == row && col < cell.buttons.size() && cell.buttons.get(col).isVisible()) {
                cell.buttons.get(col).requestFocus();
                return true;
            }
        }
        return false;
    }

    private static List<String> urls(List<MediaLibrary.Item> items) {
        return items.stream().map(MediaLibrary.Item::url).toList();
    }

    /** One row: a title, a hint, or emoji buttons kept from one item to the next. */
    private final class Row extends ListCell<EmojiRows.Row> {
        private final HBox box = new HBox(GAP);
        private final List<Button> buttons = new ArrayList<>();
        private final Label label = new Label();

        Row() {
            getStyleClass().add("emoji-row");
            label.setWrapText(true);
            // A row's graphic isn't told the list's width: without a bound, a long hint ran past the edge.
            label.maxWidthProperty().bind(list.widthProperty().subtract(2 * 10 + 16));
            label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        }

        @Override
        protected void updateItem(EmojiRows.Row item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            switch (item) {
                case null -> setGraphic(null);
                case EmojiRows.Title t -> label(t.text(), "emoji-category");
                case EmojiRows.Hint h -> label(h.text(), "settings-help");
                case EmojiRows.Emojis e -> {
                    while (buttons.size() < e.emoji().size()) buttons.add(button(buttons.size()));
                    for (int i = 0; i < e.emoji().size(); i++) draw(buttons.get(i), e.emoji().get(i));
                    box.getChildren().setAll(buttons.subList(0, e.emoji().size()));
                    setGraphic(box);
                }
            }
        }

        private void label(String text, String style) {
            label.setText(text);
            label.getStyleClass().setAll("label", style);
            setGraphic(label);
        }

        private Button button(int col) {
            Button b = new Button();
            b.getStyleClass().add("emoji-button");
            ImageView view = new ImageView();
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.fitHeightProperty().bind(b.fontProperty().map(f -> f.getSize() * EmojiArt.FIT));
            b.setUserData(view);
            b.setOnAction(ev -> use((String) b.getProperties().get("emoji")));
            b.setOnKeyPressed(ev -> {
                move(getIndex(), col, ev.getCode());
                if (ev.getCode().isArrowKey()) ev.consume();
            });
            return b;
        }

        private void draw(Button b, String e) {
            b.getProperties().put("emoji", e);
            Image picture = pictures.get(e);
            ImageView view = (ImageView) b.getUserData();
            view.setImage(picture);
            b.setGraphic(picture == null ? null : view);
            b.setText(picture == null ? e : null);
            b.setAccessibleText(I18n.t("emoji.named", e));
            if (mode == Mode.PANEL) LibraryMenu.attach(b, library, MediaLibrary.emoji(e), () -> use(e), toast, null);
        }
    }
}
