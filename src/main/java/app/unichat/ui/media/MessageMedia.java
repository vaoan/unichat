package app.unichat.ui.media;

import app.unichat.ui.Formats;
import app.unichat.ui.Icons;
import app.unichat.ui.emoji.MediaItems;
import app.unichat.ui.timeline.MessageActions;
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
public final class MessageMedia {
    private static final double AUDIO_WIDTH = 240;

    private final MediaSource svc;
    private final Consumer<String> openLink;
    private final Consumer<String> toast;
    private final MediaLibrary library;
    private final Supplier<Scene> scene;
    private final AudioPlayback audio;

    public MessageMedia(MediaSource svc, Consumer<String> openLink, Consumer<String> toast, MediaLibrary library, Supplier<Scene> scene, AudioPlayback audio) {
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

    public Node imageNode(Message m) {
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
                clickable(box, I18n.t("media.reelOpen"), () -> openReel(m));
                return box;
            }
            clickable(box, sticker ? I18n.t("media.stickerOpen")
                    : m.fileName() == null ? I18n.t("media.imageOpen") : I18n.t("media.imageFileOpen", m.fileName()), () -> {
                Window owner = scene.get().getWindow();
                if (sticker) MediaViewer.show(owner, svc.media(m.mediaUrl(), 0, 0), I18n.t("media.sticker"), () -> MediaSaver.save(owner, svc, m, toast));
                else Gallery.open(owner, svc, m, toast);
            });
        }
        return box;
    }

    /**
     * A picture in a message's HTML. Its size isn't known beforehand, so it takes its shape once loaded; that's
     * remembered, or the row would grow again each time it scrolls back into view.
     */
    public Node htmlPicture(String url, double width, double maxHeight, double knownShape) {
        // Its shape before it loads (the chat apps' rule: reserve the space, or the row grows and the chat moves): the
        // HTML's own size, else the one seen last time, kept across restarts (PictureShapes), else 16:9.
        double shape = knownShape > 0 ? knownShape : PictureShapes.get().shape(url, DEFAULT_SHAPE);
        StackPane box = mediaBox(width, Math.min(maxHeight, width / shape), false);
        ImageView view = new ImageView();
        view.setFitWidth(width);
        view.setFitHeight(maxHeight);
        view.setPreserveRatio(true);
        box.getChildren().add(view);
        whenLoaded(url, (int) (Math.max(width, maxHeight) * 2), img -> {
            view.setImage(img);
            double real = img.getWidth() / img.getHeight();
            PictureShapes.get().remember(url, real);
            double h = Math.min(maxHeight, width / real);
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
    public Node inlinePicture(String url, double height, String description) {
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
    public Node linkPlayers(java.util.List<String> links, Chat chat, double width) {
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
    public Node inlineVideoNode(Message m) {
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

    /** A reel that came only as its picture: its video in the player, fetched from its page; the page if that fails. */
    private void openReel(Message m) {
        MediaOpening.reel(scene.get().getWindow(), m.externalUrl(), audio, openLink);
    }

    private void openVideo(Message m) {
        MediaOpening.video(scene.get().getWindow(), svc, m, m.fileName(), audio, toast);
    }

    /** Stickers, GIFs and WebP may be animated: those load the original file, never a (static) thumbnail. */
    static boolean mayBeAnimated(Message m) {
        String mime = m.mimeType() == null ? "" : m.mimeType();
        return m.type() == MessageType.STICKER || mime.equals("image/gif") || mime.equals("image/webp");
    }

    /** Thumbnail first, then the (possibly animated) original; shared with the sticker and GIF grids. */
    public static java.util.concurrent.CompletableFuture<java.nio.file.Path> loadMedia(MediaSource svc, ImageView view, Message m, int pxW, int pxH) {
        if (!mayBeAnimated(m)) {
            java.util.concurrent.CompletableFuture<java.nio.file.Path> still = svc.media(m.mediaUrl(), pxW, pxH);
            Image known = Platform.isFxApplicationThread() ? Stills.get(m.mediaUrl(), pxW, pxH) : null;
            if (known != null) view.setImage(known); // decoded before: no blank while it's decoded again
            else still.thenAcceptAsync(p -> showStill(view, m.mediaUrl(), p, pxW, pxH, false), AnimatedImage.DECODER);
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
        svc.media(m.mediaUrl(), pxW, pxH).thenAcceptAsync(p -> showStill(view, m.mediaUrl(), p, pxW, pxH, true), AnimatedImage.DECODER);
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
    private static void showStill(ImageView view, String url, java.nio.file.Path p, int pxW, int pxH, boolean ifEmpty) {
        try {
            Image img = AnimatedImage.still(p, pxW, pxH);
            Stills.put(url, pxW, pxH, img);
            Platform.runLater(() -> {
                if (!ifEmpty || view.getImage() == null) view.setImage(img);
            });
        } catch (Exception e) {
            System.err.println("Could not decode " + p.getFileName() + ": " + e);
        }
    }

    public Node fileNode(Message m) {
        String icon = switch (m.type()) {
            case VIDEO, AUDIO -> Icons.PLAY;
            default -> Icons.FILE;
        };
        StackPane ic = new StackPane(Icons.icon(icon, 18));
        ic.getStyleClass().add("file-icon");
        String preview = FilePreview.url(m);
        if (preview != null) showFilePreview(ic, preview, FilePreview.key(m), m.type() == MessageType.VIDEO);
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
            else if (Gallery.isPicture(m) && scene.get() != null) Gallery.open(scene.get().getWindow(), svc, m, toast); // a photo sent as a document
            else openFile(m);
        });
        box.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        box.setAccessibleText(I18n.t("media.fileOpen", size.getText(), name.getText()));
        return box;
    }

    private static final int FILE_THUMB = 38; // the icon's size, so names line up whether there is a picture or not

    /**
     * Previews already made, by file (name and size): the message we send shows first as our own copy, then as the
     * server's, and without this its picture vanished and came back each time (downloading the file again to make it).
     */
    private static final java.util.Map<String, Image> FILE_PREVIEWS = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Image> eldest) {
                    return size() > 300; // 300 of 152 px: about 30 MB at most
                }
            });

    /** The picture in place of the icon once it's here (a video keeps its play mark on top); the icon if it never comes. */
    private void showFilePreview(StackPane ic, String url, String key, boolean video) {
        Image known = FILE_PREVIEWS.get(key);
        if (known != null) {
            setFilePreview(ic, known, video);
            return;
        }
        int px = FILE_THUMB * 2;
        svc.media(url, px, px).thenAcceptAsync(p -> {
            Image img;
            try {
                img = AnimatedImage.still(p, px * 2, px * 2);
            } catch (Exception e) {
                return;
            }
            if (img.isError() || img.getWidth() <= 0) return;
            FILE_PREVIEWS.put(key, img);
            Platform.runLater(() -> setFilePreview(ic, img, video));
        }, AnimatedImage.DECODER);
    }

    private static void setFilePreview(StackPane ic, Image img, boolean video) {
        ImageView view = new ImageView(img);
        double side = Math.min(img.getWidth(), img.getHeight()); // the middle square, as Telegram crops it
        view.setViewport(new javafx.geometry.Rectangle2D((img.getWidth() - side) / 2, (img.getHeight() - side) / 2, side, side));
        view.setFitWidth(FILE_THUMB);
        view.setFitHeight(FILE_THUMB);
        view.setSmooth(true);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(FILE_THUMB, FILE_THUMB);
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        view.setClip(clip);
        ic.getChildren().setAll(view);
        if (video) ic.getChildren().add(Icons.icon(Icons.PLAY, 18));
        ic.getStyleClass().add("has-preview");
    }

    public void transcribe(Message m) {
        Transcripts.get().request(m.mediaUrl(), () -> svc.media(m.mediaUrl(), 0, 0));
    }

    /** The player inside the message; music and such keep their name, a voice note needs none. */
    public Node audioNode(Message m, Chat chat) {
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
    public Node transcriptNode(Message m, double maxWidth) {
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

    /** Whether {@link #view} has something to open for {@code m}: a photo, a video or a reel. */
    public boolean canView(Message m) {
        if (m.deleted()) return false;
        if (m.isReelLink()) return m.externalUrl() != null;
        return m.mediaUrl() != null && (m.type() == MessageType.VIDEO || Gallery.isPicture(m));
    }

    /** What a click on {@code m}'s picture or video opens; for the keyboard, through the message's menu. */
    public void view(Message m) {
        if (!canView(m) || scene.get() == null) return;
        if (m.isReelLink()) openReel(m);
        else if (m.type() == MessageType.VIDEO) openVideo(m);
        else Gallery.open(scene.get().getWindow(), svc, m, toast);
    }

    public void openLink(String url) { openLink.accept(url); }

    public void openFile(Message m) {
        MediaOpening.file(svc, m, toast);
    }
}
