package app.unichat.support;

import app.unichat.support.I18n;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Downloads of pinned files: {@code target} only appears, whole, if the checksum matches. */
public final class Downloads {
    /** One for every download: each client keeps its own threads and connections. */
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    private Downloads() {}

    /** See {@link #fetch(String, long, String, Path, String, DoubleConsumer, Consumer)}. */
    public static void fetch(String url, long size, String sha256, Path target, String what, DoubleConsumer progress)
            throws IOException, InterruptedException {
        fetch(url, size, sha256, target, what, progress, part -> { });
    }

    /**
     * Blocking.
     *
     * @param size       in bytes; 0 when unknown, and {@code progress} isn't told
     * @param what       for error messages, already in the language in use ({@code I18n.t("download.what.voiceModel")})
     * @param progress   gets 0..1
     * @param beforeMove gets the whole, checked file just before it takes {@code target}'s name
     */
    public static void fetch(String url, long size, String sha256, Path target, String what, DoubleConsumer progress,
                             Consumer<Path> beforeMove) throws IOException, InterruptedException {
        HttpResponse<InputStream> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close(); // left open, the connection stayed busy with a body nobody reads
            throw new IOException(I18n.t("download.http", res.statusCode(), what));
        }
        MessageDigest md = sha256();
        try (InputStream in = new DigestInputStream(res.body(), md)) {
            SafeFile.at(target, ".part").beforeMove(part -> {
                if (!HexFormat.of().formatHex(md.digest()).equals(sha256)) throw new IOException(I18n.t("download.corrupt", what));
                beforeMove.accept(part);
            }).write(out -> {
                byte[] buf = new byte[1 << 16];
                long done = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    done += n;
                    if (size > 0) progress.accept(Math.min(1, (double) done / size));
                }
            });
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
