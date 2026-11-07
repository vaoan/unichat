package app.unichat.matrix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The person's own copy of their favourite stickers and GIFs, in the data folder beside the list that names them: it travels
 * with them, and neither the cache's limit nor a bridge that lost the file can take a favourite away. A copy only goes when
 * it stops being a favourite: never when the list comes in empty at start, which may just be a damaged list.
 */
final class SavedMedia {
    private static final int MAX_RETRIES = 5;

    private final Path dir;
    private final Function<String, CompletableFuture<Path>> fetch;
    private final Function<String, String> names;
    private final Executor io;
    private final long retrySeconds;
    private Set<String> wanted = Set.of();

    SavedMedia(Path dir, Function<String, CompletableFuture<Path>> fetch, Function<String, String> names, Executor io, long retrySeconds) {
        this.dir = dir;
        this.fetch = fetch;
        this.names = names;
        this.io = io;
        this.retrySeconds = retrySeconds;
    }

    /** The copy of {@code url}, or null. */
    Path find(String url) {
        return MediaDownloader.findCached(dir, names.apply(url));
    }

    /** These are the favourites from now on: copies what's new, deletes what stopped being one. */
    synchronized void keep(Collection<String> urls) {
        Set<String> now = Set.copyOf(new HashSet<>(urls));
        if (now.equals(wanted)) return;
        Set<String> gone = new HashSet<>(wanted);
        gone.removeAll(now);
        wanted = now;
        io.execute(() -> {
            gone.forEach(this::delete);
            copyMissing(now, 0);
        });
    }

    /** At start the bridges may not be up yet: what couldn't be fetched is tried again later, and at the next start. */
    private void copyMissing(Set<String> urls, int retry) {
        List<CompletableFuture<Boolean>> copies = new ArrayList<>();
        for (String url : urls) {
            if (find(url) == null) copies.add(fetch.apply(url).handleAsync((file, err) -> err == null && copy(url, file), io));
        }
        CompletableFuture.allOf(copies.toArray(CompletableFuture[]::new)).thenRun(() -> {
            long failed = copies.stream().filter(c -> !c.join()).count();
            if (failed == 0) return;
            System.err.println("Favourite stickers without a saved copy yet: " + failed);
            if (retry < MAX_RETRIES && urls.equals(current())) {
                CompletableFuture.delayedExecutor(retrySeconds, TimeUnit.SECONDS, io).execute(() -> copyMissing(urls, retry + 1));
            }
        });
    }

    private synchronized Set<String> current() {
        return wanted;
    }

    private boolean copy(String url, Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path tmp = dir.resolve(names.apply(url) + ".download");
        try {
            Files.createDirectories(dir);
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, dir.resolve(names.apply(url) + (dot < 0 ? "" : name.substring(dot))), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            System.err.println("Could not save a copy of a favourite sticker: " + e.getMessage());
            return false;
        }
    }

    private void delete(String url) {
        Path copy = find(url);
        if (copy == null) return;
        try {
            Files.deleteIfExists(copy);
        } catch (IOException e) {
            System.err.println("Could not delete the copy of a sticker: " + e.getMessage());
        }
    }
}
