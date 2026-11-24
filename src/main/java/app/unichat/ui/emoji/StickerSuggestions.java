package app.unichat.ui.emoji;

import app.unichat.model.MediaLibrary;
import app.unichat.settings.Settings;
import app.unichat.support.I18n;
import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Nodes;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Above the message box, when what's typed is one emoji alone: the stickers of that emoji (favourites, recents, then the
 * sets), as Telegram suggests them; one click sends it in place of the emoji, which leaves the box. Hidden otherwise,
 * and when turned off (Ajustes → Apariencia → Escritura).
 */
public final class StickerSuggestions {
    /** How many it offers: a row's worth. */
    private static final int MOST = 12;
    private static final Set<String> EMOJI = new HashSet<>();

    static {
        for (String e : StickerGrid.allEmoji()) EMOJI.add(StickerFinder.plain(e));
    }

    private final StickerGrid grid;
    private final ObservableValue<String> typed;
    private final BooleanSupplier editing;
    private final Supplier<Runnable> clearer;
    private final HBox row = new HBox(6);
    private final ScrollPane node = new ScrollPane(row);
    private final FxCoalescer redraw = new FxCoalescer(this::redraw);
    /** What's shown (whether the chat takes stickers, and which): the same again doesn't rebuild the tiles. */
    private List<String> shown = List.of();

    /**
     * {@code clearer}: asked at a click, what empties the box once the sticker went (if it still holds the emoji); none
     * while {@code editing}. The window calls {@link #refresh} when the chat, the box's mode or the settings change.
     */
    public StickerSuggestions(StickerGrid grid, ObservableValue<String> typed, BooleanSupplier editing, Supplier<Runnable> clearer) {
        this.grid = grid;
        this.typed = typed;
        this.editing = editing;
        this.clearer = clearer;
        node.setFitToHeight(true);
        node.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        node.getStyleClass().addAll("sticker-suggestions", "side-panel-scroll");
        typed.addListener((o, a, t) -> redraw.request());
        grid.onRefresh(redraw::request); // a set brought later adds its stickers
        Nodes.show(node, false);
    }

    public Node node() {
        return node;
    }

    /** Draws it again for what's true now (another chat, an edit begun or ended, the setting changed). */
    public void refresh() {
        redraw.request();
    }

    /** The one emoji {@code text} is, or null when it's anything else (more emoji, a word, nothing). */
    static @Nullable String oneEmoji(@Nullable String text) {
        String t = StickerFinder.plain(text == null ? "" : text);
        return EMOJI.contains(t) ? t : null;
    }

    private void redraw() {
        String emoji = Settings.get().suggestStickers() && !editing.getAsBoolean() ? oneEmoji(typed.getValue()) : null;
        List<MediaLibrary.Item> found = emoji == null ? List.of() : grid.withEmoji(emoji);
        found = found.subList(0, Math.min(MOST, found.size()));
        boolean accepted = grid.accepts();
        List<String> now = new ArrayList<>();
        if (!found.isEmpty()) now.add(String.valueOf(accepted));
        for (MediaLibrary.Item i : found) now.add(i.url());
        if (now.equals(shown)) return;
        shown = now;
        row.getChildren().clear();
        for (MediaLibrary.Item i : found) {
            row.getChildren().add(grid.tile(i, StickerFinder.says("library.stickerSuggested", i), accepted, clearer));
        }
        node.setAccessibleText(found.isEmpty() ? null : I18n.t("sticker.suggestions", emoji));
        Nodes.show(node, !found.isEmpty());
    }
}
