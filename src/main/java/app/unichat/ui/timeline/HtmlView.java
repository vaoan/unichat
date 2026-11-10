package app.unichat.ui.timeline;

import app.unichat.ui.Theme;
import app.unichat.ui.emoji.EmojiInline;
import app.unichat.ui.media.MessageMedia;
import app.unichat.support.I18n;

import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Draws a message's HTML the way a browser would: tag by tag, knowing nothing of who wrote it. How it looks is its
 * author's business. What's unknown shows its contents, and what could act or reach out on its own (scripts, frames,
 * pictures outside our server, links that aren't web pages) doesn't get through.
 */
final class HtmlView {
    private static final Set<String> SKIPPED = Set.of("mx-reply", "script", "style", "head", "title", "meta", "link", "iframe",
            "object", "embed", "noscript", "template", "video", "audio");
    private static final Set<String> BLOCKS = Set.of("p", "div", "section", "article", "header", "footer", "main", "nav", "aside",
            "center", "details", "summary", "figure", "figcaption", "dl", "dt", "dd", "address", "caption");
    private static final double LIST_INDENT = 22;
    private static final double QUOTE_INSET = 20;
    private static final double PICTURE_MAX_HEIGHT = 360;
    private static final double INLINE_PICTURE_MAX = 64;
    private static final double EMOJI_SIZE = 20;
    /** Far deeper than any real message, far shallower than the UI thread's stack (and the scene graph's) can take. */
    private static final int MAX_DEPTH = 64;

    private final double available;
    private final MessageMedia media;
    private final Consumer<String> openLink;
    private int depth;

    private HtmlView(double available, MessageMedia media, Consumer<String> openLink) {
        this.available = available;
        this.media = media;
        this.openLink = openLink;
    }

    static Node of(String html, double width, MessageMedia media, Consumer<String> openLink) {
        VBox root = new VBox(4);
        root.getStyleClass().add("html");
        root.setMaxWidth(width);
        new HtmlView(width, media, openLink).contents(Jsoup.parseBodyFragment(html).body(), root, width, Look.PLAIN);
        return root;
    }

    /** Every web address in it, written out or behind a link, and what its video and audio tags play. */
    static List<String> links(String html) {
        Element body = Jsoup.parseBodyFragment(html).body();
        body.select("mx-reply").remove();
        Set<String> out = new LinkedHashSet<>();
        for (Element e : body.select("a[href], video[src], audio[src], source[src]")) {
            String url = e.hasAttr("href") ? e.attr("href") : e.attr("src");
            if (isWeb(url)) out.add(url);
        }
        body.select("a").remove();
        out.addAll(RichText.addresses(body.wholeText()));
        return List.copyOf(out);
    }

    private static boolean isWeb(String url) {
        return url.matches("(?i)https?://\\S+");
    }

    /** What's inherited from the tags around some text. */
    private record Look(boolean bold, boolean italic, boolean underline, boolean strike, boolean code, boolean pre, double scale, String color) {
        static final Look PLAIN = new Look(false, false, false, false, false, false, 1, null);

        Look bolder() { return new Look(true, italic, underline, strike, code, pre, scale, color); }
        Look slanted() { return new Look(bold, true, underline, strike, code, pre, scale, color); }
        Look underlined() { return new Look(bold, italic, true, strike, code, pre, scale, color); }
        Look struck() { return new Look(bold, italic, underline, true, code, pre, scale, color); }
        Look coded() { return new Look(bold, italic, underline, strike, true, pre, scale, color); }
        Look preformatted() { return new Look(bold, italic, underline, strike, true, true, scale, color); }
        Look scaled(double s) { return new Look(bold, italic, underline, strike, code, pre, s, color); }
        Look colored(String c) { return new Look(bold, italic, underline, strike, code, pre, scale, c); }
    }

    /** Blocks stack in {@code target}; text between them runs together in a flow, opened when the first of it arrives. */
    private final class Blocks {
        final VBox target;
        final double width;
        TextFlow run;

        Blocks(VBox target, double width) {
            this.target = target;
            this.width = width;
        }

        void inline(Node n) {
            if (run == null) {
                run = new TextFlow();
                run.getStyleClass().add("message-text");
                run.setLineSpacing(Theme.lineSpacing());
                run.setMaxWidth(width);
                target.getChildren().add(run);
            }
            run.getChildren().add(n);
        }

        void block(Node n) {
            run = null;
            target.getChildren().add(n);
        }
    }

    private void contents(Element parent, VBox target, double width, Look look) {
        walk(parent, new Blocks(target, width), look);
    }

    /** Each tag is one more call: past {@link #MAX_DEPTH} what's inside is just its text, so no message can overflow the stack. */
    private void walk(Element parent, Blocks b, Look look) {
        if (depth >= MAX_DEPTH) {
            String s = look.pre() ? parent.wholeText() : parent.text();
            if (!s.isBlank()) b.inline(styled(s, look));
            return;
        }
        depth++;
        try {
            for (org.jsoup.nodes.Node child : parent.childNodes()) {
                if (child instanceof TextNode t) text(t, b, look);
                else if (child instanceof Element e && !SKIPPED.contains(e.normalName())) element(e, b, look);
            }
        } finally {
            depth--;
        }
    }

    private void text(TextNode t, Blocks b, Look look) {
        String s = look.pre() ? t.getWholeText() : t.getWholeText().replaceAll("\\s+", " ");
        if (s.isBlank() && (b.run == null || !look.pre() && s.isEmpty())) return;
        for (RichText.Span span : RichText.addressSpans(s)) {
            if (span.kind() == RichText.Kind.LINK) b.inline(RichText.link(span.text(), span.text(), openLink, b.width));
            else for (javafx.scene.Node n : EmojiInline.nodes(span.text(), run -> styled(run, look))) b.inline(n);
        }
    }

    private Text styled(String s, Look look) {
        Text t = new Text(s);
        t.getStyleClass().add("msg-text");
        if (look.bold()) t.getStyleClass().add("bold");
        if (look.italic()) t.getStyleClass().add("italic");
        if (look.code()) t.getStyleClass().add("code");
        t.setUnderline(look.underline());
        t.setStrikethrough(look.strike());
        StringBuilder style = new StringBuilder();
        if (look.scale() != 1) style.append("-fx-font-size: ").append(look.scale()).append("em;");
        if (look.color() != null) style.append("-fx-fill: ").append(look.color()).append(';');
        if (!style.isEmpty()) t.setStyle(style.toString());
        return t;
    }

    private void element(Element e, Blocks b, Look look) {
        String tag = e.normalName();
        if (BLOCKS.contains(tag)) {
            nested(e, b, look, null);
            return;
        }
        switch (tag) {
            case "br" -> b.inline(styled("\n", look));
            case "b", "strong", "th" -> walk(e, b, look.bolder());
            case "i", "em", "cite", "dfn", "var" -> walk(e, b, look.slanted());
            case "u", "ins" -> walk(e, b, look.underlined());
            case "s", "del", "strike" -> walk(e, b, look.struck());
            case "code", "kbd", "samp", "tt" -> walk(e, b, look.coded());
            case "sub", "sup", "small" -> walk(e, b, look.scaled(0.8));
            case "h1" -> heading(e, b, look, 1.5);
            case "h2" -> heading(e, b, look, 1.3);
            case "h3" -> heading(e, b, look, 1.15);
            case "h4", "h5", "h6" -> heading(e, b, look, 1);
            case "pre" -> nested(e, b, look.preformatted(), "html-pre");
            case "blockquote" -> quote(e, b, look);
            case "ul", "ol" -> list(e, b, look, tag.equals("ol"));
            case "table" -> table(e, b, look);
            case "hr" -> b.block(new Separator());
            case "a" -> anchor(e, b, look);
            case "img" -> image(e, b, look);
            case "span", "font" -> span(e, b, look);
            default -> walk(e, b, look);
        }
    }

    private void nested(Element e, Blocks b, Look look, String style) {
        VBox box = new VBox(4);
        if (style != null) box.getStyleClass().add(style);
        contents(e, box, b.width, look);
        if (!box.getChildren().isEmpty()) b.block(box);
    }

    private void heading(Element e, Blocks b, Look look, double scale) {
        nested(e, b, look.bolder().scaled(scale), null);
    }

    private void quote(Element e, Blocks b, Look look) {
        VBox box = new VBox(6);
        box.getStyleClass().add("html-quote");
        String color = color(e.attr("background-color"), e.attr("data-mx-color"), e.attr("color"));
        if (color != null && !Theme.highContrast()) box.setStyle("-fx-border-color: transparent transparent transparent " + color + ";");
        box.setMaxWidth(b.width);
        contents(e, box, b.width - QUOTE_INSET, look);
        if (!box.getChildren().isEmpty()) b.block(box);
    }

    private void list(Element e, Blocks b, Look look, boolean numbered) {
        VBox box = new VBox(2);
        int n = 0;
        for (Element item : e.children()) {
            n++;
            VBox inner = new VBox(2);
            contents(item, inner, b.width - LIST_INDENT, look);
            Label mark = new Label(numbered ? n + "." : "•");
            mark.setMinWidth(javafx.scene.control.Control.USE_PREF_SIZE);
            mark.getStyleClass().add("html-mark");
            box.getChildren().add(new HBox(6, mark, inner));
        }
        b.block(box);
    }

    private void table(Element e, Blocks b, Look look) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("html-table");
        grid.setHgap(12);
        grid.setVgap(4);
        int rows = 0;
        int widest = e.select("tr").stream().mapToInt(r -> r.children().size()).max().orElse(1);
        double cell = (b.width - 12 * (widest - 1)) / Math.max(1, widest);
        for (Element row : e.select("tr")) {
            int col = 0;
            for (Element c : row.children()) {
                VBox inner = new VBox(2);
                inner.setMaxWidth(cell);
                contents(c, inner, cell, c.normalName().equals("th") ? look.bolder() : look);
                grid.add(inner, col++, rows);
            }
            rows++;
        }
        b.block(grid);
    }

    private void anchor(Element e, Blocks b, Look look) {
        String url = e.attr("href");
        if (!isWeb(url)) {
            walk(e, b, look);
            return;
        }
        String label = e.text().isBlank() ? altsOf(e) : e.text();
        b.inline(RichText.link(label.isBlank() ? url : label, url, openLink, b.width));
    }

    private static String altsOf(Element e) {
        StringBuilder s = new StringBuilder();
        for (Element img : e.select("img")) s.append(img.attr("alt"));
        return s.toString();
    }

    /** Only from our server: a picture from anywhere else would tell its site who read the message. */
    private void image(Element e, Blocks b, Look look) {
        String src = e.attr("src");
        if (!src.startsWith("mxc://")) {
            String alt = e.attr("alt");
            if (!alt.isBlank()) b.inline(styled(alt, look));
            return;
        }
        double h = number(e.attr("height"));
        if (e.hasAttr("data-mx-emoticon") || (h > 0 && h <= INLINE_PICTURE_MAX)) {
            b.inline(media.inlinePicture(src, h > 0 ? Math.min(h, INLINE_PICTURE_MAX) : EMOJI_SIZE, e.attr("alt")));
        } else {
            b.block(media.htmlPicture(src, b.width, PICTURE_MAX_HEIGHT));
        }
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void span(Element e, Blocks b, Look look) {
        if (e.hasAttr("data-mx-spoiler")) {
            spoiler(e, b, look);
            return;
        }
        String color = color(e.attr("data-mx-color"), e.attr("color"));
        walk(e, b, color == null ? look : look.colored(color));
    }

    /** Hidden until asked for, as whoever wrote it wanted. */
    private void spoiler(Element e, Blocks b, Look look) {
        String reason = e.attr("data-mx-spoiler");
        Hyperlink veil = new Hyperlink(reason.isBlank() ? I18n.t("html.spoiler") : I18n.t("html.spoilerReason", reason));
        veil.getStyleClass().addAll("msg-link", "html-spoiler");
        b.inline(veil);
        TextFlow flow = b.run;
        veil.setOnAction(a -> {
            VBox holder = new VBox();
            Blocks hidden = new Blocks(holder, b.width);
            walk(e, hidden, look);
            List<Node> shown = new ArrayList<>();
            holder.getChildren().forEach(n -> shown.addAll(n instanceof TextFlow f ? f.getChildren() : List.of(n)));
            int at = flow.getChildren().indexOf(veil);
            flow.getChildren().remove(at);
            flow.getChildren().addAll(at, List.copyOf(shown));
        });
    }

    /** The first that's a plain color: these go into a style, and whoever wrote them isn't to be trusted with that. */
    private static String color(String... candidates) {
        for (String c : candidates) if (c.matches("#[0-9A-Fa-f]{6}")) return c;
        return null;
    }
}
