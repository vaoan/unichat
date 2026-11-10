package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import app.unichat.support.I18n;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The emoji list, one emoji's button, and the reaction picker (a popup with the list); also the quick-reaction set. */
public final class EmojiPicker {
    private EmojiPicker() {}

    public static final String[] QUICK = {"❤️", "👍", "😂", "😮", "😢", "🙏"};

    /**
     * Unicode's emoji by group ({@code emoji.group.<key>}), from {@code emoji/emoji.txt} (made by native/emoji-list.py):
     * Emoji 15.0, what macOS 13.3 and Windows 11 draw, without the skin-tone variants.
     */
    static final Map<String, List<String>> CATEGORIES = load();

    private static Map<String, List<String>> load() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (InputStream in = EmojiPicker.class.getResourceAsStream("/app/unichat/emoji/emoji.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0) out.put("emoji.group." + line.substring(0, tab), List.of(line.substring(tab + 1).strip().split(" ")));
            }
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("emoji list missing", e);
        }
        return out;
    }

    /** One emoji; {@code library} may be null (no menu, no recents). */
    static Button button(String e, Consumer<String> onPick, MediaLibrary library, Consumer<String> toast) {
        Button b = new Button(e);
        b.getStyleClass().add("emoji-button");
        b.setAccessibleText(I18n.t("emoji.named", e));
        if (EmojiArt.inPieces(e)) drawn(b, e);
        Runnable use = () -> {
            onPick.accept(e);
            if (library != null) library.addRecent(MediaLibrary.emoji(e));
        };
        b.setOnAction(ev -> use.run());
        if (library != null) LibraryMenu.attach(b, library, MediaLibrary.emoji(e), use, toast, null);
        return b;
    }

    /** Hidden until its picture is ready; for good if there's none (better missing than in pieces). */
    private static void drawn(Button b, String e) {
        Nodes.show(b, false);
        EmojiArt.whenDrawn(e, img -> {
            if (img == null) return;
            javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(img);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.fitHeightProperty().bind(b.fontProperty().map(f -> f.getSize() * EmojiArt.FIT));
            b.setText(null);
            b.setGraphic(view);
            Nodes.show(b, true);
        });
    }

    /** Shows the picker anchored above {@code anchor}; {@code onPick} receives the emoji. */
    public static void show(Node anchor, Consumer<String> onPick) {
        popup(anchor, null, EmojiGrid.Mode.PLAIN, onPick);
    }

    /** To react: the ones reacted with most first, and the search box ready to type in. */
    static void showForReaction(Node anchor, MediaLibrary library, Consumer<String> onPick) {
        popup(anchor, library, EmojiGrid.Mode.REACTIONS, onPick);
    }

    private static void popup(Node anchor, MediaLibrary library, EmojiGrid.Mode mode, Consumer<String> onPick) {
        Popup popup = new Popup();
        popup.setAutoHide(true);
        EmojiGrid grid = new EmojiGrid(library, mode, e -> {
            onPick.accept(e);
            popup.hide();
        }, t -> { });
        grid.setPrefSize(340, 380);
        grid.getStylesheets().addAll(anchor.getScene().getStylesheets());
        VBox wrap = new VBox(grid);
        wrap.getStyleClass().add("popup-surface");
        wrap.getStyleClass().addAll(anchor.getScene().getRoot().getStyleClass());
        wrap.setStyle(anchor.getScene().getRoot().getStyle());
        popup.getContent().add(wrap);
        var b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, b.getMinX(), b.getMinY() - 390);
        grid.searchField().requestFocus();
    }
}
