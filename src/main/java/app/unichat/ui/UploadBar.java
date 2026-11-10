package app.unichat.ui;

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
final class UploadBar extends HBox {
    private static final int THUMB = 36;
    /** A batch that went fine says so for this long, then the bar goes away; one with failures waits to be closed. */
    private static final Duration DONE_SHOWN = Duration.seconds(5);

    private final UploadQueue queue;
    private final ChatReader chats;
    private final Label what = new Label();
    private final Label file = new Label();
    private final Hyperlink where = new Hyperlink();
    private final ProgressBar bar = new ProgressBar(0);
    /** The whole batch as a number ("37 %"), beside the bar, and the time left. */
    private final Label percent = new Label();
    private final Label eta = new Label();
    /** What's happening to the current file: going up, waiting for the network, or waiting to try again. */
    private final Label stage = new Label();
    /** Counts down the seconds to the next try. */
    private final javafx.animation.Timeline tick = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(Duration.seconds(1), e -> refresh()));
    private final ImageView preview = new ImageView();
    private final StackPane thumb = new StackPane();
    private final Button cancel;
    private final Button close;
    /** Progress comes many times a second from the upload's thread: one redraw per frame is plenty. */
    private final FxCoalescer redraw = new FxCoalescer(this::refresh);
    private final Runnable listener = redraw::request;
    private final PauseTransition hideLater = new PauseTransition(DONE_SHOWN);
    private Path shownFile;
    private String chatId;

    UploadBar(UploadQueue queue, ChatReader chats, Consumer<String> openChat) {
        super(10);
        this.queue = queue;
        this.chats = chats;
        getStyleClass().addAll("audio-bar", "upload-bar");
        setAlignment(Pos.CENTER_LEFT);

        preview.setFitWidth(THUMB);
        preview.setFitHeight(THUMB);
        preview.setPreserveRatio(true);
        thumb.getStyleClass().add("upload-thumb");
        thumb.setMinSize(THUMB, THUMB);
        thumb.setPrefSize(THUMB, THUMB);
        thumb.setMaxSize(THUMB, THUMB);

        what.getStyleClass().add("audio-who");
        what.setMinWidth(Region.USE_PREF_SIZE);
        file.getStyleClass().add("audio-time");
        file.setMaxWidth(260);
        where.getStyleClass().add("upload-where");
        where.setOnAction(e -> {
            if (chatId != null) openChat.accept(chatId);
        });
        stage.getStyleClass().add("audio-time");
        // Three columns that never push each other (the names and the stage change with every file):
        // what and where at a fixed width | the bar, and under it the stage, taking the rest | the numbers at a fixed width.
        HBox where2 = new HBox(4, file, where);
        where2.setAlignment(Pos.CENTER_LEFT);
        file.setMinWidth(0);
        where.setMinWidth(0);
        VBox texts = new VBox(0, what, where2);
        texts.setAlignment(Pos.CENTER_LEFT);
        what.setMinWidth(0);
        fixedEm(texts, 24);
        percent.getStyleClass().add("upload-percent");
        eta.getStyleClass().add("audio-time");
        VBox numbers = new VBox(0, percent, eta);
        numbers.setAlignment(Pos.CENTER_LEFT);
        fixedEm(numbers, 10);
        tick.setCycleCount(javafx.animation.Animation.INDEFINITE);

        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setMinWidth(80);
        stage.setMinWidth(0);
        VBox middle = new VBox(3, bar, stage);
        middle.setAlignment(Pos.CENTER_LEFT);
        middle.setMinWidth(80);
        HBox.setHgrow(middle, Priority.ALWAYS);
        cancel = Icons.iconButton(Icons.CLOSE, I18n.t("uploadBar.cancel"), queue::cancel);
        close = Icons.iconButton(Icons.CLOSE, I18n.t("uploadBar.close"), queue::dismiss);
        hideLater.setOnFinished(e -> queue.dismiss());
        getChildren().addAll(thumb, texts, middle, numbers, cancel, close);
        queue.addListener(listener);
        refresh();
    }

    /** Exactly {@code em} times the text size wide, following the text size as it changes. */
    private void fixedEm(Region r, double em) {
        javafx.beans.binding.DoubleBinding w = javafx.beans.binding.Bindings.createDoubleBinding(
                () -> em * what.getFont().getSize(), what.fontProperty());
        r.minWidthProperty().bind(w);
        r.prefWidthProperty().bind(w);
        r.maxWidthProperty().bind(w);
    }

    /** The queue outlives this bar (the window is rebuilt on a language change). */
    void dispose() {
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
        percent.setText(percent(s.overall()));
        if (done) {
            String text = I18n.t("uploadBar.done", s.sent(), s.total());
            if (s.failed() > 0) text += " · " + I18n.n("uploadBar.failed", s.failed());
            what.setText(text);
            bar.setAccessibleText(text);
            Nodes.show(file, false);
            Nodes.show(eta, false);
            stage.setText("");
            tick.stop();
            shownFile = null;
            thumb.getChildren().setAll(Icons.icon(s.failed() > 0 ? Icons.ERROR : Icons.CHECK, 22));
            if (s.failed() == 0 && hideLater.getStatus() != javafx.animation.Animation.Status.RUNNING) hideLater.play();
            return;
        }
        hideLater.stop();
        UploadQueue.Upload u = s.current();
        String text = I18n.t("uploadBar.sending", s.position(), s.total());
        if (s.sent() > 0) text += " · " + I18n.n("uploadBar.sentSoFar", s.sent());
        if (s.failed() > 0) text += " · " + I18n.n("uploadBar.failed", s.failed());
        what.setText(text);
        Nodes.show(file, true);
        file.setText(u.file().getFileName().toString());
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
        eta.setText(eta(s.etaMs()));
        Nodes.show(eta, s.etaMs() >= 0);
        bar.setAccessibleText(I18n.t("uploadBar.a11y", s.position(), s.total(), title, (int) Math.floor(s.overall() * 100))
                + ". " + stage.getText() + (s.etaMs() >= 0 ? ". " + eta.getText() : ""));
        if (!u.file().equals(shownFile)) showPreview(u.file());
    }

    /** The picture itself when it's one Unichat can read; otherwise the icon of what it is. */
    private void showPreview(Path f) {
        shownFile = f;
        String kind = kind(f);
        thumb.getChildren().setAll(Icons.icon("video".equals(kind) ? Icons.PLAY : Icons.FILE, 22));
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
            thumb.getChildren().setAll(preview);
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

    /** "faltan ~1 h 20 min", "faltan ~12 min", "falta menos de un minuto". */
    static String eta(long ms) {
        if (ms < 0) return "";
        long min = Math.round(ms / 60_000.0);
        if (min < 1) return I18n.t("uploadBar.etaSoon");
        return min < 60 ? I18n.t("uploadBar.etaMinutes", min) : I18n.t("uploadBar.etaHours", min / 60, min % 60);
    }

    /** "37 %": whole numbers, and 100 only when everything is done (99.6 % reads 99 %). */
    static String percent(double fraction) {
        return (int) Math.floor(Math.max(0, Math.min(1, fraction)) * 100) + " %";
    }

    /** "image", "video" or "file", by what the system says it is, else by its name. */
    static String kind(Path f) {
        String mime = MimeTypes.of(f);
        return mime.startsWith("image/") ? "image" : mime.startsWith("video/") ? "video" : "file";
    }
}
