package app.unichat.model;

/** The video or audio a web link turned out to hold. {@code size} is 0 when the site doesn't say. */
public record LinkMedia(Kind kind, String url, String mimeType, long size) {
    public enum Kind { VIDEO, AUDIO }
}
