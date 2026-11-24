package app.unichat.model;

import org.jspecify.annotations.Nullable;

public enum MessageType {
    TEXT, EMOTE, NOTICE, IMAGE, VIDEO, AUDIO, FILE, STICKER, SYSTEM;

    /** What a file of this MIME type goes out as; FILE when it's unknown or null. */
    public static MessageType ofMime(@Nullable String mime) {
        if (mime == null) return FILE;
        return mime.startsWith("image/") ? IMAGE : mime.startsWith("video/") ? VIDEO : mime.startsWith("audio/") ? AUDIO : FILE;
    }
}
