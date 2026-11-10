package app.unichat.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Emoji JavaFX can't draw as one picture: it lays out flags (🇨🇴), keycaps (#️⃣) and joined sequences (👨‍🦰, ❤️‍🔥)
 * as their separate parts, 490 of Emoji 15.0's 1870 on a Mac. Java2D joins them with the same emoji font, so those are
 * drawn there as pictures, off the UI thread and once per run; one Java2D can't join either (Windows' font has no
 * flags) is left out rather than shown in pieces. The button still writes the emoji itself.
 */
final class EmojiArt {
    private static final List<String> FONTS = List.of("Apple Color Emoji", "Segoe UI Emoji", "Noto Color Emoji");
    /** Drawn this big, shown at the text's size: sharp on a Retina screen. */
    private static final int PX = 64;
    /** Shown this many times the text's size: the picture has a margin around the drawing, the text glyph doesn't. */
    static final double FIT = 1.35;
    /** A joined emoji is as wide as a plain one; its parts side by side are about twice. */
    private static final double SAME_WIDTH = 1.3;

    private static Set<String> pieces;
    private static CompletableFuture<Map<String, Image>> pictures;
    /** Emoji met in messages (skin tones included, not only the list's): their picture, or none when JavaFX draws them whole. */
    private static final Map<String, java.util.Optional<Image>> met = new HashMap<>();

    /** A run of a message's text, or one emoji in it to show as {@code picture}. */
    record Segment(String text, Image picture) {}

    private EmojiArt() {}

    /** Whether JavaFX draws {@code e} in pieces. On the FX thread. */
    static boolean inPieces(String e) {
        if (pieces == null) pieces = measure();
        return pieces.contains(e);
    }

    /** The pictures of those drawn in pieces, for the ones Java2D could join; started on first use. On the FX thread. */
    static CompletableFuture<Map<String, Image>> pictures() {
        if (pictures == null) {
            if (pieces == null) pieces = measure();
            Set<String> todo = Set.copyOf(pieces);
            String family = family();
            pictures = family == null || todo.isEmpty() ? CompletableFuture.completedFuture(Map.of())
                    : CompletableFuture.supplyAsync(() -> draw(todo, family));
        }
        return pictures;
    }

    private static Set<String> measure() {
        String family = family();
        if (family == null) return Set.of();
        Font f = Font.font(family, 20);
        double plain = width("😀", f);
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (List<String> group : EmojiPicker.CATEGORIES.values()) {
            for (String e : group) if (width(e, f) > plain * SAME_WIDTH) out.add(e);
        }
        return out;
    }

    private static double width(String s, Font f) {
        Text t = new Text(s);
        t.setFont(f);
        return t.getLayoutBounds().getWidth();
    }

    private static Map<String, Image> draw(Set<String> emoji, String family) {
        Map<String, Image> out = new HashMap<>();
        for (String e : emoji) {
            Image img = drawOne(e, family);
            if (img != null) out.put(e, img);
        }
        return out;
    }

    /** {@code e} drawn by Java2D, or null when it can't join it either. Offscreen: any thread. */
    private static Image drawOne(String e, String family) {
        java.awt.Font font = new java.awt.Font(family, java.awt.Font.PLAIN, PX * 3 / 4);
        FontRenderContext frc = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().getFontRenderContext();
        TextLayout t = new TextLayout(e, font, frc);
        if (t.getAdvance() > new TextLayout("😀", font, frc).getAdvance() * SAME_WIDTH) return null;
        BufferedImage img = new BufferedImage(PX, PX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        t.draw(g, (PX - t.getAdvance()) / 2, (PX + t.getAscent() - t.getDescent()) / 2);
        g.dispose();
        return SwingFXUtils.toFXImage(img, null);
    }

    /**
     * {@code text} as runs of text and the emoji in it JavaFX would draw in pieces, each with its picture. Every
     * emoji of more than one character (flags, keycaps, joined ones, skin tones) is measured once. On the FX thread.
     */
    static List<Segment> segments(String text) {
        List<Segment> out = new java.util.ArrayList<>();
        java.text.BreakIterator chars = java.text.BreakIterator.getCharacterInstance();
        chars.setText(text);
        int runStart = 0;
        for (int start = chars.first(), end = chars.next(); end != java.text.BreakIterator.DONE; start = end, end = chars.next()) {
            Image picture = picture(text.substring(start, end));
            if (picture == null) continue;
            if (start > runStart) out.add(new Segment(text.substring(runStart, start), null));
            out.add(new Segment(text.substring(start, end), picture));
            runStart = end;
        }
        if (runStart < text.length() || out.isEmpty()) out.add(new Segment(text.substring(runStart), null));
        return out;
    }

    /** The picture for one character as a person sees it, if it's an emoji JavaFX would draw in pieces. */
    private static Image picture(String cluster) {
        if (cluster.codePointCount(0, cluster.length()) < 2 || !Character.isEmoji(cluster.codePointAt(0))) return null;
        return met.computeIfAbsent(cluster, c -> {
            String family = family();
            if (family == null || width(c, Font.font(family, 20)) <= width("😀", Font.font(family, 20)) * SAME_WIDTH) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.ofNullable(drawOne(c, family));
        }).orElse(null);
    }

    private static String family() {
        List<String> installed = Font.getFamilies();
        return FONTS.stream().filter(installed::contains).findFirst().orElse(null);
    }

    /** Runs {@code then} on the FX thread with the picture of {@code e}, or null when it can't be shown. */
    static void whenDrawn(String e, java.util.function.Consumer<Image> then) {
        pictures().thenAccept(m -> Platform.runLater(() -> then.accept(m.get(e))));
    }
}
