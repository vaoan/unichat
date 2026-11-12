package app.unichat.ui.timeline;

import app.unichat.ui.FxCoalescer;
import app.unichat.ui.Icons;
import app.unichat.ui.TaskBar;
import app.unichat.ui.Nodes;
import app.unichat.ui.media.AnimatedImage;
import app.unichat.model.Chat;
import app.unichat.model.MimeTypes;
import app.unichat.service.ChatReader;
import app.unichat.service.UploadQueue;
import app.unichat.support.I18n;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * On top of the window while a batch of files goes up, like the audio bar: which file (a preview, or its icon), where
 * to (a link to the chat), how far, and a way to drop what's left. Stays after the end to say how it went.
 */
public final class UploadBar extends TaskBar {
    private static final int THUMB = LEAD;
    /** A batch that went fine says so for this long, then the bar goes away; one with failures waits to be closed. */
    private static final Duration DONE_SHOWN = Duration.seconds(5);

    private final UploadQueue queue;
    private final ChatReader chats;
    /** Counts down the seconds to the next try. */
    private final javafx.animation.Timeline tick = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(Duration.seconds(1), e -> refresh()));
    private final ImageView preview = new ImageView();
    private final Button cancel;
    private final Button close;
    /** Progress comes many times a second from the upload's thread: one redraw per frame is plenty. */
    private final FxCoalescer redraw = new FxCoalescer(this::refresh);
    private final Runnable listener = redraw::request;
    private final PauseTransition hideLater = new PauseTransition(DONE_SHOWN);
    private Path shownFile;
    private String chatId;

    public UploadBar(UploadQueue queue, ChatReader chats, Consumer<String> openChat) {
        super("upload-bar");
        this.queue = queue;
        this.chats = chats;
        preview.setFitWidth(THUMB);
        preview.setFitHeight(THUMB);
        preview.setPreserveRatio(true);
        where.setOnAction(e -> {
            if (chatId != null) openChat.accept(chatId);
        });
        tick.setCycleCount(javafx.animation.Animation.INDEFINITE);
        cancel = Icons.iconButton(Icons.CLOSE, I18n.t("uploadBar.cancel"), queue::cancel);
        close = Icons.iconButton(Icons.CLOSE, I18n.t("uploadBar.close"), queue::dismiss);
        hideLater.setOnFinished(e -> queue.dismiss());
        actions(cancel, close);
        queue.addListener(listener);
        refresh();
    }

    /** The queue outlives this bar (the window is rebuilt on a language change). */
    public void dispose() {
        queue.removeListener(listener);
        hideLater.stop();
        tick.stop();
    }

    private void refresh() {
        UploadQueue.Status s = queue.status();
        Nodes.show(this, !s.idle());
        if (s.idle()) {
            hideLater.stop();
            shownFile = null;
            return;
        }
        boolean done = s.finished();
        Nodes.show(cancel, !done);
        Nodes.show(close, done);
        bar.setProgress(s.overall());
        big.setText(percent(s.overall()));
        if (done) {
            String text = I18n.t("uploadBar.done", s.sent(), s.total());
            if (s.failed() > 0) text += " · " + I18n.n("uploadBar.failed", s.failed());
            what.setText(text);
            bar.setAccessibleText(text);
            detail.setText("");
            where.setText("");
            small.setText("");
            stage.setText("");
            tidy();
            tick.stop();
            shownFile = null;
            lead.getChildren().setAll(Icons.icon(s.failed() > 0 ? Icons.ERROR : Icons.CHECK, 22));
            if (s.failed() == 0 && hideLater.getStatus() != javafx.animation.Animation.Status.RUNNING) hideLater.play();
            return;
        }
        hideLater.stop();
        UploadQueue.Upload u = s.current();
        String text = I18n.t("uploadBar.sending", s.position(), s.total());
        if (s.sent() > 0) text += " · " + I18n.n("uploadBar.sentSoFar", s.sent());
        if (s.failed() > 0) text += " · " + I18n.n("uploadBar.failed", s.failed());
        what.setText(text);
        detail.setText(u.file().getFileName().toString());
        Chat chat = chats.chat(u.chatId());
        String title = chat == null || chat.title() == null ? "" : chat.title();
        chatId = u.chatId();
        where.setText(I18n.t("uploadBar.where", title));
        where.setAccessibleText(I18n.t("uploadBar.goToChat", title));
        String network = chat == null ? "" : chat.network().displayName();
        stage.setText(stage(s, network, System.currentTimeMillis()));
        boolean counting = s.phase() == UploadQueue.Phase.RETRYING;
        if (counting && tick.getStatus() != javafx.animation.Animation.Status.RUNNING) tick.play();
        if (!counting) tick.stop();
        small.setText(eta(s.etaMs()));
        tidy();
        bar.setAccessibleText(I18n.t("uploadBar.a11y", s.position(), s.total(), title, (int) Math.floor(s.overall() * 100))
                + ". " + stage.getText() + (s.etaMs() >= 0 ? ". " + small.getText() : ""));
        if (!u.file().equals(shownFile)) showPreview(u.file());
    }

    /** The picture itself when it's one Unichat can read; otherwise the icon of what it is. */
    private void showPreview(Path f) {
        shownFile = f;
        String kind = kind(f);
        lead.getChildren().setAll(Icons.icon("video".equals(kind) ? Icons.PLAY : Icons.FILE, 22));
        if (!"image".equals(kind)) return;
        CompletableFuture.supplyAsync(() -> {
            try {
                Image img = AnimatedImage.still(f, THUMB * 2, THUMB * 2);
                return img.isError() ? null : img;
            } catch (Exception e) {
                return null;
            }
        }, AnimatedImage.DECODER).thenAccept(img -> Platform.runLater(() -> {
            if (img == null || !f.equals(shownFile)) return;
            preview.setImage(img);
            lead.getChildren().setAll(preview);
        }));
    }

    /** What happens to the current file now, as the bar's second line says it. */
    static String stage(UploadQueue.Status s, String network, long now) {
        String attempt = s.retries() > 0 ? " · " + I18n.t("uploadBar.attempt", s.retries() + 1) : "";
        return switch (s.phase()) {
            case UPLOADING -> I18n.t("uploadBar.stage.uploading", (int) Math.floor(s.progress() * 100)) + attempt;
            case DELIVERING -> I18n.t("uploadBar.stage.delivering", network.isEmpty() ? I18n.t("uploadBar.theNetwork") : network) + attempt;
            case RETRYING -> I18n.n("uploadBar.retrying", s.retries()) + " · "
                    + I18n.t("uploadBar.stage.retryIn", Math.max(0, (s.retryAt() - now + 999) / 1000));
        };
    }

    /** "image", "video" or "file", by what the system says it is, else by its name. */
    static String kind(Path f) {
        String mime = MimeTypes.of(f);
        return mime.startsWith("image/") ? "image" : mime.startsWith("video/") ? "video" : "file";
    }
}
