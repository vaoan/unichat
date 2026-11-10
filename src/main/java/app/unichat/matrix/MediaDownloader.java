package app.unichat.matrix;

import app.unichat.links.LinkInspector;
import app.unichat.model.LinkMedia;
import app.unichat.bridge.BridgePorts;
import app.unichat.settings.Settings;
import app.unichat.support.Hashes;
import app.unichat.model.MimeTypes;
import app.unichat.support.I18n;
import app.unichat.support.Urls;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/** Downloads media on demand into {@code cache/media} and keeps that folder under its limit ({@link MediaCache}); favourites have their own copy ({@link SavedMedia}). */
final class MediaDownloader {
    private final MatrixClient client;
    private final LinkInspector links = new LinkInspector();
    private final ExecutorService io;
    private final Map<String, CompletableFuture<Path>> mediaInFlight = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong lastCacheTrim = new java.util.concurrent.atomic.AtomicLong();

    private volatile java.util.Set<String> pinned = java.util.Set.of();
    private final SavedMedia saved;

    MediaDownloader(MatrixClient client, ExecutorService io) {
        this.client = client;
        this.io = io;
        saved = new SavedMedia(Settings.dataDir().resolve("media-library"), url -> media(url, 0, 0),
                url -> Hashes.sha256(cacheKey(url, 0, 0)), io, 60);
    }

    /** Who wants to know how far each download in flight got, by its cache key. */
    private final Map<String, java.util.List<java.util.function.DoubleConsumer>> watching = new ConcurrentHashMap<>();

    /** {@link #media(String, int, int)}, telling {@code progress} how much has arrived while it downloads. */
    CompletableFuture<Path> media(String url, int width, int height, java.util.function.DoubleConsumer progress) {
        String key = cacheKey(url, width, height);
        watching.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(progress);
        CompletableFuture<Path> f = media(url, width, height);
        f.whenComplete((p, e) -> {
            var l = watching.get(key);
            if (l != null) l.remove(progress);
        });
        return f;
    }

    /** The local file of {@code url}: from the cache, or downloaded once however many ask at the same time. */
    CompletableFuture<Path> media(String url, int width, int height) {
        if (url == null || url.isBlank()) return CompletableFuture.failedFuture(new MatrixException(0, null, I18n.t("error.noUrl")));
        if (url.startsWith("file:")) return CompletableFuture.completedFuture(Path.of(URI.create(url)));
        Path own = saved.find(url);
        if (own != null) return CompletableFuture.completedFuture(own);
        String key = cacheKey(url, width, height);
        Path dir = Settings.get().cacheDir().resolve("media");
        String hash = Hashes.sha256(key);
        Path cached = findCached(dir, hash);
        if (cached != null) {
            MediaCache.touch(cached);
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<Path> f = new CompletableFuture<>();
        CompletableFuture<Path> prev = mediaInFlight.putIfAbsent(key, f);
        if (prev != null) return prev;
        io.execute(() -> {
            try {
                f.complete(download(url, width, height, dir, hash, done -> {
                    var l = watching.get(key);
                    if (l != null) l.forEach(c -> c.accept(done));
                }));
                trimMediaCache(dir);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            } finally {
                mediaInFlight.remove(key, f);
            }
        });
        return f;
    }

    CompletableFuture<java.util.Optional<LinkMedia>> linkMedia(String url) {
        if (!Settings.get().linkPreviews()) return CompletableFuture.completedFuture(java.util.Optional.empty());
        return links.inspect(url);
    }

    /** Pinned in the cache from now on: {@link MediaCache}. */
    void keep(java.util.Collection<String> urls) {
        pinned = urls.stream().map(u -> Hashes.sha256(cacheKey(u, 0, 0))).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** The favourites from now on, each with its own copy. */
    void save(java.util.Collection<String> urls) {
        saved.keep(urls);
    }

    /**
     * A bridge's direct media and web addresses only come as the original, whatever the size asked: one file for all of them.
     * Each size used to be a whole copy (a GIF of several MB, twice or more).
     */
    static String cacheKey(String url, int width, int height) {
        boolean scalable = url.startsWith("mxc://") && BridgePorts.directMediaBase(serverOf(url)) == null;
        return url + "|" + (scalable ? Math.max(0, width) + "x" + Math.max(0, height) : "0x0");
    }

    private static String serverOf(String mxc) {
        String rest = mxc.substring("mxc://".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    /** At most once a minute: listing thousands of files after every thumbnail would cost more than it saves. */
    private void trimMediaCache(Path dir) {
        long now = System.currentTimeMillis(), last = lastCacheTrim.get();
        if (now - last < 60_000 || !lastCacheTrim.compareAndSet(last, now)) return;
        MediaCache.trim(dir, Settings.get().mediaCacheMb() * 1024L * 1024L, pinned);
    }

    private Path download(String url, int w, int h, Path dir, String hash, java.util.function.DoubleConsumer progress) throws IOException {
        Path tmp = dir.resolve(hash + ".download");
        Files.deleteIfExists(tmp);
        String contentType;
        if (url.startsWith("mxc://")) {
            String rest = url.substring("mxc://".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) throw new MatrixException(0, null, I18n.t("media.badUrl"));
            String sm = Urls.enc(rest.substring(0, slash)) + "/" + Urls.enc(rest.substring(slash + 1));
            contentType = null;
            String bridge = BridgePorts.directMediaBase(rest.substring(0, slash));
            if (bridge != null) {
                // Direct media: the bridge fetches it from the network now (nothing was copied to the server).
                contentType = client.download(bridge + "/_matrix/client/v1/media/download/" + sm, false, tmp, progress);
            } else if (w > 0 && h > 0) {
                String q = "?width=" + w + "&height=" + h + "&method=scale";
                try {
                    contentType = fetchMedia("/thumbnail/" + sm + q, tmp, null);
                } catch (MatrixException e) {
                    contentType = null; // not thumbnailable: fall back to the original
                }
            }
            if (contentType == null && !Files.exists(tmp)) contentType = fetchMedia("/download/" + sm, tmp, progress);
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            // Web addresses come from messages: never to this machine or its network.
            contentType = links.download(url, tmp);
        } else {
            throw new MatrixException(0, null, I18n.t("media.unsupportedUrl"));
        }
        Path target = dir.resolve(hash + extension(contentType));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** Authenticated media (Matrix 1.11) with fallback to the legacy unauthenticated endpoints. */
    private String fetchMedia(String suffix, Path tmp, java.util.function.DoubleConsumer progress) throws IOException {
        try {
            return client.download("/_matrix/client/v1/media" + suffix, true, tmp, progress);
        } catch (MatrixException e) {
            if (!e.isUnrecognized()) throw e;
            return client.download("/_matrix/media/v3" + suffix, true, tmp, progress);
        }
    }

    static Path findCached(Path dir, String hash) {
        if (!Files.isDirectory(dir)) return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, hash + "*")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".download") && !n.contains(".part")) return p;
            }
        } catch (IOException ignored) {
            // treat as not cached
        }
        return null;
    }

    static String extension(String contentType) {
        String ext = MimeTypes.extension(contentType);
        // JavaFX refuses a ".mov" by its name, but plays the same file as ".mp4".
        return ext.equals(".mov") ? ".mp4" : ext;
    }
}
