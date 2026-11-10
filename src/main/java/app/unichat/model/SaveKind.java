package app.unichat.model;

/** What a chat that saves what it receives can save, each chosen on its own (the chat's options). */
public enum SaveKind {
    PHOTOS, VIDEOS, AUDIO, DOCUMENTS, GIFS, LINKS;

    /** The kind of {@code m}'s media; null for a sticker or a message without media (links are apart). */
    public static SaveKind of(Message m) {
        if (m.mediaUrl() == null || !m.isMedia()) return null;
        if (m.isGif()) return GIFS;
        return switch (m.type()) {
            case IMAGE -> PHOTOS;
            case VIDEO -> VIDEOS;
            case AUDIO -> AUDIO;
            case FILE -> DOCUMENTS;
            default -> null;
        };
    }
}
