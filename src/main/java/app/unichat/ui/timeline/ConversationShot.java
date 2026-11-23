package app.unichat.ui.timeline;

import app.unichat.model.Message;
import app.unichat.support.I18n;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A picture of a stretch of a chat as it looks: the chat's header (its picture and name), the stretch's messages drawn as
 * the chat draws them (names, times, reactions, quotes, pictures), with their days, and a footer if wanted. Drawn off
 * screen in a scene of its own with the window's look (its theme and text size), so it doesn't depend on what's scrolled
 * into view; it waits for the pictures in it to load (as long as {@code wait} at most: one that can't shows as it does
 * in the chat). At twice the size, as a phone's screen shows it.
 */
final class ConversationShot {
    /** How wide it's drawn, in points: a phone's screen, so it reads there as the chat does (its messages fit it). */
    static final double WIDTH = 420;
    /**
     * The most it draws: a stretch longer is refused, said, before it's made. One picture of a thousand messages (or one
     * stranger's message of 60 000 lines) at 2x is gigabytes: the window's heap would run out (`ui/media/CLAUDE.md`).
     */
    static final int MAX_MESSAGES = 400, MAX_HEIGHT_PX = 16_000;
    /** Pixels a point: sharp on a phone's screen and a retina one. */
    static final double SCALE = 2;
    /** How often it looks whether its pictures arrived. */
    private static final long LOOK_MS = 100;

    /** The whole picture, and where the header and each message end in it (in its pixels), for cutting it. */
    record Shot(Image whole, List<Integer> ends) {}

    private ConversationShot() {}

