package app.unichat.ui.timeline;

import app.unichat.model.Chat;
import app.unichat.model.LinkMedia;
import app.unichat.model.LinkPreview;
import app.unichat.ui.media.MessageMedia;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A link's card under its message, the way Telegram draws a web page: a bar in the accent colour, the site, the title
 * (the link itself), a few lines of text, and its picture or video below, or a small picture beside the text when the
 * page's is small. A GIF plays by itself. Every text is the page's (a stranger's): labels only, never markup.
 */
final class LinkPreviewCard {
    /** Below this on both sides a page's picture is an icon or a logo: beside the text, not over the whole card. */
    static final int SMALL_PICTURE = 400;
    private static final double THUMBNAIL = 64;
    private static final double MAX_MEDIA_HEIGHT = 360;
    /** The bar and the padding on both sides (`.link-preview` in app.css). */
    private static final double INSET = 3 + 10 + 10;
    private static final int DESCRIPTION_LINES = 6;

    private LinkPreviewCard() {}

    static Node of(LinkPreview p, double width, MessageMedia media, Consumer<String> openLink, Chat chat) {
        double inner = Math.max(80, width - INSET);
        VBox text = new VBox(2);
        if (!p.site().isEmpty()) text.getChildren().add(label(p.site(), "link-preview-site", inner));
        if (!p.title().isEmpty()) {
            var title = RichText.link(p.title(), p.url(), openLink, inner);
            title.setText(p.title());
            title.setWrapText(true);
            title.getStyleClass().add("link-preview-title");
            text.getChildren().add(title);
        }
        if (!p.description().isEmpty()) {
            Label d = label(p.description(), "link-preview-text", inner);
            d.setStyle("-fx-max-height: " + DESCRIPTION_LINES * 1.4 + "em;");
            text.getChildren().add(d);
        }
        VBox card = new VBox(6);
        card.getStyleClass().add("link-preview");
        card.setMaxWidth(width);
        String thumbnail = thumbnailed(p) ? p.image() : null; // thumbnailed: there is one
        if (thumbnail != null) {
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMaxWidth(inner - THUMBNAIL - 8);
            HBox row = new HBox(8, text, media.htmlPicture(thumbnail, THUMBNAIL, THUMBNAIL, 1));
            row.setAlignment(Pos.TOP_LEFT);
            card.getChildren().add(row);
        } else {
            if (!text.getChildren().isEmpty()) card.getChildren().add(text);
            Node shown = mediaOf(p, inner, media, chat);
            if (shown != null) card.getChildren().add(shown);
        }
        return card;
    }

    /** A small picture with no video goes beside the text, as Telegram does with a site's logo. */
    static boolean thumbnailed(LinkPreview p) {
        boolean small = p.width() > 0 && p.height() > 0 && p.width() < SMALL_PICTURE && p.height() < SMALL_PICTURE;
        return p.video() == null && p.image() != null && small && !(p.title().isEmpty() && p.description().isEmpty());
    }

    private static @Nullable Node mediaOf(LinkPreview p, double width, MessageMedia media, Chat chat) {
        LinkMedia v = p.video();
        if (v != null) return media.linkPlayer(v, p.width(), p.height(), p.gif(), chat);
        String image = p.image();
        if (image == null) return null;
        double shape = p.width() > 0 && p.height() > 0 ? (double) p.width() / p.height() : 0;
        return media.htmlPicture(image, width, MAX_MEDIA_HEIGHT, shape);
    }

    private static Label label(String text, String style, double width) {
        Label l = new Label(text);
        l.getStyleClass().add(style);
        l.setWrapText(true);
        l.setMaxWidth(width);
        return l;
    }
}
