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
    /** A joined emoji is as wide as a plain one; its parts side by side are about twice. */
    private static final double SAME_WIDTH = 1.3;

    private static Set<String> pieces;
    private static CompletableFuture<Map<String, Image>> pictures;

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
        java.awt.Font font = new java.awt.Font(family, java.awt.Font.PLAIN, PX * 3 / 4);
        Map<String, Image> out = new HashMap<>();
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        FontRenderContext frc = probe.createGraphics().getFontRenderContext();
        float plain = new TextLayout("😀", font, frc).getAdvance();
        for (String e : emoji) {
            TextLayout t = new TextLayout(e, font, frc);
            if (t.getAdvance() > plain * SAME_WIDTH) continue;
            BufferedImage img = new BufferedImage(PX, PX, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            t.draw(g, (PX - t.getAdvance()) / 2, (PX + t.getAscent() - t.getDescent()) / 2);
            g.dispose();
            out.put(e, SwingFXUtils.toFXImage(img, null));
        }
        return out;
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
