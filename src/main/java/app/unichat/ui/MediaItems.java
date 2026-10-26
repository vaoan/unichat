package app.unichat.ui;

import app.unichat.model.MediaLibrary;
import app.unichat.model.Message;
import app.unichat.model.MessageType;

import java.time.Instant;

/** Converts between chat messages and favourite/recent library items. */
final class MediaItems {
    private MediaItems() {}

    static MediaLibrary.Item of(Message m) {
        MediaLibrary.Kind kind = m.type() == MessageType.STICKER ? MediaLibrary.Kind.STICKER : MediaLibrary.Kind.GIF;
        return new MediaLibrary.Item(kind, m.mediaUrl(), m.mimeType(), m.width(), m.height(), m.mediaInfo());
    }

    /** A stand-in message so a library item loads like any chat media (thumbnail, then the animation). */
    static Message message(MediaLibrary.Item i) {
        Message m = new Message("lib-" + i.url(), "", "", "", false, Instant.EPOCH);
        m.setType(i.kind() == MediaLibrary.Kind.STICKER ? MessageType.STICKER : MessageType.IMAGE);
        m.setMediaUrl(i.url());
        m.setMimeType(i.mime());
        m.setSize(i.width(), i.height());
        return m;
    }
}
