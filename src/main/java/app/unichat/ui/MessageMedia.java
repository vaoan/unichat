package app.unichat.ui;

import app.unichat.model.Chat;
import app.unichat.support.I18n;
import app.unichat.video.WebmStickers;
import javafx.scene.control.ProgressIndicator;
import app.unichat.service.MediaSource;
import app.unichat.model.MediaLibrary;
import app.unichat.model.LinkMedia;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.stt.Transcripts;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.scene.shape.Rectangle;
import java.awt.Desktop;
import java.util.function.Consumer;
import javafx.scene.Scene;
import java.util.function.Supplier;

/** What a message shows for its media: images and stickers, file chips, voice note transcripts. */
final class MessageMedia {
    private static final double AUDIO_WIDTH = 240;

    private final MediaSource svc;
    private final Consumer<String> openLink;
    private final Consumer<String> toast;
    private final MediaLibrary library;
    private final Supplier<Scene> scene;
    private final AudioPlayback audio;

    MessageMedia(MediaSource svc, Consumer<String> openLink, Consumer<String> toast, MediaLibrary library, Supplier<Scene> scene, AudioPlayback audio) {
        this.svc = svc;
        this.openLink = openLink;
        this.toast = toast;
        this.library = library;
        this.scene = scene;
        this.audio = audio;
    }

    private static final double DEFAULT_SHAPE = 16.0 / 9;
    private static final int MAX_LINKS_OPENED = 5;
    private static final int MAX_LINK_PLAYERS = 3;
    /** Width over height of the pictures seen so far in messages' HTML. */
    private final java.util.Map<String, Double> pictureShapes = new java.util.HashMap<>();

    Node imageNode(Message m) {
        boolean sticker = m.type() == MessageType.STICKER;
        MediaSizes.Size size = sticker ? MediaSizes.sticker(m.width(), m.height()) : MediaSizes.media(m.width(), m.height());
        double dw = size.width(), dh = size.height();
        StackPane box = mediaBox(dw, dh, sticker);
        ImageView view = new ImageView();
        view.setFitWidth(dw);
        view.setFitHeight(dh);
        view.setPreserveRatio(sticker);
        box.getChildren().add(view);
        if (m.mediaUrl() != null) {
            loadMedia(svc, view, m, (int) (dw * 2), (int) (dh * 2));
            if (MessageActions.isStickerOrGif(m)) library.rememberSeen(MediaItems.of(m));
            if (m.isReelLink()) {
                box.getChildren().add(playBadge());
                clickable(box, I18n.t("media.reelOpen"), () -> openLink.accept(m.externalUrl()));
                return box;
            }
            clickable(box, sticker ? I18n.t("media.stickerOpen")
                    : m.fileName() == null ? I18n.t("media.imageOpen") : I18n.t("media.imageFileOpen", m.fileName()), () -> {
                Window owner = scene.get().getWindow();
                MediaViewer.show(owner, svc.media(m.mediaUrl(), 0, 0), sticker ? I18n.t("media.sticker") : m.fileName(),
                        () -> MediaSaver.save(owner, svc, m, toast));
            });
        }
        return box;
    }

    /**
     * A picture in a message's HTML. Its size isn't known beforehand, so it takes its shape once loaded; that's
     * remembered, or the row would grow again each time it scrolls back into view.
     */
    Node htmlPicture(String url, double width, double maxHeight) {
        StackPane box = mediaBox(width, Math.min(maxHeight, width / pictureShapes.getOrDefault(url, DEFAULT_SHAPE)), false);
        ImageView view = new ImageView();
        view.setFitWidth(width);
        view.setFitHeight(maxHeight);
        view.setPreserveRatio(true);
        box.getChildren().add(view);
        whenLoaded(url, (int) (Math.max(width, maxHeight) * 2), img -> {
            view.setImage(img);
            double shape = img.getWidth() / img.getHeight();
            pictureShapes.put(url, shape);
            double h = Math.min(maxHeight, width / shape);
            box.setPrefHeight(h);
            box.setMinHeight(h);
            box.setMaxHeight(h);
            ((Rectangle) box.getClip()).setHeight(h);
        });
        clickable(box, I18n.t("media.imageOpen"), () -> {
            Window owner = scene.get().getWindow();
            Message holder = new Message(url, "", "", null, false, java.time.Instant.now());
            holder.setType(MessageType.IMAGE);
            holder.setMediaUrl(url);
            MediaViewer.show(owner, svc.media(url, 0, 0), I18n.t("media.image"), () -> MediaSaver.save(owner, svc, holder, toast));
        });
        return box;
    }

