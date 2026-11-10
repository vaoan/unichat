package app.unichat.gif;

import app.unichat.model.MediaLibrary;
import app.unichat.settings.Settings;
import app.unichat.support.BoundedBody;
import app.unichat.support.I18n;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * Turning a provider's GIF into something to send and keep: the library item (with the small GIF preview, since
 * the sent file is usually an mp4 that JavaFX can't show) and the downloaded file, kept in {@code gif-cache/} in
 * the data folder and pruned after a day (a crash can't leave them behind in the system temp folder).
 */
public final class GifFiles {
    public static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private GifFiles() {}

    public static MediaLibrary.Item item(Gif g) {
        boolean mp4 = g.mp4Url() != null;
        ObjectNode info = JsonNodeFactory.instance.objectNode().put("preview", g.previewUrl()).put("title", g.title());
        return new MediaLibrary.Item(MediaLibrary.Kind.GIF, mp4 ? g.mp4Url() : g.gifUrl(), mp4 ? "video/mp4" : "image/gif",
                g.width(), g.height(), info);
    }

    /** The image to show for a library GIF: its saved preview, else the file itself (null when there's neither). */
    public static String preview(MediaLibrary.Item i) {
        String p = i.info() == null ? null : HttpJson.text(i.info().path("preview"));
        return p != null ? p : "video/mp4".equals(i.mime()) ? null : i.url();
    }

    /** Far above any GIF a provider serves (their mp4s are a few MB): only a stranger's endless file gets there. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    /**
     * The address comes from the provider's answer, so it's a stranger's: https only (redirects never drop to http) and at
     * most {@link #MAX_BYTES}, within {@code timeout} for the headers and again for the body.
     */
    public static Path download(String url, String ext, Duration timeout) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IOException(I18n.t("link.notAllowed"));
        return fetch(uri, ext, timeout, MAX_BYTES);
    }

    static Path fetch(URI url, String ext, Duration timeout, long maxBytes) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(url).timeout(timeout).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = new BoundedBody(r.body(), maxBytes, timeout)) {
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            Path dir = Settings.dataDir().resolve("gif-cache");
            Files.createDirectories(dir);
            prune(dir);
            Path f = Files.createTempFile(dir, "gif", ext);
            try {
                Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(f);
                throw e;
            }
            return f;
        }
    }

    private static void prune(Path dir) {
        Instant old = Instant.now().minus(Duration.ofDays(1));
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> {
                try {
                    return Files.getLastModifiedTime(f).toInstant().isBefore(old);
                } catch (IOException e) {
                    return false;
                }
            }).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // Nothing to prune.
        }
    }
}
