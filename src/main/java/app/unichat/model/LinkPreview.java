package app.unichat.model;

/**
 * What a link is, shown under its message as a card: the site, a title, some text, and a picture or the video it holds
 * (a GIF plays by itself). Empty texts are "", sizes 0 when unknown, {@code image} and {@code video} null when it has none.
 */
public record LinkPreview(String url, String site, String title, String description, String image, int width, int height,
                          LinkMedia video, boolean gif) {
    public LinkPreview {
        site = site == null ? "" : site.strip();
        title = title == null ? "" : title.strip();
        description = description == null ? "" : description.strip();
        width = Math.max(0, width);
        height = Math.max(0, height);
    }

    /** A link that is only a file: a video, a sound or a picture. */
    public static LinkPreview of(String url, LinkMedia media, String picture) {
        return new LinkPreview(url, "", "", "", picture, 0, 0, media, false);
    }

    /** Nothing a card could show. */
    public boolean isEmpty() {
        return title.isEmpty() && description.isEmpty() && image == null && video == null;
    }

    public LinkPreview withVideo(LinkMedia media, boolean isGif) {
        return new LinkPreview(url, site, title, description, image, width, height, media, isGif);
    }
}