    /** A custom emoji or small icon, in the running text. */
    Node inlinePicture(String url, double height, String description) {
        ImageView view = new ImageView();
        view.setFitHeight(height);
        view.setPreserveRatio(true);
        view.setAccessibleText(description);
        whenLoaded(url, (int) (height * 2), view::setImage);
        return view;
    }

    private void whenLoaded(String url, int px, java.util.function.Consumer<Image> show) {
        svc.media(url, px, px).thenAcceptAsync(p -> {
            try {
                Image img = AnimatedImage.still(p, px, px);
                if (img.getHeight() > 0) Platform.runLater(() -> show.accept(img));
            } catch (Exception e) {
                System.err.println("Could not decode " + p.getFileName() + ": " + e);
            }
        }, AnimatedImage.DECODER);
    }

    /**
     * Players for what a message's links hold, added as each answers. A short video plays by itself; a longer one is a
     * chip that opens it, so nothing large downloads unasked; audio has its controls.
     */
    Node linkPlayers(java.util.List<String> links, Chat chat, double width) {
        VBox box = new VBox(4);
        box.setMaxWidth(width);
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (String link : links.stream().limit(MAX_LINKS_OPENED).toList()) {
            svc.linkMedia(link).thenAcceptAsync(found -> found.ifPresent(lm -> {
                if (shown.size() < MAX_LINK_PLAYERS && shown.add(lm.url())) box.getChildren().add(player(lm, chat));
            }), Platform::runLater);
        }
        return box;
    }

