package app.unichat.autosave;

import app.unichat.settings.Settings;
import app.unichat.support.Folders;
import app.unichat.support.Hashes;
import app.unichat.support.I18n;
import app.unichat.support.Threads;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The video behind a link the bridge gave only as its picture (an Instagram reel): fetched by the same engine that saves
 * links ({@link LinkSaver}: its sites, the person's cookies, H.264 in the end), once, into {@code cache/media} as
 * {@code reel-<hash>.<ext>}, so `MediaCache` trims it with the rest. Before it, a click on such a reel opened Instagram.
 */
public final class LinkVideo {
    /** What fetches {@code url} into {@code dir}: null when done, else why not. */
    interface Fetch {
        Failure save(String url, Path dir) throws IOException, InterruptedException;
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(Threads.daemon("link-video"));

    private LinkVideo() { }

    /** {@code url}'s video, from the cache or fetched now (off the caller's thread); failing with why it couldn't. */
    public static CompletableFuture<Path> of(String url) {
        Path cache = Settings.get().cacheDir().resolve("media");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fetch(url, cache, (u, dir) -> LinkSaver.save(u, dir, "reel", p -> { }, LinkSaver.tools(), LinkSaver.COOKIES));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, IO);
    }

    static Path fetch(String url, Path cache, Fetch engine) throws IOException, InterruptedException {
        String name = "reel-" + Hashes.sha256(url).substring(0, 32);
        Optional<Path> have = finished(cache, name);
        if (have.isPresent()) return have.get();
        // The engine's own folder, hidden and named ".part" so the cache's trimming takes it for a leftover if cut short.
        Path work = Files.createDirectories(cache.resolve("." + name + ".part"));
        try {
            Failure why = engine.save(url, work);
            if (why != null) throw new IOException(why.why());
            Path video;
            try (Stream<Path> files = Files.list(work)) {
                video = files.filter(LinkVideo::whole).max(Comparator.comparingLong(LinkVideo::size))
                        .orElseThrow(() -> new IOException(I18n.t("video.downloadFailed")));
            }
            String file = video.getFileName().toString();
            Path into = cache.resolve(name + file.substring(Math.max(0, file.lastIndexOf('.'))));
            return Files.move(video, into, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Folders.deleteQuietly(work);
        }
    }

    /** The video fetched before for {@code name}, if it's still in the cache. */
    private static Optional<Path> finished(Path cache, String name) throws IOException {
        if (!Files.isDirectory(cache)) return Optional.empty();
        try (Stream<Path> files = Files.list(cache)) {
            return files.filter(f -> f.getFileName().toString().startsWith(name + ".") && Files.isRegularFile(f)).findFirst();
        }
    }

    /** A file the engine finished: not hidden, not half-written. */
    private static boolean whole(Path f) {
        String n = f.getFileName().toString();
        return Files.isRegularFile(f) && !n.startsWith(".") && !n.contains(".part");
    }

    private static long size(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return 0;
        }
    }
}
