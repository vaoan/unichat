package app.unichat.gif;

import app.unichat.model.MediaLibrary;
import app.unichat.settings.Settings;
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

    public static Path download(String url, String ext, Duration timeout) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            Path dir = Settings.dataDir().resolve("gif-cache");
            Files.createDirectories(dir);
            prune(dir);
            Path f = Files.createTempFile(dir, "gif", ext);
            Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
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