    private Node player(LinkMedia lm, Chat chat) {
        Message m = new Message(lm.url(), chat.id(), "", null, false, java.time.Instant.now());
        m.setMediaUrl(lm.url());
        m.setMimeType(lm.mimeType());
        m.setFileSize(lm.size());
        String name = lm.url().replaceAll("[?#].*", "").replaceAll(".*/", "");
        m.setFileName(name.isBlank() ? lm.url() : java.net.URLDecoder.decode(name.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8));
        if (lm.kind() == LinkMedia.Kind.AUDIO) {
            m.setType(MessageType.AUDIO);
            return audioNode(m, chat);
        }
        m.setType(MessageType.VIDEO);
        m.setMediaInfo(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        return m.playsInline() ? inlineVideoNode(m) : fileNode(m);
    }

    /** A video that plays by itself (Message.playsInline), and opens big like a picture. */
    Node inlineVideoNode(Message m) {
        boolean sticker = m.type() == MessageType.STICKER;
        MediaSizes.Size size = sticker ? MediaSizes.sticker(m.width(), m.height()) : MediaSizes.media(m.width(), m.height());
        double dw = size.width(), dh = size.height();
        StackPane box = mediaBox(dw, dh, sticker);
        if (isWebm(m)) showConverted(box, m, dw, dh);
        else box.getChildren().add(new InlineVideo(svc, m.mediaUrl(), dw, dh, () -> fileNode(m)));
        if (MessageActions.isStickerOrGif(m)) library.rememberSeen(MediaItems.of(m));
        clickable(box, I18n.t(sticker ? "media.stickerOpen" : "media.videoOpen"), () -> openVideo(m));
        return box;
    }

    private static boolean isWebm(Message m) {
        return m.mimeType() != null && m.mimeType().startsWith("video/webm");
    }

    /**
     * Labelled WebM, which JavaFX can't play: converted once to an animated picture (WebmStickers), and a file chip if that
     * can't be. The label isn't the file: with ffmpeg at hand bridges deliver a GIF and still call it video/webm.
     */
    private void showConverted(StackPane box, Message m, double width, double height) {
        ImageView view = new ImageView();
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.setPreserveRatio(true);
        ProgressIndicator wait = new ProgressIndicator();
        wait.setMaxSize(28, 28);
        wait.setAccessibleText(I18n.t("media.preparingSticker"));
        box.getChildren().addAll(view, wait);
        int px = (int) (Math.max(width, height) * 2);
        svc.media(m.mediaUrl(), 0, 0)
                .thenApplyAsync(p -> isPicture(p) ? p : converted(p), WebmStickers.WORKER)
                .thenAcceptAsync(p -> {
                    AnimatedImage.Frames frames = decoded(p, px);
                    Platform.runLater(() -> {
                        box.getChildren().remove(wait);
                        AnimatedImage.play(view, frames);
                    });
                }, AnimatedImage.DECODER)
                .exceptionally(err -> {
                    Platform.runLater(() -> box.getChildren().setAll(fileNode(m)));
                    return null;
                });
    }

    private static boolean isPicture(java.nio.file.Path file) {
        try {
            return AnimatedImage.isGif(file) || AnimatedImage.isWebp(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static java.nio.file.Path converted(java.nio.file.Path webm) {
        try {
            return WebmStickers.animated(webm, done -> { });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static AnimatedImage.Frames decoded(java.nio.file.Path picture, int px) {
        try {
            return AnimatedImage.decode(picture, px, px);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static StackPane mediaBox(double w, double h, boolean sticker) {
        StackPane box = new StackPane();
        box.getStyleClass().add(sticker ? "sticker-box" : "media-box");
        box.setPrefSize(w, h);
        box.setMinSize(w, h);
        box.setMaxSize(w, h);
        if (!sticker) {
            Rectangle clip = new Rectangle(w, h);
            clip.setArcWidth(24);
            clip.setArcHeight(24);
            box.setClip(clip);
        }
        return box;
    }

    private static Node playBadge() {
        StackPane badge = new StackPane(Icons.icon(Icons.PLAY, 26));
        badge.getStyleClass().add("video-badge");
        badge.setMaxSize(48, 48);
        badge.setMouseTransparent(true);
        return badge;
    }

    /**
     * On release, not click: the first press selects the message and may scroll the list, and a press and release over
     * different spots is no "click" (it took a second click to open).
     */
    private void clickable(StackPane box, String accessibleText, Runnable open) {
        box.setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress() && scene.get() != null) open.run();
        });
        box.setCursor(javafx.scene.Cursor.HAND);
        box.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        box.setAccessibleText(accessibleText);
    }

    private void openVideo(Message m) {
        audio.pause();
        Window owner = scene.get().getWindow();
        VideoViewer.show(owner, svc.media(m.mediaUrl(), 0, 0), m.fileName(), () -> MediaSaver.save(owner, svc, m, toast), this::openWithSystem);
    }

    /** Stickers, GIFs and WebP may be animated: those load the original file, never a (static) thumbnail. */
    static boolean mayBeAnimated(Message m) {
        String mime = m.mimeType() == null ? "" : m.mimeType();
        return m.type() == MessageType.STICKER || mime.equals("image/gif") || mime.equals("image/webp");
    }

    /** Thumbnail first, then the (possibly animated) original; shared with the sticker and GIF grids. */
    static java.util.concurrent.CompletableFuture<java.nio.file.Path> loadMedia(MediaSource svc, ImageView view, Message m, int pxW, int pxH) {
        if (!mayBeAnimated(m)) {
            java.util.concurrent.CompletableFuture<java.nio.file.Path> still = svc.media(m.mediaUrl(), pxW, pxH);
            still.thenAcceptAsync(p -> showStill(view, p, pxW, pxH, false), AnimatedImage.DECODER);
            return still;
        }
        java.util.concurrent.CompletableFuture<java.nio.file.Path> original = svc.media(m.mediaUrl(), 0, 0);
        if (Platform.isFxApplicationThread() && original.isDone() && !original.isCompletedExceptionally()) {
            AnimatedImage.Frames ready = AnimatedImage.decoded(original.join(), pxW, pxH);
            if (ready != null) {
                AnimatedImage.play(view, ready);
                return original;
            }
        }
        // Placeholder: a still picture shows at once; decoding a long animation takes seconds. (With direct media
        // the "thumbnail" is the original file, maybe an animated GIF: only its first frame is used.)
        svc.media(m.mediaUrl(), pxW, pxH).thenAcceptAsync(p -> showStill(view, p, pxW, pxH, true), AnimatedImage.DECODER);
        original.thenAcceptAsync(p -> {
            try {
                AnimatedImage.Frames frames = AnimatedImage.decode(p, pxW, pxH); // labelled as a sticker, maybe a plain PNG/JPEG
                Platform.runLater(() -> AnimatedImage.play(view, frames));
            } catch (Exception e) {
                System.err.println("Could not decode " + m.mediaUrl() + ": " + e);
            }
        }, AnimatedImage.DECODER);
        return original;
    }

    /** Decodes {@code p} as a still picture (off the FX thread) and shows it; {@code ifEmpty}: only if nothing's shown. */
    private static void showStill(ImageView view, java.nio.file.Path p, int pxW, int pxH, boolean ifEmpty) {
        try {
            Image img = AnimatedImage.still(p, pxW, pxH);
            Platform.runLater(() -> {
                if (!ifEmpty || view.getImage() == null) view.setImage(img);
            });
        } catch (Exception e) {
            System.err.println("Could not decode " + p.getFileName() + ": " + e);
        }
    }

    Node fileNode(Message m) {
        String icon = switch (m.type()) {
            case VIDEO, AUDIO -> Icons.PLAY;
            default -> Icons.FILE;
        };
        StackPane ic = new StackPane(Icons.icon(icon, 18));
        ic.getStyleClass().add("file-icon");
        Label name = new Label(m.fileName() == null ? m.body() : m.fileName());
        name.getStyleClass().add("file-name");
        Label size = new Label(I18n.t(m.type() == MessageType.AUDIO ? "media.kind.audio" : m.type() == MessageType.VIDEO ? "media.kind.video"
                : m.type() == MessageType.STICKER ? "media.kind.videoSticker" : "media.kind.file")
                + (m.fileSize() > 0 ? " · " + Formats.fileSize(m.fileSize()) : ""));
        size.getStyleClass().add("file-size");
        VBox text = new VBox(1, name, size);
        HBox box = new HBox(10, ic, text, Icons.icon(Icons.DOWNLOAD, 16));
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("file-chip");
        box.setCursor(javafx.scene.Cursor.HAND);
        box.setOnMouseClicked(e -> {
            if ((m.type() == MessageType.VIDEO || m.type() == MessageType.STICKER) && m.mediaUrl() != null && scene.get() != null) openVideo(m);
            else openFile(m);
        });
        box.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        box.setAccessibleText(I18n.t("media.fileOpen", size.getText(), name.getText()));
        return box;
    }

    void transcribe(Message m) {
        Transcripts.get().request(m.mediaUrl(), () -> svc.media(m.mediaUrl(), 0, 0));
    }

    /** The player inside the message; music and such keep their name, a voice note needs none. */
    Node audioNode(Message m, Chat chat) {
        AudioControls controls = new AudioControls(audio, () -> m, () -> audio.toggle(m, chat));
        controls.leaveTabOrder();
        controls.setPrefWidth(AUDIO_WIDTH);
        boolean voiceNote = m.mimeType() != null && m.mimeType().startsWith("audio/ogg");
        if (voiceNote || m.fileName() == null) return controls;
        Label name = new Label(m.fileName());
        name.getStyleClass().add("file-name");
        return new VBox(2, name, controls);
    }

    /** Under a voice note: "Transcribir", the progress, the text, or the error with "Reintentar". */
    Node transcriptNode(Message m, double maxWidth) {
        Transcripts.State st = Transcripts.get().state(m.mediaUrl());
        if (st instanceof Transcripts.Done d) {
            Label text = new Label(d.text().isEmpty() ? I18n.t("media.noSpeech") : d.text());
            text.getStyleClass().add("transcript");
            if (d.text().isEmpty()) text.getStyleClass().add("empty");
            text.setWrapText(true);
            text.setMaxWidth(maxWidth - 24);
            return text;
        }
        if (st instanceof Transcripts.Working w) {
            Label l = new Label(w.what());
            l.getStyleClass().add("transcript-status");
            return l;
        }
        Hyperlink action = new Hyperlink(I18n.t(st instanceof Transcripts.Failed ? "media.retry" : "media.transcribe"));
        action.getStyleClass().add("transcript-action");
        // Keyboard: T or the message's menu (rows are navigated as a whole, like reactions).
        action.setFocusTraversable(false);
        action.setAccessibleText(I18n.t("media.transcribeVoice"));
        action.setOnAction(e -> transcribe(m));
        if (st instanceof Transcripts.Failed f) {
            Label err = new Label(I18n.t("media.transcribeFailed", f.error()));
            err.getStyleClass().add("transcript-status");
            err.setWrapText(true);
            err.setMaxWidth(maxWidth - 24);
            return new VBox(2, err, action);
        }
        return action;
    }

    void openFile(Message m) {
        if (m.mediaUrl() == null) return;
        svc.media(m.mediaUrl(), 0, 0).whenComplete((p, err) -> {
            if (err != null) Platform.runLater(() -> toast.accept(I18n.t("media.downloadFailed")));
            else openWithSystem(p);
        });
    }

    private void openWithSystem(java.nio.file.Path p) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(p.toFile());
        } catch (Exception ex) {
            openLink.accept(p.toUri().toString());
        }
    }
}
