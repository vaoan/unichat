package app.unichat.model;

import org.jspecify.annotations.Nullable;

/**
 * What a chat that saves what it receives can save, each chosen on its own (the chat's options). {@link #TEXTS}: a copy
 * of what's said, to read what was deleted later.
 */
public enum SaveKind {
    PHOTOS, VIDEOS, AUDIO, DOCUMENTS, GIFS, LINKS, TEXTS;

    /** The kind of {@code m}'s media; null for a sticker or a message without media (links and texts are apart). */
    public static @Nullable SaveKind of(Message m) {
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
