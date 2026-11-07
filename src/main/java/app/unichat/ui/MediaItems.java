package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.MessageType;
import app.unichat.support.I18n;

import java.time.Instant;

/** Converts between chat messages and favourite/recent library items. */
final class MediaItems {
    private MediaItems() {}

    static MediaLibrary.Item of(Message m) {
        MediaLibrary.Kind kind = m.type() == MessageType.STICKER ? MediaLibrary.Kind.STICKER : MediaLibrary.Kind.GIF;
        return new MediaLibrary.Item(kind, m.mediaUrl(), m.mimeType(), m.width(), m.height(), m.mediaInfo());
    }

    /** A stand-in message so a library item loads like any chat media (thumbnail, then the animation). */
    /** The saved stickers and GIFs (and their previews) stay on disk whatever the cache's limit. */
    static void keepCached(app.unichat.service.MediaSource svc, MediaLibrary library) {
        java.util.Set<String> urls = new java.util.HashSet<>();
        for (MediaLibrary.Kind k : java.util.List.of(MediaLibrary.Kind.STICKER, MediaLibrary.Kind.GIF)) {
            for (MediaLibrary.Item i : library.favorites(k)) add(urls, i);
            for (MediaLibrary.Item i : library.recents(k)) add(urls, i);
        }
        svc.keepCached(urls);
        java.util.Set<String> favorites = new java.util.HashSet<>();
        for (MediaLibrary.Kind k : java.util.List.of(MediaLibrary.Kind.STICKER, MediaLibrary.Kind.GIF)) {
            for (MediaLibrary.Item i : library.favorites(k)) add(favorites, i);
        }
        svc.keepSaved(favorites);
    }

    /** Marks a tile whose file can't be had: not on disk, and whoever held it no longer gives it. */
    static void markIfLost(java.util.concurrent.CompletableFuture<java.nio.file.Path> loading, javafx.scene.layout.StackPane tile) {
        loading.whenComplete((file, err) -> {
            if (err == null) return;
            javafx.application.Platform.runLater(() -> {
                javafx.scene.Node mark = Icons.icon(Icons.ERROR, 16);
                javafx.scene.layout.StackPane.setAlignment(mark, javafx.geometry.Pos.BOTTOM_RIGHT);
                tile.getChildren().add(mark);
                tile.setAccessibleText(I18n.t("media.lostA11y", tile.getAccessibleText()));
                javafx.scene.control.Tooltip.install(tile, new javafx.scene.control.Tooltip(I18n.t("media.lost")));
            });
        });
    }

    private static void add(java.util.Set<String> urls, MediaLibrary.Item i) {
        urls.add(i.url());
        String preview = app.unichat.gif.GifFiles.preview(i);
        if (preview != null) urls.add(preview);
    }

    static Message message(MediaLibrary.Item i) {
        Message m = new Message("lib-" + i.url(), "", "", "", false, Instant.EPOCH);
        m.setType(i.kind() == MediaLibrary.Kind.STICKER ? MessageType.STICKER : MessageType.IMAGE);
        m.setMediaUrl(i.url());
        m.setMimeType(i.mime());
        m.setSize(i.width(), i.height());
        return m;
    }
}
