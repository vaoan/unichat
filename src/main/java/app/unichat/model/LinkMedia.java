package app.unichat.model;

import org.jspecify.annotations.Nullable;

/**
 * The video or audio a web link turned out to hold. {@code size} is 0 when the site doesn't say; {@code mimeType} is
 * null when the server named no type and the address's extension told it.
 */
public record LinkMedia(Kind kind, String url, @Nullable String mimeType, long size) {
    public enum Kind { VIDEO, AUDIO }
}
