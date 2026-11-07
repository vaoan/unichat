package app.unichat.model;

public enum MessageType {
    TEXT, EMOTE, NOTICE, IMAGE, VIDEO, AUDIO, FILE, STICKER, SYSTEM;

    /** What a file of this MIME type goes out as; FILE when it's unknown or null. */
    public static MessageType ofMime(String mime) {
        if (mime == null) return FILE;
        return mime.startsWith("image/") ? IMAGE : mime.startsWith("video/") ? VIDEO : mime.startsWith("audio/") ? AUDIO : FILE;
    }
}