    /**
     * Draws {@code messages} under {@code header} ({@code rowNode} draws a message as the chat does), {@code width}
     * wide, with {@code look}'s look; on the FX thread. Done once the pictures loaded or {@code wait} passed.
     */
    static CompletableFuture<Shot> take(Node header, List<Message> messages, Function<ThreadRows.MessageRow, Node> rowNode,
                                        Scene look, double width, boolean footer, Duration wait) {
        if (messages.size() > MAX_MESSAGES) return CompletableFuture.failedFuture(new TooLong());
        Node top = header;
        VBox rows = new VBox();
        rows.getStyleClass().addAll("timeline", "shot-rows");
        for (ThreadRows.Row r : ThreadRows.build(messages, ZoneId.systemDefault())) {
            Node n = r instanceof ThreadRows.MessageRow mr ? rowNode.apply(mr) : MessageRowView.dayRow((ThreadRows.DayRow) r);
            HBox cell = new HBox(n);
            HBox.setHgrow(n, javafx.scene.layout.Priority.ALWAYS);
            cell.getStyleClass().add("shot-row");
            if (r instanceof ThreadRows.MessageRow) cell.setUserData(true); // a piece may end after it, not after a day's line
            rows.getChildren().add(cell);
        }
        VBox root = new VBox(top, rows);
        if (footer) {
            Label f = new Label(I18n.t("capture.footer", DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", I18n.locale())
                    .format(Instant.now().atZone(ZoneId.systemDefault()))));
            f.getStyleClass().add("shot-footer");
            HBox line = new HBox(f);
            line.setAlignment(Pos.CENTER);
            root.getChildren().add(line);
        }
        root.getStyleClass().add("shot");
        root.setPrefWidth(width);
        Scene scene = new Scene(new javafx.scene.Group(root));
        scene.getStylesheets().setAll(look.getStylesheets());
        Parent lookRoot = look.getRoot();
        scene.getRoot().getStyleClass().addAll(lookRoot.getStyleClass()); // the theme (dark, contrast) and the text size
        scene.getRoot().setStyle(lookRoot.getStyle());
        CompletableFuture<Shot> done = new CompletableFuture<>();
        long until = System.nanoTime() + wait.toNanos();
        whenLoaded(root, until, () -> {
            try {
                done.complete(snap(root, top, rows));
            } catch (TooLong e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    /** Runs {@code then} on the FX thread once nothing in {@code root} is loading, or at {@code until}. */
    private static void whenLoaded(Region root, long until, Runnable then) {
        laidOut(root);
        if (root.lookup(".media-loading") == null || System.nanoTime() >= until) {
            then.run();
            return;
        }
        CompletableFuture.delayedExecutor(LOOK_MS, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> whenLoaded(root, until, then)));
    }

    @SuppressFBWarnings(value = "RC_REF_COMPARISON_BAD_PRACTICE_BOOLEAN", justification = "user data set to Boolean.TRUE by our own code as a mark: identity is the check")
    private static Shot snap(VBox root, Node header, VBox rows) {
        laidOut(root);
        if (px(root.getHeight()) > MAX_HEIGHT_PX) throw new TooLong(); // measured before a pixel is made
        List<Integer> ends = new ArrayList<>();
        ends.add(px(header.getBoundsInParent().getMaxY()));
        for (Node r : rows.getChildren()) {
            if (r.getUserData() == Boolean.TRUE) ends.add(px(rows.getBoundsInParent().getMinY() + r.getBoundsInParent().getMaxY()));
        }
        return new Shot(root.snapshot(scaled(), null), ends);
    }

    /**
     * {@code root} sized here, its width and the height it needs at that width, then laid out: inside a group, laying it
     * out left it at its old size (the height checked against the limit was stale), and the group's own sizing measured
     * its texts on one line (a dozen messages came out a few lines tall).
     */
    private static void laidOut(Region root) {
        root.applyCss();
        double w = root.getPrefWidth();
        root.resize(w, root.prefHeight(w));
        root.layout();
    }

    /** Too long a stretch to draw, said in the person's words. */
    static final class TooLong extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        TooLong() {
            super(I18n.t("capture.tooLong", MAX_MESSAGES));
        }
    }

    /**
     * {@code image} {@code width} pixels wide, for looking at: a long capture shown whole was past what a graphics card
     * takes as one picture (8192 px with Direct3D) and showed nothing.
     */
    static Image small(Image image, int width) {
        double k = image.getWidth() / width;
        int height = Math.max(1, (int) (image.getHeight() / k));
        WritableImage out = new WritableImage(width, height);
        PixelReader in = image.getPixelReader();
        PixelWriter to = out.getPixelWriter();
        for (int y = 0; y < height; y++) {
            int sy = Math.min((int) (y * k), (int) image.getHeight() - 1);
            for (int x = 0; x < width; x++) to.setArgb(x, y, in.getArgb(Math.min((int) (x * k), (int) image.getWidth() - 1), sy));
        }
        return out;
    }

    /** {@code shot} cut into pieces {@code height} pixels tall, between messages; the last one filled up with its background. */
    static List<Image> pieces(Shot shot, int height) {
        Image whole = shot.whole();
        int w = (int) whole.getWidth(), total = (int) whole.getHeight();
        PixelReader in = whole.getPixelReader();
        Color background = in.getColor(0, total - 1);
        List<Image> out = new ArrayList<>();
        for (ShotPieces.Piece p : ShotPieces.cut(shot.ends(), total, height)) {
            WritableImage piece = new WritableImage(w, height);
            PixelWriter to = piece.getPixelWriter();
            to.setPixels(0, 0, w, p.bottom() - p.top(), in, 0, p.top());
            for (int y = p.bottom() - p.top(); y < height; y++) for (int x = 0; x < w; x++) to.setColor(x, y, background);
            out.add(piece);
        }
        return out;
    }

    /** A phone's screen (9:16) for a picture {@code width} pixels wide. */
    static int phoneHeight(int width) {
        return width * 16 / 9;
    }

    private static int px(double points) {
        return (int) Math.round(points * SCALE);
    }

    private static SnapshotParameters scaled() {
        SnapshotParameters p = new SnapshotParameters();
        p.setTransform(new Scale(SCALE, SCALE));
        return p;
    }
}
