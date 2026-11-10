package app.unichat.ui.emoji;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A message's text for a {@code TextFlow}, with the emoji JavaFX draws in pieces (flags, joined ones, skin tones)
 * as pictures at the text's size ({@link EmojiArt}): the runs between them stay text, styled by {@code text}.
 */
public final class EmojiInline {
    private EmojiInline() {}

    public static List<Node> nodes(String s, Function<String, Node> text) {
        List<Node> out = new ArrayList<>();
        for (EmojiArt.Segment seg : EmojiArt.segments(s)) {
            if (seg.picture() == null) {
                out.add(text.apply(seg.text()));
                continue;
            }
            Label l = new Label();
            l.getStyleClass().add("msg-emoji");
            ImageView v = new ImageView(seg.picture());
            v.setPreserveRatio(true);
            v.setSmooth(true);
            v.fitHeightProperty().bind(l.fontProperty().map(f -> f.getSize() * EmojiArt.FIT));
            l.setGraphic(v);
            l.setAccessibleText(seg.text());
            l.setUserData(seg.text()); // what copying the message gives back
            out.add(l);
        }
        return out;
    }
}
