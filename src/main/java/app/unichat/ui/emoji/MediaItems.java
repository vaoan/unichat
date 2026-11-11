package app.unichat.ui.emoji;

import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.MessageType;

import java.time.Instant;

/** Converts between chat messages and favourite/recent library items. */
public final class MediaItems {
    private MediaItems() {}

    public static MediaLibrary.Item of(Message m) {
        MediaLibrary.Kind kind = m.type() == MessageType.STICKER ? MediaLibrary.Kind.STICKER : MediaLibrary.Kind.GIF;
        return new MediaLibrary.Item(kind, m.mediaUrl(), m.mimeType(), m.width(), m.height(), m.mediaInfo());
    }

    /** A stand-in message so a library item loads like any chat media (thumbnail, then the animation). */
    /** The saved stickers and GIFs (and their previews) stay on disk whatever the cache's limit. */
    public static void keepCached(app.unichat.service.MediaSource svc, MediaLibrary library) {
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
