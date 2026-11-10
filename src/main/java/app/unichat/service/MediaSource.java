package app.unichat.service;

import app.unichat.model.LinkMedia;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Getting the file behind a media URL. */
public interface MediaSource {
    /**
     * Returns a local file for a media/avatar URL (mxc://, http(s)://, file:), downloading and
     * caching it if needed. Thumbnails: pass width/height > 0 to request a scaled version.
     */
    CompletableFuture<Path> media(String url, int width, int height);

    /** The same, telling {@code progress} how much has arrived (0 to 1) while it downloads, when that's known. */
    default CompletableFuture<Path> media(String url, int width, int height, java.util.function.DoubleConsumer progress) {
        return media(url, width, height);
    }

    /**
     * Whether {@code error}, from {@link #media}, means the file will never come: deleted, expired, not an address it
     * can open. Otherwise (the network, the server for now) asking again later may work.
     */
    default boolean gone(Throwable error) {
        return false;
    }

    /** The video or audio a web link holds, by opening it; empty if it holds none or can't be opened. */
    default CompletableFuture<java.util.Optional<LinkMedia>> linkMedia(String url) {
        return CompletableFuture.completedFuture(java.util.Optional.empty());
    }

    /** Media that must stay on disk whatever the cache's limit: the person's saved stickers and GIFs. Replaces the last list. */
    default void keepCached(java.util.Collection<String> urls) { }

    /** The person's favourites, which get a copy of their own in the data folder. Replaces the last list. */
    default void keepSaved(java.util.Collection<String> urls) { }
}
