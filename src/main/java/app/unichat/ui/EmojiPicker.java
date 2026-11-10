package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import app.unichat.support.I18n;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Popup emoji grid by category; also provides the quick-reaction set. */
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

    /** Every category as a grid of emoji buttons ({@code onPick} gets the emoji); used by the popup and the side panel. */
    static VBox grid(Consumer<String> onPick, double wrap) {
        return grid(onPick, wrap, null, null);
    }

    /** With a library: right-click adds to or removes from the favourites, and a pick counts as recent. */
    static VBox grid(Consumer<String> onPick, double wrap, MediaLibrary library, Consumer<String> toast) {
        VBox content = new VBox(6);
        content.setPadding(new Insets(10));
        for (Map.Entry<String, List<String>> cat : CATEGORIES.entrySet()) {
            Label title = new Label(I18n.t(cat.getKey()));
            title.getStyleClass().add("emoji-category");
            FlowPane grid = new FlowPane(2, 2);
            grid.setPrefWrapLength(wrap);
            for (String e : cat.getValue()) grid.getChildren().add(button(e, onPick, library, toast));
            content.getChildren().addAll(title, grid);
        }
        return content;
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
            view.fitHeightProperty().bind(b.fontProperty().map(f -> f.getSize() * 1.2));
            b.setText(null);
            b.setGraphic(view);
            Nodes.show(b, true);
        });
    }

    /** Shows the picker anchored above {@code anchor}; {@code onPick} receives the emoji. */
    public static void show(Node anchor, Consumer<String> onPick) {
        Popup popup = new Popup();
        popup.setAutoHide(true);
        VBox content = grid(e -> {
            onPick.accept(e);
            popup.hide();
        }, 300);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(340, 320);
        scroll.getStyleClass().add("emoji-picker");
        scroll.getStylesheets().addAll(anchor.getScene().getStylesheets());
        VBox wrap = new VBox(scroll);
        wrap.getStyleClass().add("popup-surface");
        wrap.getStyleClass().addAll(anchor.getScene().getRoot().getStyleClass());
        wrap.setStyle(anchor.getScene().getRoot().getStyle());
        popup.getContent().add(wrap);
        var b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, b.getMinX(), b.getMinY() - 330);
    }
}
