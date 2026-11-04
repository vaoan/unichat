package app.unichat.ui;

import app.unichat.service.MediaSource;
import app.unichat.model.MediaLibrary;
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
    private final MediaSource svc;
    private final Consumer<String> openLink;
    private final Consumer<String> toast;
    private final Supplier<MediaLibrary> library;
    private final Supplier<Scene> scene;

    MessageMedia(MediaSource svc, Consumer<String> openLink, Consumer<String> toast, Supplier<MediaLibrary> library, Supplier<Scene> scene) {
        this.svc = svc;
        this.openLink = openLink;
        this.toast = toast;
        this.library = library;
        this.scene = scene;
    }

    private static final double MAX_MEDIA = 320;

    Node imageNode(Message m) {
        double w = m.width() > 0 ? m.width() : 240;
        double h = m.height() > 0 ? m.height() : 180;
        double scale = Math.min(1, Math.min(MAX_MEDIA / w, MAX_MEDIA / h));
        double dw = Math.max(80, w * scale), dh = Math.max(60, h * scale);
        boolean sticker = m.type() == MessageType.STICKER;
        if (sticker) {
            // Stickers are small and shown without a bubble, like on the phone.
            double s = Math.min(1, Math.min(180 / w, 180 / h));
            dw = Math.max(60, w * s);
            dh = Math.max(60, h * s);
        }
        StackPane box = new StackPane();
        box.getStyleClass().add(sticker ? "sticker-box" : "media-box");
        box.setPrefSize(dw, dh);
        box.setMinSize(dw, dh);
        box.setMaxSize(dw, dh);
        if (!sticker) {
            Rectangle clip = new Rectangle(dw, dh);
            clip.setArcWidth(24);
            clip.setArcHeight(24);
            box.setClip(clip);
        }
        ImageView view = new ImageView();
        view.setFitWidth(dw);
        view.setFitHeight(dh);
        view.setPreserveRatio(sticker);
        box.getChildren().add(view);
        if (m.mediaUrl() != null) {
            loadInto(view, m, (int) (dw * 2), (int) (dh * 2));
            if (library.get() != null && MessageActions.isStickerOrGif(m)) library.get().rememberSeen(MediaItems.of(m));
            // On release, not click: the first press selects the message and may scroll the list, and a press and
            // release over different spots is no "click" (it took a second click to open). Stickers open too: the
            // viewer plays animated WebP.
            box.setOnMouseReleased(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress() && scene.get() != null) {
                    Window owner = scene.get().getWindow();
                    MediaViewer.show(owner, svc.media(m.mediaUrl(), 0, 0), sticker ? "Sticker" : m.fileName(),
                            () -> MediaSaver.save(owner, svc, m, toast));
                }
            });
            box.setCursor(javafx.scene.Cursor.HAND);
            box.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
            box.setAccessibleText((sticker ? "Sticker" : "Imagen") + (m.fileName() == null || sticker ? "" : " " + m.fileName())
                    + ", abrir en grande");
        }
        return box;
    }

    /** Stickers, GIFs and WebP may be animated: those load the original file, never a (static) thumbnail. */
    static boolean mayBeAnimated(Message m) {
        String mime = m.mimeType() == null ? "" : m.mimeType();
        return m.type() == MessageType.STICKER || mime.equals("image/gif") || mime.equals("image/webp");
    }

    private void loadInto(ImageView view, Message m, int pxW, int pxH) {
        loadMedia(svc, view, m, pxW, pxH);
    }

    /** Thumbnail first, then the (possibly animated) original; shared with the sticker and GIF grids. */
    static void loadMedia(MediaSource svc, ImageView view, Message m, int pxW, int pxH) {
        if (!mayBeAnimated(m)) {
            svc.media(m.mediaUrl(), pxW, pxH).thenAcceptAsync(p -> showStill(view, p, pxW, pxH, false), AnimatedImage.DECODER);
            return;
        }
        // Placeholder: a still picture shows at once; decoding a long animation takes seconds. (With direct media
        // the "thumbnail" is the original file, maybe an animated GIF: only its first frame is used.)
        svc.media(m.mediaUrl(), pxW, pxH).thenAcceptAsync(p -> showStill(view, p, pxW, pxH, true), AnimatedImage.DECODER);
        svc.media(m.mediaUrl(), 0, 0).thenAcceptAsync(p -> {
            try {
                AnimatedImage.Frames frames;
                if (AnimatedImage.isWebp(p)) frames = AnimatedImage.decodeWebp(p, pxW, pxH);
                else if (AnimatedImage.isGif(p)) frames = AnimatedImage.decodeGif(p, pxW, pxH);
                else {
                    // Labelled as a sticker but a plain PNG/JPEG: never handed to JavaFX's own GIF animation.
                    Image img = new Image(p.toUri().toString(), pxW, pxH, true, true, false);
                    Platform.runLater(() -> view.setImage(img));
                    return;
                }
                Platform.runLater(() -> AnimatedImage.play(view, frames));
            } catch (Exception e) {
                System.err.println("Could not decode " + m.mediaUrl() + ": " + e);
            }
        }, AnimatedImage.DECODER);
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
        Label size = new Label((m.type() == MessageType.AUDIO ? "Audio" : m.type() == MessageType.VIDEO ? "Video" : "Archivo")
                + (m.fileSize() > 0 ? " · " + Formats.fileSize(m.fileSize()) : ""));
        size.getStyleClass().add("file-size");
        VBox text = new VBox(1, name, size);
        HBox box = new HBox(10, ic, text, Icons.icon(Icons.DOWNLOAD, 16));
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("file-chip");
        box.setCursor(javafx.scene.Cursor.HAND);
        box.setOnMouseClicked(e -> openFile(m));
        box.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        box.setAccessibleText(size.getText() + " " + name.getText() + ", abrir");
        return box;
    }

    void transcribe(Message m) {
        Transcripts.get().request(m.mediaUrl(), () -> svc.media(m.mediaUrl(), 0, 0));
    }

    /** Under a voice note: "Transcribir", the progress, the text, or the error with "Reintentar". */
    Node transcriptNode(Message m, double maxWidth) {
        Transcripts.State st = Transcripts.get().state(m.mediaUrl());
        if (st instanceof Transcripts.Done d) {
            Label text = new Label(d.text().isEmpty() ? "Sin voz" : d.text());
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
        Hyperlink action = new Hyperlink(st instanceof Transcripts.Failed ? "Reintentar" : "Transcribir");
        action.getStyleClass().add("transcript-action");
        // Keyboard: T or the message's menu (rows are navigated as a whole, like reactions).
        action.setFocusTraversable(false);
        action.setAccessibleText("Transcribir la nota de voz");
        action.setOnAction(e -> transcribe(m));
        if (st instanceof Transcripts.Failed f) {
            Label err = new Label("No se pudo transcribir: " + f.error());
            err.getStyleClass().add("transcript-status");
            err.setWrapText(true);
            err.setMaxWidth(maxWidth - 24);
            return new VBox(2, err, action);
        }
        return action;
    }

    void openFile(Message m) {
        if (m.mediaUrl() == null) return;
        svc.media(m.mediaUrl(), 0, 0).thenAccept(p -> {
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(p.toFile());
            } catch (Exception ex) {
                openLink.accept(p.toUri().toString());
            }
        });
    }
}
