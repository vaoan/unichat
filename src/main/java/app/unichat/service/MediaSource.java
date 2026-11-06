package app.unichat.service;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Getting the file behind a media URL. */
public interface MediaSource {
    /**
     * Returns a local file for a media/avatar URL (mxc://, http(s)://, file:), downloading and
     * caching it if needed. Thumbnails: pass width/height > 0 to request a scaled version.
     */
    CompletableFuture<Path> media(String url, int width, int height);

    /** Media that must stay on disk whatever the cache's limit: the person's saved stickers and GIFs. Replaces the last list. */
    default void keepCached(java.util.Collection<String> urls) { }
}
